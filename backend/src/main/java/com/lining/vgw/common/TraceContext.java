package com.lining.vgw.common;

import java.util.UUID;

/**
 * 链路追踪上下文：让同一请求的日志、响应体、调用下游都带上同一个 traceId。
 */
public final class TraceContext {

    public static final String HEADER = "X-Trace-Id";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String current() {
        String id = HOLDER.get();
        if (id == null) {
            id = generate();
            HOLDER.set(id);
        }
        return id;
    }

    public static void set(String traceId) {
        HOLDER.set(traceId);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
