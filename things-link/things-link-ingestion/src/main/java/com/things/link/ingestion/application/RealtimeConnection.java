package com.things.link.ingestion.application;

/**
 * 本机 WebSocket 会话的最小发送端口。
 *
 * <p>应用层不直接依赖 Servlet 容器会话，使订阅上限、队列合并和跨项目过滤可用轻量替身
 * 测试；Spring 适配器负责把关闭码和文本帧转换为真实 WebSocket 调用。</p>
 */
public interface RealtimeConnection {

    /** @return 本机唯一会话 ID */
    String id();

    /** @return 已由握手冻结的 JWT 身份 */
    RealtimePrincipal principal();

    /**
     * 发送一个 UTF-8 文本帧。
     *
     * @param payload 已序列化的协议 JSON 或心跳文本
     * @throws Exception 底层连接已断开或发送超时
     */
    void sendText(String payload) throws Exception;

    /**
     * 关闭会话。
     *
     * @param statusCode RFC 6455 关闭码
     * @param reason 面向连接诊断的简短原因，不得包含令牌或业务 payload
     */
    void close(int statusCode, String reason);
}
