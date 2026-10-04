package com.things.link.support.idempotency;

/** 写请求体超过安全上限；由过滤器直接转换为统一 413 响应。 */
final class RequestBodyTooLargeException extends RuntimeException {

    /** 创建不携带请求正文的轻量异常，避免敏感内容进入日志。 */
    RequestBodyTooLargeException() {
        super("request body too large", null, false, false);
    }
}
