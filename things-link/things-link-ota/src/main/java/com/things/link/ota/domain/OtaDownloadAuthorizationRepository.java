package com.things.link.ota.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 下载授权短事务仓储；只持久化密文和能力，不保存或记录明文地址。 */
public interface OtaDownloadAuthorizationRepository {
    /** 单条可信领取，领取本身不消耗签址或网络预算。 */
    Optional<Claim> claimOne();
    /** 已建立真实RLS后的只读当前事实，用于同事务审计，不授予能力。 */
    Optional<Claim> findCurrent(UUID authorizationId);
    /** 有效租约引导权威作用域，不能自行授予发送资格。 */
    Optional<Claim> authoritativeClaim(UUID authorizationId, UUID token);
    /** 原始传输观察能力在租约结束后仍可追加一次真实观察。 */
    Optional<Transport> authoritativeTransport(UUID id, UUID reservationToken);
    /** 项目门控持锁期间原子预留唯一签址、并发槽与带宽；额度不足只退避。 */
    Optional<Claim> reserveSigning(Claim claim, long expectedJobRevision);
    /** 同事务封存密文并转入下载阶段；原签址能力失效后不能采用。 */
    boolean seal(Claim claim, long expectedJobRevision, String keyVersion, byte[] nonce,
            byte[] ciphertext, String plaintextSha256, String topic);
    /** 签址结果未知不得再次签址，保持保守占位并暂停。 */
    boolean signingUnknown(Claim claim, String reason);
    /** 对同一封存密文预留最多三次传输，不再次生成地址。 */
    Optional<Transport> reserveSend(Claim claim);
    /** 普通资格暂不可用只退避，不刷新期限或消耗预算。 */
    boolean deferIneligible(Claim claim, String reason);
    /** 首次观察返回真，相同观察重放不重复产生审计。 */
    boolean recordObservation(Transport transport, String outcome, Integer httpStatus, String errorCode);
    /** 当前能力才能采用回执，过期回执只保留观察。 */
    boolean settleCurrent(Claim claim, UUID transportId);
    /** 新租约恢复失租签址或传输，永不重签未知授权。 */
    boolean recoverExpired(Claim claim);
    /** 原期限、地址期限或传输预算耗尽后暂停但不终结作业。 */
    boolean exhaustDue(Claim claim, String reason);
    /** 精确作业和授权能力围栏下安全暂停。 */
    boolean pauseSecurity(Claim claim, String reason);
    /**
     * D-161 重投登记只读事实：当前状态、已消耗传输次数、修订与重投标记；不授予任何能力。
     *
     * <p>单独读这条窄事实而不是复用 {@link Claim}，是为了让「重投申请」路径在没有任何租约时也能核对
     * 传输预算与幂等标记，同时不给调用方任何领取、签址或发送能力。</p>
     */
    Optional<Reissue> findReissue(UUID authorizationId);
    /**
     * D-161 幂等登记「同一身份、同一冻结窗口」的重投申请；不新建申请/授权行，不重置任何计数器。
     *
     * <p>只在已接受、已封存、传输预算未耗尽、活动仍 RUNNING 且原响应窗口仍开放时成功；
     * 并发重复调用只成功一次，失败不产生任何副作用。</p>
     */
    boolean requestReissue(UUID authorizationId, long expectedRevision, long expectedJobRevision);
    /** D-161 冻结窗口在登记与重签址之间关闭时撤销标记；保持原已接受终态，绝不暂停活动。 */
    boolean cancelReissue(Claim claim);

    /** D-161 重投登记事实。 */
    record Reissue(UUID authorizationId, String status, int transportCount, long revision,
            Instant responseExpiresAt, boolean requested) { }

    /** 原申请与当前能力快照；授权身份等于申请内部身份，数组均防御复制。 */
    record Claim(UUID authorizationId, OtaDownloadRequestRepository.Request request, long jobRevision,
            long revision, String status, int transportCount, UUID leaseToken, Instant leaseUntil,
            UUID signingLeaseToken, Instant signingReservedAt, Instant responseExpiresAt, Instant slotRetainUntil,
            long artifactSize, UUID activeTransportId, String keyVersion, byte[] nonce, byte[] ciphertext,
            String plaintextSha256, String topic) {
        /** 冻结密文输入。 */
        public Claim {
            nonce = nonce == null ? null : nonce.clone();
            ciphertext = ciphertext == null ? null : ciphertext.clone();
        }
        /** 返回独立随机数副本。 */
        @Override public byte[] nonce() { return nonce == null ? null : nonce.clone(); }
        /** 返回独立密文副本。 */
        @Override public byte[] ciphertext() { return ciphertext == null ? null : ciphertext.clone(); }
    }
    /** 单次传输观察能力与原授权发送租约分离，重试使用同一密文。 */
    record Transport(UUID id, UUID authorizationId, UUID tenantId, UUID projectId, UUID campaignId, UUID jobId,
            int transportNo, UUID reservationToken, UUID authorizationLeaseToken, Instant responseExpiresAt,
            Instant reservedAt, String keyVersion, byte[] nonce, byte[] ciphertext, String plaintextSha256,
            String topic) {
        /** 冻结输入密文。 */
        public Transport { nonce = nonce.clone(); ciphertext = ciphertext.clone(); }
        /** 返回独立随机数副本。 */
        @Override public byte[] nonce() { return nonce.clone(); }
        /** 返回独立密文副本。 */
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
    }
}
