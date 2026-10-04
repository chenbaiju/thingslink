package com.things.link.integration.domain;
import com.things.link.integration.application.RealtimeIdentity;
import com.things.link.integration.application.RealtimeTicketRequest;
import java.time.Instant;
import java.util.UUID;
/** 持久票据投影不含摘要或秘密；认证点查也不返回这些字段。 */
public record RealtimeTicket(UUID id,RealtimeIdentity identity,RealtimeTicketRequest request,
    Instant createdAt,Instant expiresAt,String status,String leaseMember,String instanceId,String peerIp) {}
