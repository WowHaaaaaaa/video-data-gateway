package com.lining.vgw.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lining.vgw.common.AccessScope;
import com.lining.vgw.common.BizException;
import com.lining.vgw.common.ErrorCode;
import com.lining.vgw.domain.ApiClient;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.domain.Device;
import com.lining.vgw.mapper.ApiClientMapper;
import com.lining.vgw.mapper.ChannelMapper;
import com.lining.vgw.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * API 客户端服务：发放凭证 + 鉴权校验 + 通道级授权。
 *
 * <p>凭证生命周期：
 * <pre>
 *   创建 → 生成 appKey(app_secret) → 业务平台带上 api-key 头调用
 *        → 可停用 / 可重置密钥 / 可设失效时间 / 可删 / 可配可访问通道
 * </pre>
 *
 * <p><b>通道级隔离</b>：{@code allowed_channels} 存逗号分隔的通道 ID，
 * 为空表示不限。配置后该客户端：
 * <ul>
 *   <li>列表类接口（通道/在线流/台账/流会话/录像）只能看到授权通道</li>
 *   <li>针对未授权通道的动作（换播放地址、录像、拉流）直接 401 并记台账</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiClientService {

    /** 管理控制台用的内置客户端：不允许做通道隔离，否则会把界面自己锁死 */
    public static final String CONSOLE_APP_KEY = "vwg_console";

    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiClientMapper apiClientMapper;
    private final ChannelMapper channelMapper;
    private final DeviceMapper deviceMapper;

    /**
     * 创建客户端。返回的对象里含明文 appSecret —— 这是**唯一一次**能看到它。
     *
     * @param allowedChannels 允许访问的通道 ID；空表示不限
     */
    public ApiClient create(String clientName, String contact, String remark, LocalDateTime expireTime,
                            List<Long> allowedChannels, List<Long> allowedDevices, String scopeMode,
                            Integer isAdmin) {
        validateChannelsExist(allowedChannels);
        validateDevicesExist(allowedDevices);

        ApiClient client = new ApiClient();
        client.setAppKey("vwg_" + randomString(16));
        client.setAppSecret(randomString(32));
        client.setClientName(clientName);
        client.setContact(contact);
        client.setEnabled(1);
        client.setExpireTime(expireTime);
        client.setAllowedChannels(AccessScope.toCsv(allowedChannels));
        client.setAllowedDevices(AccessScope.toCsv(allowedDevices));
        client.setScopeMode(normalizeMode(scopeMode));
        client.setIsAdmin(isAdmin != null && isAdmin == 1 ? 1 : 0);
        client.setRateLimit(0);
        client.setRemark(remark);
        apiClientMapper.insert(client);
        log.info("创建 API 客户端: appKey={} name={} allowedChannels={} allowedDevices={}",
                client.getAppKey(), clientName, client.getAllowedChannels(), client.getAllowedDevices());
        return client;
    }

    /** 鉴权：校验 appKey 是否存在、是否启用、是否过期 */
    public ApiClient authenticate(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "请求缺少调用凭证，请在请求头里带上 api-key");
        }
        ApiClient client = apiClientMapper.selectOne(new LambdaQueryWrapper<ApiClient>()
                .eq(ApiClient::getAppKey, appKey)
                .last("limit 1"));
        if (client == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "调用凭证无效（这个 appKey 不存在）");
        }
        if (client.getEnabled() == null || client.getEnabled() != 1) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "这个凭证已被停用，请联系平台管理员启用");
        }
        if (client.isExpired()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "凭证已过期，请联系平台管理员续期");
        }
        return client;
    }

    public IPage<ApiClient> page(long page, long size, String keyword) {
        LambdaQueryWrapper<ApiClient> wrapper = new LambdaQueryWrapper<ApiClient>()
                .orderByDesc(ApiClient::getId);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            // 关键字：客户端名称 / appKey / 联系人 任一匹配
            wrapper.and(w -> w.like(ApiClient::getClientName, k)
                    .or().like(ApiClient::getAppKey, k)
                    .or().like(ApiClient::getContact, k));
        }
        return apiClientMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public ApiClient requireById(Long id) {
        ApiClient c = apiClientMapper.selectById(id);
        if (c == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "客户端不存在，可能已被删除");
        }
        return c;
    }

    /** 启用 / 停用 */
    public ApiClient setEnabled(Long id, boolean enabled) {
        ApiClient c = requireById(id);
        ApiClient update = new ApiClient();
        update.setId(id);
        update.setEnabled(enabled ? 1 : 0);
        apiClientMapper.updateById(update);
        log.info("客户端 {} 已{}", c.getAppKey(), enabled ? "启用" : "停用");
        return requireById(id);
    }

    /** 重置密钥（旧密钥立即失效），返回新密钥 */
    public String resetSecret(Long id) {
        requireById(id);
        String newSecret = randomString(32);
        ApiClient update = new ApiClient();
        update.setId(id);
        update.setAppSecret(newSecret);
        apiClientMapper.updateById(update);
        return newSecret;
    }

    /**
     * 修改失效时间（续期）。
     *
     * <p>传 null 表示改为永久有效。之所以单独提供接口：
     * 原来只能"删了重建"，一旦配错失效时间就只能换一个 appKey，代价太大。
     */
    public ApiClient updateExpireTime(Long id, LocalDateTime expireTime) {
        ApiClient exists = requireById(id);
        ApiClient update = new ApiClient();
        update.setId(exists.getId());
        update.setExpireTime(expireTime);
        // 用 UpdateWrapper 显式 set，保证能把字段改成 null（updateById 会忽略 null）
        apiClientMapper.update(null,
                new LambdaUpdateWrapper<ApiClient>()
                        .set(ApiClient::getExpireTime, expireTime)
                        .eq(ApiClient::getId, id));
        log.info("客户端 {} 失效时间更新为 {}", exists.getAppKey(), expireTime);
        return requireById(id);
    }

    /**
     * 配置可访问通道（通道级隔离）。
     *
     * @param allowedChannels 通道 ID 列表；传空数组/ null 表示改为"不限"
     */
    public ApiClient updateScope(Long id, List<Long> allowedChannels, List<Long> allowedDevices, String scopeMode,
                                Integer isAdmin) {
        ApiClient exists = requireById(id);
        String mode = normalizeMode(scopeMode);
        if (CONSOLE_APP_KEY.equals(exists.getAppKey())) {
            // 控制台必须保持"不限通道 + 管理权限"，否则界面会把自己锁死
            if (ApiClient.MODE_SCOPE.equals(mode)) {
                throw new BizException(ErrorCode.ILLEGAL_STATE,
                        "管理控制台的凭证不能限制通道，否则界面会看不到自己的通道");
            }
            if (isAdmin != null && isAdmin != 1) {
                throw new BizException(ErrorCode.ILLEGAL_STATE,
                        "管理控制台的凭证必须保留管理权限，否则界面将无法管理平台");
            }
        }
        validateChannelsExist(allowedChannels);
        validateDevicesExist(allowedDevices);
        String chCsv = AccessScope.toCsv(allowedChannels);
        String devCsv = AccessScope.toCsv(allowedDevices);
        // 显式 set，保证能把字段改回 null（= 该维度不限）
        apiClientMapper.update(null,
                new LambdaUpdateWrapper<ApiClient>()
                        .set(ApiClient::getAllowedChannels, chCsv)
                        .set(ApiClient::getAllowedDevices, devCsv)
                        .set(ApiClient::getScopeMode, mode)
                        .set(ApiClient::getIsAdmin, isAdmin != null && isAdmin == 1 ? 1 : 0)
                        .eq(ApiClient::getId, id));
        log.info("客户端 {} 授权更新: mode={} admin={} channels={} devices={}",
                exists.getAppKey(), mode, isAdmin, chCsv, devCsv);
        return requireById(id);
    }

    /**
     * 通道被删除后，把它从所有客户端的授权里摘掉。
     *
     * <p>为什么要做：不清理的话，授权里会留下指向"已删除通道"的脏引用，
     * 界面上会出现"通道 #9：不存在"这种让人困惑的提示。
     *
     * <p>注意：摘完如果该客户端一条都不剩，<b>保持 SCOPE 语义（= 无授权）</b>，
     * 不要自动变成"不限" —— 那是相反的语义，等于越删权限越大。
     *
     * @return 受影响的客户端名称（用于写台账）
     */
    public List<String> removeChannelFromScopes(Long channelId) {
        if (channelId == null) {
            return List.of();
        }
        List<String> affected = new ArrayList<>();
        for (ApiClient c : clientsWithChannels()) {
            List<Long> ids = AccessScope.parse(c.getAllowedChannels());
            if (ids == null || !ids.contains(channelId)) {
                continue;
            }
            ids.remove(channelId);
            apiClientMapper.update(null, new LambdaUpdateWrapper<ApiClient>()
                    .set(ApiClient::getAllowedChannels, AccessScope.toCsv(ids))
                    .eq(ApiClient::getId, c.getId()));
            affected.add(c.getClientName());
        }
        return affected;
    }

    /** 设备被删除后，把它从所有客户端的"设备级授权"里摘掉 */
    public List<String> removeDeviceFromScopes(Long deviceId) {
        if (deviceId == null) {
            return List.of();
        }
        List<String> affected = new ArrayList<>();
        for (ApiClient c : clientsWithDevices()) {
            List<Long> ids = AccessScope.parse(c.getAllowedDevices());
            if (ids == null || !ids.contains(deviceId)) {
                continue;
            }
            ids.remove(deviceId);
            apiClientMapper.update(null, new LambdaUpdateWrapper<ApiClient>()
                    .set(ApiClient::getAllowedDevices, AccessScope.toCsv(ids))
                    .eq(ApiClient::getId, c.getId()));
            affected.add(c.getClientName());
        }
        return affected;
    }

    private List<ApiClient> clientsWithChannels() {
        return apiClientMapper.selectList(new LambdaQueryWrapper<ApiClient>()
                .isNotNull(ApiClient::getAllowedChannels));
    }

    private List<ApiClient> clientsWithDevices() {
        return apiClientMapper.selectList(new LambdaQueryWrapper<ApiClient>()
                .isNotNull(ApiClient::getAllowedDevices));
    }

    public void delete(Long id) {
        apiClientMapper.deleteById(id);
    }

    /** 归一化授权模式：只有明确 SCOPE 才是受限，其余一律 ALL（不限） */
    private String normalizeMode(String mode) {
        return ApiClient.MODE_SCOPE.equalsIgnoreCase(mode == null ? "" : mode.trim())
                ? ApiClient.MODE_SCOPE : ApiClient.MODE_ALL;
    }

    /** 校验设备 ID 是否都存在 */
    private void validateDevicesExist(List<Long> allowedDevices) {
        if (allowedDevices == null || allowedDevices.isEmpty()) {
            return;
        }
        Set<Long> ids = new LinkedHashSet<>(allowedDevices);
        ids.remove(null);
        if (ids.isEmpty()) {
            return;
        }
        List<Device> found = deviceMapper.selectBatchIds(new ArrayList<>(ids));
        Set<Long> foundIds = new LinkedHashSet<>();
        found.forEach(d -> foundIds.add(d.getId()));
        ids.removeAll(foundIds);
        if (!ids.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "这些设备不存在或已被删除：" + ids);
        }
    }

    /** 校验通道 ID 是否都存在，避免配出一堆指向已删除通道的授权 */
    private void validateChannelsExist(List<Long> allowedChannels) {
        if (allowedChannels == null || allowedChannels.isEmpty()) {
            return;
        }
        Set<Long> ids = new LinkedHashSet<>(allowedChannels);
        ids.remove(null);
        if (ids.isEmpty()) {
            return;
        }
        List<Channel> found = channelMapper.selectBatchIds(new ArrayList<>(ids));
        Set<Long> foundIds = new LinkedHashSet<>();
        found.forEach(c -> foundIds.add(c.getId()));
        ids.removeAll(foundIds);
        if (!ids.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "这些通道不存在或已被删除：" + ids);
        }
    }

    private String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }
}