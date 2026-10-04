package com.things.link.bootstrap.ota;

import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaCampaignRepository;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.ota.domain.OtaPublication;
import com.things.link.ota.domain.OtaRelease;
import com.things.link.ota.infrastructure.persistence.JdbcOtaPublicationRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaUploadRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 数据库独立运行与真实租约专项，不模拟MQTT发送成功。 */
class OtaCampaignRuntimePersistenceTests extends AbstractIntegrationTest {
    private static final HikariDataSource OWNER_POOL = pool(POSTGRES.getUsername(), POSTGRES.getPassword());
    private static final HikariDataSource APP_POOL = pool(APP_ROLE, APP_ROLE_PASSWORD);
    /** 每例隔离项目。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    private static HikariDataSource pool(String user, String password) {
        var source = new HikariDataSource();
        source.setJdbcUrl(POSTGRES.getJdbcUrl());
        source.setUsername(user);
        source.setPassword(password);
        source.setMinimumIdle(0);
        source.setMaximumPoolSize(4);
        return source;
    }

    @AfterAll static void closePools() { APP_POOL.close(); OWNER_POOL.close(); }

    /** 未启动批量取消没有作业转换行，仍捕获真实终态并按100+1分页；新事务不得扫历史。 */
    @Test void completionCaptureIncludesUndispatchedBatchAndTransactionPagination() {
        Fixture f=ready(); var c=scheduled(f,101);
        String tx=app(f,j->{
            new JdbcOtaCampaignRuntimeRepository(j).controlLock(f.tenant(),f.project());
            captureCompletion(j,f);
            String transaction=j.queryForObject("SELECT pg_current_xact_id()::text",String.class);
            assertThat(new JdbcOtaCampaignRepository(j).cancelUndispatched(1,c,f.account(),Instant.now(),"取消全部未派发")).isTrue();
            var repository=new com.things.link.ota.infrastructure.persistence.JdbcOtaJobCompletionRepository(j);
            var first=repository.page(f.tenant(),f.project(),transaction,null,100);
            assertThat(first).hasSize(100);
            var second=repository.page(f.tenant(),f.project(),transaction,first.getLast().jobId(),100);
            assertThat(second).hasSize(1);
            assertThat(first).allSatisfy(row->{
                assertThat(row.fromStatus()).isEqualTo("PENDING"); assertThat(row.status()).isEqualTo("CANCELLED");
                assertThat(row.attemptNo()).isZero(); assertThat(row.originTransaction()).isEqualTo(transaction);
                assertThat(row.completedAt().getNano()%1000).isZero(); assertThat(row.traceId()).isEqualTo("completion-fixture");
                assertThat(row.firmwareId()).isEqualTo(f.firmware()); assertThat(row.manifestSha256()).isEqualTo(c.manifestSha256());
            });
            assertThat(j.queryForObject("SELECT count(*) FROM ota_job_transition WHERE campaign_id=?",Integer.class,c.id())).isZero();
            assertThatThrownBy(()->repository.page(f.tenant(),f.project(),transaction,null,101)).isInstanceOf(IllegalArgumentException.class);
            return transaction;
        });
        assertThat(OtaCampaignRuntimePersistenceTests.<List<com.things.link.ota.domain.OtaJobCompletion>>app(f,j->
                new com.things.link.ota.infrastructure.persistence.JdbcOtaJobCompletionRepository(j).page(f.tenant(),f.project(),tx,null,100))).isEmpty();
        assertThatThrownBy(()->app(f,j->j.update("DELETE FROM ota_job_completion WHERE project_id=?",f.project()))).hasStackTraceContaining("permission denied");
        assertThatThrownBy(()->owner().update("UPDATE ota_job_completion SET trace_id='rewrite' WHERE project_id=?",f.project())).hasStackTraceContaining("OTA job completion is immutable");
        assertThat(OtaCampaignRuntimePersistenceTests.<Integer>plain(j->j.queryForObject("SELECT count(*) FROM ota_job_completion",Integer.class))).isZero();
    }

    /** 默认关闭时合法终态不产生公开来源，后续开启不得回填。 */
    @Test void completionCaptureIsDisabledWithoutOriginalTransactionMarker() {
        Fixture f=ready(); var c=scheduled(f,1);
        app(f,j->{ assertThat(new JdbcOtaCampaignRepository(j).cancelUndispatched(1,c,f.account(),Instant.now(),"无来源捕获")).isTrue(); return true; });
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_completion WHERE project_id=?",Integer.class,f.project())).isZero();
        app(f,j->{captureCompletion(j,f); return j.queryForObject("SELECT count(*) FROM ota_job_completion",Integer.class);});
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_completion WHERE project_id=?",Integer.class,f.project())).isZero();
    }

    /** 伪造scope、代次或其他事务标记不能借真实终态写入来源。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"projectId","generation","transaction"})
    void completionCaptureRejectsMismatchedMarkerAtomically(String field) {
        Fixture f=ready(); var c=scheduled(f,1);
        assertThatThrownBy(()->app(f,j->{
            captureCompletion(j,f);
            j.queryForObject("SELECT set_config('app.ota_completion_capture',(current_setting('app.ota_completion_capture')::jsonb || jsonb_build_object(?::text,'invalid'))::text,true)",String.class,field);
            return new JdbcOtaCampaignRepository(j).cancelUndispatched(1,c,f.account(),Instant.now(),"错误标记");
        })).hasStackTraceContaining("OTA completion capture scope rejected");
        assertThat(owner().queryForObject("SELECT status FROM ota_campaign WHERE id=?",String.class,c.id())).isEqualTo("SCHEDULED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_completion WHERE project_id=?",Integer.class,f.project())).isZero();
    }

    /** 真正准入跳过与来源同事务，业务后置故障回滚后仍可用原能力完成。 */
    @Test void completionObservationRollsBackWithOriginalSkip() {
        Fixture f=ready(); var c=scheduled(f,1); runtime(f,r->r.start(f.project(),c.id(),1,f.account()));
        var claim=claimRuntime();
        assertThatThrownBy(()->app(f,j->{
            captureCompletion(j,f); assertThat(new JdbcOtaCampaignRuntimeRepository(j).skip(claim,"REPORT_MISSING")).isTrue();
            throw new IllegalStateException("completion-test-rollback");
        })).hasMessage("completion-test-rollback");
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?",String.class,claim.jobId())).isEqualTo("PENDING");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_completion WHERE project_id=?",Integer.class,f.project())).isZero();
        app(f,j->{captureCompletion(j,f); return new JdbcOtaCampaignRuntimeRepository(j).skip(claim,"REPORT_MISSING");});
        assertThat(owner().queryForObject("SELECT status FROM ota_job_completion WHERE job_id=?",String.class,claim.jobId())).isEqualTo("SKIPPED_INELIGIBLE");
    }

    /** 真正桥接在提交前执行；重入不重复，101跨页及1000目标完整且载荷精确。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={101,1000})
    void completionBridgeUsesOriginalTransactionAndBoundedPages(int count) {
        Fixture f=ready(); var c=scheduled(f,count);
        app(f,j->{
            var source=completionSource(j,true); var runtime=new JdbcOtaCampaignRuntimeRepository(j,source);
            runtime.controlLock(f.tenant(),f.project()); runtime.controlLock(f.tenant(),f.project());
            assertThat(new JdbcOtaCampaignRepository(j).cancelUndispatched(1,c,f.account(),Instant.now(),"真实桥接取消")).isTrue();
            assertThat(j.queryForObject("SELECT count(*) FROM ota_job_completion WHERE project_id=?",Integer.class,f.project())).isEqualTo(count);
            assertThat(j.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,f.project())).isZero();
            return true;
        });
        var json=new tools.jackson.databind.ObjectMapper();
        var values=owner().queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'",String.class,f.project());
        assertThat(values).hasSize(count);
        var ids=new java.util.HashSet<UUID>();
        for (String text:values) {
            var source=json.readValue(text,com.things.link.shared.message.PublicWebhookSource.class);
            new com.things.link.support.webhook.PublicWebhookCodec(json).validate(source);
            var e=source.event(); assertThat(ids.add(e.eventId())).isTrue();
            assertThat(e.eventType()).isEqualTo("ota.job.completed"); assertThat(e.resourceId()).isEqualTo(e.eventId());
            assertThat(e.resourceType()).isEqualTo("ota_job"); assertThat(e.projectId()).isEqualTo(f.project());
            var original=owner().queryForMap("SELECT * FROM ota_job_completion WHERE job_id=?",e.eventId());
            assertThat(e.occurredAt()).isEqualTo(((java.sql.Timestamp)original.get("completed_at")).toInstant());
            assertThat(e.recordedAt()).isEqualTo(e.occurredAt());
            var payload=json.readTree(source.eventText()).get("payload");
            assertThat(payload.get("status").asText()).isEqualTo("CANCELLED");
            assertThat(payload.get("attemptNo").isString()).isTrue(); assertThat(payload.get("attemptNo").asText()).isEqualTo("0");
            assertThat(payload.get("stateVersion").asText()).isEqualTo(original.get("state_version").toString());
            assertThat(payload.get("failureCode").isNull()).isTrue();
            assertThat(payload.get("manifestSha256").asText()).isEqualTo(c.manifestSha256());
            assertThat(payload.size()).isEqualTo(10);
        }
    }

    /** 总量上界覆盖同事务多活动，不能分别分页后各自放行。 */
    @Test void completionBridgeRejectsMoreThanOneThousandAtomically() {
        Fixture f=ready(); var first=scheduled(f,501); var second=scheduled(f,500);
        assertThatThrownBy(()->app(f,j->{
            var r=new JdbcOtaCampaignRuntimeRepository(j,completionSource(j,true)); r.controlLock(f.tenant(),f.project());
            var c=new JdbcOtaCampaignRepository(j);
            assertThat(c.cancelUndispatched(1,first,f.account(),Instant.now(),"超界一")).isTrue();
            assertThat(c.cancelUndispatched(1,second,f.account(),Instant.now(),"超界二")).isTrue();
            return true;
        })).hasMessageContaining("1000目标上界");
        assertThat(owner().queryForList("SELECT DISTINCT status FROM ota_campaign WHERE project_id=?",String.class,f.project())).containsExactly("SCHEDULED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_completion WHERE project_id=?",Integer.class,f.project())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,f.project())).isZero();
    }

    /** Outbox真实INSERT失权必须撤销业务与完成记录；恢复后原请求仍可完成。 */
    @Test void completionBridgeFailureRollsBackOriginalState() {
        Fixture f=ready(); var c=scheduled(f,2);
        owner().execute("REVOKE INSERT ON sys_outbox_event FROM thingslink_app");
        try {
            assertThatThrownBy(()->app(f,j->{
                new JdbcOtaCampaignRuntimeRepository(j,completionSource(j,true)).controlLock(f.tenant(),f.project());
                return new JdbcOtaCampaignRepository(j).cancelUndispatched(1,c,f.account(),Instant.now(),"失权回滚");
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { owner().execute("GRANT INSERT ON sys_outbox_event TO thingslink_app"); }
        assertThat(owner().queryForObject("SELECT status FROM ota_campaign WHERE id=?",String.class,c.id())).isEqualTo("SCHEDULED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_completion WHERE project_id=?",Integer.class,f.project())).isZero();
        app(f,j->{new JdbcOtaCampaignRuntimeRepository(j,completionSource(j,true)).controlLock(f.tenant(),f.project());
            return new JdbcOtaCampaignRepository(j).cancelUndispatched(1,c,f.account(),Instant.now(),"恢复后取消");});
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,f.project())).isEqualTo(2);
    }

    /** 默认关闭、普通读取均无捕获；开启后空事务不回填历史。 */
    @Test void completionBridgeDisabledAndReadLocksDoNotCapture() {
        Fixture f=ready(); var c=scheduled(f,1);
        app(f,j->{
            new JdbcOtaCampaignRuntimeRepository(j,completionSource(j,true)).readControlLock(f.tenant(),f.project());
            assertThat(j.queryForObject("SELECT nullif(current_setting('app.ota_completion_capture',true),'')",String.class)).isNull();
            new JdbcOtaCampaignRuntimeRepository(j,completionSource(j,false)).controlLock(f.tenant(),f.project());
            return new JdbcOtaCampaignRepository(j).cancelUndispatched(1,c,f.account(),Instant.now(),"关闭来源");
        });
        app(f,j->{completionSource(j,true).capture(f.tenant(),f.project());return true;});
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_completion WHERE project_id=?",Integer.class,f.project())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,f.project())).isZero();
    }

    /** 错范围在设置标记之前失败，不修正调用者身份；无Spring实际事务同样拒绝。 */
    @Test void completionBridgeRejectsWrongScopeAndMissingTransaction() {
        Fixture f=ready();
        assertThatThrownBy(()->app(f,j->{completionSource(j,true).capture(f.tenant(),UUID.randomUUID());return true;}))
                .hasMessageContaining("事务或范围不一致");
        assertThatThrownBy(()->completionSource(owner(),true).capture(f.tenant(),f.project()))
                .hasMessageContaining("事务或范围不一致");
    }

    /** 同数据源REQUIRES_NEW有独立连接/同步器；内层提交不被外层回滚撤销或重复。 */
    @Test void completionBridgeSuspendsAndRestoresOriginalTransaction() {
        Fixture outer=ready(), inner=ready(); var a=scheduled(outer,1); var b=scheduled(inner,1);
        assertThatThrownBy(()->app(outer,j->{
            var source=completionSource(j,true); var runtime=new JdbcOtaCampaignRuntimeRepository(j,source);
            runtime.controlLock(outer.tenant(),outer.project());
            new JdbcOtaCampaignRepository(j).cancelUndispatched(1,a,outer.account(),Instant.now(),"外层取消");
            var independent=new TransactionTemplate(new DataSourceTransactionManager(j.getDataSource()));
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.execute(status->{
                j.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,inner.tenant().toString());
                j.queryForObject("SELECT set_config('app.project_id',?,true)",String.class,inner.project().toString());
                assertThat(j.queryForObject("SELECT nullif(current_setting('app.ota_completion_capture',true),'')",String.class)).isNull();
                runtime.controlLock(inner.tenant(),inner.project());
                return new JdbcOtaCampaignRepository(j).cancelUndispatched(1,b,inner.account(),Instant.now(),"内层取消");
            });
            runtime.controlLock(outer.tenant(),outer.project());
            throw new IllegalStateException("outer-rollback");
        })).hasMessage("outer-rollback");
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,outer.project())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,inner.project())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT status FROM ota_campaign WHERE id=?",String.class,a.id())).isEqualTo("SCHEDULED");
    }

    /** 归档项目无新来源，真实只读事务与新线程也不能继承捕获能力。 */
    @Test void completionBridgeDoesNotCaptureArchivedReadOnlyOrOtherThread() {
        Fixture f=ready();
        owner().update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",f.project());
        app(f,j->{
            var source=completionSource(j,true); source.capture(f.tenant(),f.project());
            assertThat(j.queryForObject("SELECT nullif(current_setting('app.ota_completion_capture',true),'')",String.class)).isNull();
            try (var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
                assertThatThrownBy(()->executor.submit(()->source.capture(f.tenant(),f.project())).get())
                        .hasCauseInstanceOf(IllegalStateException.class);
            }
            return true;
        });
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),APP_ROLE,APP_ROLE_PASSWORD);
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(ds)); transaction.setReadOnly(true);
        assertThatThrownBy(()->transaction.execute(status->{completionSource(new JdbcTemplate(ds),true).capture(f.tenant(),f.project());return true;}))
                .hasMessageContaining("事务或范围不一致");
    }

    /** 完成后到提交前的scope漂移也必须回滚，不能由Outbox组件重建正确范围掩盖。 */
    @Test void completionBridgeRejectsScopeDriftBeforeCommit() {
        Fixture f=ready(); var c=scheduled(f,1);
        assertThatThrownBy(()->app(f,j->{
            completionSource(j,true).capture(f.tenant(),f.project());
            new JdbcOtaCampaignRepository(j).cancelUndispatched(1,c,f.account(),Instant.now(),"提交前范围漂移");
            j.queryForObject("SELECT set_config('app.project_id',?,true)",String.class,UUID.randomUUID().toString());
            return true;
        })).hasMessageContaining("事务或范围不一致");
        assertThat(owner().queryForObject("SELECT status FROM ota_campaign WHERE id=?",String.class,c.id())).isEqualTo("SCHEDULED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_completion WHERE project_id=?",Integer.class,f.project())).isZero();
    }

    /** 使用生产捕获、项目许可、真实Outbox和普通RLS连接，开关仅为本例显式参数。 */
    private static com.things.link.ota.infrastructure.webhook.OtaJobCompletionSource completionSource(JdbcTemplate j,boolean enabled) {
        var json=new tools.jackson.databind.ObjectMapper();
        var rls=new com.things.link.support.tenant.TransactionLocalRlsScope(j);
        var writer=new com.things.link.support.webhook.PublicWebhookSourceWriter(
                new com.things.link.support.outbox.JdbcTransactionalOutboxRepository(j),rls,j,json,enabled,false);
        var projects=new com.things.link.project.application.ProjectLifecycleAccessService(
                new com.things.link.project.infrastructure.persistence.JdbcProjectRepository(j));
        return new com.things.link.ota.infrastructure.webhook.OtaJobCompletionSource(j,projects,
                new com.things.link.ota.infrastructure.persistence.JdbcOtaJobCompletionRepository(j),writer,rls,json);
    }

    /** 仅本片内核测试显式设置标记；生产捕获接线由3b-4提供。 */
    private static void captureCompletion(JdbcTemplate j,Fixture f) {
        j.queryForObject("""
                SELECT set_config('app.ota_completion_capture',jsonb_build_object(
                    'tenantId',?::text,'projectId',?::text,'generation',(SELECT lifecycle_generation::text FROM sys_project WHERE id=?),
                    'transaction',pg_current_xact_id()::text,'traceId','completion-fixture')::text,true)
                """,String.class,f.tenant(),f.project(),f.project());
    }

    /** owner按子先父清理全部夹具，完整删除事务不留下临时断图。 */
    @AfterEach void cleanup() {
        var source = OWNER_POOL;
        for (Fixture f : fixtures) {
            new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
                var j = new JdbcTemplate(source);
                for (String table : List.of("sys_outbox_event", "ota_job_progress_outbox", "ota_job_progress", "ota_job_expiry", "ota_job_execution_origin", "ota_notification_transport", "ota_notification_delivery", "ota_job_dispatch_outbox", "ota_job_transition", "ota_batch_transition", "ota_campaign_outbox", "ota_campaign_transition", "ota_device_job",
                        "ota_campaign_batch", "ota_campaign_creation_request", "ota_campaign", "ota_firmware_release",
                        "ota_firmware_publication", "ota_firmware_upload_session", "ota_firmware_creation_request",
                        "ota_firmware", "ota_device_report", "dev_device", "dev_thing_model_version", "dev_type")) {
                    j.update("DELETE FROM " + table + " WHERE project_id=?", f.project());
                }
                assertThat(j.queryForObject("SELECT count(*) FROM ota_job_completion WHERE project_id=?",Integer.class,f.project())).isZero();
                j.update("DELETE FROM sys_project WHERE id=?", f.project());
                j.update("DELETE FROM sys_tenant WHERE id=?", f.tenant());
                j.update("DELETE FROM sys_account WHERE id=?", f.account());
                return true;
            });
        }
    }

    /** 可信领取不用ThreadLocal账号，旧token/错范围不可推进，暂停清除租约。 */
    @Test void fencesClaimsAcrossPauseAndExpiredLease() {
        Fixture f = ready();
        OtaCampaign c = scheduled(f, 2);
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>runtime(f, r -> r.start(f.project(), c.id(), 1, f.account()))).isTrue();
        var first = claimRuntime();
        assertThat(first.campaignId()).isEqualTo(c.id());
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>runtime(f, r -> r.pause(f.project(), c.id(), 2, f.account(), "人工暂停"))).isTrue();
        assertThat(OtaCampaignRuntimePersistenceTests.<java.util.Optional<OtaCampaignRuntimeRepository.Claim>>plain(j ->
                new JdbcOtaCampaignRuntimeRepository(j).authoritativeClaim(first.jobId(), first.token()))).isEmpty();
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>runtime(f, r -> r.skip(first, "REPORT_MISSING"))).isFalse();
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>runtime(f, r -> r.resume(f.project(), c.id(), 3, f.account(), "人工恢复"))).isTrue();
        var renewed = claimRuntime();
        owner().update("UPDATE ota_device_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", renewed.jobId());
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>runtime(f,
                r -> r.securityPauseJob(renewed, "FIRMWARE_REVOKED"))).isFalse();
        var replacement = claimRuntime();
        assertThat(replacement.token()).isNotEqualTo(renewed.token());
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>runtime(f, r -> r.admit(renewed, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()))).isFalse();
        Fixture other = seed();
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>runtime(other, r -> r.admit(replacement, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()))).isFalse();
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>runtime(f, r -> r.admit(replacement, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()))).isTrue();
        assertThat(owner().queryForObject("SELECT actor_kind FROM ota_job_transition WHERE job_id=?", String.class, replacement.jobId())).isEqualTo("SYSTEM");
        assertThat(owner().queryForObject("SELECT actor_id IS NULL FROM ota_job_transition WHERE job_id=?", Boolean.class, replacement.jobId())).isTrue();
        assertThat(owner().queryForObject("SELECT deadline_at=dispatched_at+interval '30 seconds' FROM ota_device_job WHERE id=?", Boolean.class, replacement.jobId())).isTrue();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_dispatch_outbox WHERE job_id=? AND published_at IS NULL", Integer.class, replacement.jobId())).isEqualTo(1);
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>runtime(f, r -> r.admit(replacement, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()))).isFalse();
    }

    /** 全跳过失败批不会把零分母算成功，失败批禁止恢复。 */
    @Test void failsEmptyBatchAndPreservesSecurityPause() {
        Fixture f = ready();
        OtaCampaign c = scheduled(f, 2);
        runtime(f, r -> r.start(f.project(), c.id(), 1, f.account()));
        var first = claimRuntime();
        runtime(f, r -> r.skip(first, "REPORT_MISSING"));
        var second = claimRuntime();
        runtime(f, r -> r.skip(second, "REPORT_MISSING"));
        var state = runtime(f, r -> r.read(f.project(), c.id()).orElseThrow());
        assertThat(state.campaign().status()).isEqualTo("PAUSED");
        assertThat(state.pauseKind()).isEqualTo("AUTO");
        assertThat(state.pauseReason()).isEqualTo("NO_ELIGIBLE_TARGETS");
        assertThat(state.skippedCount()).isEqualTo(2);
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>runtime(f, r -> r.resume(f.project(), c.id(), state.campaign().stateVersion(), f.account(), "不能绕过"))).isFalse();
        assertThat(OtaCampaignRuntimePersistenceTests.<Integer>runtime(f, r -> r.securityPause(f.project(), f.firmware(), f.account(), "FIRMWARE_REVOKED"))).isEqualTo(1);
        assertThat(runtime(f, r -> r.read(f.project(), c.id()).orElseThrow()).pauseKind()).isEqualTo("SECURITY");
        assertThat(owner().queryForObject("SELECT status FROM ota_campaign_batch WHERE campaign_id=?", String.class, c.id())).isEqualTo("FAILED");
    }

    /** 通知落库失败必须回滚作业与独立转移，不提前消费准入租约。 */
    @Test void rollsBackAdmissionWhenNotificationWriteFails() {
        Fixture f = ready();
        OtaCampaign c = scheduled(f, 1);
        runtime(f, r -> r.start(f.project(), c.id(), 1, f.account()));
        var claim = claimRuntime();
        String function = "ota_test_outbox_" + c.id().toString().replace("-", "");
        owner().execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.campaign_id='" + c.id() + "'::uuid THEN RAISE EXCEPTION 'fixture outbox rejected'; END IF; RETURN NEW; END $$");
        owner().execute("CREATE TRIGGER " + function + " BEFORE INSERT ON ota_job_dispatch_outbox FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        try {
            assertThatThrownBy(() -> runtime(f, r -> r.admit(claim, 1, 1, OtaExecutionReportFixture.HASH, Instant.now())))
                    .hasStackTraceContaining("fixture outbox rejected");
        } finally {
            owner().execute("DROP TRIGGER " + function + " ON ota_job_dispatch_outbox");
            owner().execute("DROP FUNCTION " + function + "()");
        }
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, claim.jobId())).isEqualTo("PENDING");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=?", Integer.class, claim.jobId())).isZero();
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>runtime(f, r -> r.admit(claim, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()))).isTrue();
    }

    /** 派发意图未收束，项目清理不能先请求取消已采用对象。 */
    @Test void blocksProjectObjectCleanupBeforeDispatchSettlement() {
        Fixture f = ready();
        OtaCampaign c = scheduled(f, 1);
        runtime(f, r -> r.start(f.project(), c.id(), 1, f.account()));
        var claim = claimRuntime();
        runtime(f, r -> r.admit(claim, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()));
        UUID token = Uuid7.generate();
        owner().update("UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',"
                + "cleanup_stage='OTA',cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,"
                + "cleanup_lease_until=now()+interval '120 seconds' WHERE id=?", token, f.project());
        assertThat(cleanBatch(f, token)).isEqualTo("OTA_CAMPAIGN_EXECUTION_PENDING");
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE firmware_id=?", String.class, f.firmware())).isEqualTo("ADOPTED");
        assertThat(owner().queryForObject("SELECT cancel_requested_at IS NULL FROM ota_firmware_upload_session WHERE firmware_id=?", Boolean.class, f.firmware())).isTrue();
    }

    /** 数据库入口自身拒绝系统主体执行管理命令，也拒绝管理主体伪造自动空批裁决。 */
    @Test void rejectsSystemManagementActionsAndManualEmptyDecision() {
        Fixture f = ready();
        OtaCampaign c = scheduled(f, 1);
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>app(f, j -> j.queryForObject(
                "SELECT ota_campaign_runtime_change(?,?,NULL::bigint,'START',?,NULL,NULL)",
                Boolean.class, f.project(), c.id(), f.account()))).isFalse();
        assertThatThrownBy(() -> app(f, j -> j.queryForObject(
                "SELECT ota_campaign_runtime_change(?,?,1,'START',NULL,NULL,NULL)", Boolean.class, f.project(), c.id())))
                .hasStackTraceContaining("actor boundary rejected");
        runtime(f, r -> r.start(f.project(), c.id(), 1, f.account()));
        assertThatThrownBy(() -> app(f, j -> j.queryForObject(
                "SELECT ota_campaign_runtime_change(?,?,2,'PAUSE',NULL,'伪造系统暂停',NULL)", Boolean.class, f.project(), c.id())))
                .hasStackTraceContaining("actor boundary rejected");
        assertThatThrownBy(() -> app(f, j -> j.queryForObject(
                "SELECT ota_campaign_runtime_change(?,?,2,'EMPTY',?,'NO_ELIGIBLE_TARGETS',NULL)",
                Boolean.class, f.project(), c.id(), f.account())))
                .hasStackTraceContaining("actor boundary rejected");
        runtime(f, r -> r.pause(f.project(), c.id(), 2, f.account(), "真实人工暂停"));
        assertThatThrownBy(() -> app(f, j -> j.queryForObject(
                "SELECT ota_campaign_runtime_change(?,?,3,'RESUME',NULL,'系统不能自动恢复',NULL)", Boolean.class, f.project(), c.id())))
                .hasStackTraceContaining("actor boundary rejected");
        assertThat(runtime(f, r -> r.read(f.project(), c.id()).orElseThrow()).campaign().stateVersion()).isEqualTo(3);
    }

    /** 即使手工拼接完整转移和事件，也不能使暂停原因、时间或主体与头事实分离。 */
    @Test void rejectsPauseDowngradeAndContradictoryHistory() {
        Fixture f = ready();
        OtaCampaign c = scheduled(f, 1);
        runtime(f, r -> r.start(f.project(), c.id(), 1, f.account()));
        runtime(f, r -> r.pause(f.project(), c.id(), 2, f.account(), "原暂停"));
        assertThatThrownBy(() -> app(f, j -> j.update(
                "UPDATE ota_campaign SET state_version=state_version+1,pause_kind='MANUAL' WHERE id=?", c.id())))
                .hasStackTraceContaining("only permits security escalation");
        for (String mismatch : List.of("reason", "time", "actor")) {
            assertThatThrownBy(() -> app(f, j -> {
                j.update("WITH t AS MATERIALIZED(SELECT clock_timestamp() AS at_time) UPDATE ota_campaign"
                        + " SET state_version=state_version+1,pause_kind='SECURITY',pause_reason='安全原因',"
                        + " updated_at=t.at_time,paused_at=t.at_time+CASE WHEN ? THEN interval '1 microsecond' ELSE interval '0 seconds' END,"
                        + "pause_actor_id=? FROM t WHERE id=?", mismatch.equals("time"), f.account(), c.id());
                var at = j.queryForObject("SELECT updated_at FROM ota_campaign WHERE id=?", java.sql.Timestamp.class, c.id());
                var eventAt = at;
                var actor = mismatch.equals("actor") ? Uuid7.generate() : f.account();
                var reason = mismatch.equals("reason") ? "不同原因" : "安全原因";
                return j.queryForList("SELECT ota_campaign_runtime_event(?,'PAUSED','PAUSED',4,?,?,?)",
                        c.id(), actor, eventAt, reason);
            })).hasStackTraceContaining("pause metadata history mismatch");
        }
        assertThat(runtime(f, r -> r.read(f.project(), c.id()).orElseThrow()).campaign().stateVersion()).isEqualTo(3);
    }

    /** 真实两个事务竞争控制锁，先准入后暂停不撤回已持久通知意图。 */
    @Test void serializesAdmissionAndPauseOnProjectControlLock() throws Exception {
        Fixture f = ready();
        OtaCampaign c = scheduled(f, 1);
        runtime(f, r -> r.start(f.project(), c.id(), 1, f.account()));
        var claim = claimRuntime();
        var admitted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> runtime(f, r -> {
                assertThat(r.admit(claim, 1, 1, OtaExecutionReportFixture.HASH, Instant.now())).isTrue();
                admitted.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("准入事务未释放");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return true;
            }));
            try {
                assertThat(admitted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> app(f, j -> {
                    j.execute("SET LOCAL lock_timeout='150ms'");
                    var repository = new JdbcOtaCampaignRuntimeRepository(j);
                    repository.controlLock(f.tenant(), f.project());
                    return repository.pause(f.project(), c.id(), 2, f.account(), "竞争暂停");
                })).hasRootCauseInstanceOf(java.sql.SQLException.class)
                        .satisfies(failure -> assertThat(((java.sql.SQLException) failure.getCause()).getSQLState()).isEqualTo("55P03"));
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(true);
        }
        assertThat(OtaCampaignRuntimePersistenceTests.<Boolean>runtime(f,
                r -> r.pause(f.project(), c.id(), 2, f.account(), "准入提交后暂停"))).isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, claim.jobId())).isEqualTo("DISPATCHED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_dispatch_outbox WHERE job_id=?", Integer.class, claim.jobId())).isEqualTo(1);
    }

    /** 运行夹具全部目标位于第一批。 */
    protected OtaCampaign scheduled(Fixture f, int count) {
        var ids = devices(f, count);
        var c = campaign(f, ids, count);
        campaignRun(f, r -> r.schedule(0, c, targets(f, ids), count, f.account(), c.createdAt()));
        return c;
    }
    /** 不建立管理ThreadLocal，直接用受限领取返回的真实范围。 */
    protected static OtaCampaignRuntimeRepository.Claim claimRuntime() {
        return plain(j -> new JdbcOtaCampaignRuntimeRepository(j).claimOne().orElseThrow());
    }
    /** 真实普通角色及控制锁持有整个最终事务。 */
    protected static <T> T runtime(Fixture f, Function<JdbcOtaCampaignRuntimeRepository, T> work) {
        return app(f, j -> {
            OtaExecutionReportFixture.seed(j, f.tenant(), f.project());
            var r = new JdbcOtaCampaignRuntimeRepository(j);
            r.controlLock(f.tenant(), f.project());
            return work.apply(r);
        });
    }

    /** 构造真实完整已采用发布父图，密码学与对象行为由其他专项覆盖。 */
    protected Fixture ready() {
        Fixture f = seed();
        OtaPublication p = prepared(f);
        run(f, r -> r.recordSigned(p, p.leaseToken(), new byte[32], new byte[64], "campaign-fixture"));
        OtaPublication signed = publication(f, p);
        run(f, r -> r.commitRelease(signed, signed.leaseToken(), release(signed)));
        return f;
    }
    /** 稳定规范文本排序目标。 */
    protected List<UUID> devices(Fixture f, int count) {
        List<UUID> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = Uuid7.generate();
            owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name)"
                    + " VALUES(?,?,?,?,?,'活动目标')", id, f.tenant(), f.project(), f.type(), "target_" + id);
            result.add(id);
        }
        return result.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
    }
    /** 仅冻结设备权威字段，未绑定模型允许为空。 */
    private List<OtaCampaignRepository.Target> targets(Fixture f, List<UUID> ids) {
        return ids.stream().map(id -> new OtaCampaignRepository.Target(id, f.type(), null, 1)).toList();
    }
    /** 最小数据库计划，只用于结构持久约束，完整闭集语法由合同专项验证。 */
    protected OtaCampaign campaign(Fixture f, List<UUID> devices, int batchSize) {
        var release = run(f, r -> r.findRelease(f.project(), f.firmware()).orElseThrow());
        String ids = devices.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        byte[] plan = ("{\"batchSize\":" + batchSize + ",\"deviceIds\":[" + ids
                + "],\"executionPolicy\":{\"downloadRetryLimit\":1,\"retryBackoffSeconds\":1,"
                + "\"stageTimeoutSeconds\":{\"DISPATCHED\":30}},\"firmwareId\":\""
                + f.firmware() + "\",\"notBefore\":\"2020-01-01T00:00:00Z\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OtaCampaign c = new OtaCampaign(Uuid7.generate(), f.tenant(), f.project(), f.firmware(), release.id(),
                f.account(), plan, hash(plan), release.canonicalManifest(), hash(release.canonicalManifest()), "DRAFT",
                0, 0, 0, now, now, null, null, null);
        campaignRun(f, r -> { r.create(c, hash(c.id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), c.planSha256()); return true; });
        return c;
    }
    /** 按实际字节计算摘要。 */
    private static String hash(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** 真实普通角色活动事务。 */
    private static <T> T campaignRun(Fixture f, Function<JdbcOtaCampaignRepository, T> work) {
        return app(f, j -> work.apply(new JdbcOtaCampaignRepository(j)));
    }

    /** 正常上传状态机建立固定版本DB证据，不伪造生产验签成功。 */
    private OtaPublication prepared(Fixture f) {
        OtaUploadSession u=create(f); UUID token=Uuid7.generate();
        upload(f,r->r.claimReceive(u,token)); upload(f,r->r.markWriting(u,token));
        upload(f,r->r.recordVersion(u,token,"version-one")); upload(f,r->r.finishVerified(u,token));
        OtaUploadSession verified=current(f,u); Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        OtaPublication p=new OtaPublication(Uuid7.generate(),f.tenant(),f.project(),f.firmware(),u.id(),f.account(),Uuid7.generate(),0,0,verified.revision(),("{\"deviceTypeId\":\""+f.type()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),new byte[]{2},"PREPARED",0,null,null,null,null,null,null,now,now);
        run(f,r->{r.create(p);return true;}); OtaPublication claimed=claim(); assertThat(claimed.id()).isEqualTo(p.id()); return claimed;
    }
    /** 单条可信scope领取。 */
    private OtaPublication claim() { return plain(j->new JdbcOtaPublicationRepository(j).claimPreparedOrSigned().orElseThrow()); }
    /** owner仅模拟真实时间流逝，不更改状态或身份。 */
    private void expire(OtaPublication p) { owner().update("UPDATE ota_firmware_publication SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",p.id()); }
    /** 精确当前回执。 */
    private OtaPublication publication(Fixture f,OtaPublication p) { return run(f,r->r.find(f.project(),f.firmware(),p.id(),false).orElseThrow()); }
    /** 同一规范证据构造不可变release。 */
    private OtaRelease release(OtaPublication p) { return new OtaRelease(p.id(),p.tenantId(),p.projectId(),p.firmwareId(),p.uploadSessionId(),p.id(),p.canonicalManifest(),p.trustSnapshot(),p.spki(),p.signature(),p.receipt(),Instant.now()); }
    /** 普通app事务持久发布。 */
    private static <T> T run(Fixture f,Function<JdbcOtaPublicationRepository,T> work) { return app(f,j->work.apply(new JdbcOtaPublicationRepository(j))); }

    /** owner只提供合法固件和模型，不绕过上传会话业务写入。 */
    protected Fixture seed() {
        Fixture f = new Fixture(Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate());
        fixtures.add(f);
        JdbcTemplate jdbc=owner();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'unused','OTA上传测试')",f.account(),f.account()+"@example.invalid");
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA上传租户')",f.tenant());
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'OTA上传项目',?)",f.project(),f.tenant(),"upload_"+f.project().toString().replace("-",""));
        jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol) VALUES (?,?,?,'upload-type','上传类型','DIRECT','STANDARD')",f.type(),f.tenant(),f.project());
        jdbc.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,version_major,
                    version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1','{}'::jsonb,repeat('a',64),'PG_JSONB_TEXT_V1_SHA256')
                """,f.model(),f.tenant(),f.project(),f.type());
        jdbc.update("""
                INSERT INTO ota_firmware(id,tenant_id,project_id,created_by,device_type_id,thing_model_version_id,
                    product_key,firmware_version,schema_digest_algorithm,schema_digest,schema_profile,status,revision,created_at)
                VALUES (?,?,?,?,?,?,'upload_product','1','PG_JSONB_TEXT_V1_SHA256',repeat('a',64),
                    'TC_PROPERTY_COMPOSITE_V1','DRAFT',0,now())
                """,f.firmware(),f.tenant(),f.project(),f.account(),f.type(),f.model());
        return f;
    }

    /** 固定远期恢复时间避免本例未领取会话干扰其他测试，需恢复时显式提前。 */
    private OtaUploadSession create(Fixture f) {
        return create(f,Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** 显式创建时钟用于验证JVM与DB时钟短暂偏差，不修改已冻结身份。 */
    private OtaUploadSession create(Fixture f,Instant now) {
        UUID id=Uuid7.generate();
        OtaUploadSession session=new OtaUploadSession(id,f.tenant(),f.project(),f.firmware(),f.account(),Uuid7.generate(),
                0,1,0,"a".repeat(64),"ota-test-bucket","attempt/"+id,"WAITING",null,null,
                id.toString().replace("-","").repeat(2),"b".repeat(64),now,now.plusSeconds(3600),null,null,null,null,null,
                now.plusSeconds(3600),null);
        upload(f,repo->{repo.create(session);return true;});
        return session;
    }

    /** 当前revision来自实际持久状态，避免用构造时快照掩盖CAS语义。 */
    private OtaUploadSession current(Fixture f,OtaUploadSession s) {
        return upload(f,repo->repo.find(f.project(),f.firmware(),s.id(),false).orElseThrow());
    }

    /** 普通app角色调用实际受限函数，未声称这里执行真实对象网络。 */
    private String cleanBatch(Fixture f,UUID token) {
        return plain(jdbc->jdbc.queryForObject("SELECT coalesce(blocked_reason,'OK') FROM ota_project_cleanup_batch(?,?,1,?)",
                String.class,f.tenant(),f.project(),token));
    }

    /** 在真实普通连接的原事务中建立项目RLS。 */
    private static <T> T app(Fixture f,Function<JdbcTemplate,T> work) {
        return plain(jdbc->{jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,f.tenant().toString());
            jdbc.queryForObject("SELECT set_config('app.project_id',?,true)",String.class,f.project().toString());
            return work.apply(jdbc);});
    }

    /** 仓储所有普通动作进入实际事务；不通过owner验证RLS。 */
    private static <T> T upload(Fixture f,Function<JdbcOtaUploadRepository,T> work) {
        return app(f,jdbc->work.apply(new JdbcOtaUploadRepository(jdbc)));
    }

    /** 无默认scope普通连接用于证明受限后台claim与查询不可见边界。 */
    protected static <T> T plain(Function<JdbcTemplate,T> work) {
        var source=APP_POOL;
        var jdbc=new JdbcTemplate(source);
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->work.apply(jdbc));
    }

    /** owner限于夹具与独立最终观察。 */
    protected static JdbcTemplate owner() {
        return new JdbcTemplate(OWNER_POOL);
    }

    /** 本例独立父事实身份。 */
    protected record Fixture(UUID tenant,UUID project,UUID account,UUID type,UUID model,UUID firmware) { }
}
