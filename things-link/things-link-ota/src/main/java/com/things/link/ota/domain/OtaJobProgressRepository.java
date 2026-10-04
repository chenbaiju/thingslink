package com.things.link.ota.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 执行来源、认证进度与独立期限能力；所有业务写参加调用方短事务。 */
public interface OtaJobProgressRepository {
    /** 已建立真实作用域后读取作业；不能用于引导租户上下文。 */
    Optional<Context> locate(UUID jobId);
    /** 读取原准入证据；历史缺失返回空，禁止从当前报告臆补。 */
    Optional<OtaJobExecutionOrigin> origin(UUID jobId, int attemptNo);
    /** 读取原序号事实，供只读重放。 */
    Optional<OtaJobProgressReceipt> find(UUID jobId, int attemptNo, long progressSeq);
    /** 读取最大已接纳序号，不刷新时间。 */
    Optional<OtaJobProgressReceipt> latest(UUID jobId, int attemptNo);
    /** 原修订CAS与证据、阶段转移、非秘密出站事实同事务；空目标只保存观察。 */
    boolean accept(Context expected, OtaJobProgressReceipt receipt, String nextStatus, String reason);
    /** 无管理身份的受限数据库领取，每次最多一个到期作业。 */
    Optional<ExpiryClaim> claimExpired();
    /** 先验真到期能力，返回数据库原范围，不信调用方范围。 */
    Optional<ExpiryClaim> authoritativeExpiry(UUID jobId, UUID token);
    /** 精确原阶段、期限、修订、实时租约CAS进入恢复责任，不推测设备终态。 */
    boolean expire(ExpiryClaim claim, String reason);

    /** 普通RLS当前作业图；授权为空表示尚未密文封存。 */
    record Context(UUID tenantId, UUID projectId, UUID campaignId, UUID firmwareId, UUID jobId,
            UUID deviceId, int attemptNo, String status, long revision, long credentialVersion,
            String manifestSha256, UUID authorizationId, Instant deadlineAt) { }
    /** 独立十五秒到期能力；进度推进后原能力永久失效。 */
    record ExpiryClaim(Context context, UUID token, Instant leaseUntil) { }
}
