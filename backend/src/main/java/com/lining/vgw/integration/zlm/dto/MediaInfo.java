package com.lining.vgw.integration.zlm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * ZLMediaKit getMediaList 返回的单条流信息（一个 schema 一行）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MediaInfo {

    /** 源类型：未知（转协议产物等） */
    public static final int ORIGIN_NONE = 0;
    /** 源类型：RTMP 推流 */
    public static final int ORIGIN_RTMP_PUSH = 1;
    /** 源类型：RTSP 推流 */
    public static final int ORIGIN_RTSP_PUSH = 2;
    /** 源类型：RTSP 拉流（播放器代理方式） */
    public static final int ORIGIN_RTSP_PULL = 3;
    /** 源类型：RTMP 拉流 */
    public static final int ORIGIN_RTMP_PULL = 4;
    /** 源类型：HLS 拉流 */
    public static final int ORIGIN_HLS_PULL = 5;
    /** 源类型：播放器代理 */
    public static final int ORIGIN_PLAYER_PROXY = 6;
    /** 源类型：ffmpeg 拉流（addStreamProxy 走的就是这条） */
    public static final int ORIGIN_FFMPEG_PULL = 7;

    /** 应用名 */
    private String app;
    /** 流 ID */
    private String stream;
    /** 协议类型：rtmp / rtsp / ts / fmp4 / hls / webrtc */
    private String schema;
    /** 当前观看人数 */
    private int readerCount;
    /** 拉流总人数（各协议合计） */
    private int totalReaderCount;
    /** 源类型，取值见上面的 ORIGIN_ 常量 */
    private int originType;
    /** 存活秒数 */
    private int aliveSecond;
    /** 字节速度 */
    private long bytesSpeed;
    /**
     * 是否正在录制 MP4。
     *
     * 注意：ZLM 的字段名是 isRecordingMP4，而 Lombok 生成的 getter 会是 isRecordingMp4()，
     * Jackson 默认推导的属性名与之不匹配，必须显式标注 @JsonProperty 才能正确反序列化。
     */
    @JsonProperty("isRecordingMP4")
    private boolean recordingMp4;

    @JsonProperty("isRecordingHLS")
    private boolean recordingHls;

    public String streamKey() {
        return app + "/" + stream;
    }

    /** 是否是一个"源"（推流或拉流的本体），而不是转协议出来的产物 */
    public boolean isOrigin() {
        return originType != ORIGIN_NONE;
    }

    /** 是否是"拉"进来的流 */
    public boolean isPulled() {
        return isPullOrigin(originType);
    }

    /** 是否是"推"上来的流 */
    public boolean isPushed() {
        return originType == ORIGIN_RTMP_PUSH || originType == ORIGIN_RTSP_PUSH;
    }

    /**
     * originType 是否属于"拉流"类。
     *
     * <p>ZLM 的 addStreamProxy 会按源协议走不同实现：rtsp 源可能用播放器代理(3)，
     * 其它源走 ffmpeg 拉流(7)，所以这里一并认。
     */
    public static boolean isPullOrigin(int originType) {
        return originType == ORIGIN_RTSP_PULL
                || originType == ORIGIN_RTMP_PULL
                || originType == ORIGIN_HLS_PULL
                || originType == ORIGIN_FFMPEG_PULL;
    }

    /** 给前端/台账看的中文名 */
    public static String originName(int originType) {
        return switch (originType) {
            case ORIGIN_RTMP_PUSH, ORIGIN_RTSP_PUSH -> "推流";
            case ORIGIN_RTSP_PULL, ORIGIN_RTMP_PULL, ORIGIN_HLS_PULL, ORIGIN_FFMPEG_PULL -> "拉流";
            case ORIGIN_PLAYER_PROXY -> "播放器代理";
            default -> "转协议";
        };
    }
}