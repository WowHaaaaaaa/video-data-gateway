package com.lining.vgw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lining.vgw.common.ApiKeyContext;
import com.lining.vgw.common.BizException;
import com.lining.vgw.common.Result;
import com.lining.vgw.domain.ApiClient;
import com.lining.vgw.service.ApiClientService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * API 客户端鉴权拦截器。
 *
 * <p>校验请求头 {@code api-key}，通过后把客户端放进 {@link ApiKeyContext}，
 * 业务层（台账）据此记录"谁调的"。失败直接返回 401 JSON，不进入业务代码。
 *
 * <p>设计对齐 WVP-PRO 的 {@code api-key} 请求头方案；但没有引入 Spring Security + JWT，
 * 因为我们的调用方是系统而非人，一次查库换一次请求的可读性和排障效率更高。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyInterceptor implements HandlerInterceptor {

    public static final String HEADER = "api-key";

    private final ApiClientService apiClientService;
    private final ObjectMapper objectMapper;

    /** 可通过 vgw.security.enabled=false 整体关闭（本地调试用） */
    @Value("${vgw.security.enabled:true}")
    private boolean enabled;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!enabled) {
            return true;
        }
        // 浏览器跨域预检不带自定义头，直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        try {
            ApiClient client = apiClientService.authenticate(request.getHeader(HEADER));
            ApiKeyContext.set(client);
            // 前端控制台会在请求头里带 X-Request-From: ui，
            // 用于在台账中把"人工界面操作"与"第三方程序调用"区分开
            String from = request.getHeader(ApiKeyContext.UI_HEADER);
            ApiKeyContext.setFromUi(from != null && ApiKeyContext.UI_FLAG.equalsIgnoreCase(from.trim()));
            return true;
        } catch (BizException e) {
            log.warn("接口鉴权失败: {} {} -> {}", request.getMethod(), request.getRequestURI(), e.getMessage());
            writeUnauthorized(response, e.getMessage());
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        ApiKeyContext.clear();
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> body = Result.fail(com.lining.vgw.common.ErrorCode.UNAUTHORIZED, message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
