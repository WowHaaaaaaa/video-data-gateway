package com.lining.vgw.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流会话：一次推流的完整生命周期（聚合台账）。
 *
 * <p>与 {@link Ledger} 的关系：Ledger 记录"发生了什么事件"，StreamSession 记录"这一路流的完整过程"。
 */
@Data
@TableName("biz_stream_session")
public class StreamSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话单号，如 SS20250101103000xxxx */
    private String sessionNo;

    private Long channelId;

    /** app/stream */
    private String streamKey;

    private String clientIp;

    private Integer trackCount;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 持续秒数 */
    private Integer durationSec;

    /** NORMAL / TIMEOUT / SERVER_DOWN / REJECTED */
    private String endReason;

    /** 1-进行中 2-正常结束 3-异常结束 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_NORMAL_END = 2;
    public static final int STATUS_ABNORMAL_END = 3;
}
