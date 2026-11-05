package com.lining.vgw.config;

import com.lining.vgw.common.ApiKeyContext;
import com.lining.vgw.common.BizException;
import com.lining.vgw.common.ErrorCode;
import com.lining.vgw.domain.ApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 管理类接口的准入拦截。
 *
 * <p><b>判定依据是"管理权限"（is_admin），不是"通道范围"。</b>
 * 这两件事必须分开：
 * <ul>
 *   <li>一个平台可以"能看全部通道"，但它依然不该能查别人的 appKey / 看全平台统计</li>
 *   <li>所以 {@code scope_mode=ALL}（全部通道）≠ 管理权限；管理权限单独用 {@code is_admin} 表达</li>
 * </ul>
 *
 * <p>被拦截的接口：
 * <ul>
 *   <li>{@code /api/v1/api-clients/**}：能列出所有客户端的 appKey</li>
 *   <li>{@code /api/v1/stats/**}：全平台口径统计</li>
 *   <li>{@code /api/v1/devices} 的写操作：增删改设备属于平台运维</li>
 * </ul>
 */
@Component
public class AdminOnlyInterceptor implements HandlerInterceptor {

    /** 这些前缀下的全部方法都要求管理权限 */
    private static final Set<String> ADMIN_PREFIXES = Set.of(
            "/api/v1/api-clients",
            "/api/v1/stats"
    );

    /** 内置控制台凭证：无论配置如何都放行，避免把自己锁死 */
    private static final String CONSOLE_APP_KEY = "vwg_console";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        ApiClient client = ApiKeyContext.current();
        if (client == null || CONSOLE_APP_KEY.equals(client.getAppKey()) || client.admin()) {
            return true;   // 内部调用（ZLM 回调 / 定时任务）或拥有管理权限
        }
        String uri = request.getRequestURI();
        boolean blocked = ADMIN_PREFIXES.stream().anyMatch(uri::startsWith);
        if (!blocked && uri.startsWith("/api/v1/devices") && !"GET".equalsIgnoreCase(request.getMethod())) {
            blocked = true;
        }
        if (blocked) {
            throw new BizException(ErrorCode.UNAUTHORIZED,
                    "这个凭证没有管理权限，不能调用该接口"
                            + ""
                            + "。如需开通，请管理员在【权限范围】里打开「管理权限（超管）」");
        }
        return true;
    }
}