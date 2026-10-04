package com.things.link.shared.error;

import java.util.List;

/**
 * 业务异常：携带 {@link ErrorCode} 的运行时异常，由 support 模块的全局异常处理器
 * 统一转成标准错误响应。
 *
 * <p>它表示<b>可预期的业务失败</b>（参数不合法、资源不存在、状态冲突、配额超限），
 * 与代码缺陷导致的异常是两回事。后者不应该用本类包装 —— 那样会把 bug 伪装成
 * 正常的业务响应，掩盖真正的问题。
 *
 * <p>继承 {@link RuntimeException} 而非受检异常：业务失败会穿透多层调用栈，
 * 用受检异常会迫使每一层都声明 throws，最终导致大量无意义的 try-catch 转发。
 */
public class BusinessException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient List<String> details;

    /**
     * 用错误码的默认消息构造。
     *
     * @param errorCode 错误码
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of());
    }

    /**
     * 用自定义消息构造，适用于需要携带具体上下文的场景。
     *
     * @param errorCode 错误码
     * @param message   面向调用方的消息。<b>不要放内部实现细节</b>：表名、SQL、
     *                  文件路径泄露给外部会成为攻击面
     */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    /**
     * 携带明细列表构造，通常用于参数校验失败时逐项说明。
     *
     * @param errorCode 错误码
     * @param message   面向调用方的消息
     * @param details   逐项明细，会原样出现在响应的 {@code details} 字段
     */
    public BusinessException(ErrorCode errorCode, String message, List<String> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = List.copyOf(details);
    }

    /**
     * @return 本异常携带的错误码
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * @return 明细列表，不可变，可能为空但不会为 null
     */
    public List<String> details() {
        return details;
    }

}
