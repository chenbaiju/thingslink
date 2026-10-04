package com.things.link.dashboard.infrastructure.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** S12-1c2两语句发布状态锁对实际连接隔离级的失败关闭测试。 */
@DisplayName("JDBC发布状态锁隔离级防御")
class JdbcPublicationStateLockIsolationTests {

    /** 每例模拟已经由服务层开启的非只读事务，隔离级仍由实际连接查询决定。 */
    @BeforeEach
    void bindWritableTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    /** 清理Spring事务线程标记，避免影响同JVM的其他仓储用例。 */
    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clear();
    }

    /** 应用状态锁只允许PostgreSQL每语句刷新快照的RC及等价RU，并在无目标时安全返回空。 */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"read committed", "read uncommitted"})
    @DisplayName("应用状态锁接受RC和PostgreSQL等价RU")
    void applicationLockAcceptsReadCommittedSnapshots(String isolation) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID projectId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        when(jdbc.queryForObject("SHOW transaction_isolation", String.class)).thenReturn(isolation);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(),
                eq(projectId), eq(applicationId))).thenReturn(List.of());

        assertThat(new JdbcApplicationRepository(jdbc)
                .lockPublicationState(projectId, applicationId)).isEmpty();

        verify(jdbc).queryForObject("SHOW transaction_isolation", String.class);
        verify(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(),
                eq(projectId), eq(applicationId));
        verifyNoMoreInteractions(jdbc);
    }

    /** 应用锁在RR/Serializable下不能靠第二语句取得新快照，必须先于任何行锁拒绝。 */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"repeatable read", "serializable"})
    @DisplayName("应用状态锁在高隔离级下零锁拒绝")
    void applicationLockRejectsStaleTransactionSnapshots(String isolation) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SHOW transaction_isolation", String.class)).thenReturn(isolation);

        assertThatThrownBy(() -> new JdbcApplicationRepository(jdbc)
                .lockPublicationState(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("应用发布状态锁要求READ COMMITTED或READ UNCOMMITTED事务");

        verify(jdbc).queryForObject("SHOW transaction_isolation", String.class);
        verify(jdbc, never()).query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(), any(), any());
        verifyNoMoreInteractions(jdbc);
    }

    /** 看板状态锁只允许PostgreSQL每语句刷新快照的RC及等价RU，并在无目标时安全返回空。 */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"read committed", "read uncommitted"})
    @DisplayName("看板状态锁接受RC和PostgreSQL等价RU")
    void dashboardLockAcceptsReadCommittedSnapshots(String isolation) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID projectId = UUID.randomUUID();
        UUID dashboardId = UUID.randomUUID();
        when(jdbc.queryForObject("SHOW transaction_isolation", String.class)).thenReturn(isolation);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(),
                eq(projectId), eq(dashboardId))).thenReturn(List.of());

        assertThat(new JdbcDashboardRepository(jdbc)
                .lockPublicationState(projectId, dashboardId)).isEmpty();

        verify(jdbc).queryForObject("SHOW transaction_isolation", String.class);
        verify(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(),
                eq(projectId), eq(dashboardId));
        verifyNoMoreInteractions(jdbc);
    }

    /** 看板锁在RR/Serializable下不能靠第二语句取得新快照，必须先于任何行锁拒绝。 */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"repeatable read", "serializable"})
    @DisplayName("看板状态锁在高隔离级下零锁拒绝")
    void dashboardLockRejectsStaleTransactionSnapshots(String isolation) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SHOW transaction_isolation", String.class)).thenReturn(isolation);

        assertThatThrownBy(() -> new JdbcDashboardRepository(jdbc)
                .lockPublicationState(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("看板发布状态锁要求READ COMMITTED或READ UNCOMMITTED事务");

        verify(jdbc).queryForObject("SHOW transaction_isolation", String.class);
        verify(jdbc, never()).query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(), any(), any());
        verifyNoMoreInteractions(jdbc);
    }
}
