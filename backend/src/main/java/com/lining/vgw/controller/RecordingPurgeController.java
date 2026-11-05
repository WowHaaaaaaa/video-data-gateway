package com.lining.vgw.controller;

import com.lining.vgw.common.BizException;
import com.lining.vgw.common.ErrorCode;
import com.lining.vgw.common.Result;
import com.lining.vgw.domain.Ledger;
import com.lining.vgw.domain.Recording;
import com.lining.vgw.integration.zlm.ZlmProperties;
import com.lining.vgw.mapper.RecordingMapper;
import com.lining.vgw.service.ChannelAccessService;
import com.lining.vgw.service.ChannelService;
import com.lining.vgw.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 录像文件清理。
 *
 * <p><b>设计取舍：清理文件、保留记录。</b>
 * 录像台账是 append-only 的审计账 —— "某时刻产生过一段录像"这个事实不该被抹掉。
 * 但磁盘空间必须能释放，所以提供"仅删除磁盘文件"的操作：
 * <ul>
 *   <li>物理删除磁盘上的 mp4</li>
 *   <li>数据库记录保留，置 file_exists=0 并记录 purged_time</li>
 *   <li>台账记一笔 RECORD_PURGED，写明释放了多少空间</li>
 * </ul>
 * 这样既控制存储成本，又不丢审计痕迹。
 */
@Slf4j
@Tag(name = "录像管理")
@RestController
@RequestMapping("/api/v1/recordings")
@RequiredArgsConstructor
public class RecordingPurgeController {

    private final RecordingMapper recordingMapper;
    private final LedgerService ledgerService;
    private final ZlmProperties zlmProperties;
    private final ChannelService channelService;
    private final ChannelAccessService channelAccessService;

    @Operation(summary = "清理录像文件（台账记录保留，仅删除磁盘文件）")
    @PostMapping("/{id}/purge")
    public Result<Map<String, Object>> purge(@PathVariable Long id) {
        Recording rec = recordingMapper.selectById(id);
        if (rec == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "录像记录不存在，可能已被删除");
        }
        if (rec.getFileExists() != null && rec.getFileExists() == 0) {
            throw new BizException(ErrorCode.ILLEGAL_STATE, "这段录像的文件已经清理过了，不用重复操作");
        }

        // 通道级隔离：录像也属于某一路通道，跨通道清理要拒绝
        if (rec.getChannelId() != null) {
            channelService.findById(rec.getChannelId())
                    .ifPresent(ch -> channelAccessService.assertAccess(ch, "操作"));
        }

        long size = rec.getFileSize() == null ? 0L : rec.getFileSize();
        boolean deleted = false;
        String diskPath = resolveDiskPath(rec.getFilePath());
        if (diskPath != null) {
            Path p = Paths.get(diskPath);
            try {
                if (Files.exists(p)) {
                    Files.delete(p);
                    deleted = true;
                }
            } catch (IOException e) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, "删除文件失败，可能是文件被占用或没有权限");
            }
        }

        // 记录保留，只打标记
        Recording update = new Recording();
        update.setId(id);
        update.setFileExists(0);
        update.setPurgedTime(LocalDateTime.now());
        recordingMapper.updateById(update);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("recordingId", id);
        detail.put("fileName", rec.getFileName());
        detail.put("fileSize", size);
        detail.put("diskDeleted", deleted);
        detail.put("diskPath", diskPath);

        String msg = deleted
                ? "清理录像文件，释放 " + String.format("%.1f", size / 1048576.0) + " MB（台账记录保留）"
                : "录像文件在磁盘上已不存在，仅更新台账标记";
        ledgerService.record(Ledger.TYPE_RECORD_PURGED, null, rec.getChannelId(), id,
                "API", null, 0, msg, detail);
        log.warn("清理录像 id={} 文件={} 释放={}B 物理删除={}", id, rec.getFileName(), size, deleted);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("fileName", rec.getFileName());
        result.put("releasedBytes", size);
        result.put("releasedMb", Math.round(size / 1048576.0 * 10) / 10.0);
        result.put("diskDeleted", deleted);
        result.put("message", msg);
        return Result.ok(result);
    }

    /**
     * 解析 ZLM 的 www 根目录。
     *
     * <p>配置里写的是相对路径，而相对谁取决于进程启动时的工作目录
     * （项目根 / backend 都可能）。这里按候选顺序取第一个真实存在的目录，
     * 避免"工作目录不同导致清理录像失败"这种环境差异问题。
     */
    private Path resolveWwwDir() {
        String[] candidates = {
                zlmProperties.getWwwDir(),      // 配置优先，如 ./tools/zlm/www
                "../tools/zlm/www",             // 工作目录为 backend 时
                "./tools/zlm/www"               // 工作目录为项目根时
        };
        for (String c : candidates) {
            if (c == null || c.isBlank()) {
                continue;
            }
            Path p = Paths.get(c).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return Paths.get(zlmProperties.getWwwDir()).toAbsolutePath().normalize();
    }

    /**
     * 把台账里的 file_path 映射为磁盘路径。
     *
     * <p>file_path 可能是 http URL（正常情况），也可能是本地绝对路径（兜底）。
     * URL 情况下用 ZLM 的 www 根目录还原，并做目录越界检查，防止路径穿越。
     */
    private String resolveDiskPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        if (!filePath.startsWith("http://") && !filePath.startsWith("https://")) {
            return filePath;
        }
        String relative;
        try {
            relative = URI.create(filePath).getPath();
        } catch (RuntimeException e) {
            return null;
        }
        Path www = resolveWwwDir();
        Path target = www.resolve(relative.replaceFirst("^/+", "")).normalize();
        if (!target.startsWith(www)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件路径不合法，已阻止这次操作");
        }
        return target.toString();
    }
}