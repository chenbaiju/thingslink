package com.things.link.iam.application;

import com.things.link.iam.domain.EmailVerificationTokenRepository;
import com.things.link.iam.domain.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 验证 IAM 安全令牌清理器使用保留窗口与有限批量。 */
class IamTokenCleanupSchedulerTests {

    /** 一轮必须同时清理两类令牌，且截止时间约为当前时刻前七天。 */
    @Test
    void cleansBothTokenStoresWithBoundedRetentionWindow() {
        RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
        EmailVerificationTokenRepository verificationTokens = mock(EmailVerificationTokenRepository.class);
        IamTokenCleanupScheduler scheduler = new IamTokenCleanupScheduler(refreshTokens, verificationTokens);
        Instant before = Instant.now().minus(Duration.ofDays(7)).minusSeconds(1);

        scheduler.cleanExpired();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokens).deleteExpiredBefore(cutoff.capture(), org.mockito.ArgumentMatchers.eq(1_000));
        verify(verificationTokens).deleteExpiredBefore(cutoff.capture(), org.mockito.ArgumentMatchers.eq(1_000));
        assertThat(cutoff.getAllValues()).allSatisfy(value ->
                assertThat(value).isAfter(before).isBefore(Instant.now().minus(Duration.ofDays(7)).plusSeconds(1)));
    }
}
