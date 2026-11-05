package com.lining.vgw.integration.zlm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lining.vgw.common.BizException;
import com.lining.vgw.common.ErrorCode;
import com.lining.vgw.integration.zlm.dto.MediaInfo;
import com.lining.vgw.integration.zlm.dto.ZlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ZLMediaKit REST API 客户端。
 *
 * <p>所有调用都会自动附加 secret 参数。失败时抛 {@link BizException}(ZLM_ERROR)，
 * 调用方无需处理底层异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZlmApiClient {

    /**
     * 拉流被媒体服务器拒绝时的固定前缀。
     *
     * <p>抽成常量是因为两处要用：这里拼原始报文，{@code StreamPullService} 里再把它剥掉
     * 做二次翻译。以前两边各写一份字面量，改文案时漏了一处，就拼出
     * "拉流失败：媒体服务器拒绝了这次拉流：connect failed" 这种套娃提示。
     */
    public static final String PULL_REJECTED = "媒体服务器拒绝了这次拉流";

    private final RestClient zlmRestClient;
    private final ZlmProperties props;
    private final ObjectMapper objectMapper;

    /**
     * 调用 ZLM 的任意 API。
     *
     * @param api    接口名，如 getMediaList
     * @param params 业务参数（secret 会自动加入）
     */
    public ZlmResponse call(String api, Map<String, Object> params) {
        return call(api, params, true);
    }

    /**
     * 调用 ZLM API，但不打印失败告警。
     *
     * <p>用于"探测类"查询（例如代理是否存在）—— 这类接口"查不到"是正常结果，
     * 不该在日志里刷 WARN。
     */
    public ZlmResponse callQuiet(String api, Map<String, Object> params) {
        return call(api, params, false);
    }

    private ZlmResponse call(String api, Map<String, Object> params, boolean warnOnFail) {
        Map<String, Object> query = new LinkedHashMap<>();
        if (params != null) {
            query.putAll(params);
        }
        query.put("secret", props.getSecret());

        try {
            ZlmResponse resp = zlmRestClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/index/api/" + api);
                        query.forEach(uriBuilder::queryParam);
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<ZlmResponse>() {
                    });
            if (resp == null) {
                throw new BizException(ErrorCode.ZLM_ERROR, "媒体服务器没有返回内容，请稍后重试");
            }
            if (warnOnFail && !resp.isSuccess()) {
                log.warn("ZLM API [{}] 返回失败: code={}, msg={}", api, resp.getCode(), resp.getMsg());
            }
            return resp;
        } catch (RestClientException e) {
            log.error("调用 ZLM API [{}] 异常", api, e);
            throw new BizException(ErrorCode.ZLM_UNREACHABLE, "连不上媒体服务器（ZLM），请确认它是启动状态");
        }
    }

    /** 查询当前所有在线流 */
    public List<MediaInfo> getMediaList() {
        ZlmResponse resp = call("getMediaList", Map.of());
        if (!resp.isSuccess()) {
            return List.of();
        }
        List<MediaInfo> result = new ArrayList<>();
        for (Object item : resp.dataList()) {
            try {
                result.add(objectMapper.convertValue(item, MediaInfo.class));
            } catch (IllegalArgumentException e) {
                log.debug("忽略无法解析的媒体信息: {}", item);
            }
        }
        return result;
    }

    /** 某一路流的所有 schema 条目（同一个 app/stream 下 rtmp/rtsp/hls... 各一行） */
    public List<MediaInfo> mediaOf(String app, String stream) {
        return getMediaList().stream()
                .filter(m -> app.equals(m.getApp()) && stream.equals(m.getStream()))
                .toList();
    }

    /**
     * 某一路流"源"的类型。
     *
     * @return 1/2 推流，3/4/5/7 拉流；不在线返回 null
     */
    public Integer originTypeOf(String app, String stream) {
        return mediaOf(app, stream).stream()
                .filter(MediaInfo::isOrigin)
                .map(MediaInfo::getOriginType)
                .findFirst()
                .orElse(null);
    }

    /** 判断某一路流是否在线（推流或拉流都算，只看源，不含转协议产物） */
    public boolean isStreamOnline(String app, String stream) {
        return mediaOf(app, stream).stream().anyMatch(MediaInfo::isOrigin);
    }

    /**
     * 添加一条拉流代理：让媒体服务器主动去拉 rtsp/rtmp/http 源，并注册成我们自己的流。
     *
     * <p>这是"平台发起拉流"的实现方式 —— 摄像头不需要连我们，我们去连它。
     *
     * @param url        源地址，必须是 rtsp:// rtmp:// http(s)://
     * @param timeoutSec 拉流超时秒数
     * @return 代理 key，形如 {@code __defaultVhost__/live/cam01}，停止拉流时要用
     */
    public String addStreamProxy(String app, String stream, String url, int timeoutSec) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("vhost", props.getDefaultVhost());
        params.put("app", app);
        params.put("stream", stream);
        params.put("url", url);
        // rtsp 传输方式：0=tcp 更稳，1=udp
        params.put("rtp_type", 0);
        params.put("timeout_sec", timeoutSec);
        // 有界重试：默认 -1 表示无限重试，会让"拉不通"的任务在媒体服务器上长期残留，
        // 表现为下次下发同一个流标识时 ZLM 报 This stream already exists
        params.put("retry_count", 3);
        params.put("enable_audio", 1);
        // 不在这里开录制：录像统一走按需的 startRecord 接口，避免拉流一开就写磁盘
        params.put("enable_mp4", 0);
        params.put("enable_rtsp", 1);
        params.put("enable_rtmp", 1);
        params.put("enable_hls", 1);
        params.put("enable_ts", 1);
        params.put("enable_fmp4", 1);

        ZlmResponse resp = call("addStreamProxy", params);
        if (!resp.isSuccess()) {
            throw new BizException(ErrorCode.ZLM_ERROR, PULL_REJECTED + "：" + resp.getMsg());
        }
        Object key = resp.dataMap().get("key");
        String result = key == null ? proxyKey(app, stream) : String.valueOf(key);
        log.info("已下发拉流任务: {} <- {} (key={})", app + "/" + stream, url, result);
        return result;
    }

    /** 删除拉流代理（key 形如 __defaultVhost__/live/cam01） */
    public void delStreamProxy(String key) {
        ZlmResponse resp = call("delStreamProxy", Map.of("key", key));
        if (!resp.isSuccess()) {
            throw new BizException(ErrorCode.ZLM_ERROR, "停止拉流失败：" + resp.getMsg());
        }
        log.info("已删除拉流任务: key={}", key);
    }

    /**
     * 查询某个拉流代理是否存在。
     *
     * <p>为什么需要它：ZLM 的 {@code getMediaList} 只反映"已经在跑的流"。
     * 拉流任务下发后、源地址还没拉通（设备没上线 / 地址不通）时，
     * 代理已注册但流尚未注册 —— 这时再下发同一个流标识，ZLM 会直接报
     * {@code This stream already exists}，用户看到的现象就是"点了没反应/报错"。
     * 所以下发前必须先问一句"这个代理是不是已经存在"。
     *
     * @return 代理信息；不存在返回 null
     */
    public Map<String, Object> getProxyInfo(String key) {
        try {
            ZlmResponse resp = callQuiet("getProxyInfo", Map.of("key", key));
            if (!resp.isSuccess()) {
                return null;
            }
            Map<String, Object> data = resp.dataMap();
            return data == null || data.isEmpty() ? null : data;
        } catch (RuntimeException e) {
            // 媒体服务器不可达时不要抛：这里的语义是"查一下有没有"，查不到就当没有
            log.debug("查询拉流代理失败: key={}, {}", key, e.getMessage());
            return null;
        }
    }

    /** 删除代理但忽略"本来就没有"的错误（清理残留时用） */
    public boolean delStreamProxyQuietly(String key) {
        try {
            ZlmResponse resp = callQuiet("delStreamProxy", Map.of("key", key));
            return resp.isSuccess();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 拉流代理的默认 key 规则：vhost/app/stream */
    public String proxyKey(String app, String stream) {
        return props.getDefaultVhost() + "/" + app + "/" + stream;
    }

    /** 开一个 GB28181 RTP 收流端口，返回实际端口 */
    public int openRtpServer(String stream, int port) {
        ZlmResponse resp = call("openRtpServer", Map.of("stream_id", stream, "port", port, "tcp_mode", 0));
        Object actual = resp.dataMap().get("port");
        if (!resp.isSuccess() || actual == null) {
            throw new BizException(ErrorCode.ZLM_ERROR, "开启 RTP 收流端口失败: " + resp.getMsg());
        }
        return Integer.parseInt(String.valueOf(actual));
    }

    /** 开始录制 MP4（ZLM 要求必须显式传 vhost） */
    public void startRecordMp4(String app, String stream) {
        ZlmResponse resp = call("startRecord", Map.of(
                "type", 1,
                "vhost", props.getDefaultVhost(),
                "app", app,
                "stream", stream));
        if (!resp.isSuccess()) {
            throw new BizException(ErrorCode.ZLM_ERROR, "开始录像失败：" + resp.getMsg());
        }
    }

    /** 停止录制 MP4 */
    public void stopRecordMp4(String app, String stream) {
        ZlmResponse resp = call("stopRecord", Map.of(
                "type", 1,
                "vhost", props.getDefaultVhost(),
                "app", app,
                "stream", stream));
        if (!resp.isSuccess()) {
            throw new BizException(ErrorCode.ZLM_ERROR, "停止录像失败：" + resp.getMsg());
        }
    }

    /** 服务器配置（用于健康检查） */
    public boolean ping() {
        try {
            ZlmResponse resp = call("getServerConfig", Map.of());
            return resp.isSuccess();
        } catch (RuntimeException e) {
            return false;
        }
    }
}