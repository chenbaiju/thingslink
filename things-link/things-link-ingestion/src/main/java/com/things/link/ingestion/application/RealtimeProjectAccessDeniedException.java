package com.things.link.ingestion.application;

/**
 * 当前WebSocket账号已经确定失去项目读取资格。
 *
 * <p>该异常只包装项目域的确定业务拒绝，使握手、SUBSCRIBE和周期维护能与数据库故障区分；
 * 原业务异常作为cause保留给内部诊断，不向浏览器回显。</p>
 */
public class RealtimeProjectAccessDeniedException extends RuntimeException {

    /**
     * @param cause 项目域返回的确定成员或项目拒绝
     */
    public RealtimeProjectAccessDeniedException(RuntimeException cause) {
        super("实时连接已失去项目读取资格", cause);
    }
}
