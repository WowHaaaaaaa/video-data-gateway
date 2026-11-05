package com.lining.vgw.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lining.vgw.common.BizException;
import com.lining.vgw.common.ErrorCode;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.domain.Device;
import com.lining.vgw.mapper.ChannelMapper;
import com.lining.vgw.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 通道服务：通道主数据 + 在线状态维护。
 *
 * <p><b>状态联动规则</b>（重要）：
 * <ul>
 *   <li>通道状态由 webhook 直接维护：0 离线 / 2 推流中</li>
 *   <li><b>设备状态是派生值</b>：该设备下「至少一路通道非离线」→ 在线，否则离线</li>
 * </ul>
 * 之所以要"重新计算"而不是"跟着某一路通道走"，是因为一台设备可能有多路通道
 * （主码流 + 子码流），只有全部离线时设备才算离线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelMapper channelMapper;
    private final DeviceMapper deviceMapper;

    /** 按 ZLM 的 app/stream 查通道，用于 webhook 校验流合法性 */
    public Channel findByStreamKey(String app, String stream) {
        return channelMapper.selectOne(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getStreamApp, app)
                .eq(Channel::getStreamId, stream)
                .eq(Channel::getEnabled, 1)
                .last("limit 1"));
    }

    public Optional<Channel> findById(Long id) {
        return Optional.ofNullable(channelMapper.selectById(id));
    }

    public Channel requireById(Long id) {
        return findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "通道不存在，可能已被删除"));
    }

    /** 推流开始：通道置为"推流中"，并刷新所属设备状态 */
    public void markPublishing(Long channelId) {
        Channel c = new Channel();
        c.setId(channelId);
        c.setStatus(Channel.STATUS_PUBLISHING);
        c.setLastOnlineTime(LocalDateTime.now());
        channelMapper.updateById(c);
    }

    /** 推流结束：通道置为"离线"，并刷新所属设备状态 */
    public void markOffline(Long channelId) {
        Channel c = new Channel();
        c.setId(channelId);
        c.setStatus(Channel.STATUS_OFFLINE);
        channelMapper.updateById(c);
    }

    /**
     * 重新计算某个设备的状态。
     *
     * <p>规则：该设备下存在任意"非离线"通道 → 设备在线；否则离线。
     * 每次推流开始/结束都要调用，避免设备状态与通道状态不一致。
     */
    public void refreshDeviceStatus(Long deviceId) {
        if (deviceId == null) {
            return;
        }
        Long onlineChannels = channelMapper.selectCount(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getDeviceId, deviceId)
                .ne(Channel::getStatus, Channel.STATUS_OFFLINE));

        int target = (onlineChannels != null && onlineChannels > 0) ? 1 : 0;

        Device device = deviceMapper.selectById(deviceId);
        if (device == null) {
            return;
        }
        if (!Objects.equals(device.getStatus(), target)) {
            Device update = new Device();
            update.setId(deviceId);
            update.setStatus(target);
            deviceMapper.updateById(update);
            log.info("设备 {} 状态更新为 {}", device.getDeviceCode(), target == 1 ? "在线" : "离线");
        }
    }

    /**
     * 媒体服务器重启等场景：把所有"非离线"通道复位为离线，并重算全部设备状态。
     *
     * <p>为什么要做：ZLM 重启后原有流全部消失，但通道表里可能残留 status=2，
     * 若不复位会出现"明明没有流，通道却显示推流中"的脏状态。
     */
    public int resetAllOffline() {
        int affected = channelMapper.update(null, new LambdaUpdateWrapper<Channel>()
                .set(Channel::getStatus, Channel.STATUS_OFFLINE)
                .ne(Channel::getStatus, Channel.STATUS_OFFLINE));

        // 设备一律先置离线（此时所有通道都已离线）
        deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                .set(Device::getStatus, 0)
                .ne(Device::getStatus, 0));

        if (affected > 0) {
            log.warn("已复位 {} 个通道为离线状态", affected);
        }
        return affected;
    }

    /** 重算全部设备状态（配合 resetAllOffline 或定时任务使用） */
    public void refreshAllDeviceStatus() {
        List<Device> devices = deviceMapper.selectList(new LambdaQueryWrapper<>());
        for (Device d : devices) {
            refreshDeviceStatus(d.getId());
        }
    }
}
