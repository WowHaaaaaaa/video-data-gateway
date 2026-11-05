package com.lining.vgw.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置，访问 http://127.0.0.1:8088/doc.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI vgwOpenApi() {
        return new OpenAPI().info(new Info()
                .title("视频数据网关 API")
                .version("v1")
                .description("设备通道管理 / 业务台账 / 播放地址签发 / ZLMediaKit 回调 / Webhook 推送")
                .contact(new Contact().name("lining")));
    }
}
