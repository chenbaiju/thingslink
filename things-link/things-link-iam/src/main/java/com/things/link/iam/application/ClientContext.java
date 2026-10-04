package com.things.link.iam.application;

/**
 * 发起请求的客户端信息，随会话一起记录。
 *
 * <p>用途只有两个：将来的「登录设备列表」页面，以及安全事件排查时还原「是从哪来的」。
 *
 * <p>User-Agent可伪造；clientIp由容器按G3-SEC-1显式可信代理解析，
 * 未信任对端的转发头不会替换socket地址。IP只用于来源限流和审计，不能替代身份认证。
 *
 * @param userAgent User-Agent 头，可为 {@code null}
 * @param clientIp  客户端 IP，可为 {@code null}
 */
public record ClientContext(String userAgent, String clientIp) {

    /** 无客户端信息时使用，避免调用方到处判空。 */
    public static final ClientContext UNKNOWN = new ClientContext(null, null);

}
