package com.things.link.project.infrastructure.persistence;

import com.things.link.project.application.QuotaMetric;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证REST日用量事实写入先绑定服务端owner租户与当前已选项目。 */
class JdbcProjectUsageFactRecorderTests {

    /** 每个用例后清理线程身份，避免污染后续测试。 */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** S12-2a1d：完整二元组必须早于受限计量函数执行。 */
    @Test
    void establishesOwnerProjectScopeBeforeRecordingUsage() {
        UUID ownerTenantId = UUID.randomUUID();
        UUID collaboratorTenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TransactionLocalRlsScope rlsScope = mock(TransactionLocalRlsScope.class);
        TenantContext.set(new TenantScope(collaboratorTenantId, projectId, UUID.randomUUID()));
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(Boolean.TRUE);
        JdbcProjectUsageFactRecorder recorder = new JdbcProjectUsageFactRecorder(jdbcTemplate, rlsScope);

        boolean recorded = recorder.record(ownerTenantId, projectId, QuotaMetric.REST_API_CALL,
                "request-1", Instant.parse("2026-09-06T00:00:00Z"));

        assertThat(recorded).isTrue();
        InOrder order = inOrder(rlsScope, jdbcTemplate);
        order.verify(rlsScope).establish(ownerTenantId, projectId);
        order.verify(jdbcTemplate).queryForObject(anyString(), eq(Boolean.class),
                eq(ownerTenantId), eq(projectId), any(), eq(QuotaMetric.REST_API_CALL.name()),
                any(), eq("request-1"), any());
    }

    /** 路径项目与JWT选择不一致时不得建立任意数据库范围。 */
    @Test
    void rejectsProjectMismatchBeforeEstablishingScope() {
        UUID selectedProjectId = UUID.randomUUID();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TransactionLocalRlsScope rlsScope = mock(TransactionLocalRlsScope.class);
        TenantContext.set(new TenantScope(UUID.randomUUID(), selectedProjectId, UUID.randomUUID()));
        JdbcProjectUsageFactRecorder recorder = new JdbcProjectUsageFactRecorder(jdbcTemplate, rlsScope);

        assertThatThrownBy(() -> recorder.record(UUID.randomUUID(), UUID.randomUUID(),
                QuotaMetric.REST_API_CALL, "request-2", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(rlsScope, never()).establish(any(), any());
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));
    }
}
