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

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 统一异常处理：把各类异常翻译成统一响应体 R，HTTP 状态码取异常自带的 code。
 *
 * 【映射策略】
 * 1. CommonException 及其子类回各自的 code：BadRequestException 为 400，
 *    DbException 为 500（数据库操作失败属服务端故障，不能被说成客户端传错）；
 * 2. 参数绑定、请求体解析、参数类型不匹配、方法级参数校验失败 → 400；
 * 3. IllegalStateException、RuntimeException 等一切未被上面命中的异常 → 兜底 500。
 *
 * 【为什么不把它们统一成业务异常】兜底 500 承载的是基础设施与编程错误
 * （消息体序列化失败、未注册的消息类型、下游检索不可用等）：这不是调用方请求错了，
 * 而是服务端自身故障。统一成 400 会把 500 变成 400 —— 监控里的 5xx 归零、告警不再触发，
 * 真实故障被静默掩盖。故这里刻意保留两条出路。
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

    /**
     * 数据库操作异常：DbException 自带 code 500，因此这里只补日志、不改状态码 ——
     * 数据库故障必须让运维看见（error + 堆栈），不能被降级成 400。
     */
    @ExceptionHandler(DbException.class)
    public Object handleDbException(DbException e) {
        log.error("mysql数据库操作异常 -> ", e);
        return processResponse(e);
    }

    /**
     * 业务异常：响应状态码原样返回异常自带的 code，日志级别按 code 分流（分流理由见方法内说明）。
     */
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

    /**
     * 方法级参数校验失败：{@code ConstraintViolationException}。
     *
     * 【注意】它不经 MVC 参数解析，而是由类级 {@code @Validated} 注册的 MethodValidationInterceptor 抛出，
     * 因此不会被上面的 MethodArgumentNotValidException 处理器接住；不处理就会落到 Exception 兜底变成 500，
     * 把"参数写错了"说成"服务端坏了"（例如 {@code POST /users/code?phone=123} 命中 @Pattern 时）。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Object handleConstraintViolationException(ConstraintViolationException e) {
        String msg = e.getConstraintViolations() == null ? null
                : e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .filter(m -> m != null && !m.isEmpty())
                .distinct()
                .collect(Collectors.joining("|"));
        if (msg == null || msg.isEmpty()) {
            msg = "请求参数不合法";
        }
        log.warn("方法级参数校验异常 -> {}", msg);
        log.debug("", e);
        return processResponse(new BadRequestException(msg));
    }

    /**
     * 最后一道兜底：所有未被上面命中的异常（含 IllegalStateException、RuntimeException）
     * 一律回 500，并保留 error 级日志与请求 URI —— 这是真实故障唯一的对外出口。
     *
     * 【严禁】在此处把异常"友好化"成 400、或吞掉异常：那会让故障在监控里消失，
     * 把"服务端坏了"说成"客户端传错了"。
     */
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
