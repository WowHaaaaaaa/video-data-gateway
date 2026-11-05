package com.lining.vgw.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 播放地址。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayAddress {

    private Long channelId;

    private String channelName;

    /** app/stream */
    private String streamKey;

    /** 协议：flv / ws-flv / hls / webrtc / rtsp / rtmp */
    private String protocol;

    private String url;

    /** 该路流当前是否在线 */
    private boolean online;

    /** 是否为低延迟协议 */
    private boolean lowLatency;
}
