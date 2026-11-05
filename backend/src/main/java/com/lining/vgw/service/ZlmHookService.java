package com.lining.vgw.service;

import com.lining.vgw.domain.Channel;
import com.lining.vgw.domain.Ledger;
import com.lining.vgw.domain.Recording;
import com.lining.vgw.domain.StreamSession;
import com.lining.vgw.mapper.RecordingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * ZLMediaKit 事件处理器（Webhook 业务的真正入口）。
 *
 * <p>ZLM 在关键节点会 POST 事件到本服务，本服务据此：
 * <ol>
 *   <li>校验流是否合法（未注册的流直接拒绝推流）</li>
 *   <li>维护流会话（开始/结束、自动算时长）</li>
 *   <li>写业务台账 / 录像台账</li>
 * </ol>
 *
 * <p><b>去重策略</b>：ZLM 的 on_stream_changed 会按协议逐条触发（rtmp/rtsp/ts/hls...），
 * 若不去重，一次断流会写多笔 PUBLISH_STOP。这里做两层防护：
 * <ul>
 *   <li>ZLM 侧配置 stream_changed_schemas=rtmp/rtsp，只订阅源流协议</li>
 *   <li>本侧只在"确实关掉了一个进行中会话"时才记账（session != null）</li>
 * </ul>
 *
 * <p>返回值必须是 ZLM 认识的格式 {@code {"code":0,"msg":"success"}}，因此直接返回 Map。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZlmHookService {

    /** 拉流代理的"客户端"就是媒体服务器自己，会话里用它标记来源 */
    private static final String PULL_CLIENT_IP = "zlm-pull";

    /** HLS/切片类协议的事件噪声很大（每个分片都可能触发），不计入播放台账 */
    private static final Set<String> NOISY_SCHEMAS = Set.of("hls", "hls.fmp4", "ts", "fmp4");

    private final ChannelService channelService;
    private final StreamSessionService sessionService;
    private final LedgerService ledgerService;
    private final RecordingMapper recordingMapper;
    private final com.lining.vgw.integration.zlm.ZlmProperties zlmProperties;

    /** 统一入口 */
    public Map<String, Object> handle(String event, Map<String, Object> body) {
        try {
            switch (event) {
                case "on_publish" -> {
                    return onPublish(body);
                }
                case "on_stream_changed" -> onStreamChanged(body);
                case "on_play" -> onPlay(body);
                case "on_stream_none_reader" -> onNoneReader(body);
                case "on_record_mp4" -> onRecordMp4(body);
                case "on_server_started" -> onServerStarted();
                default -> log.debug("未关心的 ZLM 事件: {}", event);
            }
        } catch (Exception e) {
            // 回调处理异常不能影响 ZLM 自身，记录后按成功返回，避免误拒推流
            log.error("处理 ZLM 事件 [{}] 异常, body={}", event, body, e);
        }
        return success();
    }

    /**
     * 推流鉴权 + 开户。返回非 0 的 code 会让 ZLM 拒绝这次推流。
     */
    private Map<String, Object> onPublish(Map<String, Object> body) {
        String app = str(body, "app");
        String stream = str(body, "stream");
        String ip = str(body, "ip");
        Integer tracks = intOrNull(body, "tracks");

        Channel channel = channelService.findByStreamKey(app, stream);
        if (channel == null) {
            log.warn("拒绝未注册推流: {}/{} from {}", app, stream, ip);
            ledgerService.record(Ledger.TYPE_PUBLISH_REJECT, null, null, null, "ZLM", ip,
                    2001, "未注册的流: " + app + "/" + stream, body);
            Map<String, Object> reject = new HashMap<>();
            reject.put("code", -1);
            reject.put("msg", "stream not registered: " + app + "/" + stream);
            return reject;
        }

        StreamSession session = sessionService.open(channel, ip, tracks);
        channelService.markPublishing(channel.getId());
        // 设备状态是派生值：推流开始后重算一次
        channelService.refreshDeviceStatus(channel.getDeviceId());
        ledgerService.record(Ledger.TYPE_PUBLISH_START, channel.getDeviceId(), channel.getId(),
                session.getId(), "ZLM", ip, 0, null, body);
        log.info("推流开始: {}/{} session={}", app, stream, session.getSessionNo());
        return success();
    }

    /** 流注册状态变化：regist=false 表示流已注销（断流） */
    private void onStreamChanged(Map<String, Object> body) {
        Object regist = body.get("regist");
        boolean registered = regist instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(regist));
        String app = str(body, "app");
        String stream = str(body, "stream");
        String streamKey = app + "/" + stream;
        if (registered) {
            // 推流的上线由 on_publish 处理（那里要做鉴权、开会话）；
            // 拉流代理不触发 on_publish，只会走到这里，所以补一次开户
            markOnlineIfSessionMissing(app, stream);
            return;
        }

        StreamSession session = sessionService.closeByStreamKey(streamKey,
                "NORMAL", StreamSession.STATUS_NORMAL_END);
        Channel channel = channelService.findByStreamKey(app, stream);
        if (channel != null) {
            channelService.markOffline(channel.getId());
            // 设备状态是派生值：断流后重算一次
            channelService.refreshDeviceStatus(channel.getDeviceId());
        }
        // 只有确实关掉了一个进行中会话才记一笔，避免多协议重复记账
        if (session == null) {
            log.debug("流 {} 无进行中会话，忽略重复的 stream_changed 事件 (schema={})",
                    streamKey, str(body, "schema"));
            return;
        }
        ledgerService.record(Ledger.TYPE_PUBLISH_STOP,
                channel == null ? null : channel.getDeviceId(),
                channel == null ? null : channel.getId(),
                session.getId(), "ZLM", null, 0, null, body);
        log.info("推流结束: {} 时长={}s", streamKey, session.getDurationSec());
    }

    /** 有人开始播放 */
    private void onPlay(Map<String, Object> body) {
        if (NOISY_SCHEMAS.contains(lower(str(body, "schema")))) {
            return;
        }
        String app = str(body, "app");
        String stream = str(body, "stream");
        Channel channel = channelService.findByStreamKey(app, stream);
        ledgerService.record(Ledger.TYPE_PLAY_START,
                channel == null ? null : channel.getDeviceId(),
                channel == null ? null : channel.getId(),
                null, "ZLM", str(body, "ip"), 0, null, body);
    }

    /** 无人观看（播放结束） */
    private void onNoneReader(Map<String, Object> body) {
        if (NOISY_SCHEMAS.contains(lower(str(body, "schema")))) {
            return;
        }
        String app = str(body, "app");
        String stream = str(body, "stream");
        Channel channel = channelService.findByStreamKey(app, stream);
        ledgerService.record(Ledger.TYPE_PLAY_STOP,
                channel == null ? null : channel.getDeviceId(),
                channel == null ? null : channel.getId(),
                null, "ZLM", null, 0, null, body);
    }

    /** 录像文件生成完毕 → 写录像台账 */
    private void onRecordMp4(Map<String, Object> body) {
        String app = str(body, "app");
        String stream = str(body, "stream");
        Channel channel = channelService.findByStreamKey(app, stream);

        Recording recording = new Recording();
        recording.setChannelId(channel == null ? null : channel.getId());
        recording.setStreamKey(app + "/" + stream);
        recording.setFileName(str(body, "file_name"));
        // 注意：ZLM 在 Windows 下按 GBK 输出 JSON，回调里的 file_path 含中文目录会变成乱码。
        // 相对路径字段 url（如 record/live/cam01/2026-10-29/xx.mp4）是纯 ASCII 的，
        // 用它拼出可直接回放的 http 地址，避免台账里存一堆乱码。
        String relative = str(body, "url");
        if (relative != null && !relative.isBlank()) {
            String base = zlmProperties.getBaseUrl() == null ? "" : zlmProperties.getBaseUrl().replaceAll("/+$", "");
            recording.setFilePath(base + "/" + relative.replaceAll("^/+", ""));
        } else {
            recording.setFilePath(str(body, "file_path"));
        }
        recording.setFileSize(longOrNull(body, "file_size"));
        recording.setDurationSec(intOrNull(body, "time_len"));

        Long startEpoch = longOrNull(body, "start_time");
        if (startEpoch != null) {
            LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochSecond(startEpoch), ZoneId.systemDefault());
            recording.setVideoStartTime(start);
            Integer len = recording.getDurationSec();
            recording.setVideoEndTime(len == null ? start : start.plusSeconds(len));
        }
        recording.setSource("AUTO");
        recording.setStatus(Recording.STATUS_COMPLETED);
        try {
            recordingMapper.insert(recording);
        } catch (RuntimeException e) {
            log.warn("录像台账写入失败: {}", recording.getFilePath(), e);
        }

        ledgerService.record(Ledger.TYPE_RECORD_FINISH,
                channel == null ? null : channel.getDeviceId(),
                channel == null ? null : channel.getId(),
                recording.getId(), "ZLM", null, 0, null, body);
    }

    /** ZLM 启动：把上一轮遗留的"进行中"会话全部标记异常结束 */
    private void onServerStarted() {
        int n = sessionService.markAllRunningAbnormal("SERVER_DOWN");
        // ZLM 重启后原有流全部消失，通道表里可能残留"推流中"，必须复位
        int channels = channelService.resetAllOffline();
        ledgerService.record(Ledger.TYPE_SERVER_STARTED, null, null, null, "ZLM", null, 0,
                "媒体服务器启动, 清理异常会话 " + n + " 个, 复位通道 " + channels + " 个", null);
    }

    /**
     * 拉流上线时补一次开户。
     *
     * <p>推流：ZLM 先回调 on_publish（鉴权 + 开会话），再回调 on_stream_changed(regist=true)；
     * 拉流代理：只有 on_stream_changed。这里补开会话、更新通道/设备状态并记一笔台账，
     * 让"平台发起拉流"和"设备推流"在会话表、通道状态、台账三处口径一致。
     */
    private void markOnlineIfSessionMissing(String app, String stream) {
        Channel channel = channelService.findByStreamKey(app, stream);
        if (channel == null) {
            return;
        }
        if (sessionService.findRunningByStreamKey(channel.streamKey()) != null) {
            return;   // 推流路径已经开过会话，避免重复记账
        }
        StreamSession session = sessionService.open(channel, PULL_CLIENT_IP, 1);
        channelService.markPublishing(channel.getId());
        channelService.refreshDeviceStatus(channel.getDeviceId());
        ledgerService.record(Ledger.TYPE_PUBLISH_START, channel.getDeviceId(), channel.getId(),
                session.getId(), "ZLM", null, 0, "拉流代理上线（平台发起拉流）", null);
        log.info("拉流上线: {} session={}", channel.streamKey(), session.getSessionNo());
    }

    private Map<String, Object> success() {
        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("msg", "success");
        return ok;
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private Integer intOrNull(Map<String, Object> body, String key) {
        Long v = longOrNull(body, key);
        return v == null ? null : v.intValue();
    }

    private Long longOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
