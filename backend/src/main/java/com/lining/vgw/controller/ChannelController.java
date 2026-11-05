package com.lining.vgw.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lining.vgw.common.BizException;
import com.lining.vgw.common.ErrorCode;
import com.lining.vgw.common.PageQuery;
import com.lining.vgw.common.PageResult;
import com.lining.vgw.common.Result;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.domain.Device;
import com.lining.vgw.dto.ChannelSaveReq;
import com.lining.vgw.mapper.ChannelMapper;
import com.lining.vgw.mapper.DeviceMapper;
import com.lining.vgw.domain.Ledger;
import com.lining.vgw.service.ApiClientService;
import com.lining.vgw.service.ChannelAccessService;
import com.lining.vgw.service.ChannelScopeService;
import com.lining.vgw.service.StreamProbeService;
import com.lining.vgw.service.LedgerService;
import com.lining.vgw.service.StreamPullService;
import io.swagger.v3.oas.annotations.Operation;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通道管理接口。
 *
 * <p>所有查询都会按当前 api-key 的授权通道过滤（通道级隔离），
 * 针对单个通道的动作则先过 {@link ChannelAccessService}。
 */
@Tag(name = "通道管理")
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelMapper channelMapper;
    private final DeviceMapper deviceMapper;
    private final ChannelAccessService channelAccessService;
    private final ChannelScopeService channelScopeService;
    private final ApiClientService apiClientService;
    private final LedgerService ledgerService;
    private final StreamPullService streamPullService;
    private final StreamProbeService streamProbeService;

    @Operation(summary = "分页查询通道（按客户端授权通道过滤）")
    @GetMapping
    public Result<PageResult<Channel>> page(@ParameterObject PageQuery query,
                                            @RequestParam(required = false) Long deviceId,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) String keyword) {
        Page<Channel> page = new Page<>(query.safePage(), query.safeSize());
        List<Long> scope = channelScopeService.effectiveChannelIdsOrNull();
        LambdaQueryWrapper<Channel> wrapper = new LambdaQueryWrapper<Channel>()
                // 通道级隔离：null 表示不限，否则只查授权的通道（无授权时是哨兵 [-1]）
                .in(scope != null, Channel::getId, scope)
                .eq(deviceId != null, Channel::getDeviceId, deviceId)
                .eq(status != null, Channel::getStatus, status)
                .like(StringUtils.hasText(keyword), Channel::getChannelName, keyword)
                .orderByDesc(Channel::getId);
        IPage<Channel> result = channelMapper.selectPage(page, wrapper);
        fillDeviceNames(result.getRecords());
        return Result.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords()));
    }

    @Operation(summary = "查询单个通道")
    @GetMapping("/{id}")
    public Result<Channel> get(@PathVariable Long id) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "通道不存在，可能已被删除");
        }
        channelAccessService.assertAccess(channel, "访问");
        fillDeviceNames(List.of(channel));
        return Result.ok(channel);
    }

    /**
     * 批量补"所属设备名称"。
     *
     * <p>一次 selectBatchIds 解决整页的映射，避免每条通道都查一次设备（N+1）。
     * 设备被删或 device_id 为空时留空，前端显示"-"。
     */
    private void fillDeviceNames(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) {
            return;
        }
        Set<Long> deviceIds = channels.stream()
                .map(Channel::getDeviceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (deviceIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameById = deviceMapper.selectBatchIds(deviceIds).stream()
                .collect(Collectors.toMap(Device::getId, Device::getDeviceName, (a, b) -> a));
        channels.forEach(c -> c.setDeviceName(nameById.get(c.getDeviceId())));
    }

    @Operation(summary = "新增通道（同名流标识若曾被逻辑删除则自动复用）")
    @PostMapping
    public Result<Channel> create(@RequestBody @Valid ChannelSaveReq req) {
        // 1) 是否被"有效"记录占用
        Long active = channelMapper.selectCount(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getStreamApp, req.getStreamApp())
                .eq(Channel::getStreamId, req.getStreamId()));
        if (active != null && active > 0) {
            throw new BizException(ErrorCode.DUPLICATE,
                    "这个流标识已经被别的通道占用了：" + req.getStreamApp() + "/" + req.getStreamId() + "，请换一个");
        }
        Long codeActive = channelMapper.selectCount(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getChannelCode, req.getChannelCode()));
        if (codeActive != null && codeActive > 0) {
            throw new BizException(ErrorCode.DUPLICATE, "通道编码 " + req.getChannelCode() + " 已存在，请换一个");
        }

        // 2) 若同流标识存在"已逻辑删除"的记录，复用它（唯一索引保持不动，历史台账引用不失效）
        Channel deleted = channelMapper.selectAnyByStreamKey(req.getStreamApp(), req.getStreamId());
        if (deleted != null) {
            deleted.setDeviceId(req.getDeviceId());
            deleted.setChannelCode(req.getChannelCode());
            deleted.setChannelName(req.getChannelName());
            deleted.setStreamApp(req.getStreamApp());
            deleted.setStreamId(req.getStreamId());
            deleted.setPullUrl(req.getPullUrl());
            deleted.setEnabled(req.getEnabled());
            channelMapper.revive(deleted);
            return Result.ok(channelMapper.selectById(deleted.getId()));
        }

        // 3) 正常新建
        Channel channel = new Channel();
        channel.setDeviceId(req.getDeviceId());
        channel.setChannelCode(req.getChannelCode());
        channel.setChannelName(req.getChannelName());
        channel.setStreamApp(req.getStreamApp());
        channel.setStreamId(req.getStreamId());
        channel.setPullUrl(req.getPullUrl());
        channel.setStatus(Channel.STATUS_OFFLINE);
        channel.setEnabled(req.getEnabled());
        channelMapper.insert(channel);
        return Result.ok(channel);
    }

    @Operation(summary = "修改通道")
    @PutMapping("/{id}")
    public Result<Channel> update(@PathVariable Long id, @RequestBody @Valid ChannelSaveReq req) {
        Channel exists = channelMapper.selectById(id);
        if (exists == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "通道不存在，可能已被删除");
        }
        channelAccessService.assertAccess(exists, "修改");
        Channel channel = new Channel();
        channel.setId(id);
        channel.setDeviceId(req.getDeviceId());
        channel.setChannelName(req.getChannelName());
        channel.setPullUrl(req.getPullUrl());
        channel.setEnabled(req.getEnabled());
        channelMapper.updateById(channel);
        return Result.ok(channelMapper.selectById(id));
    }

    @Operation(summary = "删除通道（并从各客户端授权中自动移除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Channel exists = channelMapper.selectById(id);
        if (exists != null) {
            channelAccessService.assertAccess(exists, "删除");
        }
        channelMapper.deleteById(id);
        if (exists != null) {
            // 级联清理：通道没了，授权里就不该再留着它（否则界面出现"通道 #9 不存在"的脏引用）
            List<String> affected = apiClientService.removeChannelFromScopes(id);
            if (!affected.isEmpty()) {
                ledgerService.record(Ledger.TYPE_AUTH_CHANGED, exists.getDeviceId(), exists.getId(), null,
                        "API", null, 0,
                        "通道已删除，自动从 " + affected.size() + " 个客户端授权中移除：" + String.join("、", affected), null);
            }
        }
        return Result.ok();
    }

    @Operation(summary = "探测流标识在媒体服务器上的真实状态（在线/推流/拉流/残留代理）")
    @GetMapping("/{id}/probe")
    public Result<Map<String, Object>> probe(@PathVariable Long id) {
        return Result.ok(streamProbeService.probe(id));
    }

    @Operation(summary = "平台发起拉流（由媒体服务器去拉通道的拉流地址）")
    @PostMapping("/{id}/pull")
    public Result<Map<String, Object>> pull(@PathVariable Long id) {
        return Result.ok(streamPullService.start(id));
    }

    @Operation(summary = "停止拉流（删除拉流代理）")
    @PostMapping("/{id}/unpull")
    public Result<Map<String, Object>> unpull(@PathVariable Long id) {
        return Result.ok(streamPullService.stop(id));
    }
}