package com.lining.vgw.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 通道新增/修改请求。
 */
@Data
public class ChannelSaveReq {

    @NotNull(message = "所属设备不能为空")
    private Long deviceId;

    @NotBlank(message = "通道编码不能为空")
    private String channelCode;

    @NotBlank(message = "通道名称不能为空")
    private String channelName;

    @NotBlank(message = "streamApp 不能为空")
    private String streamApp = "live";

    @NotBlank(message = "streamId 不能为空")
    private String streamId;

    private String pullUrl;

    private Integer enabled = 1;
}
