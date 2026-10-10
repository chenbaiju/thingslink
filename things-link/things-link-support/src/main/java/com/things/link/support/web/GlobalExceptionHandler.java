package com.things.link.support.web;

import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.error.ErrorCode;
import com.things.link.support.trace.TraceContext;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 全局异常处理器：把一切异常收敛成 {@link ApiError} 这一个响应结构。
 *
 * <p>架构文档 11.1 要求错误响应统一。统一不是美观问题 —— 形状不一致的直接后果是
 * 客户端要为每个接口写一套错误处理，而漏掉的那些会变成用户看到的白屏。
 *
 * <p>放在 support 模块而不是各业务模块：错误响应的形状是全平台契约，
 * 不允许任何模块自行发挥。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理可预期的业务失败。
     *
     * <p>用 WARN 而不是 ERROR：业务失败是系统正常工作的一部分（参数不合法、
     * 资源不存在）。把它们记成 ERROR 会淹没真正的故障，让告警失去意义。
     *
     * @param e 业务异常
     * @return 标准错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.errorCode();
        log.warn("业务异常 code={} message={}", errorCode.code(), e.getMessage());
        return build(errorCode, e.getMessage(), e.details());
    }

    /**
     * 处理 Bean Validation 校验失败。
     *
     * <p>把每个字段的错误逐条放进 details，客户端可以直接定位到具体输入框。
     * 只返回一句「参数不合法」会让调用方无从下手。
     *
     * @param e 校验异常
     * @return 标准错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> "%s: %s".formatted(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        log.warn("参数校验失败 details={}", details);
        return build(CommonErrorCode.INVALID_PARAMETER,
                CommonErrorCode.INVALID_PARAMETER.defaultMessage(), details);
    }

    /**
     * 处理请求体无法解析（JSON 语法错误、类型不匹配）。
     *
     * <p>刻意不把解析器的原始消息返回给客户端：它会暴露内部类名与字段路径。
     *
     * @param e 解析异常
     * @return 标准错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMalformedRequest(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return build(CommonErrorCode.MALFORMED_REQUEST,
                CommonErrorCode.MALFORMED_REQUEST.defaultMessage(), List.of());
    }

    /**
     * 处理必填查询参数缺失、路径参数类型转换和方法级 Bean Validation 失败。
     *
     * <p>这两类异常不经过 {@link MethodArgumentNotValidException}：枚举拼写错误由 Spring
     * 转换器抛出，{@code @RequestParam @Min} 则由方法校验抛出。若不单独收敛会落入 500。</p>
     *
     * @param e 参数异常
     * @return 通用参数错误
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ConstraintViolationException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleRequestParameterException(Exception e) {
        log.warn("查询或路径参数不合法: {}", e.getMessage());
        return build(CommonErrorCode.INVALID_PARAMETER,
                CommonErrorCode.INVALID_PARAMETER.defaultMessage(), List.of());
    }

    /**
     * 处理「路径不存在」。
     *
     * <p>没有这个处理器时，未匹配任何 Controller 的请求会落到下面的兜底逻辑，
     * 变成 <b>500 + ERROR 日志</b>。两个后果都不轻：
     * <ul>
     *   <li>客户端拿到 500 会以为服务端故障并重试，而实际是它请求了不存在的路径</li>
     *   <li>每一次扫描器探测、每一个拼错的 URL 都会写一条 ERROR 日志，
     *       真正的故障会被淹没在里面 —— 告警也就跟着失去意义</li>
     * </ul>
     *
     * <p>用 WARN 而非 ERROR：这是调用方的问题，不是服务端故障。
     *
     * @param e 无匹配处理器异常
     * @return 标准错误响应
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNoResourceFound(Exception e) {
        // 两个异常都要接：真实应用里由静态资源处理器兜底，抛
        // NoResourceFoundException；而 MockMvc 的 standaloneSetup 没有资源处理器，
        // 抛的是 NoHandlerFoundException。只接一个的话，要么线上不生效、
        // 要么测试测不到，两种都是「以为修好了其实没有」
        log.warn("请求的路径不存在: {}", e.getMessage());
        return build(CommonErrorCode.RESOURCE_NOT_FOUND,
                CommonErrorCode.RESOURCE_NOT_FOUND.defaultMessage(), List.of());
    }

    /**
     * 将已存在路径的错误请求方法返回为405，避免只读接口被误报为服务故障。
     * @param e 不支持的方法异常
     * @return 包含允许方法及统一错误体的响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        var code = CommonErrorCode.METHOD_NOT_ALLOWED;
        return ResponseEntity.status(code.httpStatus()).headers(e.getHeaders())
                .body(new ApiError(code.code(), code.defaultMessage(), TraceContext.current(), List.of()));
    }

    /**
     * 兜底：未预期的异常。
     *
     * <p>这里是代码缺陷的出口，用 ERROR 级别并打完整堆栈。
     *
     * <p><b>返回给客户端的消息刻意保持模糊</b>：异常信息可能包含表名、SQL 片段、
     * 文件路径甚至连接串，泄露出去会成为攻击面。真实原因只进服务端日志，客户端
     * 凭响应里的 traceId 找运维定位 —— 这正是 traceId 存在的意义。
     *
     * @param e 未处理的异常
     * @return 标准错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(Exception e) {
        log.error("未处理的异常", e);
        return build(CommonErrorCode.INTERNAL_ERROR,
                CommonErrorCode.INTERNAL_ERROR.defaultMessage(), List.of());
    }

    /**
     * 组装标准错误响应。
     *
     * @param errorCode 错误码
     * @param message   面向调用方的消息
     * @param details   明细列表
     * @return 带正确 HTTP 状态码的响应
     */
    private ResponseEntity<ApiError> build(ErrorCode errorCode, String message, List<String> details) {
        ApiError body = new ApiError(errorCode.code(), message, TraceContext.current(), details);
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

}
