package com.lining.vgw.common;

import com.lining.vgw.domain.ApiClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通道级数据隔离的"访问范围"（纯静态工具，不依赖任何 Spring Bean）。
 *
 * <p>授权有两个维度，取<b>并集</b>：
 * <ul>
 *   <li>{@code allowed_channels}：单通道授权（精确到某一路上）</li>
 *   <li>{@code allowed_devices}：设备级授权 —— 该设备名下<b>所有</b>通道都算授权，
 *       包括以后新增的通道（新增通道自动继承，不用重新授权）</li>
 * </ul>
 * 两者都为空 = <b>不限</b>（管理控制台内置凭证就是这种）。
 *
 * <p>需要把"设备"展开成"通道 id"的场合（列表过滤）请用
 * {@link com.lining.vgw.service.ChannelScopeService}，那里会查库。
 */
public final class AccessScope {

    /** 受限但没有任何授权时使用的哨兵 id（不存在 id = -1 的通道） */
    private static final List<Long> DENY_ALL = List.of(-1L);

    private AccessScope() {
    }

    /** 当前客户端是否不受限制（未鉴权场景如 ZLM 回调 / 定时任务也算不受限） */
    public static boolean isUnrestricted() {
        ApiClient c = ApiKeyContext.current();
        if (c == null) {
            return true;   // ZLM 回调 / 定时任务，无客户端上下文
        }
        // 只有明确标了 SCOPE 的客户端才受限；ALL / 历史数据(NULL) 都视为不限
        return !c.isScopeLimited();
    }

    /** 显式授权的通道 id；null 表示未配置（不限） */
    public static List<Long> explicitChannelIdsOrNull() {
        ApiClient c = ApiKeyContext.current();
        return c == null ? null : parse(c.getAllowedChannels());
    }

    /** 设备级授权的设备 id；null 表示未配置 */
    public static List<Long> deviceIdsOrNull() {
        ApiClient c = ApiKeyContext.current();
        return c == null ? null : parse(c.getAllowedDevices());
    }

    /**
     * 单个通道的授权判定。
     *
     * @param channelId 通道 id
     * @param deviceId  通道所属设备 id（设备级授权用；不知道可传 null）
     */
    public static boolean canAccess(Long channelId, Long deviceId) {
        if (isUnrestricted()) {
            return true;
        }
        List<Long> devices = deviceIdsOrNull();
        if (devices != null && deviceId != null && devices.contains(deviceId)) {
            return true;
        }
        List<Long> channels = explicitChannelIdsOrNull();
        return channels != null && channelId != null && channels.contains(channelId);
    }

    /** 只知道通道 id 时的简化版（等价于 deviceId = null） */
    public static boolean canAccess(Long channelId) {
        return canAccess(channelId, null);
    }

    /** 全拒哨兵（用于 SQL 的 IN 条件，避免拼出 IN ()） */
    public static List<Long> denyAllSentinel() {
        return DENY_ALL;
    }

    /**
     * 解析逗号分隔的 id 串。
     *
     * @return null 表示未配置；空列表表示"配了但没有有效值"（语义上等于全拒）
     */
    public static List<Long> parse(String csv) {
        if (blank(csv)) {
            return null;
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
                // 脏数据忽略：宁可少授权也不误放行
            }
        }
        return new ArrayList<>(ids);
    }

    /** 把 id 列表格式化成入库用的逗号串；空列表返回 null（= 不限） */
    public static String toCsv(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : new LinkedHashSet<>(ids)) {
            if (id == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}