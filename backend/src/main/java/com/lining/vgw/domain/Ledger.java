package com.lining.vgw.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 业务台账（事件流水）：所有业务事件按时间落一条，可查询、可追溯。
 */
@Data
@TableName("biz_ledger")
public class Ledger {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务流水号 */
    private String bizNo;

    /** 事件类型，见 {@link #TYPE_} 常量 */
    private String eventType;

    private LocalDateTime eventTime;

    private Long deviceId;

    private Long channelId;

    /** 通道编号快照（写入台账时固化，避免通道改名/删除导致历史失真） */
    private String channelCode;

    /** 流标识快照 app/stream（与 ZLM 日志、webhook 报文一一对应，便于排障） */
    private String streamKey;

    /** 关联的会话/播放/录像 ID */
    private Long refId;

    /** 调用方客户端 ID（biz_api_client.id）；为空表示内部事件（如 ZLM 回调、定时任务） */
    private Long clientId;

    /** ZLM / API / TIMER / MANUAL */
    private String source;

    private String clientIp;

    private String operator;

    /** 0-成功 其他-失败码 */
    private Integer resultCode;

    private String resultMsg;

    /** 事件明细（JSON 字符串） */
    private String detail;

    private LocalDateTime createTime;

    // ---- 事件类型常量 ----
    public static final String TYPE_PUBLISH_START = "PUBLISH_START";
    public static final String TYPE_PUBLISH_STOP = "PUBLISH_STOP";
    public static final String TYPE_PUBLISH_REJECT = "PUBLISH_REJECT";
    public static final String TYPE_PLAY_START = "PLAY_START";
    public static final String TYPE_PLAY_STOP = "PLAY_STOP";
    public static final String TYPE_RECORD_START = "RECORD_START";
    public static final String TYPE_RECORD_STOP = "RECORD_STOP";
    public static final String TYPE_RECORD_FINISH = "RECORD_FINISH";
    /** 录像文件被清理（台账保留，磁盘文件删除） */
    public static final String TYPE_RECORD_PURGED = "RECORD_PURGED";
    public static final String TYPE_DEVICE_ONLINE = "DEVICE_ONLINE";
    public static final String TYPE_DEVICE_OFFLINE = "DEVICE_OFFLINE";
    public static final String TYPE_SERVER_STARTED = "SERVER_STARTED";
    public static final String TYPE_API_CALL = "API_CALL";
    /** 对账纠正：由定时任务根据媒体服务器真实流列表修正状态 */
    public static final String TYPE_STATE_RECONCILE = "STATE_RECONCILE";
    /** 平台发起拉流（命令下发，来自 API / 控制台） */
    public static final String TYPE_STREAM_PULL_START = "STREAM_PULL_START";
    /** 平台停止拉流（命令下发） */
    public static final String TYPE_STREAM_PULL_STOP = "STREAM_PULL_STOP";
    /** 授权范围变更（例如通道/设备被删除后，从客户端授权中自动移除） */
    public static final String TYPE_AUTH_CHANGED = "AUTH_CHANGED";
}
