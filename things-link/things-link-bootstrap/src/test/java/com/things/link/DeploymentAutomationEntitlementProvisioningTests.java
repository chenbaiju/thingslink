package com.things.link;

import com.things.link.project.application.DeploymentEntitlementPolicy;
import com.things.link.project.domain.QuotaMetric;
import com.things.link.testing.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Flyway owner 首次设置后，配置漂移必须在启动迁移阶段拒绝。 */
class DeploymentAutomationEntitlementProvisioningTests extends AbstractIntegrationTest {
    @Autowired Flyway flyway;

    @Test void ownerProvisioningIsIdempotentAndRejectsDeploymentConfigurationDrift() throws SQLException {
        assertThat(ownerMode()).isEqualTo("COMMERCIAL");
        FlywayCompatibilityConfiguration.provisionAutomationEntitlement(flyway, new MockEnvironment());
        assertThatThrownBy(() -> FlywayCompatibilityConfiguration.provisionAutomationEntitlement(
                flyway, nonCommercialEnvironment("2")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不一致");
        assertThatThrownBy(() -> FlywayCompatibilityConfiguration.provisionAutomationEntitlement(
                flyway, nonCommercialEnvironment("0")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("必须为正整数");
        assertThat(ownerMode()).isEqualTo("COMMERCIAL");
    }

    @Test void deploymentEntitlementTableAndColumnsHaveChineseComments() throws SQLException {
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
                var statement = connection.createStatement()) {
            try (var table = statement.executeQuery("""
                    SELECT obj_description('public.sys_deployment_automation_entitlement'::regclass, 'pg_class')
                    """)) {
                assertThat(table.next()).isTrue();
                assertChineseComment(table.getString(1), "部署自动化权益表注释");
            }
            try (var columns = statement.executeQuery("""
                    SELECT a.attname, col_description(a.attrelid, a.attnum)
                      FROM pg_catalog.pg_attribute a
                     WHERE a.attrelid = 'public.sys_deployment_automation_entitlement'::regclass
                       AND a.attnum > 0 AND NOT a.attisdropped
                    """)) {
                int columnCount = 0;
                while (columns.next()) {
                    columnCount++;
                    assertChineseComment(columns.getString(2), "字段 " + columns.getString(1) + " 的注释");
                }
                assertThat(columnCount).isEqualTo(4);
            }
        }
    }

    private void assertChineseComment(String comment, String description) {
        assertThat(comment).as(description).isNotBlank();
        assertThat(comment.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN))
                .as(description + "应包含汉字").isTrue();
    }

    private MockEnvironment nonCommercialEnvironment(String automationLimit) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("things-link.deployment.entitlement-mode", "NONCOMMERCIAL");
        String prefix = "things-link.deployment.noncommercial-capacity.";
        for (DeploymentEntitlementPolicy.Capacity capacity : DeploymentEntitlementPolicy.Capacity.values()) {
            environment.withProperty(prefix + capacity.name().toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-'), "30");
        }
        for (QuotaMetric metric : QuotaMetric.values()) {
            if (metric.dailyCounter()) {
                environment.withProperty(prefix + "daily." + metric.name().toLowerCase(java.util.Locale.ROOT)
                        .replace('_', '-'), metric == QuotaMetric.AUTOMATION_EXECUTION ? automationLimit : "30");
            }
        }
        return environment;
    }

    private String ownerMode() throws SQLException {
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT entitlement_mode FROM sys_deployment_automation_entitlement")) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
