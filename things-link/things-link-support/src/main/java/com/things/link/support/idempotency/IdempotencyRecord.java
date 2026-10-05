package com.things.link.support.idempotency;

import java.time.Instant;
import java.util.UUID;

/**
 * 幂等记录，对应 {@code idempotency_record} 表的一行。
 *
 * @param id              记录 ID（UUIDv7）
 * @param tenantId        租户 ID；S1 接入认证前为 null
 * @param projectId       项目 ID；账号级接口下为 null
 * @param idempotencyKey  按已认证主体摘要后的存储键；存量行可能仍是客户端原值
 * @param requestMethod   HTTP 方法
 * @param requestPath     请求路径
 * @param requestBodyHash 请求体的 SHA-256 十六进制
 * @param status          当前状态，取值见 {@link Status}
 * @param expiresAt       过期时间
 */
public record IdempotencyRecord(
        UUID id,
        UUID tenantId,
        UUID projectId,
        String idempotencyKey,
        String requestMethod,
        String requestPath,
        String requestBodyHash,
        Status status,
        Instant expiresAt) {

    /** 记录状态。与表上的 CHECK 约束保持一致，改动要同时改迁移脚本。 */
    public enum Status {
        /** 已抢占执行权，业务尚未完成。并发的重复请求看到这个状态应返回 409。 */
        IN_PROGRESS,
        /** 已完成，仅作为阻止重复执行的墓碑；公共层不保存或重放响应。 */
        COMPLETED
    }

}
