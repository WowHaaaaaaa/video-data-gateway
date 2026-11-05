package com.lining.vgw.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lining.vgw.common.PageQuery;
import com.lining.vgw.common.PageResult;
import com.lining.vgw.common.Result;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.domain.Ledger;
import com.lining.vgw.domain.Recording;
import com.lining.vgw.integration.zlm.ZlmApiClient;
import com.lining.vgw.mapper.RecordingMapper;
import com.lining.vgw.service.ChannelAccessService;
import com.lining.vgw.service.ChannelScopeService;
import com.lining.vgw.service.ChannelService;
import com.lining.vgw.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 录像控制与录像台账。
 *
 * <p>设计取舍：ZLM 的 {@code protocol.enable_mp4} 一旦打开，所有推流都会自动录像，
 * 长时间运行会迅速写满磁盘。因此这里关闭全局自动录像，改为<b>按需触发</b>：
 * 调用 start 接口 → ZLM 为该路流开启录制 → 文件切片完成时 on_record_mp4 回调 → 写入录像台账。
 */
@Slf4j
@Tag(name = "录像管理")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RecordingController {

    private final ChannelService channelService;
    private final ChannelAccessService channelAccessService;
    private final ChannelScopeService channelScopeService;
    private final ZlmApiClient zlmApiClient;
    private final LedgerService ledgerService;
    private final RecordingMapper recordingMapper;

    @Operation(summary = "开始录像")
    @PostMapping("/channels/{id}/record/start")
    public Result<Map<String, Object>> start(@PathVariable Long id) {
        Channel channel = channelService.requireById(id);
        channelAccessService.assertAccess(channel, "操作");
        zlmApiClient.startRecordMp4(channel.getStreamApp(), channel.getStreamId());

        ledgerService.record(Ledger.TYPE_RECORD_START, channel.getDeviceId(), channel.getId(), null,
                "API", null, 0, "开启录像", null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channelId", channel.getId());
        data.put("streamKey", channel.streamKey());
        data.put("recording", true);
        return Result.ok(data);
    }

    @Operation(summary = "停止录像")
    @PostMapping("/channels/{id}/record/stop")
    public Result<Map<String, Object>> stop(@PathVariable Long id) {
        Channel channel = channelService.requireById(id);
        channelAccessService.assertAccess(channel, "操作");
        zlmApiClient.stopRecordMp4(channel.getStreamApp(), channel.getStreamId());

        ledgerService.record(Ledger.TYPE_RECORD_STOP, channel.getDeviceId(), channel.getId(), null,
                "API", null, 0, "停止录像", null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channelId", channel.getId());
        data.put("streamKey", channel.streamKey());
        data.put("recording", false);
        return Result.ok(data);
    }

    @Operation(summary = "分页查询录像台账")
    @GetMapping("/recordings")
    public Result<PageResult<Recording>> page(@ParameterObject PageQuery query,
                                              @RequestParam(required = false) Long channelId) {
        Page<Recording> page = new Page<>(query.safePage(), query.safeSize());
        // 通道级隔离：null=不限，否则只查授权通道
        List<Long> scope = channelScopeService.effectiveChannelIdsOrNull();
        LambdaQueryWrapper<Recording> wrapper = new LambdaQueryWrapper<Recording>()
                .in(scope != null, Recording::getChannelId, scope)
                .eq(channelId != null, Recording::getChannelId, channelId)
                .orderByDesc(Recording::getId);
        IPage<Recording> result = recordingMapper.selectPage(page, wrapper);
        return Result.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords()));
    }
}
