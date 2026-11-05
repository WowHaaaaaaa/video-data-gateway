package com.lining.vgw.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频通道（1 设备 N 通道）。stream_app / stream_id 是 ZLM 的推流标识，
 * 也是 webhook 里校验"这路流是否合法"的依据。
 */
@Data
@TableName("biz_channel")
public class Channel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deviceId;

    private String channelCode;

    private String channelName;

    /** ZLM 应用名，如 live */
    private String streamApp;

    /** ZLM 流 ID，如 cam01 */
    private String streamId;

    /**
     * 所属设备名称（仅在返回给前端时填充，不是表字段）。
     *
     * <p>通道表只存 device_id —— 但列表上光看"通道名"分不清是哪台设备上的，
     * 排查和演示时都得来回翻设备页。这里用 @TableField(exist = false) 做个只读字段，
     * 由 Controller 一次性批量补上，避免一列 N 次查询。
     */
    @TableField(exist = false)
    private String deviceName;

    /** 源拉流地址（可选） */
    private String pullUrl;

    /** 0-离线 1-在线（预留） 2-有流在跑（界面统一显示为"在线"） */
    private Integer status;

    private Integer enabled;

    private LocalDateTime lastOnlineTime;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public String streamKey() {
        return streamApp + "/" + streamId;
    }

    /** 离线：媒体服务器上没有这路流，播不了 */
    public static final int STATUS_OFFLINE = 0;
    public static final int STATUS_ONLINE = 1;
    public static final int STATUS_PUBLISHING = 2;
}
