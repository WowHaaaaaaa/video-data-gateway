package com.lining.vgw.controller;

import com.lining.vgw.common.ApiKeyContext;
import com.lining.vgw.common.BizException;
import com.lining.vgw.common.ErrorCode;
import com.lining.vgw.common.Result;
import com.lining.vgw.domain.ApiClient;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.domain.Ledger;
import com.lining.vgw.dto.PlayAddress;
import com.lining.vgw.service.ChannelAccessService;
import com.lining.vgw.service.ChannelService;
import com.lining.vgw.service.LedgerService;
import com.lining.vgw.service.StreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 流与播放地址接口。
 *
 * <p>播放地址是"业务平台调视频"的入口：客户端拿 api-key 来换地址，
 * 我们在此处完成<b>通道级授权</b>并<b>记一笔台账</b>（谁、何时、要了哪路流）。
 */
@Tag(name = "流与播放")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StreamController {

    private final StreamService streamService;
    private final ChannelService channelService;
    private final LedgerService ledgerService;
    private final ChannelAccessService channelAccessService;

    @Operation(summary = "查询在线流（DB + ZLM 实时合并）")
    @GetMapping("/streams")
    public Result<List<Map<String, Object>>> streams() {
        return Result.ok(streamService.listStreams());
    }

    @Operation(summary = "签发播放地址（协议转换由媒体服务器完成，网关只负责拼地址）")
    @GetMapping("/channels/{id}/play-address")
    public Result<PlayAddress> playAddress(
            @Parameter(description = "通道 ID", required = true, example = "3")
            @PathVariable Long id,
            @Parameter(description = "播放协议："
                    + "flv(默认，浏览器+flv.js) / ws-flv(浏览器+flv.js) / hls(浏览器+hls.js，延迟10~30s) "
                    + "浏览器可直接播；"
                    + "rtsp / rtmp 需外部播放器(VLC/ffmpeg)；"
                    + "webrtc 需媒体服务器编译 WebRTC 支持（当前 ZLM 构建不含，地址会返回但连不上）",
                    schema = @Schema(allowableValues = {"flv", "ws-flv", "hls", "webrtc", "rtsp", "rtmp"},
                            defaultValue = "flv", example = "hls"))
            @RequestParam(defaultValue = "flv") String protocol) {
        Channel channel = channelService.requireById(id);

        // 授权判定统一走 ChannelAccessService（设备级 + 通道级，越权自动记台账）
        channelAccessService.assertAccess(channel, "访问");

        PlayAddress address;
        try {
            address = streamService.playAddress(channel, protocol);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, e.getMessage());
        }

        // 台账：记录了 client_id（由鉴权拦截器写入上下文，LedgerService 自动带出）
        ledgerService.record(Ledger.TYPE_API_CALL, channel.getDeviceId(), channel.getId(), null,
                "API", null, 0, "签发播放地址 protocol=" + protocol, address);

        return Result.ok(address);
    }
}
