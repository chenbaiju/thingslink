package com.things.link.iam.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.iam.domain.EmailVerificationTokenRepository;
import com.things.link.iam.domain.RefreshTokenRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** 周期限批清理超过安全排查保留窗口的刷新令牌与邮箱验证令牌。 */
@Component
@ConditionalOnProperty(
        prefix = "things-link.iam.token-cleanup",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@DataPlaneDatabase
public class IamTokenCleanupScheduler {

    /** 单表每轮最多删除 1000 行，避免持续流量下形成大事务。 */
    private static final int BATCH_SIZE = 1_000;

    /** 已过期安全事实保留七天，兼顾异常排查与表容量。 */
    private static final Duration RETENTION = Duration.ofDays(7);

    /** 刷新令牌事实仓储。 */
    private final RefreshTokenRepository refreshTokens;

    /** 邮箱验证与密码重置令牌事实仓储。 */
    private final EmailVerificationTokenRepository verificationTokens;

    /**
     * @param refreshTokens 刷新令牌事实仓储
     * @param verificationTokens 邮箱验证与密码重置令牌事实仓储
     */
    public IamTokenCleanupScheduler(RefreshTokenRepository refreshTokens,
                                    EmailVerificationTokenRepository verificationTokens) {
        this.refreshTokens = refreshTokens;
        this.verificationTokens = verificationTokens;
    }

    /**
     * 每分钟清理两张表各一批；积压由后续轮次追平，多实例通过仓储的 SKIP LOCKED 安全竞争。
     */
    @Scheduled(
            initialDelayString = "${things-link.iam.token-cleanup.initial-delay-millis:60000}",
            fixedDelayString = "${things-link.iam.token-cleanup.fixed-delay-millis:60000}",
            scheduler = "maintenanceScheduler")
    public void cleanExpired() {
        Instant cutoff = Instant.now().minus(RETENTION);
        refreshTokens.deleteExpiredBefore(cutoff, BATCH_SIZE);
        verificationTokens.deleteExpiredBefore(cutoff, BATCH_SIZE);
    }
}
