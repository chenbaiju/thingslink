package com.things.link.bootstrap.ota;

import com.things.link.ota.application.OtaBusinessRetryService;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 数据库独立运行与真实租约专项，不模拟MQTT发送成功。 */
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR0138业务有限重试的真实PostgreSQL正向闭环。
 *
 * <p>独立继承基础集成测试并自带夹具，避免与活动运行持久化用例互相重复执行或污染。</p>
 */
@org.springframework.test.context.TestPropertySource(properties = {
        "things-link.ota.retry.runtime-enabled=false",
        "things-link.ota.campaign.runtime-enabled=false"})
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class OtaBusinessRetryIntegrationTests extends AbstractIntegrationTest {
    /** 每例隔离项目。 */
    private final java.util.List<Fixture> fixtures = new java.util.ArrayList<>();

    @AfterEach void cleanup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        for (Fixture f : fixtures) {
            new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
                var j = new JdbcTemplate(source);
                for (String table : List.of("ota_install_stop_control", "ota_install_stop_report", "ota_install_stop_operation", "ota_job_progress_outbox", "ota_job_progress", "ota_job_expiry", "ota_job_execution_origin", "ota_notification_transport", "ota_notification_delivery", "ota_job_dispatch_outbox", "ota_job_transition", "ota_batch_transition", "ota_campaign_outbox", "ota_campaign_transition", "ota_device_job",
                        "ota_campaign_batch", "ota_campaign_creation_request", "ota_campaign", "ota_firmware_release",
                        "ota_firmware_publication", "ota_firmware_upload_session", "ota_firmware_creation_request",
                        "ota_firmware", "ota_device_report", "dev_device", "dev_thing_model_version", "dev_type")) {
                    j.update("DELETE FROM " + table + " WHERE project_id=?", f.project());
                }
                j.update("DELETE FROM sys_project WHERE id=?", f.project());
                j.update("DELETE FROM sys_tenant WHERE id=?", f.tenant());
                j.update("DELETE FROM sys_account WHERE id=?", f.account());
                return true;
            });
        }
    }

    /** 只读归属必须有原来源及当前范围，当前失败不能被误认为已处理历史。 */
    @Test void resolvedFailureRequiresOriginalAttemptAndActualScope() {
        Fixture f=ready(); Fixture other=ready();
        UUID job=retryWaiting(f,1);
        assertThat(resolved(f,job,1)).isTrue();
        assertThat(resolved(other,job,1)).isFalse();
        assertThat(resolved(f,job,0)).isFalse();
        assertThat(resolved(f,job,2)).isFalse();
        Boolean absent=plain(j -> j.queryForObject("SELECT ota_retry_failure_resolved(?,1)",Boolean.class,job));
        assertThat(absent).isFalse();
        var claim=retries.claimDue().orElseThrow();
        assertThat(retries.classify(claim,"RETRY_BACKOFF_ELAPSED")).isTrue();
        assertThat(resolved(f,job,1)).isTrue();
        assertThat(resolved(f,job,2)).isFalse();
        seedExhaustedNotification(f,job);
        assertThat(resolved(f,job,2)).isFalse();
        assertThat(OtaBusinessRetryIntegrationTests.<Boolean>retryRun(f,r -> r.beginRetry(f.tenant(),f.project(),job,
                "DISPATCH_TRANSIENT_FAILURE","DISPATCH_NOT_ACKNOWLEDGED"))).isTrue();
        assertThat(resolved(f,job,2)).isTrue();
        assertThat(resolved(f,job,3)).isFalse();
        owner().update("DELETE FROM ota_job_execution_origin WHERE job_id=? AND attempt_no=2",job);
        assertThat(resolved(f,job,2)).isFalse();
        assertThat(resolved(f,job,1)).isFalse();
    }

    /** 使用普通角色与双轴RLS，不借owner越权读取其他项目原事实。 */
    private static boolean resolved(Fixture f,UUID job,int attempt) {
        return app(f,j -> j.queryForObject("SELECT ota_retry_failure_resolved(?,?)",Boolean.class,job,attempt));
    }

    /** 历史暂停的多条当前失败一次归类，原失败和attempt不被改写；旧修订不能重放。 */
    @Test void authorizedResumeClassifiesAllCurrentFailuresAtomically() {
        Fixture f=ready(); OtaCampaign c=scheduled(f,2);
        runtime(f,r->r.start(f.project(),c.id(),1,f.account()));
        var jobs=new ArrayList<UUID>();
        for(int i=0;i<2;i++) {
            var claim=claimRuntime();
            assertThat(OtaBusinessRetryIntegrationTests.<Boolean>runtime(f,r->r.admit(claim,1,1,OtaExecutionReportFixture.HASH,Instant.now()))).isTrue();
            jobs.add(claim.jobId()); seedExhaustedNotification(f,claim.jobId());
        }
        var failures=owner().queryForList("SELECT * FROM ota_notification_delivery WHERE campaign_id=? ORDER BY job_id",c.id());
        runtime(f,r->r.pause(f.project(),c.id(),2,f.account(),"旧暂停"));
        assertThat(OtaBusinessRetryIntegrationTests.<Boolean>runtime(f,r->r.resume(f.project(),c.id(),3,f.account(),"受权恢复"))).isTrue();
        assertThat(owner().queryForList("SELECT status FROM ota_device_job WHERE campaign_id=?",String.class,c.id())).containsOnly("RETRY_WAIT");
        assertThat(owner().queryForList("SELECT attempt_no FROM ota_device_job WHERE campaign_id=?",Integer.class,c.id())).containsOnly(1);
        assertThat(owner().queryForList("SELECT * FROM ota_notification_delivery WHERE campaign_id=? ORDER BY job_id",c.id())).isEqualTo(failures);
        assertThat(OtaBusinessRetryIntegrationTests.<Boolean>runtime(f,r->r.resume(f.project(),c.id(),3,f.account(),"旧修订"))).isFalse();
    }

    /** 后一候选证据缺失时，即使第一条可归类，直接仓储false也必须回滚所有转移。 */
    @Test void rejectedLaterResumeCandidateRollsBackEarlierClassification() {
        Fixture f=ready(); OtaCampaign c=scheduled(f,2);
        runtime(f,r->r.start(f.project(),c.id(),1,f.account()));
        for(int i=0;i<2;i++) {
            var claim=claimRuntime(); runtime(f,r->r.admit(claim,1,1,OtaExecutionReportFixture.HASH,Instant.now()));
            seedExhaustedNotification(f,claim.jobId());
        }
        runtime(f,r->r.pause(f.project(),c.id(),2,f.account(),"旧暂停"));
        var last=owner().queryForObject("SELECT id FROM ota_device_job WHERE campaign_id=? ORDER BY id DESC LIMIT 1",UUID.class,c.id());
        owner().update("DELETE FROM ota_job_execution_origin WHERE job_id=?",last);
        var before=owner().queryForList("SELECT * FROM ota_device_job WHERE campaign_id=? ORDER BY id",c.id());
        assertThat(OtaBusinessRetryIntegrationTests.<Boolean>runtime(f,r->r.resume(f.project(),c.id(),3,f.account(),"不得部分恢复"))).isFalse();
        assertThat(owner().queryForList("SELECT * FROM ota_device_job WHERE campaign_id=? ORDER BY id",c.id())).isEqualTo(before);
        assertThat(owner().queryForObject("SELECT status FROM ota_campaign WHERE id=?",String.class,c.id())).isEqualTo("PAUSED");
    }

    /** 有真实失败也不能绕过停止围栏或事务scope。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"STOP", "NO_SCOPE", "WRONG_SCOPE"})
    void historicalResumeRejectsUnsafeScopeAndStop(String boundary) {
        Fixture f=ready(); OtaCampaign c=scheduled(f,1);
        runtime(f,r->r.start(f.project(),c.id(),1,f.account()));
        var claim=claimRuntime(); runtime(f,r->r.admit(claim,1,1,OtaExecutionReportFixture.HASH,Instant.now()));
        seedExhaustedNotification(f,claim.jobId());
        runtime(f,r->r.pause(f.project(),c.id(),2,f.account(),"旧暂停"));
        if(boundary.equals("STOP")) seedInstallStopFence(f,claim.jobId());
        var before=owner().queryForMap("SELECT * FROM ota_device_job WHERE id=?",claim.jobId());
        Boolean result=boundary.equals("STOP") ? runtime(f,r->r.resume(f.project(),c.id(),3,f.account(),"禁止旁路"))
            : plain(j->{
                if(boundary.equals("WRONG_SCOPE")) {
                    j.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());
                    j.queryForObject("SELECT set_config('app.project_id',?,true)",String.class,f.project().toString());
                }
                return new JdbcOtaCampaignRuntimeRepository(j).resume(f.project(),c.id(),3,f.account(),"禁止旁路");
            });
        assertThat(result).isFalse();
        assertThat(owner().queryForMap("SELECT * FROM ota_device_job WHERE id=?",claim.jobId())).isEqualTo(before);
    }

    /** 最后一次已失败尝试经受权恢复进入耗尽，不增加重试预算。 */
    @Test void authorizedResumeExhaustsFinalAttemptWithoutRedispatch() {
        Fixture f=ready(); UUID job=retryWaiting(f,1);
        assertThat(retries.classify(claimDue(f),"RETRY_BACKOFF_ELAPSED")).isTrue();
        UUID campaign=owner().queryForObject("SELECT campaign_id FROM ota_device_job WHERE id=?",UUID.class,job);
        seedExhaustedNotification(f,job);
        runtime(f,r->r.pause(f.project(),campaign,2,f.account(),"旧最后失败"));
        assertThat(OtaBusinessRetryIntegrationTests.<Boolean>runtime(f,r->r.resume(f.project(),campaign,3,f.account(),"归类耗尽"))).isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?",String.class,job)).isEqualTo("TIMED_OUT");
        assertThat(owner().queryForObject("SELECT attempt_no FROM ota_device_job WHERE id=?",Integer.class,job)).isEqualTo(2);
        assertThat(resolved(f,job,2)).isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_campaign WHERE id=?",String.class,campaign)).isEqualTo("PAUSED");
        assertThat(owner().queryForObject("SELECT status FROM ota_campaign_batch WHERE campaign_id=?",String.class,campaign)).isEqualTo("FAILED");
    }

    /** 被测编排服务，使用生产装配而非替身。 */
    @Autowired private OtaBusinessRetryService retries;

    @org.junit.jupiter.api.Order(1)
    @Test void dispatchesNewAttemptAfterBackoffWithSameJobAndManifest() {
        Fixture f = ready();
        OtaCampaign c = scheduled(f, 1);
        runtime(f, r -> r.start(f.project(), c.id(), 1, f.account()));
        var claim = claimRuntime();
        assertThat(OtaBusinessRetryIntegrationTests.<Boolean>runtime(f, r -> r.admit(claim, 1, 1, OtaExecutionReportFixture.HASH, java.time.Instant.now()))).isTrue();
        var jobId = claim.jobId();
        String manifest = owner().queryForObject("SELECT manifest_sha256 FROM ota_job_dispatch_outbox WHERE job_id=?",
                String.class, jobId);
        java.time.Instant firstDispatched = owner().queryForObject(
                "SELECT dispatched_at FROM ota_device_job WHERE id=?", java.time.Instant.class, jobId);
        // 先准备真实暂时失败观察（通知投递耗尽），再让退避立即到期。
        seedExhaustedNotification(f, jobId);
        boolean accepted = OtaBusinessRetryIntegrationTests.<Boolean>retryRun(f,
                r -> r.beginRetry(f.tenant(), f.project(), jobId, "DISPATCH_TRANSIENT_FAILURE",
                        "DISPATCH_NOT_ACKNOWLEDGED"));
        assertThat(accepted).as("beginRetry must accept an observed transient failure").isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, jobId))
                .isEqualTo("RETRY_WAIT");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? AND to_status='RETRY_WAIT'",
                Integer.class, jobId)).isEqualTo(1);
        awaitNaturalBackoff(jobId);
        var due = claimDue(f);
        assertThat(due.jobId()).isEqualTo(jobId);
        assertThat(retries.classify(due, "RETRY_BACKOFF_ELAPSED")).isTrue();
        // 新尝试：尝试号前进、同一manifest、首次派发时间不变、当前期限刷新。
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, jobId))
                .isEqualTo("DISPATCHED");
        assertThat(owner().queryForObject("SELECT attempt_no FROM ota_device_job WHERE id=?", Integer.class, jobId))
                .isEqualTo(2);
        assertThat(owner().queryForObject("SELECT first_dispatched_at FROM ota_device_job WHERE id=?",
                java.time.Instant.class, jobId)).isEqualTo(firstDispatched);
        assertThat(owner().queryForObject("SELECT manifest_sha256 FROM ota_job_dispatch_outbox WHERE job_id=? AND attempt_no=2",
                String.class, jobId)).isEqualTo(manifest);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_dispatch_outbox WHERE job_id=?", Integer.class, jobId))
                .isEqualTo(2);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? AND to_status='DISPATCHED'",
                Integer.class, jobId)).isEqualTo(2);
    }

    /** 重试等待期安装前停止围栏先赢：直接取消，不派发新尝试也不重发停止命令。 */
    @org.junit.jupiter.api.Order(3)
    @Test void installStopWinsOverRetryWaitWithoutDispatchingNewAttempt() {
        Fixture f = ready();
        var jobId = retryWaiting(f, 1);
        seedInstallStopFence(f, jobId);
        // 停止围栏先赢：到期领取必须跳过该作业，因此不会派发新尝试。
        assertThat(claimDueOrEmpty(f)).isEmpty();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, jobId))
                .isEqualTo("RETRY_WAIT");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_dispatch_outbox WHERE job_id=?", Integer.class, jobId))
                .isEqualTo(1);
        // 收束路径需要完整停止来源图，端到端断言见DEBT.md D-150；本用例只断言不派发新尝试。
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? AND to_status='CANCELLED'",
                Integer.class, jobId)).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? AND to_status='RETRY_WAIT'",
                Integer.class, jobId)).isEqualTo(1);
    }

    /** 冻结预算耗尽：downloadRetryLimit=1用尽后，最后一次暂时失败直接进入封闭TIMED_OUT。 */
    @org.junit.jupiter.api.Order(3)
    @Test void exhaustsRetryBudgetIntoStableTimedOut() {
        Fixture f = ready();
        var jobId = retryWaiting(f, 1);
        // 第一次到期：预算仍允许新尝试（downloadRetryLimit=1）。
        var first = claimDue(f);
        assertThat(retries.classify(first, "RETRY_BACKOFF_ELAPSED")).isTrue();
        assertThat(owner().queryForObject("SELECT attempt_no FROM ota_device_job WHERE id=?", Integer.class, jobId))
                .isEqualTo(2);
        // 第二次观察到暂时失败：预算已用尽，直接进入封闭耗尽终态。
        seedExhaustedNotification(f, jobId);
        assertThat(OtaBusinessRetryIntegrationTests.<Boolean>retryRun(f,
                r -> r.beginRetry(f.tenant(), f.project(), jobId, "DISPATCH_TRANSIENT_FAILURE",
                        "DISPATCH_NOT_ACKNOWLEDGED"))).isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, jobId))
                .isEqualTo("TIMED_OUT");
        assertThat(owner().queryForObject("SELECT failure_code FROM ota_device_job WHERE id=?", String.class, jobId))
                .isEqualTo("DISPATCH_TRANSIENT_FAILURE");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? AND to_status='TIMED_OUT'",
                Integer.class, jobId)).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? AND to_status='RETRY_WAIT'",
                Integer.class, jobId)).isEqualTo(1);
    }

    /** ADR0205：上一尝试耗尽不能证明新尝试已失败，包括最后一次预算耗尽。 */
    @Test void finalAttemptRequiresItsOwnFailureObservation() {
        Fixture f=ready(); UUID job=retryWaiting(f,1); var due=claimDue(f);
        assertThat(retries.classify(due,"RETRY_BACKOFF_ELAPSED")).isTrue();
        assertThat(OtaBusinessRetryIntegrationTests.<Boolean>retryRun(f,
                r->r.beginRetry(f.tenant(),f.project(),job,"DISPATCH_TRANSIENT_FAILURE","NO_CURRENT_FAILURE"))).isFalse();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?",String.class,job)).isEqualTo("DISPATCHED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? AND to_status='TIMED_OUT'",Integer.class,job)).isZero();
        var transportFixture=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        new TransactionTemplate(new DataSourceTransactionManager(transportFixture)).executeWithoutResult(tx->{
            var fixtureJdbc=new JdbcTemplate(transportFixture);
            fixtureJdbc.execute("SET LOCAL session_replication_role=replica");
            assertThat(fixtureJdbc.update("UPDATE ota_notification_delivery SET status='RETRY_WAIT' WHERE job_id=? AND job_attempt_no=2",job)).isEqualTo(1);
        });
        assertThat(OtaBusinessRetryIntegrationTests.<Boolean>retryRun(f,
                r->r.beginRetry(f.tenant(),f.project(),job,"DISPATCH_TRANSIENT_FAILURE","TRANSPORT_STILL_RETRYING"))).isFalse();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?",String.class,job)).isEqualTo("DISPATCHED");
    }

    /** 完整副本而非仅jobId决定可消费能力；真实能力未被错误副本消耗。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"token","revision","attempt","campaign","device","dueAt","failure","leaseUntil"})
    void rejectsChangedClaimThenConsumesOriginalOnce(String changed) {
        Fixture f=ready(); UUID job=retryWaiting(f,1); var d=claimDue(f);
        var forged=new OtaCampaignRuntimeRepository.RetryDue(d.tenantId(),d.projectId(),
                changed.equals("campaign")?UUID.randomUUID():d.campaignId(),d.jobId(),
                changed.equals("device")?UUID.randomUUID():d.deviceId(),
                changed.equals("attempt")?d.attemptNo()+1:d.attemptNo(),
                changed.equals("revision")?d.jobRevision()+1:d.jobRevision(),
                changed.equals("dueAt")?d.nextAttemptAt().minusSeconds(1):d.nextAttemptAt(),
                changed.equals("failure")?"WRONG_FAILURE":d.failureCode(),
                changed.equals("token")?UUID.randomUUID():d.token(),
                changed.equals("leaseUntil")?d.leaseUntil().plusSeconds(1):d.leaseUntil());
        assertThat(retries.classify(forged,"RETRY_BACKOFF_ELAPSED")).isFalse();
        assertThat(owner().queryForObject("SELECT lease_token FROM ota_device_job WHERE id=?",UUID.class,job)).isEqualTo(d.token());
        assertThat(retries.classify(d,"RETRY_BACKOFF_ELAPSED")).isTrue();
        assertThat(retries.classify(d,"RETRY_BACKOFF_ELAPSED")).isFalse();
        assertThat(owner().queryForObject("SELECT attempt_no FROM ota_device_job WHERE id=?",Integer.class,job)).isEqualTo(2);
    }

    /** 旧token在数据库期限届满及被其他领取替代后均无权推进。 */
    @Test void expiredAndReplacedClaimCannotDispatchNewAttempt() {
        Fixture f=ready(); UUID job=retryWaiting(f,1); var old=claimDue(f);
        owner().update("UPDATE ota_device_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",job);
        assertThat(retries.classify(old,"RETRY_BACKOFF_ELAPSED")).isFalse();
        var fresh=claimDue(f);assertThat(fresh.token()).isNotEqualTo(old.token());
        assertThat(retries.classify(old,"RETRY_BACKOFF_ELAPSED")).isFalse();
        assertThat(retries.classify(fresh,"RETRY_BACKOFF_ELAPSED")).isTrue();
    }

    /** 审计故障发生在真实派发之后，必须同时回滚已消费token与新尝试。 */
    @Test void auditFailureRollsBackConsumedClaimForSameTokenRecovery() {
        Fixture f=ready(); UUID job=retryWaiting(f,1); var due=claimDue(f);
        String fn="test_retry_audit_"+job.toString().replace("-","");
        owner().execute("CREATE FUNCTION "+fn+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+f.project()+"'::uuid AND NEW.action='ota.retry.dispatchRetry' THEN RAISE EXCEPTION 'injected audit failure'; END IF; RETURN NEW; END $$");
        owner().execute("CREATE TRIGGER "+fn+" BEFORE INSERT ON sys_audit_log FOR EACH ROW EXECUTE FUNCTION "+fn+"()");
        try{assertThatThrownBy(()->retries.classify(due,"RETRY_BACKOFF_ELAPSED")).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally{owner().execute("DROP TRIGGER "+fn+" ON sys_audit_log");owner().execute("DROP FUNCTION "+fn+"()");}
        assertThat(owner().queryForObject("SELECT lease_token FROM ota_device_job WHERE id=?",UUID.class,job)).isEqualTo(due.token());
        assertThat(owner().queryForObject("SELECT attempt_no FROM ota_device_job WHERE id=?",Integer.class,job)).isEqualTo(1);
        assertThat(retries.classify(due,"RETRY_BACKOFF_ELAPSED")).isTrue();
    }

    /** 两个独立事务同时使用同一实际能力，只允许一条新派发意图。 */
    @Test void concurrentClaimConsumersCreateOneAttempt() throws Exception {
        Fixture f=ready(); UUID job=retryWaiting(f,1); var due=claimDue(f);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=executor.submit(()->{start.await();return retries.classify(due,"RETRY_BACKOFF_ELAPSED");});
            var b=executor.submit(()->{start.await();return retries.classify(due,"RETRY_BACKOFF_ELAPSED");});
            start.countDown();assertThat(java.util.List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally {executor.shutdownNow();assertThat(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_dispatch_outbox WHERE job_id=?",Integer.class,job)).isEqualTo(2);
    }

    /** RECOVERY_REQUIRED计入attempt但不消耗退避额度：退避指数只由既有RETRY_WAIT转移计数。
     *
     * <p>本断言是结构性证据：直接校验已安装的受限函数只按`to_status='RETRY_WAIT'`计数退避指数，
     * 不把`RECOVERY_REQUIRED`计入；行为级断言需要真实对账事实链，见DEBT.md D-150。</p>
     */
    @org.junit.jupiter.api.Order(5)
    @Test void retryBackoffCountsOnlyRetryWaitTransitions() {
        String definition = owner().queryForObject(
                "SELECT pg_get_functiondef(oid) FROM pg_proc WHERE proname='ota_job_begin_retry'", String.class);
        assertThat(definition).contains("AND t.to_status='RETRY_WAIT'");
        assertThat(definition).doesNotContain("to_status IN ('RETRY_WAIT','RECOVERY_REQUIRED')");
        assertThat(definition).doesNotContain("to_status='RECOVERY_REQUIRED'");
    }

    /** 永久错误不重试：已进入安全执行阶段或缺少失败观察时拒绝进入重试等待。 */
    @org.junit.jupiter.api.Order(4)
    @Test void rejectsRetryWithoutTransientFailureObservation() {
        Fixture f = ready();
        OtaCampaign c = scheduled(f, 1);
        runtime(f, r -> r.start(f.project(), c.id(), 1, f.account()));
        var claim = claimRuntime();
        assertThat(OtaBusinessRetryIntegrationTests.<Boolean>runtime(f, r -> r.admit(claim, 1, 1, OtaExecutionReportFixture.HASH, java.time.Instant.now()))).isTrue();
        // 缺失败观察：状态仍为DISPATCHED且无耗尽投递，必须拒绝。
        assertThat(OtaBusinessRetryIntegrationTests.<Boolean>retryRun(f, r -> r.beginRetry(f.tenant(), f.project(), claim.jobId(), "DISPATCH_TRANSIENT_FAILURE",
                        "NO_OBSERVATION")))
                .isFalse();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, claim.jobId()))
                .isEqualTo("DISPATCHED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? AND to_status='RETRY_WAIT'",
                Integer.class, claim.jobId())).isZero();
    }

    /** 到期领取的可空形式，用于断言"被围栏跳过"。 */
    private static java.util.Optional<OtaCampaignRuntimeRepository.RetryDue> claimDueOrEmpty(Fixture f) {
        return plain(j -> {
            j.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, f.tenant().toString());
            j.queryForObject("SELECT set_config('app.project_id',?,true)", String.class, f.project().toString());
            return new JdbcOtaCampaignRuntimeRepository(j).claimRetryDue();
        });
    }

    /** owner以关闭用户触发器的事务准备安装前停止围栏事实，不假装设备回执由生产路径产生。 */
    private void seedInstallStopFence(Fixture f, UUID jobId) {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)).execute(status -> {
            var j = new JdbcTemplate(source);
            j.execute("SET LOCAL session_replication_role=replica");
            UUID operation = com.things.link.shared.id.Uuid7.generate();
            UUID report = com.things.link.shared.id.Uuid7.generate();
            j.update("""
                    INSERT INTO ota_install_stop_operation(id,tenant_id,project_id,campaign_id,job_id,device_id,
                        attempt_no,credential_version,manifest_sha256,origin_hash,cancellation_revision,job_revision,
                        parent_baseline,stop_baseline,authorization_ids,canonical,payload_hash,created_at,deadline_at)
                    SELECT ?,j.tenant_id,j.project_id,j.campaign_id,j.id,j.device_id,j.attempt_no,
                        j.qualified_credential_version,c.manifest_sha256,repeat('a',64),2,j.state_version,
                        convert_to('{}','UTF8'),decode('02','hex'),ARRAY[]::uuid[],convert_to('{}','UTF8'),
                        encode(sha256(convert_to('{}','UTF8')),'hex'),now(),now()+interval '60 seconds'
                    FROM ota_device_job j JOIN ota_campaign c ON c.id=j.campaign_id WHERE j.id=?
                    """, operation, jobId);
            j.update("""
                    INSERT INTO ota_install_stop_report(id,operation_id,tenant_id,project_id,campaign_id,job_id,
                        device_id,attempt_no,credential_version,report_id,report_seq,boot_id,canonical,payload_hash,
                        disposition,status,reason,broker_received_at,accepted_at)
                    SELECT ?,o.id,o.tenant_id,o.project_id,o.campaign_id,o.job_id,o.device_id,o.attempt_no,
                        o.credential_version,?,1,?,'{1}',
                        encode(sha256('{1}'),'hex'),
                        'CANCELLED','STOPPED','INSTALL_STOP_WON_BEFORE_RETRY',now(),now()
                    FROM ota_install_stop_operation o WHERE o.id=?
                    """.replace("'{1}'", "convert_to('{}','UTF8')"), report, com.things.link.shared.id.Uuid7.generate(),
                    com.things.link.shared.id.Uuid7.generate(), operation);
            j.update("""
                    INSERT INTO ota_install_stop_control(operation_id,tenant_id,project_id,campaign_id,job_id,
                        accepted_report_id,stopped_report_id,next_query_at,fenced_at)
                    SELECT o.id,o.tenant_id,o.project_id,o.campaign_id,o.job_id,?,?,now(),now()
                    FROM ota_install_stop_operation o WHERE o.id=?
                    """, report, report, operation);
            return true;
        });
    }

    /** 到期领取不建立管理ThreadLocal账号，失败即整体回滚。 */
    private static OtaCampaignRuntimeRepository.RetryDue claimDue(Fixture f) {
        return plain(j -> {
            j.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, f.tenant().toString());
            j.queryForObject("SELECT set_config('app.project_id',?,true)", String.class, f.project().toString());
            return new JdbcOtaCampaignRuntimeRepository(j).claimRetryDue()
                    .orElseThrow(() -> new IllegalStateException("重试到期领取为空"));
        });
    }

    /** 受限函数在真实普通角色与项目RLS内执行。 */
    private static <T> T retryRun(Fixture f, java.util.function.Function<JdbcOtaCampaignRuntimeRepository, T> work) {
        return runtime(f, work);
    }

    /** 走真实准入后进入重试等待的已派发作业。
     * @param f 已就绪夹具
     * @param limitUnused 调用方保留位，当前冻结策略固定downloadRetryLimit=1
     * @return 处于RETRY_WAIT的作业标识
     */
    private UUID retryWaiting(Fixture f, int limitUnused) {
        OtaCampaign c = scheduled(f, 1);
        runtime(f, r -> r.start(f.project(), c.id(), 1, f.account()));
        var claim = claimRuntime();
        OtaBusinessRetryIntegrationTests.<Boolean>runtime(f, r -> r.admit(claim, 1, 1, OtaExecutionReportFixture.HASH, java.time.Instant.now()));
        seedExhaustedNotification(f, claim.jobId());
        assertThat(OtaBusinessRetryIntegrationTests.<Boolean>retryRun(f,
                r -> r.beginRetry(f.tenant(), f.project(), claim.jobId(), "DISPATCH_TRANSIENT_FAILURE",
                        "DISPATCH_NOT_ACKNOWLEDGED"))).isTrue();
        awaitNaturalBackoff(claim.jobId());
        return claim.jobId();
    }


    /** 夹具由owner直接准备一条真实的"通知投递已耗尽"暂时失败观察。
     * @param f 已就绪夹具
     * @param jobId 已派发作业
     */
    private void seedExhaustedNotification(Fixture f, UUID jobId) {
        // 夹具在单一事务内临时关闭用户触发器，以构造"交付已耗尽"这一暂时失败观察；
        // 该事务不产生未决事件，也不修改生产守卫或约束本身。
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)).execute(status -> {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("SET LOCAL session_replication_role=replica");
            int changed = jdbc.update("""
                    UPDATE ota_notification_delivery SET status='EXHAUSTED',revision=revision+1,
                        exhausted_at=clock_timestamp(),updated_at=clock_timestamp(),
                        reason='NOTIFICATION_DELIVERY_EXHAUSTED'
                    WHERE job_id=? AND status='WAITING' AND job_attempt_no=(SELECT attempt_no FROM ota_device_job WHERE id=?)
                    """, jobId, jobId);
            if (changed != 1) throw new IllegalStateException("通知交付事实不在WAITING，无法准备耗尽观察");
            return true;
        });
    }


    /** 冻结retryBackoffSeconds=1：等待退避自然到期，不由夹具伪造时间。 */
    private static void awaitNaturalBackoff(java.util.UUID jobId) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean due = owner().queryForObject(
                    "SELECT next_attempt_at<=clock_timestamp() FROM ota_device_job WHERE id=?", Boolean.class, jobId);
            if (Boolean.TRUE.equals(due)) return;
            try { Thread.sleep(200); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return; }
        }
        throw new IllegalStateException("退避未在预期时间内到期");
    }

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
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),APP_ROLE,APP_ROLE_PASSWORD);
        var jdbc=new JdbcTemplate(source);
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->work.apply(jdbc));
    }

    /** owner限于夹具与独立最终观察。 */
    protected static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }

    /** 本例独立父事实身份。 */
    protected record Fixture(UUID tenant,UUID project,UUID account,UUID type,UUID model,UUID firmware) { }
}
