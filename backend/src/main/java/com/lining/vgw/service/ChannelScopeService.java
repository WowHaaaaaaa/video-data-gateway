package com.lining.vgw.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lining.vgw.common.AccessScope;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.mapper.ChannelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把"授权范围"翻译成查询条件和单条判定。
 *
 * <p>为什么单独一个服务：{@link AccessScope} 是纯静态工具（只读 ThreadLocal，不查库），
 * 而"设备级授权"必须展开成该设备名下的通道 id —— 这一步要查库，所以放在 Spring Bean 里。
 *
 * <p>授权模型（两个维度取并集）：
 * <pre>
 *   allowed_devices  中的设备 → 其名下所有通道（含以后新增的）都算授权
 *   allowed_channels 中的通道 → 单独授权
 *   两者都为空               → 不限
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ChannelScopeService {

    private final ChannelMapper channelMapper;

    public boolean unrestricted() {
        return AccessScope.isUnrestricted();
    }

    /**
     * 当前客户端可见的通道 id 集合。
     *
     * @return null = 不限；受限时返回<b>非空</b>列表（一个都没授权时返回哨兵 [-1]，
     *         让 SQL 的 IN 条件天然查不到数据，也避免拼出非法的 IN ()）
     */
    public List<Long> effectiveChannelIdsOrNull() {
        if (unrestricted()) {
            return null;
        }
        Set<Long> ids = new LinkedHashSet<>();

        List<Long> explicit = AccessScope.explicitChannelIdsOrNull();
        if (explicit != null) {
            ids.addAll(explicit);
        }

        List<Long> devices = AccessScope.deviceIdsOrNull();
        if (devices != null && !devices.isEmpty()) {
            // 设备级授权：把该设备名下的通道展开进来
            List<Channel> owned = channelMapper.selectList(
                    new LambdaQueryWrapper<Channel>().in(Channel::getDeviceId, devices));
            owned.forEach(c -> ids.add(c.getId()));
        }

        // SCOPE 模式下两个列表都空 = 一个都没授权 → 全拒（而不是"不限"）
        return ids.isEmpty() ? AccessScope.denyAllSentinel() : new ArrayList<>(ids);
    }

    /**
     * 当前客户端可见的设备 id。
     *
     * @return null = 不限；受限时返回非空列表（无授权时返回哨兵 [-1]）
     */
    public List<Long> effectiveDeviceIdsOrNull() {
        if (unrestricted()) {
            return null;
        }
        Set<Long> devIds = new LinkedHashSet<>();
        List<Long> devices = AccessScope.deviceIdsOrNull();
        if (devices != null) {
            devIds.addAll(devices);
        }
        List<Long> chIds = effectiveChannelIdsOrNull();
        List<Long> realChIds = chIds.stream().filter(id -> id != null && id > 0).collect(java.util.stream.Collectors.toList());
        if (!realChIds.isEmpty()) {
            channelMapper.selectBatchIds(realChIds).forEach(c -> {
                if (c.getDeviceId() != null) {
                    devIds.add(c.getDeviceId());
                }
            });
        }
        return devIds.isEmpty() ? AccessScope.denyAllSentinel() : new ArrayList<>(devIds);
    }

    /** 单个通道判定（设备级授权优先，再看通道级授权） */
    public boolean canAccess(Channel channel) {
        if (channel == null) {
            return true;
        }
        return AccessScope.canAccess(channel.getId(), channel.getDeviceId());
    }
}