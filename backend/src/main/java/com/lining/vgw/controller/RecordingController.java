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
import com.lining.vgw.domain.Ledger;
import com.lining.vgw.domain.Recording;
import com.lining.vgw.integration.zlm.ZlmApiClient;
import com.lining.vgw.mapper.RecordingMapper;
import com.lining.vgw.service.ChannelAccessService;
import com.lining.vgw.service.ChannelScopeService;
import com.lining.vgw.service.ChannelService;
import com.lining.vgw.service.RecordingFileService;
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
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final RecordingFileService recordingFileService;

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

    /**
     * 下载录像文件。
     *
     * <p><b>为什么不直接把 ZLM 的 http 地址丢给浏览器</b>：那样等于绕过网关直接访问录像目录——
     * 没有 api-key 鉴权、没有通道级隔离、也查不到"谁下载了哪段录像"。这里由网关从磁盘流式读出来，
     * 带上 Content-Disposition，浏览器点击就是另存为，而不是在页面里内联播放。
     *
     * <p><b>为什么用 FileSystemResource 而不是 byte[]</b>：录像动辄几十 MB 到 GB，
     * 读进内存会直接把堆打满。Resource 交给 Spring 的转换器边读边写，
     * 顺带天然支持 Range 请求（浏览器可断点续传）。
     */
    @Operation(summary = "下载录像文件（流式返回，浏览器另存为）")
    @GetMapping("/recordings/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Recording rec = recordingMapper.selectById(id);
        if (rec == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "录像记录不存在，可能已被删除");
        }
        if (rec.getFileExists() != null && rec.getFileExists() == 0) {
            throw new BizException(ErrorCode.ILLEGAL_STATE, "这段录像的文件已经清理过了，无法下载");
        }

        // 通道级隔离：录像属于某一路通道，跨通道下载要拒绝（通道已删则跳过鉴权，与清理接口保持一致）
        Channel channel = rec.getChannelId() == null ? null
                : channelService.findById(rec.getChannelId()).orElse(null);
        if (channel != null) {
            channelAccessService.assertAccess(channel, "下载");
        }

        Path target = recordingFileService.resolveDiskPath(rec.getFilePath());
        if (target == null || !Files.isRegularFile(target)) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "录像文件在磁盘上找不到了（可能被手工删除），台账记录仍然保留");
        }

        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "读取录像文件失败，请稍后重试");
        }

        String downloadName = downloadName(rec);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(downloadName, StandardCharsets.UTF_8)
                .build();

        ledgerService.record(Ledger.TYPE_API_CALL,
                channel == null ? null : channel.getDeviceId(), rec.getChannelId(), id,
                "API", null, 0, "下载录像文件 " + downloadName, null);
        log.info("下载录像 id={} 文件={} 大小={}B", id, downloadName, size);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("video/mp4"))
                .contentLength(size)
                .body(new FileSystemResource(target));
    }

    /**
     * 下载用的文件名：流标识 + 原文件名。
     * 原始文件名形如 {@code 09-15-38-0.mp4}，同名文件一堆，加上流标识才分得清是哪路通道。
     */
    private String downloadName(Recording rec) {
        String raw = (rec.getFileName() == null || rec.getFileName().isBlank())
                ? "recording.mp4" : rec.getFileName();
        String key = rec.getStreamKey();
        if (key == null || key.isBlank()) {
            return raw;
        }
        String stream = key.substring(key.lastIndexOf('/') + 1);
        return (stream.isBlank() || raw.startsWith(stream + "_")) ? raw : stream + "_" + raw;
    }
}
