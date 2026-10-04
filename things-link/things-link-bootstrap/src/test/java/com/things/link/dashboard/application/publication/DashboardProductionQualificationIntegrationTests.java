package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.domain.DashboardDraft;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 生产宿主资格失败关闭及发布事实零写入的真实PostgreSQL装配验收。 */
@DisplayName("看板生产资格真实集成")
class DashboardProductionQualificationIntegrationTests extends AbstractIntegrationTest {

    /** 最小候选JSON构造器。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 被测包内资格入口，通过同包测试证明生产装配而不扩大公开API。 */
    @Autowired
    private DashboardPublicationQualificationService qualifications;
    /** 通过真实PostgreSQL jsonb规范化构造与草稿身份绑定的候选。 */
    @Autowired
    private DashboardPublicationCandidateFactory candidates;

    /** 生产宿主未配置时空画布也失败关闭，版本、指针、关系与审计均不得变化。 */
    @Test
    @DisplayName("生产宿主缺失时资格失败关闭且发布事实不变")
    void missingProductionHostFailsClosedWithoutPublicationWrites() throws Exception {
        List<Long> before = publicationFacts();
        DashboardPublicationCandidate candidate = candidates.prepare(emptyCanvasDraft());

        assertThatThrownBy(() -> qualifications.qualify(candidate))
                .isInstanceOfSatisfying(DashboardPublicationQualificationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(
                            DashboardPublicationQualificationException.Reason.HOST_COMPONENT_UNAVAILABLE);
                    assertThat(failure.getMessage()).isEqualTo("看板宿主组件不可用");
                });

        assertThat(publicationFacts()).isEqualTo(before);
    }

    /** 构造严格Schema门面已接受的完整空画布持久草稿值。 */
    private static DashboardDraft emptyCanvasDraft() {
        ObjectNode schema = JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1");
        schema.putObject("presentation").put("mode", "RESPONSIVE_GRID");
        schema.putArray("models");
        schema.putArray("variables");
        schema.putArray("pages").addObject().put("id", "main").put("title", "空画布")
                .putArray("components");
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        return new DashboardDraft(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), schema, 0,
                UUID.randomUUID(), now, now, List.of());
    }

    /** 以表owner读取全局发布版本、当前指针、版本关系和看板审计计数。 */
    private static List<Long> publicationFacts() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement query = connection.createStatement()) {
            query.setQueryTimeout(5);
            try (ResultSet rows = query.executeQuery("""
                    SELECT
                      (SELECT count(*) FROM dash_dashboard_version),
                      (SELECT count(*) FROM dash_dashboard WHERE current_version_id IS NOT NULL),
                      (SELECT count(*) FROM dash_dashboard_version_model_ref),
                      (SELECT count(*) FROM sys_audit_log WHERE target_type='dashboard')
                    """)) {
                assertThat(rows.next()).isTrue();
                return List.of(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4));
            }
        }
    }
}
