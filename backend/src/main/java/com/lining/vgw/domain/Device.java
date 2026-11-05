package com.lining.vgw.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频设备（主数据）。
 */
@Data
@TableName("biz_device")
public class Device {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备编码（唯一） */
    private String deviceCode;

    private String deviceName;

    /** 接入类型：RTMP / RTSP / GB28181 */
    private String deviceType;

    private String manufacturer;

    private String ipAddress;

    /** 0-离线 1-在线 */
    private Integer status;

    /** 0-停用 1-启用 */
    private Integer enabled;

    private String remark;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
