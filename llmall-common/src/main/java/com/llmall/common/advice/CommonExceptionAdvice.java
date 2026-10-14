package com.llmall.common.advice;

import com.llmall.common.domain.R;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.CommonException;
import com.llmall.common.exception.DbException;
import com.llmall.common.utils.WebUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.util.NestedServletException;

import java.util.stream.Collectors;

/**
 * 统一异常处理：把各类异常翻译成统一响应体（R），HTTP 状态码直接取异常自带的 code，
 * 参数校验类异常为 400，未预期异常兜底 500。
 *
 * 【注意】必须限定 SERVLET 应用：本类经 spring.factories 装配到所有服务，而网关是 WebFlux 应用、
 * 类路径上没有 javax.servlet，去掉该条件网关会以 NoClassDefFoundError 启动失败。
 */
@RestControllerAdvice
@Slf4j
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonExceptionAdvice {

    /** 回给前端的根因消息最大长度，超出部分截断 */
    private static final int MAX_ROOT_MESSAGE_LENGTH = 300;

    @ExceptionHandler(DbException.class)
    public Object handleDbException(DbException e) {
        log.error("mysql数据库操作异常 -> ", e);
        return processResponse(e);
    }

    @ExceptionHandler(CommonException.class)
    public Object handleCommonException(CommonException e) {
        // 4xx 属预期内的业务拒绝，用 warn 且不打堆栈；5xx 才是真故障，保留 error + 堆栈，
        // 混在一起打 ERROR 会让正常拒绝刷满日志与告警
        if (e.getCode() >= 500) {
            log.error("业务异常 -> {}，原因：{}", e.getClass().getName(), e.getMessage(), e);
        } else {
            log.warn("业务拒绝 -> {}，原因：{}", e.getClass().getName(), e.getMessage());
        }
        return processResponse(e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors()
                .stream().map(ObjectError::getDefaultMessage)
                .collect(Collectors.joining("|"));
        log.warn("请求参数校验异常 -> {}", msg);
        log.debug("", e);
        return processResponse(new BadRequestException(msg));
    }
    /**
     * 参数绑定异常（表单/查询参数）：{@code org.springframework.validation.BindException}。
     *
     * 【注意】同名类 {@code java.net.BindException} 与参数绑定无关，且导入错类不会编译报错：
     * 届时没有任何处理器命中，直接落到 {@code Exception} 兜底变成 500。
     */
    @ExceptionHandler(BindException.class)
    public Object handleBindException(BindException e) {
        String msg = e.getBindingResult().getAllErrors()
                .stream().map(ObjectError::getDefaultMessage)
                .collect(Collectors.joining("|"));
        if (msg.isEmpty()) {
            msg = "请求参数格式错误";
        }
        log.warn("请求参数绑定异常 -> {}", msg);
        log.debug("", e);
        return processResponse(new BadRequestException(msg));
    }

    /**
     * 请求体不是合法 JSON / 无法反序列化（少括号、类型不符、body 缺失……）：按 400 返回并带上
     * 最具体的原因，否则会退化成 500，把"客户端传错了"说成"服务端坏了"。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Object handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        String detail = rootMessage(e);
        log.warn("请求体解析失败 -> {}", detail);
        log.debug("", e);
        return processResponse(new BadRequestException("请求体格式错误，请检查 JSON 格式：" + detail));
    }

    /**
     * 路径/查询参数类型不匹配（如把 {@code /orders/abc} 传给 Long 型 orderId）：按 400 返回并
     * 指明参数名、期望类型与实际值；不处理同样会变成 500。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Object handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        Class<?> requiredType = e.getRequiredType();
        String msg = "参数 " + e.getName() + " 类型不正确，期望 "
                + (requiredType == null ? "未知类型" : requiredType.getSimpleName())
                + "，实际值：" + e.getValue();
        log.warn("请求参数类型不匹配 -> {}", msg);
        log.debug("", e);
        return processResponse(new BadRequestException(msg));
    }

    @ExceptionHandler(NestedServletException.class)
    public Object handleNestedServletException(NestedServletException e) {
        log.warn("参数异常 -> NestedServletException，{}", e.getMessage());
        log.debug("", e);
        return processResponse(new BadRequestException("请求参数处理异常"));
    }

    @ExceptionHandler(Exception.class)
    public Object handleRuntimeException(Exception e) {
        log.error("其他异常 uri : {} -> ", WebUtils.getRequest().getRequestURI(), e);
        return processResponse(new CommonException("服务器内部异常", 500));
    }

    private ResponseEntity<R<Void>> processResponse(CommonException e){
        return ResponseEntity.status(e.getCode()).body(R.error(e));
    }

    /** 取最具体的原因并截断：Jackson 的报错常常带很长一段原文，直接回给前端会淹没重点 */
    private String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage() == null ? e.getClass().getSimpleName() : root.getMessage();
        return msg.length() > MAX_ROOT_MESSAGE_LENGTH
                ? msg.substring(0, MAX_ROOT_MESSAGE_LENGTH) + "..."
                : msg;
    }
}
