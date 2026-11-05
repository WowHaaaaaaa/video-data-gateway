package com.lining.vgw.config;

import com.lining.vgw.integration.zlm.ZlmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Web 层与 HTTP 客户端配置。
 */
@Configuration
@EnableConfigurationProperties(ZlmProperties.class)
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ApiKeyInterceptor apiKeyInterceptor;
    private final AdminOnlyInterceptor adminOnlyInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 演示环境放开跨域；生产应按域名白名单收敛
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /**
     * 对外 API 统一做客户端鉴权（请求头 api-key）。
     *
     * <p>放行的只有健康检查：它是给运维/监控探活用，不应要求凭证。
     * ZLM 的 /hook/** 不走这里（ZLM 是本机直连，且它不认识 api-key）。
     */

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/health");
        // 管理类接口（客户端管理 / 统计 / 设备写操作）只允许"不限"客户端调用
        registry.addInterceptor(adminOnlyInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/health");
    }

    /**
     * 调用 ZLMediaKit REST API 的客户端。
     */
    @Bean
    public RestClient zlmRestClient(ZlmProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
