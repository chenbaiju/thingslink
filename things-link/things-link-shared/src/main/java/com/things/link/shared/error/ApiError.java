package com.things.link.shared.error;

import java.util.List;

/**
 * 标准错误响应体。
 *
 * <p>结构由架构文档 11.1 规定，<b>全平台所有接口的错误响应都是这个形状</b>，
 * 没有例外。形状不统一的直接后果是客户端要为每个接口写一套错误处理。
 *
 * <pre>
 * {
 *   "code": 30104,
 *   "message": "设备不存在",
 *   "traceId": "0af7651916cd43dd8448eb211c80319c",
 *   "details": []
 * }
 * </pre>
 *
 * <p>{@code traceId} 是这个结构里最实用的字段：用户报障时只需提供它，就能在日志
 * 里定位到完整的请求链路，包括跨 Kafka 的异步处理（架构文档第 13 节）。
 *
 * @param code    业务错误码，分段规则见 {@link ErrorCode}
 * @param message 面向调用方的消息，不含内部实现细节
 * @param traceId 请求链路 ID，用于服务端排障
 * @param details 逐项明细，通常用于参数校验失败；无明细时为空列表而非 null，
 *                避免客户端做 null 判断
 */
public record ApiError(
        int code,
        String message,
        String traceId,
        List<String> details) {

    /**
     * 紧凑构造器：把 details 规整为不可变列表，并把 null 归一为空列表。
     */
    public ApiError {
        details = details == null ? List.of() : List.copyOf(details);
    }

}
