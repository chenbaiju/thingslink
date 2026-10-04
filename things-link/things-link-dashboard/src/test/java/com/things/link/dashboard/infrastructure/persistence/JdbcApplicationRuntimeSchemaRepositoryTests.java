package com.things.link.dashboard.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 运行Schema JDBC端口的单语句、参数绑定及单目标正文结构测试。 */
class JdbcApplicationRuntimeSchemaRepositoryTests {

    /**
     * 当前应用、全部有界关系身份和单个目标Schema必须由一次SQL观察取得；请求UUID先绑定目标关系，
     * 其余参数按可信RLS身份、appKey、当前版本与发布代次顺序绑定。
     */
    @Test
    @SuppressWarnings("unchecked")
    void usesOneBoundedQueryAndReadsOnlyRequestedSchemaBody() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID applicationVersionId = UUID.randomUUID();
        UUID dashboardVersionId = UUID.randomUUID();
        String appKey = "app_0123456789abcdef0123456789abcdef";
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        JdbcApplicationRuntimeSchemaRepository repository =
                new JdbcApplicationRuntimeSchemaRepository(jdbcTemplate);

        assertThat(repository.findSchema(
                tenantId, projectId, appKey, applicationVersionId, 9, dashboardVersionId)).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class),
                eq(dashboardVersionId), eq(tenantId), eq(projectId), eq(appKey),
                eq(applicationVersionId), eq(9L));
        assertThat(sql.getValue())
                .contains("application.current_version_id = ?")
                .contains("application.publication_revision = ?")
                .contains("reference.dashboard_version_id = ?")
                .contains("application_references")
                .contains("dashboard.current_version_id AS dashboard_current_version_id");
        assertThat(occurrences(sql.getValue(), "AS dashboard_schema,")).isEqualTo(1);
        assertThat(occurrences(sql.getValue(), "dashboard_version.schema::text")).isEqualTo(3);
    }

    /** 统计固定SQL片段出现次数，防止后续无意预取其他看板Schema正文。 */
    private static int occurrences(String value, String fragment) {
        return (value.length() - value.replace(fragment, "").length()) / fragment.length();
    }
}
