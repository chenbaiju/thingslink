package com.things.link.bootstrap.assistant;

import com.things.link.assistant.application.*;
import com.things.link.assistant.domain.AnalysisCall;
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
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static com.things.link.assistant.domain.AnalysisCall.Status.*;
import static org.assertj.core.api.Assertions.*;

/** 真APP/RLS/短事务，测试凭据及合成设备，不访问模型。 */
class AnalysisCallLifecycleTests extends AbstractAssistantIntegrationTest {
    static final String MASTER = ModelConfigurationApiTests.master();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("things-link.assistant.credentials.active-key-id", () -> "call-test");
        r.add("things-link.assistant.credentials.keys.call-test", () -> MASTER);
    }
    @Autowired AnalysisCallService calls;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.things.link.device.application.ConsoleDeviceEvidenceService devices;
    @Autowired ModelConfigurationService models;
    @org.springframework.test.context.bean.override.mockito.MockitoBean(name="ruleClock") Clock clock;
    @Autowired JdbcTemplate app;
    @Autowired TransactionTemplate tx;
    JdbcTemplate owner;
    DataFixture data;
    UUID project, tenant, actor;
    AnalysisRequest request;
    @BeforeEach void setup() {
        org.mockito.Mockito.when(clock.instant()).thenReturn(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        data = WebAppDataRuntimeFixture.seed(owner);
        project = data.runtime().projectId(); tenant = data.runtime().tenantId(); actor = data.runtime().actorId();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')", UUID.randomUUID(), project, actor);
        as(() -> models.replace(project, new ModelCredentialInput("0", "synthetic-analysis-only")));
        as(() -> models.enable(project, "1", true));
        role("OPERATOR");
        request = new AnalysisRequest(data.first(), data.model(), List.of("temperature"), PreparedModelEvidence.Template.STATUS_SUMMARY);
        assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
    }
    private <T> T as(Supplier<T> action) { return as(tenant, project, actor, action); }
    private <T> T as(UUID t, UUID p, UUID a, Supplier<T> action) {
        TenantContext.set(new TenantScope(t,p,a));
        try { return action.get(); } finally { TenantContext.clear(); }
    }
    private void role(String role) { owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?", role,project,actor); }
    private String key() { return key(clock.instant()); }
    private String key(Instant issued) {
        return new UUID((issued.toEpochMilli()<<16)|0x7000|(UUID.randomUUID().getMostSignificantBits()&0xfff),
                (UUID.randomUUID().getLeastSignificantBits()&0x3fffffffffffffffL)|0x8000000000000000L).toString();
    }
    private AnalysisCallView.Reservation prepare(String key) { return as(() -> calls.prepare(project,key,request)); }
    private AnalysisCallView dispatch(UUID id) { return as(() -> calls.dispatch(project,id,request)).call(); }
    private AnalysisCallView complete(UUID id, AnalysisCall.Status status) { return as(() -> calls.complete(project,id,status)); }
    private void denied(Runnable action, int http) {
        var e = catchThrowableOfType(action::run, BusinessException.class);
        assertThat(e).isNotNull(); assertThat(e.errorCode().httpStatus()).isEqualTo(http);
    }
    @Test void durablePrepareDispatchAndReplayNeverContainsBodiesOrAnotherPermit() {
        String k=key();var first=prepare(k);var id=first.call().id();
        assertThat(first.fresh()).isTrue();assertThat(prepare(k).fresh()).isFalse();
        var permit=as(() -> calls.dispatch(project,id,request));
        assertThat(permit.call().status()).isEqualTo(DISPATCHED);denied(()->dispatch(id),409);
        as(() -> { calls.claimForExecution(project,permit); return null; });
        assertThat(complete(id,SUCCEEDED).status()).isEqualTo(SUCCEEDED);
        assertThat(complete(id,SUCCEEDED).status()).isEqualTo(SUCCEEDED);
        denied(()->complete(id,FAILED),409);assertThat(prepare(k).fresh()).isFalse();
        String row=owner.queryForObject("SELECT row_to_json(c)::text FROM assistant_analysis_call c WHERE id=?",String.class,id);
        assertThat(row).doesNotContain(k,"synthetic-analysis-only","temperature","ciphertext","answer","evidence");
        assertThat(first.toString()).doesNotContain(k,"temperature","synthetic-analysis-only");
    }
    @Test void failedUnknownAndChangedBodyRequireAnExplicitNewKey() {
        for(var status:List.of(FAILED,UNKNOWN)) {
            String k=key();var first=prepare(k);complete(first.call().id(),status);
            assertThat(prepare(k).call().status()).isEqualTo(status);denied(()->dispatch(first.call().id()),409);
            var changed=new AnalysisRequest(data.second(),data.model(),request.propertyKeys(),request.template());
            denied(()->as(()->calls.prepare(project,k,changed)),409);
            assertThat(prepare(key()).fresh()).isTrue();
        }
        var call=prepare(key()).call();var changed=new AnalysisRequest(data.first(),data.model(),List.of("secret","temperature"),request.template());
        denied(()->as(()->calls.dispatch(project,call.id(),changed)),409);
        denied(()->complete(call.id(),SUCCEEDED),409);
    }
    @Test void parallelPreparationAndDispatchHaveExactlyOneWinner() throws Exception {
        String k=key();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CyclicBarrier(2);
            Callable<AnalysisCallView.Reservation> work=()->{start.await(5,TimeUnit.SECONDS);return prepare(k);};
            var a=pool.submit(work);var b=pool.submit(work);var aa=a.get(15,TimeUnit.SECONDS);var bb=b.get(15,TimeUnit.SECONDS);
            assertThat(List.of(aa.fresh(),bb.fresh())).containsExactlyInAnyOrder(true,false);
            assertThat(aa.call().id()).isEqualTo(bb.call().id());
            Callable<Boolean> send=()->{start.await(5,TimeUnit.SECONDS);try {dispatch(aa.call().id());return true;} catch(BusinessException e) {assertThat(e.errorCode().httpStatus()).isEqualTo(409);return false;}};
            var c=pool.submit(send);var d=pool.submit(send);
            assertThat(List.of(c.get(15,TimeUnit.SECONDS),d.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void uuidClockBoundariesAndDeletedExpiredMetadataCannotReopenKey() {
        for(String bad:List.of(UUID.randomUUID().toString(),new UUID((clock.instant().toEpochMilli()<<16)|0x7abc,0x8abcdefabcdefabcL).toString().toUpperCase(Locale.ROOT),key(clock.instant().minusSeconds(86400)),key(clock.instant().plusSeconds(301))))
            denied(()->prepare(bad),400);
        denied(()->prepare(key(clock.instant().minusSeconds(86340))),400);
        assertThat(prepare(key(clock.instant().plusSeconds(300))).fresh()).isTrue();
        String old=key();var c=prepare(old).call();
        org.mockito.Mockito.doReturn(clock.instant().plusSeconds(60)).when(clock).instant();
        assertThat(as(()->calls.read(project,c.id())).status()).isEqualTo(UNKNOWN);
        assertThat(complete(c.id(),SUCCEEDED).status()).isEqualTo(UNKNOWN);
        denied(()->dispatch(c.id()),409);
        org.mockito.Mockito.doReturn(clock.instant().plusSeconds(86400)).when(clock).instant();
        // 数据库时钟也必须已过期；另造过期行而不篡改保护时间。
        String expiredKey=key(Instant.now().minusSeconds(86410));
        owner.update("""
            INSERT INTO assistant_analysis_call(id,tenant_id,project_id,created_by,device_id,model_version_id,key_hash,request_hash,
                configuration_revision,status,created_at,deadline,expires_at)
            VALUES (?,?,?,?,?,?,encode(digest(?,'sha256'),'hex'),repeat('b',64),2,'RESERVED',now()-interval '25 hours',
                now()-interval '25 hours'+interval '60 seconds',now()-interval '1 second')
            """,UUID.randomUUID(),tenant,project,actor,data.first(),data.model(),expiredKey);
        assertThat(as(()->calls.cleanupExpired(project))).isEqualTo(1);
        denied(()->prepare(expiredKey),400);denied(()->prepare(old),400);
    }
    @Test void changingConfigurationBeforeDispatchRejectsWithoutConsumingAgain() {
        String k=key();var c=prepare(k).call();role("ADMIN");as(()->models.enable(project,"2",false));
        denied(()->dispatch(c.id()),409);denied(()->prepare(key()),409);
        assertThat(prepare(k).fresh()).isFalse();
        as(()->models.enable(project,"3",true));denied(()->dispatch(c.id()),409);
        assertThat(prepare(key()).fresh()).isTrue();
    }
    @Test void viewerRevocationArchiveAndWrongSelectedProjectAreRecheckedOnEveryStep() {
        var c=prepare(key()).call();role("VIEWER");
        denied(()->prepare(key()),403);denied(()->dispatch(c.id()),403);
        denied(()->complete(c.id(),FAILED),403);denied(()->as(()->calls.read(project,c.id())),403);
        role("OPERATOR");denied(()->as(tenant,UUID.randomUUID(),actor,()->calls.read(project,c.id())),404);
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",project,actor);
        denied(()->dispatch(c.id()),404);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OPERATOR')",UUID.randomUUID(),project,actor);
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",project);
        denied(()->dispatch(c.id()),403);denied(()->prepare(key()),403);
    }
    @Test void lookupByOriginalKeyExpiresUnknownWithoutReleasingOrRenewingExecution() {
        String original=key();var call=prepare(original).call();
        var permit=as(()->calls.dispatch(project,call.id(),request));
        as(()->calls.claimForExecution(project,permit));
        assertThat(as(()->calls.readByKey(project,original)).id()).isEqualTo(call.id());
        org.mockito.Mockito.doReturn(clock.instant().plusSeconds(61)).when(clock).instant();
        var view=as(()->calls.readByKey(project,original));
        assertThat(view.status()).isEqualTo(UNKNOWN);assertThat(view.deadline()).isEqualTo(call.deadline());
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_analysis_slot WHERE call_id=?",Integer.class,call.id())).isEqualTo(1);
        org.mockito.Mockito.doReturn(clock.instant().plusSeconds(86400)).when(clock).instant();
        denied(()->as(()->calls.readByKey(project,original)),400);
    }
    @Test void keyLookupRechecksRoleAfterDeviceRevalidation() {
        String original=key();var call=prepare(original).call();
        org.mockito.Mockito.doAnswer(invocation->{invocation.callRealMethod();role("VIEWER");return null;})
                .when(devices).revalidate(project,data.first(),data.model());
        denied(()->as(()->calls.readByKey(project,original)),403);
        assertThat(owner.queryForObject("SELECT status FROM assistant_analysis_call WHERE id=?",String.class,call.id())).isEqualTo("RESERVED");
    }
    @Test void collaboratorUsesActualTenantAndCreatorCannotReadNeighbors() {
        UUID foreign=UUID.randomUUID();owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'analysis collaborator')",foreign);
        String k=key();var c=as(foreign,project,actor,()->calls.prepare(project,k,request)).call();
        assertThat(owner.queryForObject("SELECT tenant_id FROM assistant_analysis_call WHERE id=?",UUID.class,c.id())).isEqualTo(tenant);
        UUID other=UUID.randomUUID();owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'synthetic','other')",other,other+"@example.test");
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OPERATOR')",UUID.randomUUID(),project,other);
        denied(()->as(tenant,project,other,()->calls.read(project,c.id())),404);
        denied(()->as(tenant,project,other,()->calls.dispatch(project,c.id(),request)),404);
        var own=as(tenant,project,other,()->calls.prepare(project,k,request));assertThat(own.fresh()).isTrue();
        assertThat(own.call().id()).isNotEqualTo(c.id());
        assertThat(as(foreign,project,actor,()->calls.readByKey(project,k)).id()).isEqualTo(c.id());
        assertThat(as(tenant,project,other,()->calls.readByKey(project,k)).id()).isEqualTo(own.call().id());
    }
    @Test void deviceModelAndPropertyOwnershipRemainInDeviceModule() {
        var c=prepare(key()).call();var other=WebAppDataRuntimeFixture.seed(owner);
        var cross=new AnalysisRequest(other.first(),other.model(),List.of("temperature"),request.template());
        denied(()->as(()->calls.prepare(project,key(),cross)),404);
        var wrong=new AnalysisRequest(data.first(),other.model(),List.of("temperature"),request.template());
        denied(()->as(()->calls.prepare(project,key(),wrong)),409);
        var absent=new AnalysisRequest(data.first(),data.model(),List.of("not_present"),request.template());
        denied(()->as(()->calls.prepare(project,key(),absent)),400);
        owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?",data.first());
        denied(()->dispatch(c.id()),404);denied(()->as(()->calls.read(project,c.id())),404);
    }
    @Test void appRlsImmutableIdentityAndOrdinaryDeletionCannotEraseProtection() {
        var c=prepare(key()).call();
        assertThat(as(()->app.update("DELETE FROM assistant_analysis_call WHERE id=?",c.id()))).isZero();
        assertThat(as(UUID.randomUUID(),project,actor,()->app.queryForObject("SELECT count(*) FROM assistant_analysis_call",Integer.class))).isZero();
        assertThat(as(tenant,UUID.randomUUID(),actor,()->app.queryForObject("SELECT count(*) FROM assistant_analysis_call",Integer.class))).isZero();
        for(String set:List.of("expires_at=now()-interval '1 day'","request_hash=repeat('c',64)","created_by=gen_random_uuid()","status='SUCCEEDED',finished_at=now()"))
            assertThatThrownBy(()->as(()->app.update("UPDATE assistant_analysis_call SET "+set+" WHERE id=?",c.id()))).isInstanceOf(DataAccessException.class);
        complete(c.id(),FAILED);
        assertThatThrownBy(()->as(()->app.update("UPDATE assistant_analysis_call SET status='RESERVED',finished_at=NULL WHERE id=?",c.id()))).isInstanceOf(DataAccessException.class);
    }
    @Test void reservationCommitSurvivesFailureInOuterTransaction() {
        String k=key();assertThatThrownBy(()->as(()->tx.execute(s->{var r=calls.prepare(project,k,request);assertThat(r.fresh()).isTrue();throw new IllegalStateException("outer rollback");}))).isInstanceOf(IllegalStateException.class);
        assertThat(prepare(k).fresh()).isFalse();
    }
    @Test void propertyOrderIsPartOfRequestIdentity() {
        String k=key();var forward=new AnalysisRequest(data.first(),data.model(),List.of("temperature","secret"),request.template());
        var reverse=new AnalysisRequest(data.first(),data.model(),List.of("secret","temperature"),request.template());
        as(()->calls.prepare(project,k,forward));denied(()->as(()->calls.prepare(project,k,reverse)),409);
    }

    @Test void modelDriftAfterReservationAndRevocationAfterDeviceRecheckDenyDispatch() {
        var c=prepare(key()).call();UUID next=UUID.randomUUID();
        owner.update("""
            INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
            SELECT ?,tenant_id,project_id,device_type_id,'1.1.0',1,1,0,'MINOR',schema_profile,model_snapshot,schema_digest,digest_algorithm
            FROM dev_thing_model_version WHERE id=?
            """,next,data.model());
        owner.update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?",next,data.first());
        denied(()->dispatch(c.id()),409);
        owner.update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?",data.model(),data.first());
        org.mockito.Mockito.doAnswer(invocation->{
            invocation.callRealMethod();
            owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",project,actor);
            return null;
        }).when(devices).revalidate(project,data.first(),data.model());
        try { denied(()->dispatch(c.id()),404); }
        finally { org.mockito.Mockito.reset(devices); }
        assertThat(owner.queryForObject("SELECT status FROM assistant_analysis_call WHERE id=?",String.class,c.id())).isEqualTo("RESERVED");
    }

}
