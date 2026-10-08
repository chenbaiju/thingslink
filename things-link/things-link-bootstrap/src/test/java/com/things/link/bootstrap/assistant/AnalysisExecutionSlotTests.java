package com.things.link.bootstrap.assistant;

import com.things.link.assistant.application.*;
import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.domain.AnalysisCallRepository;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static com.things.link.assistant.domain.AnalysisCall.Status.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 无JVM计数桩，真实APP连接及持久槽；只使用假凭据，不启动外部调用。 */
class AnalysisExecutionSlotTests extends AbstractAssistantIntegrationTest {
    static final String MASTER=ModelConfigurationApiTests.master();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("things-link.assistant.credentials.active-key-id",()->"slot-test");
        r.add("things-link.assistant.credentials.keys.slot-test",()->MASTER);
    }
    @Autowired AnalysisCallService calls;
    @Autowired ModelConfigurationService models;
    @Autowired JdbcTemplate app;
    @Autowired com.things.link.assistant.domain.ModelCredentialCipher cipher;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @MockitoSpyBean AnalysisCallRepository metadata;
    @MockitoSpyBean com.things.link.assistant.domain.AnalysisSlotRepository slots;
    @MockitoSpyBean(name="ruleClock") java.time.Clock clock;
    JdbcTemplate owner;
    DataFixture data;
    UUID actor;
    @BeforeEach void setup() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        data=seed();actor=data.runtime().actorId();
        assertThat(app.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);
    }
    DataFixture seed() {
        var d=WebAppDataRuntimeFixture.seed(owner);UUID a=d.runtime().actorId();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",UUID.randomUUID(),d.runtime().projectId(),a);
        as(d,a,()->models.replace(d.runtime().projectId(),new ModelCredentialInput("0","synthetic-slot-test")));
        as(d,a,()->models.enable(d.runtime().projectId(),"1",true));return d;
    }
    UUID member(DataFixture d) {
        UUID a=UUID.randomUUID();owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'synthetic','slot-member')",a,a+"@example.test");
        join(d,a);return a;
    }
    void join(DataFixture d,UUID a) { owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OPERATOR')",UUID.randomUUID(),d.runtime().projectId(),a); }
    <T> T as(DataFixture d,UUID a,Supplier<T> action) {
        TenantContext.set(new TenantScope(d.runtime().tenantId(),d.runtime().projectId(),a));
        try {return action.get();} finally {TenantContext.clear();}
    }
    AnalysisRequest request(DataFixture d) {return new AnalysisRequest(d.first(),d.model(),List.of("temperature"),PreparedModelEvidence.Template.STATUS_SUMMARY);}
    AnalysisCallView reserve(DataFixture d,UUID a) {
        return as(d,a,()->calls.prepare(d.runtime().projectId(),com.things.link.shared.id.Uuid7.generate().toString(),request(d))).call();
    }
    AnalysisDispatchPermit dispatch(DataFixture d,UUID a,AnalysisCallView c) {return as(d,a,()->calls.dispatch(d.runtime().projectId(),c.id(),request(d)));}
    AnalysisDispatchPermit permit(DataFixture d,UUID a) {return dispatch(d,a,reserve(d,a));}
    void claim(DataFixture d,UUID a,AnalysisDispatchPermit p) {as(d,a,()->{calls.claimForExecution(d.runtime().projectId(),p);return null;});}
    boolean release(DataFixture d,UUID a,AnalysisDispatchPermit p) {return as(d,a,()->calls.releasePermit(d.runtime().projectId(),p));}
    void denied(Runnable work,int status) {
        var e=catchThrowableOfType(work::run,BusinessException.class);assertThat(e).isNotNull();assertThat(e.errorCode().httpStatus()).isEqualTo(status);
    }
    int count(DataFixture d) {return owner.queryForObject("SELECT count(*) FROM assistant_analysis_slot WHERE project_id=?",Integer.class,d.runtime().projectId());}
    @Test void projectAllowsTwoUsersAndDeniesThirdUntilStoppedWorkReleases() {
        UUID second=member(data),third=member(data);var a=permit(data,actor);var b=permit(data,second);var pending=reserve(data,third);
        denied(()->dispatch(data,third,pending),429);assertThat(count(data)).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT status FROM assistant_analysis_call WHERE id=?",String.class,pending.id())).isEqualTo("RESERVED");
        as(data,actor,()->calls.complete(data.runtime().projectId(),a.call().id(),FAILED));
        denied(()->dispatch(data,third,pending),429); // 状态结束本身不证明工作已停止。
        assertThat(release(data,actor,a)).isTrue();assertThat(release(data,actor,a)).isFalse();
        assertThat(dispatch(data,third,pending).call().status()).isEqualTo(DISPATCHED);assertThat(count(data)).isEqualTo(2);
        assertThat(release(data,second,new AnalysisDispatchPermit(b.call(),UUID.randomUUID()))).isFalse();assertThat(count(data)).isEqualTo(2);
    }
    @Test void userLimitSpansProjectsAndDoesNotRevealOtherProjectDetails() {
        var other=seed();join(other,actor);var a=permit(data,actor);var pending=reserve(other,actor);
        denied(()->dispatch(other,actor,pending),429);assertThat(count(other)).isZero();
        UUID independent=other.runtime().actorId();assertThat(permit(other,independent).call().status()).isEqualTo(DISPATCHED);
        release(data,actor,a);assertThat(dispatch(other,actor,pending).call().status()).isEqualTo(DISPATCHED);
        assertThat(count(other)).isEqualTo(2);
    }
    @Test void parallelThirdAdmissionHasExactlyOneWinnerInRemainingProjectSlot() throws Exception {
        permit(data,actor);UUID b=member(data),c=member(data);var rb=reserve(data,b);var rc=reserve(data,c);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CyclicBarrier(2);
            Callable<Boolean> wb=()->{start.await(5,TimeUnit.SECONDS);try {dispatch(data,b,rb);return true;} catch(BusinessException e){assertThat(e.errorCode().httpStatus()).isEqualTo(429);return false;}};
            Callable<Boolean> wc=()->{start.await(5,TimeUnit.SECONDS);try {dispatch(data,c,rc);return true;} catch(BusinessException e){assertThat(e.errorCode().httpStatus()).isEqualTo(429);return false;}};
            var x=pool.submit(wb);var y=pool.submit(wc);assertThat(List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(count(data)).isEqualTo(2);
    }
    @Test void parallelSameUserAcrossProjectsHasOnlyOneWinner() throws Exception {
        var other=seed();join(other,actor);var a=reserve(data,actor);var b=reserve(other,actor);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CyclicBarrier(2);
            Callable<Boolean> wa=()->{start.await(5,TimeUnit.SECONDS);try{dispatch(data,actor,a);return true;}catch(BusinessException e){assertThat(e.errorCode().httpStatus()).isEqualTo(429);return false;}};
            Callable<Boolean> wb=()->{start.await(5,TimeUnit.SECONDS);try{dispatch(other,actor,b);return true;}catch(BusinessException e){assertThat(e.errorCode().httpStatus()).isEqualTo(429);return false;}};
            var x=pool.submit(wa);var y=pool.submit(wb);assertThat(List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(count(data)+count(other)).isEqualTo(1);
    }
    @Test void claimIsAtomicSingleUseAndSuccessNeedsAClaim() throws Exception {
        var p=permit(data,actor);denied(()->as(data,actor,()->calls.complete(data.runtime().projectId(),p.call().id(),SUCCEEDED)),409);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CyclicBarrier(2);Callable<Boolean> work=()->{start.await(5,TimeUnit.SECONDS);try{claim(data,actor,p);return true;}catch(BusinessException e){assertThat(e.errorCode().httpStatus()).isEqualTo(409);return false;}};
            var a=pool.submit(work);var b=pool.submit(work);assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(as(data,actor,()->calls.complete(data.runtime().projectId(),p.call().id(),SUCCEEDED)).status()).isEqualTo(SUCCEEDED);
        assertThat(count(data)).isEqualTo(1);assertThat(release(data,actor,p)).isTrue();denied(()->claim(data,actor,p),409);
        assertThat(p.toString()).doesNotContain(p.token().toString(),"synthetic-slot-test");
    }
    @Test void currentRoleConfigurationAndOwnerAreRecheckedAtClaimAndRelease() {
        var p=permit(data,actor);UUID other=member(data);
        denied(()->claim(data,other,p),404);denied(()->release(data,other,p),404);
        as(data,actor,()->models.enable(data.runtime().projectId(),"2",false));denied(()->claim(data,actor,p),409);
        as(data,actor,()->models.enable(data.runtime().projectId(),"3",true));denied(()->claim(data,actor,p),409);
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",data.runtime().projectId(),actor);
        denied(()->claim(data,actor,p),403);denied(()->release(data,actor,p),403);assertThat(count(data)).isEqualTo(1);
    }
    @Test void failedDispatchRollsBackSlotAndDoesNotLeaveAHiddenOccupant() {
        var c=reserve(data,actor);
        doReturn(false).when(metadata).transition(argThat(v->v!=null&&c.id().equals(v.id())),eq(DISPATCHED),any());
        try {denied(()->dispatch(data,actor,c),409);} finally {reset(metadata);}
        assertThat(count(data)).isZero();assertThat(dispatch(data,actor,c).call().status()).isEqualTo(DISPATCHED);
    }
    AnalysisDispatchPermit expired(DataFixture d,UUID who) {
        UUID id=UUID.randomUUID(),token=UUID.randomUUID();
        owner.update("""
            INSERT INTO assistant_analysis_call(id,tenant_id,project_id,created_by,device_id,model_version_id,key_hash,request_hash,
                configuration_revision,status,created_at,deadline,expires_at)
            VALUES(?,?,?,?,?,?,encode(digest(?,'sha256'),'hex'),repeat('a',64),2,'RESERVED',now()-interval '2 minutes',
                now()-interval '1 minute',now()+interval '23 hours')
            """,id,d.runtime().tenantId(),d.runtime().projectId(),who,d.first(),d.model(),id.toString());
        owner.update("""
            INSERT INTO assistant_analysis_slot(call_id,tenant_id,project_id,created_by,project_slot,token,acquired_at,deadline)
            SELECT id,tenant_id,project_id,created_by,1,?,created_at,deadline FROM assistant_analysis_call WHERE id=?
            """,token,id);
        owner.update("UPDATE assistant_analysis_call SET status='DISPATCHED',dispatched_at=created_at+interval '1 second' WHERE id=?",id);
        return new AnalysisDispatchPermit(new AnalysisCallView(id,DISPATCHED,null,null,null,null,null),token);
    }
    @Test void expiredUserSlotReclaimedAcrossProjectsAndLateReleaseCannotDeleteReplacement() {
        var other=seed();join(other,actor);var old=expired(data,actor);
        denied(()->claim(data,actor,old),409);
        var fresh=permit(other,actor);assertThat(count(data)).isZero();assertThat(count(other)).isEqualTo(1);
        assertThat(release(data,actor,old)).isFalse();assertThat(count(other)).isEqualTo(1);
        assertThat(release(other,actor,fresh)).isTrue();
    }
    @Test void expiredProjectSlotsAreRemovedBeforeOrdinaryMetadataCleanup() {
        var old=expired(data,actor);
        assertThat(as(data,actor,()->calls.cleanupExpired(data.runtime().projectId()))).isZero();assertThat(count(data)).isZero();
        assertThat(as(data,actor,()->calls.read(data.runtime().projectId(),old.call().id())).status()).isEqualTo(UNKNOWN);
        assertThat(permit(data,actor).call().status()).isEqualTo(DISPATCHED);
    }
    @Test void rawAppCannotReadWriteSlotsOrInvokeFunctionsUnderWrongScope() {
        var p=permit(data,actor);UUID tenant=data.runtime().tenantId(),project=data.runtime().projectId();
        assertThatThrownBy(()->as(data,actor,()->app.queryForObject("SELECT count(*) FROM assistant_analysis_slot",Integer.class))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->as(data,actor,()->app.update("DELETE FROM assistant_analysis_slot WHERE call_id=?",p.call().id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->as(data,actor,()->app.update("UPDATE assistant_analysis_slot SET deadline=now()+interval '1 day' WHERE call_id=?",p.call().id()))).isInstanceOf(DataAccessException.class);
        TenantContext.set(new TenantScope(UUID.randomUUID(),project,actor));
        try {
            assertThat(app.queryForObject("SELECT assistant_analysis_slot_release(?,?,?,?,?)",Boolean.class,tenant,project,actor,p.call().id(),p.token())).isFalse();
            assertThat(app.queryForObject("SELECT assistant_analysis_slot_claim(?,?,?,?,?)",Boolean.class,tenant,project,actor,p.call().id(),p.token())).isFalse();
            assertThat(app.queryForObject("SELECT assistant_analysis_slot_acquire(?,?,?,?,?)",String.class,tenant,project,actor,p.call().id(),UUID.randomUUID())).isEqualTo("REJECTED");
        } finally {TenantContext.clear();}
        assertThat(count(data)).isEqualTo(1);
    }
    @Test void admissionWaitingDoesNotExtendDeadlineOrCommitAnExpiredPermit() {
        var c=reserve(data,actor);var later=c.deadline().plusSeconds(1);
        doAnswer(invocation->{var result=invocation.callRealMethod();doReturn(later).when(clock).instant();return result;})
            .when(slots).acquire(argThat(v->v!=null&&c.id().equals(v.id())),any());
        try {denied(()->dispatch(data,actor,c),409);}
        finally {reset(slots);reset(clock);}
        assertThat(count(data)).isZero();
        assertThat(owner.queryForObject("SELECT status FROM assistant_analysis_call WHERE id=?",String.class,c.id())).isEqualTo("RESERVED");
    }
    @Test void collaboratorJwtTenantDoesNotChangeSlotOwnership() {
        UUID foreign=UUID.randomUUID();owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'slot collaborator')",foreign);
        var c=reserve(data,actor);
        TenantContext.set(new TenantScope(foreign,data.runtime().projectId(),actor));
        try {
            var p=calls.dispatch(data.runtime().projectId(),c.id(),request(data));
            calls.claimForExecution(data.runtime().projectId(),p);
            assertThat(owner.queryForObject("SELECT tenant_id FROM assistant_analysis_slot WHERE call_id=?",UUID.class,c.id())).isEqualTo(data.runtime().tenantId());
            assertThat(calls.releasePermit(data.runtime().projectId(),p)).isTrue();
        } finally {TenantContext.clear();}
    }

    @Test void claimedContextBindsOriginalMetadataAndClearsSyntheticSecretOutsideTransaction() {
        var permit=permit(data,actor);
        var context=as(data,actor,()->calls.claimForExecution(data.runtime().projectId(),permit));
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(context.call().id()).isEqualTo(permit.call().id());
        assertThat(context.call().deadline()).isEqualTo(permit.call().deadline());
        assertThat(context.call().projectId()).isEqualTo(data.runtime().projectId());
        assertThat(context.call().tenantId()).isEqualTo(data.runtime().tenantId());
        assertThat(context.credential().revision()).isEqualTo(context.call().configurationRevision());
        assertThat(context.toString()).doesNotContain(context.call().id().toString(),"synthetic-slot-test","ciphertext");
        byte[][] captured=new byte[1][];
        boolean delivered=cipher.deliver(context.credential(),key->{
            captured[0]=key;
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(key).isEqualTo("synthetic-slot-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return true;
        });
        assertThat(delivered).isTrue();
        assertThat(captured[0]).isEqualTo(new byte[captured[0].length]);
        denied(()->claim(data,actor,permit),409);
    }

    @Test void changedDispatchViewCannotObtainCredentialOrConsumeClaim() {
        var permit=permit(data,actor);var c=permit.call();
        var changed=new AnalysisCallView(c.id(),c.status(),c.createdAt(),c.deadline().plusSeconds(1),
                c.expiresAt(),c.dispatchedAt(),c.finishedAt());
        denied(()->claim(data,actor,new AnalysisDispatchPermit(changed,permit.token())),409);
        assertThat(owner.queryForObject("SELECT claimed_at IS NULL FROM assistant_analysis_slot WHERE call_id=?",Boolean.class,c.id())).isTrue();
        assertThat(as(data,actor,()->calls.claimForExecution(data.runtime().projectId(),permit)).call().id()).isEqualTo(c.id());
    }

    @Test void claimWaitPastOriginalDeadlineRollsBackAndReturnsNoContext() {
        var permit=permit(data,actor);
        doAnswer(invocation->{var answer=invocation.callRealMethod();doReturn(permit.call().deadline()).when(clock).instant();return answer;})
                .when(slots).claim(argThat(c->c!=null&&permit.call().id().equals(c.id())),eq(permit.token()));
        try {denied(()->claim(data,actor,permit),409);}
        finally {reset(slots);reset(clock);}
        assertThat(owner.queryForObject("SELECT claimed_at IS NULL FROM assistant_analysis_slot WHERE call_id=?",Boolean.class,permit.call().id())).isTrue();
        claim(data,actor,permit);
    }

    @Test void outerRollbackCannotReopenCommittedExecutionContext() {
        var permit=permit(data,actor);
        assertThatThrownBy(()->as(data,actor,()->transaction.execute(status->{
            calls.claimForExecution(data.runtime().projectId(),permit);
            throw new IllegalStateException("synthetic outer rollback");
        }))).isInstanceOf(IllegalStateException.class);
        denied(()->claim(data,actor,permit),409);
    }

    @Test void configurationChangeWaitsForClaimCommitButCanRevokeImmediatelyAfterwards() throws Exception {
        var permit=permit(data,actor);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(invocation->{var answer=invocation.callRealMethod();entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return answer;})
                .when(slots).claim(argThat(c->c!=null&&permit.call().id().equals(c.id())),eq(permit.token()));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var claimed=executor.submit(()->as(data,actor,()->calls.claimForExecution(data.runtime().projectId(),permit)));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var attempted=new CountDownLatch(1);
            var disabled=executor.submit(()->as(data,actor,()->{attempted.countDown();return models.enable(data.runtime().projectId(),"2",false);}));
            try {
                assertThat(attempted.await(2,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->disabled.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {release.countDown();}
            var context=claimed.get(5,TimeUnit.SECONDS);
            assertThat(context.credential().enabled()).isTrue();
            assertThat(context.credential().revision()).isEqualTo(2);
            assertThat(disabled.get(5,TimeUnit.SECONDS).enabled()).isFalse();
            denied(()->claim(data,actor,permit),409);
        } finally {release.countDown();reset(slots);}
    }

}
