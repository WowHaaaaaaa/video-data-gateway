package com.lining.vgw.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 设备新增/修改请求。
 */
@Data
public class DeviceSaveReq {

    @NotBlank(message = "设备编码不能为空")
    private String deviceCode;

    @NotBlank(message = "设备名称不能为空")
    private String deviceName;

    /** RTMP / RTSP / GB28181 */
    private String deviceType = "RTMP";

    private String manufacturer;

    private String ipAddress;

    private Integer enabled = 1;

    private String remark;
}
