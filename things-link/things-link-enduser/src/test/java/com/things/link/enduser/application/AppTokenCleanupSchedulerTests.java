package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppRefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** ADR0144：启停装配、保留窗口与故障传播。 */
class AppTokenCleanupSchedulerTests {
    @Test
    void defaultsToEnabledAndUsesSevenDayBoundedBatch() {
        var repository = mock(AppRefreshTokenRepository.class);
        Instant before = Instant.now().minus(Duration.ofDays(7));
        new ApplicationContextRunner().withBean(AppRefreshTokenRepository.class, () -> repository)
                .withUserConfiguration(AppTokenCleanupScheduler.class).run(context -> {
                    assertThat(context).hasSingleBean(AppTokenCleanupScheduler.class);
                    context.getBean(AppTokenCleanupScheduler.class).cleanExpired();
                });
        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteExpiredBefore(cutoff.capture(), eq(1000));
        assertThat(cutoff.getValue()).isBetween(before, Instant.now().minus(Duration.ofDays(7)));
    }

    @Test
    void canDisableWithoutCreatingMaintenanceBean() {
        new ApplicationContextRunner().withBean(AppRefreshTokenRepository.class, () -> mock(AppRefreshTokenRepository.class))
                .withUserConfiguration(AppTokenCleanupScheduler.class)
                .withPropertyValues("things-link.enduser.token-cleanup.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AppTokenCleanupScheduler.class));
    }

    @Test
    void failureIsVisibleAndNextInvocationCanRetry() {
        var repository = mock(AppRefreshTokenRepository.class);
        when(repository.deleteExpiredBefore(any(), eq(1000))).thenThrow(new IllegalStateException("db offline")).thenReturn(1);
        var scheduler = new AppTokenCleanupScheduler(repository);
        assertThatThrownBy(scheduler::cleanExpired).hasMessage("db offline");
        scheduler.cleanExpired();
        verify(repository, times(2)).deleteExpiredBefore(any(), eq(1000));
    }
}
