package com.lining.vgw.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把各类异常统一收敛成 {@link Result}，避免向调用方泄漏堆栈。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * 唯一索引冲突。
     *
     * 典型场景：逻辑删除后重新登记同名业务键 —— 数据库抛 DuplicateKeyException，
     * 若不处理前端只会看到"系统内部错误"。这里翻译成人能看懂的话。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        log.warn("唯一约束冲突: {}", raw);
        String friendly;
        if (raw.contains("uk_stream_key")) {
            friendly = "该流标识（app/stream）已被占用";
        } else if (raw.contains("uk_channel_code")) {
            friendly = "该通道编码已被占用";
        } else if (raw.contains("uk_device_code")) {
            friendly = "该设备编码已被占用";
        } else if (raw.contains("uk_app_key")) {
            friendly = "该 appKey 已存在";
        } else {
            friendly = "数据已存在（唯一约束冲突）";
        }
        return Result.fail(ErrorCode.DUPLICATE, friendly + "，请更换后重试");
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::friendlyFieldError)
                .collect(Collectors.joining("；"));
        if (msg == null || msg.isBlank()) {
            msg = "参数格式不对，请检查后重试";
        }
        return Result.fail(ErrorCode.PARAM_ERROR, msg);
    }

    /**
     * 把字段校验错误翻译成用户能看懂的话。
     * 注意：类型转换失败（比如 page=abc）也走 MethodArgumentNotValidException，
     * 此时 getDefaultMessage() 是 Spring 的英文原文，必须换掉。
     */
    private static String friendlyFieldError(FieldError error) {
        if (error.isBindingFailure()) {
            return "参数格式不对：" + error.getField();
        }
        String m = error.getDefaultMessage();
        return (m == null || m.isBlank()) ? ("参数不合法：" + error.getField()) : m;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(ErrorCode.PARAM_ERROR, msg);
    }

    /**
     * 静态资源不存在（Spring 6.1 起抛 NoResourceFoundException）。
     *
     * 这属于"路径写错 / 前端文件没部署"，不是系统故障，必须返回 404 ——
     * 否则会被下面的兜底处理器吞成"系统内部错误 5000"，排障时严重误导。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public Result<Void> handleNoResource(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.warn("静态资源不存在: {}", e.getResourcePath());
        return Result.fail(ErrorCode.NOT_FOUND, "请求的地址不存在：" + e.getResourcePath());
    }
    /** 参数绑定失败（例如 page=abc 这种类型转换不了） */
    @ExceptionHandler(org.springframework.validation.BindException.class)
    public Result<Void> handleBind(org.springframework.validation.BindException e) {
        String field = e.getFieldError() == null ? "" : "：" + e.getFieldError().getField();
        return Result.fail(ErrorCode.PARAM_ERROR, "参数格式不对" + field + "（请检查数字/时间等格式）");
    }

    /** 请求方法不对，例如该用 POST 却用了 GET */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethod(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return Result.fail(ErrorCode.PARAM_ERROR, "这个接口不支持 " + e.getMethod() + " 方式，请查看接口文档");
    }

    /** 请求体类型不对，例如没带 Content-Type: application/json */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public Result<Void> handleMediaType(org.springframework.web.HttpMediaTypeNotSupportedException e) {
        return Result.fail(ErrorCode.PARAM_ERROR, "提交的内容类型不支持，请在请求头里设置 Content-Type: application/json");
    }

    /** 缺少必填的查询参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(ErrorCode.PARAM_ERROR, "缺少必填参数：" + e.getParameterName());
    }

    /** 参数类型不对（比如 id 传了 abc） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.fail(ErrorCode.PARAM_ERROR, "参数格式不对：" + e.getName());
    }

    /** 请求体读不出来：JSON 语法错误、必填对象缺失、字段类型不匹配等 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(ErrorCode.PARAM_ERROR,
                "提交的内容格式不正确：请确认是合法的 JSON，且字段名与类型和接口文档一致");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常 [{}]: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        return Result.fail(ErrorCode.SYSTEM_ERROR, "系统出了点问题，请稍后重试；如果一直这样，请把这个页面的操作步骤告诉管理员");
    }
}
