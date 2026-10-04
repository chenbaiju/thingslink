package com.things.link.ingestion.application;

/** ADR0099：两种身份共用的有限发送端口，不暴露Spring会话或业务值队列。 */
public interface DashboardRealtimeConnection {
    /** @return 本机连接唯一标识 */
    String id();
    /** @return 握手冻结身份 */
    DashboardRealtimePrincipal principal();
    /** @return 仅独立App看板v2端点可开启告警域，旧端点默认保持原闭集。 */
    default boolean dashboardV2() { return false; }
    /** @return 握手已取得的分享租约；账号身份不建立该租约。 */
    default DashboardShareConnectionLease.Lease shareLease() { return null; }
    /** @param payload 已限制UTF-8字节数的帧 @throws Exception 传输失败或发送超时 */
    void sendText(String payload) throws Exception;
    /** @param code 标准关闭码 @param reason 不含凭据与业务正文的固定原因 */
    void close(int code, String reason);
}
