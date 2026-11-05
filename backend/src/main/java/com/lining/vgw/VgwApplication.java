package com.lining.vgw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 视频数据网关启动类。
 *
 * <p>职责划分：
 * <ul>
 *   <li>媒体面：ZLMediaKit（独立进程）负责收流、转协议、分发、录像</li>
 *   <li>业务面：本服务负责设备通道管理、业务台账、对外 REST API 与 Webhook 推送</li>
 * </ul>
 */
@EnableScheduling
@SpringBootApplication
public class VgwApplication {

    public static void main(String[] args) {
        SpringApplication.run(VgwApplication.class, args);
    }
}
