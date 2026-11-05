package com.lining.vgw.common;

import com.lining.vgw.domain.ApiClient;

/**
 * 当前请求的 API 客户端上下文。
 *
 * <p>由鉴权拦截器在请求进入时写入，业务层（尤其是台账）据此自动记录"这次操作是谁调的"。
 * 与 {@link TraceContext} 同构，用 ThreadLocal 承载单次请求内的上下文。
 *
 * <p>另外记录一个"是否来自 Web 控制台"的标记：界面操作虽然也走 HTTP 接口，
 * 但在台账里应与第三方系统调用区分开（source 记为 MANUAL 而不是 API）。
 */
public final class ApiKeyContext {

    /** 前端控制台在请求头里携带的标记值 */
    public static final String UI_HEADER = "X-Request-From";
    public static final String UI_FLAG = "ui";

    private static final ThreadLocal<ApiClient> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> FROM_UI = new ThreadLocal<>();

    private ApiKeyContext() {
    }

    public static void set(ApiClient client) {
        HOLDER.set(client);
    }

    public static ApiClient current() {
        return HOLDER.get();
    }

    /** 当前客户端 ID；未经过鉴权（如 ZLM 回调）返回 null */
    public static Long currentClientId() {
        ApiClient c = HOLDER.get();
        return c == null ? null : c.getId();
    }

    public static String currentAppKey() {
        ApiClient c = HOLDER.get();
        return c == null ? null : c.getAppKey();
    }

    /** 标记本次请求是否来自 Web 控制台 */
    public static void setFromUi(boolean fromUi) {
        FROM_UI.set(fromUi);
    }

    public static boolean isFromUi() {
        return Boolean.TRUE.equals(FROM_UI.get());
    }

    public static void clear() {
        HOLDER.remove();
        FROM_UI.remove();
    }
}