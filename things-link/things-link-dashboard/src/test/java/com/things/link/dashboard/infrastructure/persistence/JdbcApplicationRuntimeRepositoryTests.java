package com.things.link.dashboard.infrastructure.persistence;

import com.things.link.dashboard.domain.PublishedApplicationRuntimeProjection;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 应用运行JDBC投影对JSON类型与损坏正文的失败关闭单测。 */
class JdbcApplicationRuntimeRepositoryTests {

    /** 非字符串displayName即使可被PostgreSQL文本化，也必须按持久损坏拒绝。 */
    @Test
    @SuppressWarnings("unchecked")
    void rejectsNonStringDisplayNameInsteadOfCoercingJsonScalar() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = currentRow("number", "42", "d".repeat(64), "d".repeat(64));
        doAnswer(invocation -> List.of(
                ((RowMapper<PublishedApplicationRuntimeProjection>) invocation.getArgument(1))
                        .mapRow(resultSet, 0)))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class),
                        any(), any(), any(), any());
        JdbcApplicationRuntimeRepository repository = new JdbcApplicationRuntimeRepository(jdbcTemplate);

        assertThatThrownBy(() -> repository.findCurrent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), appKey()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("当前应用版本快照缺少冻结格式或字符串公开展示名称");
    }

    /** 合法字符串展示名保持原文，不执行trim或其他规范化。 */
    @Test
    @SuppressWarnings("unchecked")
    void mapsStringDisplayNameWithoutNormalization() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        ResultSet resultSet = currentRow("string", " 生产总览 ", "d".repeat(64), "d".repeat(64));
        when(resultSet.getObject("tenant_id", UUID.class)).thenReturn(tenantId);
        when(resultSet.getObject("project_id", UUID.class)).thenReturn(projectId);
        when(resultSet.getObject("application_id", UUID.class)).thenReturn(applicationId);
        when(resultSet.getString("app_key")).thenReturn(appKey());
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(invocation -> List.of(
                ((RowMapper<PublishedApplicationRuntimeProjection>) invocation.getArgument(1))
                        .mapRow(resultSet, 0)))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class),
                        any(), any(), any(), any());
        JdbcApplicationRuntimeRepository repository = new JdbcApplicationRuntimeRepository(jdbcTemplate);

        assertThat(repository.findCurrent(tenantId, projectId, applicationId, appKey()))
                .contains(new PublishedApplicationRuntimeProjection(
                        tenantId, projectId, applicationId, appKey(), " 生产总览 "));
    }

    /** 持久摘要与当前JSONB权威文本不一致时必须失败关闭，不能公开可能被篡改的展示名。 */
    @Test
    @SuppressWarnings("unchecked")
    void rejectsSnapshotDigestMismatch() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = currentRow("string", "伪造展示名", "a".repeat(64), "b".repeat(64));
        doAnswer(invocation -> List.of(
                ((RowMapper<PublishedApplicationRuntimeProjection>) invocation.getArgument(1))
                        .mapRow(resultSet, 0)))
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class),
                        any(), any(), any(), any());
        JdbcApplicationRuntimeRepository repository = new JdbcApplicationRuntimeRepository(jdbcTemplate);

        assertThatThrownBy(() -> repository.findCurrent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), appKey()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("当前应用版本快照摘要不一致");
    }

    /** 构造当前版本投影中的JSON格式、displayName及摘要字段。 */
    private static ResultSet currentRow(
            String displayNameType, String displayName, String storedDigest, String calculatedDigest) {
        try {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("format_version")).thenReturn("tc.application/v1");
            when(resultSet.getString("display_name_type")).thenReturn(displayNameType);
            when(resultSet.getString("display_name")).thenReturn(displayName);
            when(resultSet.getString("snapshot_digest_algorithm"))
                    .thenReturn("PG_JSONB_TEXT_V1_SHA256");
            when(resultSet.getString("snapshot_digest")).thenReturn(storedDigest);
            when(resultSet.getString("calculated_snapshot_digest")).thenReturn(calculatedDigest);
            return resultSet;
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("测试结果集桩建立失败", exception);
        }
    }

    /** 返回冻结语法的确定appKey。 */
    private static String appKey() {
        return "app_00000000000000000000000000000001";
    }
}
