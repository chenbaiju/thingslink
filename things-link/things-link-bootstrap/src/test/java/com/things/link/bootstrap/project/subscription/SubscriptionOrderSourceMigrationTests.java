package com.things.link.bootstrap.project.subscription;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0160真实旧库升级：缺证拒绝，不改写历史；核对既有订单后可恢复。 */
@Testcontainers
class SubscriptionOrderSourceMigrationTests {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName.parse(
            "timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("subscription_source").withUsername("thingslink").withPassword("thingslink");

    @Test void refusesMissingHistoricalSourceWithoutMutationThenUpgradesReconciledEvidence() throws Exception {
        flyway("20260920.0260").migrate();
        UUID tenant=UUID.randomUUID(),order=UUID.randomUUID(),subscription=UUID.randomUUID();
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var q=c.createStatement()) {
            q.executeUpdate("INSERT INTO sys_tenant(id,name) VALUES ('"+tenant+"','source-upgrade')");
            String plan;
            try(var r=q.executeQuery("SELECT r.id FROM sys_plan_revision r JOIN sys_plan p ON p.id=r.plan_id WHERE p.code='STANDARD' AND r.revision_code='product-revision-1'")){r.next();plan=r.getString(1);}
            // 两个独立历史事实：订单已存在，订阅尚未关联；升级不得自动推断两者关联。
            q.executeUpdate("INSERT INTO sys_tenant_order(id,tenant_id,plan_revision_id,provider,status,provider_event_id,amount_cents,currency,paid_at) VALUES ('"+order+"','"+tenant+"','"+plan+"','SIMULATED','PAID','upgrade-fixture',123,'CNY',now())");
            q.executeUpdate("INSERT INTO sys_tenant_subscription(id,tenant_id,plan_revision_id,status,starts_at,ends_at,billing_period,renewal_mode,price_cents,currency) VALUES ('"+subscription+"','"+tenant+"','"+plan+"','ACTIVE',now(),now()+interval '1 year','YEAR','MANUAL',123,'CNY')");
            assertThatThrownBy(()->flyway("20260920.0270").migrate()).hasStackTraceContaining("SUBSCRIPTION_ORDER_SOURCE_INVALID");
            try(var r=q.executeQuery("SELECT price_cents,source_order_id FROM sys_tenant_subscription WHERE id='"+subscription+"'")){
                r.next();assertThat(r.getLong(1)).isEqualTo(123);assertThat(r.getObject(2)).isNull();
            }
            try(var r=q.executeQuery("SELECT count(*) FROM sys_tenant_order WHERE tenant_id='"+tenant+"'")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
            q.executeUpdate("UPDATE sys_tenant_subscription SET source_order_id='"+order+"' WHERE id='"+subscription+"'");
            verifyLocalReconciliation(c);
            assertThat(flyway("20260920.0270").migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway("20260920.0270").migrate().migrationsExecuted).isZero();
            try(var r=q.executeQuery("SELECT count(*) FROM pg_constraint WHERE conname IN ('sys_tenant_subscription_paid_source_ck','sys_tenant_subscription_source_order_fk') AND convalidated")){
                r.next();assertThat(r.getInt(1)).isEqualTo(2);
            }
        }
    }

    private void verifyLocalReconciliation(java.sql.Connection c) throws Exception {
        java.nio.file.Path root=java.nio.file.Path.of("").toAbsolutePath();
        while(!java.nio.file.Files.isRegularFile(root.resolve("deploy/sql/provision-console-tenant.sql"))) root=root.getParent();
        UUID tenant=UUID.randomUUID();
        String provision=java.nio.file.Files.readString(root.resolve("deploy/sql/provision-console-tenant.sql"))
                .replace(":'tenant_id'","'"+tenant+"'").replace(":'tenant_name'","'local-reconciliation-test'")
                .replace(":'plan_code'","'PROFESSIONAL'");
        try(var q=c.createStatement()) {
            q.execute(provision);
            UUID subscription;
            try(var r=q.executeQuery("SELECT id FROM sys_tenant_subscription WHERE tenant_id='"+tenant+"'")){r.next();subscription=r.getObject(1,UUID.class);}
            q.executeUpdate("UPDATE sys_tenant_subscription SET source_order_id=NULL WHERE id='"+subscription+"'");
            q.executeUpdate("DELETE FROM sys_tenant_order WHERE tenant_id='"+tenant+"'");
            String script=java.nio.file.Files.readString(root.resolve("deploy/sql/reconcile-local-simulated-subscription.sql"))
                    .replace(":'subscription_id'","'"+subscription+"'").replace(":'expected_tenant_id'","'"+tenant+"'")
                    .replace(":'evidence_ref'","'S14-6d-local-fixture-evidence'");
            q.executeUpdate("UPDATE sys_tenant_subscription SET revision=2 WHERE id='"+subscription+"'");
            assertThatThrownBy(()->q.execute(script)).hasMessageContaining("RECONCILIATION_NOT_PRISTINE_LOCAL_PROVISION");
            q.execute("ROLLBACK");
            q.executeUpdate("UPDATE sys_tenant_subscription SET revision=1 WHERE id='"+subscription+"'");
            q.execute("CREATE FUNCTION pg_temp.reject_reconciliation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'TEST_AUDIT_UNAVAILABLE'; END $$");
            q.execute("CREATE TRIGGER test_reconciliation_failure BEFORE INSERT ON sys_audit_log FOR EACH ROW EXECUTE FUNCTION pg_temp.reject_reconciliation()");
            assertThatThrownBy(()->q.execute(script)).hasMessageContaining("TEST_AUDIT_UNAVAILABLE");
            q.execute("ROLLBACK");
            q.execute("DROP TRIGGER test_reconciliation_failure ON sys_audit_log");
            try(var r=q.executeQuery("SELECT count(*) FROM sys_tenant_order WHERE tenant_id='"+tenant+"'")){r.next();assertThat(r.getInt(1)).isZero();}
            q.execute(script);q.execute(script);
            try(var r=q.executeQuery("SELECT s.price_cents,s.revision,o.provider,o.paid_at>=s.created_at AS current_receipt,a.details->'originalSubscription'->>'source_order_id' AS old_source,a.details->>'meaning' AS meaning FROM sys_tenant_subscription s JOIN sys_tenant_order o ON o.id=s.source_order_id JOIN sys_audit_log a ON a.target_id=s.id WHERE s.id='"+subscription+"'")) {
                assertThat(r.next()).isTrue();assertThat(r.getLong("price_cents")).isEqualTo(1198000);assertThat(r.getLong("revision")).isEqualTo(2);
                assertThat(r.getString("provider")).isEqualTo("SIMULATED");assertThat(r.getBoolean("current_receipt")).isTrue();
                assertThat(r.getString("old_source")).isNull();assertThat(r.getString("meaning")).isEqualTo("CURRENT_LOCAL_SIMULATION_NOT_HISTORICAL_PAYMENT");assertThat(r.next()).isFalse();
            }
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())
                .locations("classpath:db/migration").placeholders(Map.of("app_role_password","thingslink"))
                .target(target).load();
    }
}
