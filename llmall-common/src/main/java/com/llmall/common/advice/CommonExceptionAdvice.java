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
 * 统一异常处理：把业务异常翻译成统一的响应体（R），HTTP 状态码沿用异常自带的 code。
 *
 * 【重要】必须限定 SERVLET 应用：
 *   llmall-common 通过 spring.factories 被所有服务自动装配，而网关是 WebFlux（Reactive）应用，
 *   类路径上没有 javax.servlet。不加这个条件，网关启动时会因为
 *   NoClassDefFoundError: javax/servlet/ServletException 直接起不来（本项目实测踩过）。
 */
@RestControllerAdvice
@Slf4j
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonExceptionAdvice {

    @ExceptionHandler(DbException.class)
    public Object handleDbException(DbException e) {
        log.error("mysql数据库操作异常 -> ", e);
        return processResponse(e);
    }

    @ExceptionHandler(CommonException.class)
    public Object handleCommonException(CommonException e) {
        // 分级：4xx 是"预期内的业务拒绝"（库存不足、请勿重复提交、无权访问…），用 warn 且不打堆栈；
        // 5xx 才是真故障，保留 error + 堆栈。混在一起打 ERROR 会让正常拒绝刷满日志与告警。
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
     * 参数绑定异常：{@code org.springframework.validation.BindException}。
     *
     * 【为什么必须盯紧这个 import】这里原先写的是 {@code java.net.BindException}
     * （网络编程里"端口被占用"的异常），与 spring 的参数绑定异常同名但毫无关系。
     * 后果是：表单/查询参数绑定失败时没有任何处理器命中，直接落到下面的
     * {@code Exception} 兜底 → 500「服务器内部异常」，用户明明只是参数写错了格式，
     * 却看到"服务挂了"，前端也无从提示。异常处理器导入错的类不会编译报错，
     * 只有在真跑一次非法请求时才会暴露 —— 属于必须靠实测才能发现的缺陷。
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
     * 请求体不是合法 JSON / 无法反序列化（少括号、类型不符、body 缺失……）。
     * 同样的问题：没有这个处理器时会退化成 500，把"客户端传错了"说成"服务端坏了"。
     * msg 里带上最具体的原因，前端才能把"哪个字段什么格式不对"直接展示给用户。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Object handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        String detail = rootMessage(e);
        log.warn("请求体解析失败 -> {}", detail);
        log.debug("", e);
        return processResponse(new BadRequestException("请求体格式错误，请检查 JSON 格式：" + detail));
    }

    /**
     * 路径/查询参数类型不匹配（例如把 {@code /orders/abc} 传给 Long 类型的 orderId）。
     * 不处理同样会变成 500；这里明确指出是哪个参数、期望什么类型、实际传了什么。
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
        return msg.length() > 300 ? msg.substring(0, 300) + "..." : msg;
    }
}
