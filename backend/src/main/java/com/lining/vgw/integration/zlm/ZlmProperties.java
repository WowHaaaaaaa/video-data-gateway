package com.lining.vgw.integration.zlm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ZLMediaKit 连接配置。
 *
 * <p>注意：<b>secret 必须是 32 位字母数字串</b>。
 * ZLMediaKit 2024 之后的版本会校验该值，格式非法会在启动时随机生成并回写 config.ini，
 * 导致本服务调用 API 时返回 {@code -100 Incorrect secret}。
 */
@Data
@ConfigurationProperties(prefix = "vgw.zlm")
public class ZlmProperties {

    /** ZLM 的 HTTP/API 地址，如 http://127.0.0.1 */
    private String baseUrl = "http://127.0.0.1";

    /** 与 ZLM config.ini 中 [api] secret 一致，必须是 32 位字母数字串 */
    private String secret;

    /** 网关自身地址，ZLM 会把 webhook 回调到这里 */
    private String hookBaseUrl = "http://127.0.0.1:8088";

    /** 默认应用名，推流地址形如 rtmp://ip/live/{streamId} */
    private String defaultApp = "live";

    /**
     * 默认虚拟主机。ZLMediaKit 的多数 API（如 startRecord / openRtpServer）要求显式传 vhost，
     * 单机默认就是 __defaultVhost__。
     */
    private String defaultVhost = "__defaultVhost__";

    /**
     * ZLM 的静态文件根目录（其 config.ini 里的 http.rootPath）。
     * 录像文件存在这里，清理磁盘文件时用它把 http URL 映射回本地路径。
     */
    private String wwwDir = "./tools/zlm/www";
}
