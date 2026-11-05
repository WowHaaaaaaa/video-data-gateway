package com.lining.vgw.service;

import com.lining.vgw.domain.Channel;
import com.lining.vgw.integration.zlm.ZlmApiClient;
import com.lining.vgw.integration.zlm.ZlmProperties;
import com.lining.vgw.integration.zlm.dto.MediaInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 流标识探测：直接向媒体服务器问"这个流标识现在到底是什么状态"。
 *
 * <p><b>为什么需要：</b>界面上一行通道能看到的只有"通道名 + 状态标签"，
 * 但那两个都是<b>我们库里的值</b>（可能滞后）。排查问题时最想知道的是：
 * <ul>
 *   <li>媒体服务器上到底有没有这个 app/stream？</li>
 *   <li>如果有，是推上来的还是拉进来的？有多少人在看？在录像吗？</li>
 *   <li>如果没有，是不是有一个"下了任务但没拉通"的残留代理？</li>
 * </ul>
 *
 * <p>这个接口把上述信息一次性摊开，并给一句人话结论 ——
 * 相当于给每个通道配了个"体检"按钮，通道和流标识对不对得上，一眼就能看出来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamProbeService {

    private final ChannelService channelService;
    private final ChannelAccessService channelAccessService;
    private final ZlmApiClient zlmApiClient;
    private final ZlmProperties zlmProperties;

    public Map<String, Object> probe(Long channelId) {
        Channel channel = channelService.requireById(channelId);
        channelAccessService.assertAccess(channel, "访问");

        String app = channel.getStreamApp();
        String stream = channel.getStreamId();
        String host = hostOf(zlmProperties.getBaseUrl());

        List<MediaInfo> medias = zlmApiClient.mediaOf(app, stream);
        Integer originType = medias.stream()
                .filter(MediaInfo::isOrigin)
                .map(MediaInfo::getOriginType)
                .findFirst()
                .orElse(null);
        boolean online = originType != null;
        boolean pulled = originType != null && MediaInfo.isPullOrigin(originType);
        boolean pushed = online && !pulled;
        int readers = medias.stream().mapToInt(MediaInfo::getReaderCount).max().orElse(0);
        boolean recording = medias.stream().anyMatch(MediaInfo::isRecordingMp4);
        String schemas = medias.stream().map(MediaInfo::getSchema).distinct().sorted()
                .collect(Collectors.joining(", "));

        String proxyKey = zlmApiClient.proxyKey(app, stream);
        Map<String, Object> proxy = zlmApiClient.getProxyInfo(proxyKey);
        boolean proxyExist = proxy != null;
        boolean hasPullUrl = channel.getPullUrl() != null && !channel.getPullUrl().isBlank();

        String conclusion;
        if (online && pushed) {
            conclusion = "流标识正在被推流（在线，观看 " + readers + " 人）。推流与拉流互斥，"
                    + "要改用拉流请先停掉推流";
        } else if (online) {
            conclusion = proxyExist
                    ? "拉流任务正常：流已上线（观看 " + readers + " 人），可以正常取播放地址"
                    : "流已在线（拉流来源），但媒体服务器上查不到代理记录（可能是手动/外部拉流）";
        } else if (proxyExist) {
            conclusion = "拉流任务已注册但流还没上线：多半是拉流地址不通或设备未就绪。"
                    + "可点【停止拉流】清理残留代理后重试";
        } else if (hasPullUrl) {
            conclusion = "流标识当前不存在（既没有推流，也没有拉流任务）。该通道是拉流型，可以直接【发起拉流】";
        } else {
            conclusion = "流标识当前不存在。该通道是推流型：设备向下面的推流地址推流后会自动上线";
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channelId", channel.getId());
        data.put("channelCode", channel.getChannelCode());
        data.put("channelName", channel.getChannelName());
        data.put("streamKey", channel.streamKey());
        data.put("enabled", channel.getEnabled());
        data.put("channelStatus", channel.getStatus());
        data.put("online", online);
        data.put("originType", originType);
        data.put("originName", originType == null ? "无" : MediaInfo.originName(originType));
        data.put("schemas", schemas);
        data.put("readerCount", readers);
        data.put("recording", recording);
        data.put("proxyRegistered", proxyExist);
        data.put("proxyKey", proxyKey);
        data.put("pullUrl", StreamPullService.mask(channel.getPullUrl()));
        data.put("pushUrl", "rtmp://" + host + "/" + app + "/" + stream);
        data.put("rtspUrl", "rtsp://" + host + ":554/" + app + "/" + stream);
        data.put("httpFlvUrl", "http://" + host + "/" + app + "/" + stream + ".live.flv");
        data.put("conclusion", conclusion);
        return data;
    }

    private String hostOf(String baseUrl) {
        String h = baseUrl == null ? "127.0.0.1" : baseUrl.trim();
        h = h.replaceFirst("^https?://", "");
        while (h.endsWith("/")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }
}