package com.things.link.shared.error;

/**
 * 表示同一持久消息身份仍由未到期租约处理，来源消息必须在有界恢复窗口内保留。
 *
 * <p>该异常只表达“稍后重放同一稳定身份可安全接管”，不代表任意业务冲突或未知异常可重试。
 * 消息入口应继续使用有限退避，并在预算耗尽后进入已确认的 DLQ，避免无限阻塞分区。</p>
 */
public final class RetryableLeaseConflictException extends RuntimeException {

    /** @param message 不含载荷和凭据的稳定诊断 */
    public RetryableLeaseConflictException(String message) {
        super(message);
    }
}
