package com.lining.vgw.common;

/**
 * 统一错误码。
 *
 * <p>分段约定：0 成功；1xxx 参数；2xxx 业务；3xxx 上游(ZLMediaKit)；5xxx 系统。
 */
public enum ErrorCode {

    SUCCESS(0, "success"),

    PARAM_ERROR(1000, "参数错误"),
    UNAUTHORIZED(1001, "未授权"),

    NOT_FOUND(2001, "资源不存在"),
    DUPLICATE(2002, "资源已存在"),
    ILLEGAL_STATE(2003, "当前状态不允许该操作"),

    ZLM_ERROR(3001, "媒体服务器调用失败"),
    ZLM_UNREACHABLE(3002, "媒体服务器不可达"),

    SYSTEM_ERROR(5000, "系统内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
