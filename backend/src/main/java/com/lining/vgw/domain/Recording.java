package com.lining.vgw.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 录像台账：由 ZLM 的 on_record_mp4 回调写入。
 */
@Data
@TableName("biz_recording")
public class Recording {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long channelId;

    private String streamKey;

    private String fileName;

    private String filePath;

    /** 字节 */
    private Long fileSize;

    private Integer durationSec;

    private LocalDateTime videoStartTime;

    private LocalDateTime videoEndTime;

    /** MANUAL / AUTO */
    private String source;

    /** 1-录制中 2-已完成 3-异常 */
    private Integer status;

    private LocalDateTime createTime;

    /** 文件是否存在：0 已清理（台账保留） / 1 存在 */
    private Integer fileExists;

    /** 文件清理时间 */
    private LocalDateTime purgedTime;

    public static final int STATUS_COMPLETED = 2;
}
