package com.lining.vgw.service;

import com.lining.vgw.common.BizException;
import com.lining.vgw.common.ErrorCode;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.domain.Ledger;
import com.lining.vgw.integration.zlm.ZlmApiClient;
import com.lining.vgw.integration.zlm.dto.MediaInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 平台发起拉流（Pull）。
 *
 * <p><b>两种接入方式的区别：</b>
 * <ul>
 *   <li>推流（Push）：设备主动把流推到我们这里，我们只负责收 —— USB 摄像头就是这种</li>
 *   <li>拉流（Pull）：设备是被动源（网络摄像头 / NVR / 其它流媒体服务器），
 *       必须由平台去它的 RTSP 地址把流拉过来</li>
 * </ul>
 *
 * <p><b>为什么必须放在平台侧做：</b>业务平台不应该知道摄像头的 RTSP 地址和密码，
 * 它只跟我们要"通道 3 的流"。地址、鉴权、重连这些细节全在网关内部消化。
 *
 * <p><b>实现方式</b>：调用 ZLM 的 {@code addStreamProxy}，由媒体服务器去拉源流，
 * 注册成 {@code app/stream}（和推流完全一样的下游待遇：一套地址、五种协议）。
 *
 * <p><b>一个容易踩的坑：</b>拉流任务下发成功 ≠ 流已上线。
 * 如果源地址不通，ZLM 里代理已注册但流没注册，此时 {@code getMediaList} 看不到它；
 * 再点一次【发起拉流】，ZLM 会报 {@code This stream already exists}。
 * 所以本类在下发前会先用 {@code getProxyInfo} 查一遍残留代理并清理，
 * 失败时也会把 ZLM 的英文报错翻译成能指导操作的中文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamPullService {

    /** 拉流超时（秒）：源地址连不上时不要太久 */
    private static final int PULL_TIMEOUT_SEC = 5;
    /** 只接受这几种源地址；其它（比如 file:// 本地文件）一律拒绝 */
    private static final Pattern SUPPORTED_SCHEME =
            Pattern.compile("^(rtsp|rtsps|rtmp|http|https)://.+", Pattern.CASE_INSENSITIVE);
    /** 地址里的密码片段，台账里要脱敏：rtsp://user:pass@host → rtsp://user:***@host */
    private static final Pattern CREDENTIAL = Pattern.compile("(//[^/:@]+):([^@/]+)@");

    private final ChannelService channelService;
    private final ChannelAccessService channelAccessService;
    private final ZlmApiClient zlmApiClient;
    private final LedgerService ledgerService;

    /** 发起拉流 */
    public Map<String, Object> start(Long channelId) {
        Channel channel = channelService.requireById(channelId);
        channelAccessService.assertAccess(channel, "操作");

        if (!Objects.equals(channel.getEnabled(), 1)) {
            throw new BizException(ErrorCode.ILLEGAL_STATE, "这个通道已停用，启用后才能发起拉流");
        }
        String url = channel.getPullUrl();
        if (url == null || url.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "这个通道还没填拉流地址。请先到【通道管理】里编辑它，填上 rtsp:// 开头的地址");
        }
        url = url.trim();
        if (!SUPPORTED_SCHEME.matcher(url).matches()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "拉流地址格式不对，只能填 rtsp:// 或 rtmp:// 开头的地址：" + mask(url));
        }

        String app = channel.getStreamApp();
        String stream = channel.getStreamId();

        // 互斥：同一路流标识不能既有推流又有拉流
        Integer origin = zlmApiClient.originTypeOf(app, stream);
        if (origin != null && !MediaInfo.isPullOrigin(origin)) {
            throw new BizException(ErrorCode.ILLEGAL_STATE,
                    "这个通道现在正在推流（" + channel.streamKey() + "），不能再拉流"
                            + "。如果要改成拉流，请先把推流停掉");
        }
        if (origin != null) {
            throw new BizException(ErrorCode.ILLEGAL_STATE, "该通道已在拉流中，无需重复发起");
        }

        // 清理"上一次没拉通"的残留代理：代理在、流不在，是导致
        // ZLM 报 "This stream already exists" 的根因
        String key = zlmApiClient.proxyKey(app, stream);
        if (zlmApiClient.getProxyInfo(key) != null) {
            log.warn("发现未上线的残留拉流代理 {}，先清理再重新下发", key);
            zlmApiClient.delStreamProxyQuietly(key);
        }

        String proxyKey = addProxy(channel, url, true);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("pullUrl", mask(url));
        detail.put("proxyKey", proxyKey);
        ledgerService.record(Ledger.TYPE_STREAM_PULL_START, channel.getDeviceId(), channel.getId(), null,
                "API", null, 0, "平台发起拉流（命令已下发）", detail);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channelId", channel.getId());
        data.put("channelCode", channel.getChannelCode());
        data.put("streamKey", channel.streamKey());
        data.put("pullUrl", mask(url));
        data.put("proxyKey", proxyKey);
        data.put("pulling", true);
        data.put("tip", "拉流任务已下发。若 3~5 秒内通道仍未变「在线」，说明拉流地址连不上，"
                + "可点【探测】查看媒体服务器上的真实状态");
        return data;
    }

    /** 停止拉流 */
    public Map<String, Object> stop(Long channelId) {
        Channel channel = channelService.requireById(channelId);
        channelAccessService.assertAccess(channel, "操作");

        String app = channel.getStreamApp();
        String stream = channel.getStreamId();
        String key = zlmApiClient.proxyKey(app, stream);

        Integer origin = zlmApiClient.originTypeOf(app, stream);
        boolean pulling = origin != null && MediaInfo.isPullOrigin(origin);
        boolean proxyExist = zlmApiClient.getProxyInfo(key) != null;

        if (!pulling && !proxyExist) {
            throw new BizException(ErrorCode.ILLEGAL_STATE,
                    "该通道当前没有进行中的拉流任务，无需停止");
        }

        // 两种都要清理：拉流中的、以及"下了任务但没拉通"的残留代理
        zlmApiClient.delStreamProxy(key);

        String msg = pulling ? "平台停止拉流（命令已下发）" : "清理未上线的拉流任务（流标识未被占用）";
        ledgerService.record(Ledger.TYPE_STREAM_PULL_STOP, channel.getDeviceId(), channel.getId(), null,
                "API", null, 0, msg, null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channelId", channel.getId());
        data.put("channelCode", channel.getChannelCode());
        data.put("streamKey", channel.streamKey());
        data.put("pulling", false);
        data.put("tip", pulling ? "拉流任务已删除，流会随之注销" : "残留拉流任务已清理");
        return data;
    }

    /**
     * 下发拉流，并把 ZLM 的英文报错翻译成能指导操作的中文。
     *
     * @param retryOnExists 遇到 "already exists" 时是否清理后重试一次
     */
    private String addProxy(Channel channel, String url, boolean retryOnExists) {
        String app = channel.getStreamApp();
        String stream = channel.getStreamId();
        try {
            return zlmApiClient.addStreamProxy(app, stream, url, PULL_TIMEOUT_SEC);
        } catch (BizException e) {
            String raw = String.valueOf(e.getMessage());
            if (raw.contains("不可达") || raw.contains("timed out")) {
                // 读超时的典型场景：ZLM 正在阻塞地尝试连接源地址，
                // 此时"任务可能已经注册成功、只是流还没上线"。
                // 先问一句代理在不在，避免把"已下发未上线"误报成"媒体服务器不可达"
                String key = zlmApiClient.proxyKey(app, stream);
                if (zlmApiClient.getProxyInfo(key) != null) {
                    log.warn("下发拉流返回超时，但代理已注册，按已下发处理: {}", channel.streamKey());
                    return key;
                }
            }
            if (retryOnExists && raw.contains("already exists")) {
                // 极端情况：探测与下发之间存在并发（或 ZLM 侧残留），清一次再来
                log.warn("ZLM 报告流标识已存在，清理代理后重试一次: {}", channel.streamKey());
                zlmApiClient.delStreamProxyQuietly(zlmApiClient.proxyKey(app, stream));
                return addProxy(channel, url, false);
            }
            throw translate(channel, e, raw, url);
        }
    }

    /** ZLM 报错 → 中文提示 */
    private BizException translate(Channel channel, BizException origin, String raw, String url) {
        if (raw.contains("不可达") && !raw.contains("timed out")) {
            return origin;   // 真的连不上媒体服务器，原样抛
        }
        if (raw.contains("timed out")) {
            return new BizException(ErrorCode.ZLM_ERROR,
                    "拉流下发超时：媒体服务器正在尝试连接 " + mask(url) + "，"
                            + "可能要多等一会儿。请确认摄像头在线、地址可达，稍后点【探测】看是否已就绪");
        }
        // 剥掉 ZlmApiClient 加的固定前缀，剩下的才是 ZLM 原始英文报错（用于分类）
        // 注意：以前这里手写的是旧文案 "媒体服务器拒绝拉流任务: "，
        // 后来前缀改成了"媒体服务器拒绝了这次拉流"，剥不掉，于是提示套娃成
        // "拉流失败：媒体服务器拒绝了这次拉流：connect failed"。现在统一引用常量。
        String msg = raw
                .replace(ZlmApiClient.PULL_REJECTED + "：", "")
                .replace(ZlmApiClient.PULL_REJECTED + ":", "")
                .trim();
        String lower = msg.toLowerCase();
        String text;
        if (lower.contains("already exists")) {
            text = "这个流标识（" + channel.streamKey() + "）在媒体服务器上已经存在，可能是上一次拉流没清理干净，"
                    + "请先点【停止拉流】清理一下再试";
        } else if (lower.contains("play schema")) {
            // 媒体服务器没编译这种源协议的支持。当前 ZLM 构建只接受 rtsp/rtmp 输入，
            // 给 http/https 源会直接回 "not supported play schema:http://..."，
            // 跟"地址不通"是两件事，单独给一句话，免得上线后去查摄像头是不是离线
            text = "拉流失败：媒体服务器不支持这种源协议（" + msg + "）。"
                    + "当前只支持 rtsp:// 和 rtmp:// 开头的拉流地址";
        } else if (lower.contains("timeout") || lower.contains("connect") || lower.contains("refused")) {
            text = "拉流失败：连不上拉流地址（" + msg + "）。请确认设备在线、IP 与端口正确，"
                    + "且该地址在本机可访问（可在浏览器/ffplay 先验证一下）";
        } else if (lower.contains("401") || lower.contains("unauthor") || lower.contains("auth")) {
            text = "拉流失败：源设备拒绝认证（" + msg + "）。请检查拉流地址里的用户名/密码";
        } else {
            // 未分类的拒绝：对外只给一句确定的结论，原始报文进日志（排障时不丢信息，
            // 界面/接口调用方也不用看英文堆栈）
            log.warn("拉流被媒体服务器拒绝（未分类）: channel={} raw={}", channel.streamKey(), msg);
            text = "拉流失败：媒体服务器拒绝了这次拉流";
        }
        return new BizException(ErrorCode.ZLM_ERROR, text);
    }

    /** 台账脱敏：不要把摄像头密码写进账本 */
    public static String mask(String url) {
        if (url == null) {
            return null;
        }
        return CREDENTIAL.matcher(url).replaceFirst("$1:***@");
    }
}