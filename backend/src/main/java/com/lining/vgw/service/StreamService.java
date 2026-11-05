package com.lining.vgw.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lining.vgw.common.AccessScope;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.dto.PlayAddress;
import com.lining.vgw.integration.zlm.ZlmApiClient;
import com.lining.vgw.integration.zlm.ZlmProperties;
import com.lining.vgw.integration.zlm.dto.MediaInfo;
import com.lining.vgw.mapper.ChannelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 流服务：合并在线流信息、签发播放地址。
 */
@Service
@RequiredArgsConstructor
public class StreamService {

    private final ChannelMapper channelMapper;
    private final ZlmApiClient zlmApiClient;
    private final ZlmProperties zlmProperties;

    /**
     * 在线流列表：DB 里的通道 + ZLM 实时在线状态合并。
     */
    public List<Map<String, Object>> listStreams() {
        List<MediaInfo> medias = zlmApiClient.getMediaList();
        Map<String, List<MediaInfo>> grouped = medias.stream()
                .collect(Collectors.groupingBy(MediaInfo::streamKey));

        List<Channel> channels = channelMapper.selectList(
                new LambdaQueryWrapper<Channel>().orderByAsc(Channel::getId));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Channel channel : channels) {
            // 通道级隔离：未授权的通道不出现在列表里
            if (!AccessScope.canAccess(channel.getId(), channel.getDeviceId())) {
                continue;
            }
            String key = channel.streamKey();
            List<MediaInfo> items = grouped.getOrDefault(key, List.of());
            boolean online = items.stream().anyMatch(MediaInfo::isOrigin);
            Integer originType = items.stream().filter(MediaInfo::isOrigin)
                    .map(MediaInfo::getOriginType).findFirst().orElse(null);
            boolean pulled = originType != null && MediaInfo.isPullOrigin(originType);
            int readers = items.stream().mapToInt(MediaInfo::getReaderCount).max().orElse(0);
            List<String> schemas = items.stream().map(MediaInfo::getSchema).distinct().collect(Collectors.toList());
            // 是否正在录制（ZLM 的 getMediaList 已经带了该标记，无需额外调用）
            boolean recording = items.stream().anyMatch(MediaInfo::isRecordingMp4);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channelId", channel.getId());
            item.put("channelCode", channel.getChannelCode());
            item.put("channelName", channel.getChannelName());
            item.put("streamKey", key);
            item.put("status", channel.getStatus());
            item.put("online", online);
            item.put("readerCount", readers);
            item.put("schemas", schemas);
            item.put("recording", recording);
            item.put("originType", originType);
            // 这路流是拉进来的还是推上来的：前端据此显示接入方式
            item.put("pulled", pulled);
            item.put("originName", originType == null ? "未知" : MediaInfo.originName(originType));
            result.add(item);
        }
        return result;
    }

    /**
     * 签发播放地址。
     *
     * @param protocol flv / ws-flv / hls / webrtc / rtsp / rtmp
     */
    public PlayAddress playAddress(Channel channel, String protocol) {
        String host = hostOf(zlmProperties.getBaseUrl());
        String app = channel.getStreamApp();
        String stream = channel.getStreamId();
        String p = protocol == null ? "flv" : protocol.toLowerCase();

        String url = switch (p) {
            case "flv" -> "http://" + host + "/" + app + "/" + stream + ".live.flv";
            case "ws-flv", "ws" -> "ws://" + host + "/" + app + "/" + stream + ".live.flv";
            case "hls" -> "http://" + host + "/" + app + "/" + stream + "/hls.m3u8";
            case "webrtc", "rtc" -> "http://" + host + "/index/api/webrtc?app=" + app
                    + "&stream=" + stream + "&type=play";
            case "rtsp" -> "rtsp://" + host + ":554/" + app + "/" + stream;
            case "rtmp" -> "rtmp://" + host + "/" + app + "/" + stream;
            default -> throw new IllegalArgumentException("不支持的协议: " + protocol);
        };

        boolean online = zlmApiClient.isStreamOnline(app, stream);
        boolean lowLatency = !"hls".equals(p);

        PlayAddress vo = new PlayAddress();
        vo.setChannelId(channel.getId());
        vo.setChannelName(channel.getChannelName());
        vo.setStreamKey(channel.streamKey());
        vo.setProtocol(p);
        vo.setUrl(url);
        vo.setOnline(online);
        vo.setLowLatency(lowLatency);
        return vo;
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
