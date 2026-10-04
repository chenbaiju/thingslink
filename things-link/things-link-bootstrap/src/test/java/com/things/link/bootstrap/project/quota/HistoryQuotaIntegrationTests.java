package com.things.link.bootstrap.project.quota;

import com.things.link.project.application.PlanCapacityService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.telemetry.application.PropertyHistoryService;
import com.things.link.telemetry.application.PropertyIngestionService;
import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S14-R4e：完整当前迁移上的权威窗口、真实设备授权及原始/连续聚合查询。 */
class HistoryQuotaIntegrationTests extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TenantProvisioning tenants;
    @Autowired TenantResourcePackageService packages;
    @Autowired PlanCapacityService plans;
    @Autowired PropertyHistoryService history;
    @Autowired PropertyIngestionService points;
    private final JdbcTemplate owner = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    private UUID tenant, project, actor, type, device;
    private Instant now;

    @BeforeEach void seed() {
        tenant=tx.execute(s -> tenants.createTenant("R4e"));
        project=Uuid7.generate(); actor=Uuid7.generate(); type=Uuid7.generate(); device=Uuid7.generate();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'test-only','R4e')",actor,actor+"@example.com");
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,project_key,status) VALUES (?,?,'R4e',?,'ACTIVE')",project,tenant,project.toString().replace("-",""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",Uuid7.generate(),project,actor);
        TenantContext.set(new TenantScope(tenant,project,actor));
        jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?,?,?,'r4e','R4e','STANDARD','DIRECT','DRAFT')",type,tenant,project);
        jdbc.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES (?,?,?,?,?,'R4e')",device,tenant,project,type,device.toString());
        TenantContext.set(new TenantScope(tenant,project,actor));
        now=jdbc.queryForObject("SELECT statement_timestamp()",Timestamp.class).toInstant();
    }
    @AfterEach void cleanup() {
        try {
            owner.update("DELETE FROM ts_property_point_internal WHERE project_id=?",project);
            jdbc.update("DELETE FROM dev_device WHERE id=?",device);
            jdbc.update("DELETE FROM dev_type WHERE id=?",type);
            jdbc.update("DELETE FROM sys_project_member WHERE project_id=?",project);
            jdbc.update("DELETE FROM sys_project WHERE id=?",project);
            jdbc.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM sys_account WHERE id=?",actor);
            jdbc.update("DELETE FROM sys_tenant WHERE id=?",tenant);
        } finally { TenantContext.clear(); }
    }
    private void point(Instant at, Double value, String text) {
        owner.update("INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double,value_text) VALUES (?,?,'temperature',?,?,?,?)",project,device,Timestamp.from(at),Uuid7.generate(),value,text);
    }
    private com.things.link.telemetry.domain.PropertyHistoryResult query(Instant from,Instant to) {
        return history.query(project,device,"temperature",from,to,HistoryGranularity.RAW,HistoryAggregation.AVG);
    }
    private static void code(Runnable action,int expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode().code()).isEqualTo(expected));
    }
    @Test void freeClipsBothReadEntrancesBeforeNonNumericCheckAndDoesNotDeleteFacts() {
        point(now.minusSeconds(8*86400),null,"outside"); point(now.minusSeconds(60),12.0,null); point(now.plusSeconds(86400),99.0,null);
        assertThat(query(now.minusSeconds(10*86400),now.plusSeconds(2*86400)).points()).singleElement().satisfies(p -> assertThat(p.value()).isEqualTo(12));
        assertThat(points.listProperties(project,device,"temperature",null,null,null,10).items()).hasSize(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM ts_property_point_internal WHERE project_id=?",Long.class,project)).isEqualTo(3);
        assertThat(history.queryVersionedTrusted(project,device,"temperature",now.minusSeconds(10*86400),now.plusSeconds(86400),HistoryGranularity.RAW,HistoryAggregation.AVG).points()).hasSize(1);
    }
    @Test void emptyIntersectionValidationAndOwnershipRemainDistinct() {
        assertThat(query(now.minusSeconds(10*86400),now.minusSeconds(8*86400)).points()).isEmpty();
        assertThat(query(now.plusSeconds(86400),now.plusSeconds(2*86400)).points()).isEmpty();
        code(() -> query(now,now),10001);
        code(() -> points.listProperties(project,device,null,now.minusSeconds(10*86400),now.minusSeconds(8*86400),"bad-cursor",10),10001);
        code(() -> history.query(project,UUID.randomUUID(),"temperature",now.minusSeconds(60),now,HistoryGranularity.RAW,HistoryAggregation.AVG),10004);
    }
    @Test void monthEndLeapDayAndNonUtcSessionUseSameProductionCalendarFunction() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL TIME ZONE 'America/Los_Angeles'");
            assertThat(lower("2024-03-31T12:34:56Z","MONTH",1)).isEqualTo(Instant.parse("2024-02-29T12:34:56Z"));
            assertThat(lower("2025-03-31T12:34:56Z","MONTH",1)).isEqualTo(Instant.parse("2025-02-28T12:34:56Z"));
            assertThat(lower("2024-03-11T12:00:00Z","DAY",1)).isEqualTo(Instant.parse("2024-03-10T12:00:00Z"));
        });
        assertThat(lower("2024-03-31T12:34:56Z","DAY",Long.MAX_VALUE)).isNull();
    }
    private Instant lower(String reference,String unit,long amount) {
        Timestamp result=jdbc.queryForObject("SELECT plan_history_window_lower_bound(?::timestamptz,?::varchar,?::numeric)",Timestamp.class,reference,unit,amount);
        return result==null?null:result.toInstant();
    }
    @Test void packagesAdjustmentsExpiryAndMissingProjectionUseOwnerSnapshot() {
        point(now.minusSeconds(8*86400),4.0,null);
        assertThat(query(now.minusSeconds(10*86400),now).points()).isEmpty();
        // 原有售卖入口尚未冻结随基础档变化的单位，保持50034，不虚构可售资格。
        code(() -> packages.createSimulatedPackageOrder(tenant,"HISTORY_WINDOW",3,null),50034);
        jdbc.update("""
                INSERT INTO sys_tenant_resource_package(id,tenant_id,dimension_code,amount,unit,window_kind,
                  starts_at,ends_at,source,status,adjustment_reason,adjustment_operator_id,adjustment_key)
                VALUES (?,?,'HISTORY_WINDOW',3,'DAY','ROLLING',now()-interval '1 hour',now()+interval '1 hour',
                  'OPERATION_ADJUSTMENT','ACTIVE','R4e',?,'r4e-adjustment')
                """,Uuid7.generate(),tenant,actor);
        assertThat(query(now.minusSeconds(10*86400),now).points()).hasSize(1);
        jdbc.update("UPDATE sys_tenant_resource_package SET ends_at=statement_timestamp()-interval '1 second' WHERE tenant_id=?",tenant);
        assertThat(query(now.minusSeconds(10*86400),now).points()).isEmpty();
        jdbc.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='FREE') WHERE id=?",tenant);
        code(() -> query(now.minusSeconds(60),now),50048);
        code(() -> plans.historyWindow(UUID.randomUUID(),project),50048);
    }
    @Test void clippingPrecedesBucketBudgetAndAggregatesContainNoPartialBoundary() {
        Instant day=now.truncatedTo(java.time.temporal.ChronoUnit.DAYS).minusSeconds(86400);
        point(day.plusSeconds(10),10.0,null); point(day.plusSeconds(50),90.0,null);
        owner.execute("CALL refresh_continuous_aggregate('ts_property_point_1m_internal','"+day+"'::timestamptz,'"+day.plusSeconds(120)+"'::timestamptz)");
        assertThat(history.query(project,device,"temperature",day,day.plusSeconds(40),HistoryGranularity.ONE_MINUTE,HistoryAggregation.AVG).points()).isEmpty();
        assertThat(history.query(project,device,"temperature",day.plusSeconds(20),day.plusSeconds(60),HistoryGranularity.ONE_MINUTE,HistoryAggregation.AVG).points()).isEmpty();
        assertThat(history.query(project,device,"temperature",day,day.plusSeconds(60),HistoryGranularity.ONE_MINUTE,HistoryAggregation.AVG).points())
                .singleElement().satisfies(p -> assertThat(p.value()).isEqualTo(50));
        assertThat(history.query(project,device,"temperature",now.minusSeconds(5000L*86400),now,HistoryGranularity.ONE_HOUR,HistoryAggregation.AVG).actualGranularity()).isEqualTo(HistoryGranularity.ONE_HOUR);
    }
}
