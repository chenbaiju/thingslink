package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 云端认领与绑定使用的一次性短时效令牌事实（ADR 0037、G2-A1b）。
 *
 * <p>本对象刻意不持有令牌明文或哈希：明文只允许在未来签发边界出现一次，哈希只在
 * 仓储调用参数中短暂存在。这样即使领域对象被日志或调试器打印，也不会泄露凭据。
 *
 * <p>是否可消费必须由用途状态机同时复核过期时间、尝试次数、消费状态、项目角色与
 * 设备状态，并在关系变更同一事务内条件更新，不能仅凭本对象判断。G2-A1e/A1f 起
 * TRANSFER/SHARE 还必须复核 {@code issuedByAppUserId} 仍是设备当前 PRIMARY，防止旧令牌
 * 在主控变化后继续夺权或授予访问。
 *
 * @param id                  令牌记录 ID（UUIDv7）
 * @param tenantId            归属租户
 * @param projectId           所属项目
 * @param projectGeneration   签发时项目生命周期代次；删除后旧能力永久失效
 * @param deviceId            目标设备
 * @param purpose             令牌用途
 * @param targetRole          消费成功后的目标设备关系角色
 * @param issuedByAppUserId   签发人；TRANSFER/SHARE 必须非空，CLAIM 为空
 * @param expiresAt           失效时刻
 * @param attemptCount        已进入业务复核的尝试次数
 * @param maxAttempts         签发时冻结的最大尝试次数
 * @param consumedAt          成功消费时刻，未消费为 {@code null}
 * @param consumedByAppUserId 消费人，未消费为 {@code null}
 * @param createdAt           创建时刻
 * @param updatedAt           最后变更时刻
 */
public record AppDeviceBindToken(
        UUID id,
        UUID tenantId,
        UUID projectId,
        long projectGeneration,
        UUID deviceId,
        Purpose purpose,
        AppUserDevice.RelationRole targetRole,
        UUID issuedByAppUserId,
        Instant expiresAt,
        int attemptCount,
        int maxAttempts,
        Instant consumedAt,
        UUID consumedByAppUserId,
        Instant createdAt,
        Instant updatedAt) {

    /** 能力代次必须非负；负值既不能签发，也不能进入数据库后依赖约束兜底。 */
    public AppDeviceBindToken {
        if (projectGeneration < 0) {
            throw new IllegalArgumentException("设备绑定令牌的项目生命周期代次不能为负数");
        }
    }

    /**
     * 兼容代次列上线前的构造调用；旧能力事实按ADR0073解释为项目代次0。
     *
     * @param id 令牌记录ID
     * @param tenantId 归属租户
     * @param projectId 所属项目
     * @param deviceId 目标设备
     * @param purpose 令牌用途
     * @param targetRole 目标关系角色
     * @param issuedByAppUserId 签发人
     * @param expiresAt 失效时刻
     * @param attemptCount 已复核次数
     * @param maxAttempts 最大复核次数
     * @param consumedAt 消费时刻
     * @param consumedByAppUserId 消费人
     * @param createdAt 创建时刻
     * @param updatedAt 更新时间
     */
    public AppDeviceBindToken(
            UUID id,
            UUID tenantId,
            UUID projectId,
            UUID deviceId,
            Purpose purpose,
            AppUserDevice.RelationRole targetRole,
            UUID issuedByAppUserId,
            Instant expiresAt,
            int attemptCount,
            int maxAttempts,
            Instant consumedAt,
            UUID consumedByAppUserId,
            Instant createdAt,
            Instant updatedAt) {
        this(id, tenantId, projectId, 0L, deviceId, purpose, targetRole,
                issuedByAppUserId, expiresAt, attemptCount, maxAttempts,
                consumedAt, consumedByAppUserId, createdAt, updatedAt);
    }

    /** 绑定令牌用途；合法目标角色组合由数据库 CHECK 与后续应用校验共同保证。 */
    public enum Purpose {
        /** 首次认领尚无主控的设备，只能得到 PRIMARY。 */
        CLAIM,
        /** 由现有授权流程共享给另一用户，只能得到 MEMBER 或 READ_ONLY。 */
        SHARE,
        /** 转移主控权，只能得到 PRIMARY。 */
        TRANSFER
    }
}
