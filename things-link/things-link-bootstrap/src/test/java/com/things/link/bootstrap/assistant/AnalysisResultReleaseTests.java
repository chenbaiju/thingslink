package com.things.link.bootstrap.assistant;

import com.things.link.assistant.application.*;
import com.things.link.assistant.domain.*;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.device.application.ConsoleDeviceEvidenceService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisResultReleaseTests extends AbstractAssistantIntegrationTest {
    static final String MASTER = ModelConfigurationApiTests.master();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("things-link.assistant.credentials.active-key-id", () -> "release-test");
        r.add("things-link.assistant.credentials.keys.release-test", () -> MASTER);
    }
    @Autowired AnalysisCallService calls;
    @Autowired ModelConfigurationService models;
    @Autowired TransactionTemplate tx;
    @MockitoSpyBean ConsoleDeviceEvidenceService devices;
    @MockitoSpyBean AnalysisCallRepository repository;
    @MockitoSpyBean ModelCredentialCipher cipher;
    @MockitoBean(name="ruleClock") Clock clock;
    JdbcTemplate owner;
    DataFixture data;
    UUID project, tenant, actor;
    AnalysisRequest request;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        data = WebAppDataRuntimeFixture.seed(owner);
        project=data.runtime().projectId();tenant=data.runtime().tenantId();actor=data.runtime().actorId();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",UUID.randomUUID(),project,actor);
        as(() -> models.replace(project,new ModelCredentialInput("0","synthetic-release-only")));
        as(() -> models.enable(project,"1",true));
        request=new AnalysisRequest(data.first(),data.model(),List.of("temperature"),PreparedModelEvidence.Template.STATUS_SUMMARY);
    }
    <T> T as(Supplier<T> action) {
        TenantContext.set(new TenantScope(tenant,project,actor));
        try { return action.get(); } finally { TenantContext.clear(); }
    }
    AnalysisExecutionContext execution() {
        return as(() -> {
            var call=calls.prepare(project,Uuid7.generate().toString(),request).call();
            return calls.claimForExecution(project,calls.dispatch(project,call.id(),request));
        });
    }
    ReleasedAnalysisResult release(AnalysisExecutionContext context, AnalysisResultPermit permit) {
        return as(() -> calls.releaseResult(project,context,permit));
    }
    String status(AnalysisExecutionContext context) {
        return owner.queryForObject("SELECT status FROM assistant_analysis_call WHERE id=?",String.class,context.call().id());
    }
    void denied(Runnable action, int status) {
        var failure=catchThrowableOfType(action::run,BusinessException.class);
        assertThat(failure).isNotNull();assertThat(failure.errorCode().httpStatus()).isEqualTo(status);
    }
    void role(String name) { owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",name,project,actor); }

    @Test void releasesOnceAfterCommitWithoutPersistingContentOrReleasingSlot() {
        var context=execution();var permit=AnalysisResultPermitFixture.issue(context);
        var result=release(context,permit);
        assertThat(result.call().status()).isEqualTo(AnalysisCall.Status.SUCCEEDED);
        assertThat(status(context)).isEqualTo("SUCCEEDED");
        assertThat(result.content().summary()).isEqualTo("synthetic-private-summary");
        assertThat(result.usage().totalTokens()).isEqualTo(12);
        assertThat(result.toString()).doesNotContain("synthetic","summary");
        assertThat(permit.toString()).doesNotContain("synthetic","summary");
        assertThat(owner.queryForObject("SELECT row_to_json(c)::text FROM assistant_analysis_call c WHERE id=?",String.class,context.call().id()))
                .doesNotContain("synthetic","summary","content","usage","e-device");
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_analysis_slot WHERE call_id=?",Integer.class,context.call().id())).isEqualTo(1);
        denied(() -> release(context,permit),409);
        assertThat(as(() -> calls.read(project,context.call().id())).status()).isEqualTo(AnalysisCall.Status.SUCCEEDED);
        verify(cipher,never()).deliver(any(),any());
    }
    @Test void concurrentDistinctPermitsStillReleaseSameCallOnlyOnce() throws Exception {
        var context=execution();var first=AnalysisResultPermitFixture.issue(context);var second=AnalysisResultPermitFixture.issue(context);
        var barrier=new CyclicBarrier(2);
        try (var pool=Executors.newFixedThreadPool(2)) {
            java.util.function.Function<AnalysisResultPermit,Callable<Boolean>> work=permit -> () -> {
                barrier.await(5,TimeUnit.SECONDS);
                try { release(context,permit);return true; }
                catch (BusinessException conflict) { assertThat(conflict.errorCode().httpStatus()).isEqualTo(409);return false; }
            };
            var a=pool.submit(work.apply(first));var b=pool.submit(work.apply(second));
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void revocationAfterDeviceReadRejectsFinalReturn() {
        var context=execution();var permit=AnalysisResultPermitFixture.issue(context);
        doAnswer(invocation -> { invocation.callRealMethod();role("VIEWER");return null; })
                .when(devices).revalidate(project,data.first(),data.model());
        denied(() -> release(context,permit),403);assertThat(status(context)).isEqualTo("DISPATCHED");
    }
    @Test void changedConfigurationRejectsFinalReturn() {
        var context=execution();var permit=AnalysisResultPermitFixture.issue(context);
        as(() -> models.enable(project,"2",false));
        denied(() -> release(context,permit),409);assertThat(status(context)).isEqualTo("DISPATCHED");
    }
    @Test void deletedDeviceRejectsFinalReturn() {
        var context=execution();var permit=AnalysisResultPermitFixture.issue(context);
        owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?",data.first());
        denied(() -> release(context,permit),404);
    }
    @Test void changedModelRejectsFinalReturn() {
        var context=execution();var permit=AnalysisResultPermitFixture.issue(context);UUID next=UUID.randomUUID();
        owner.update("""
            INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
            SELECT ?,tenant_id,project_id,device_type_id,'1.1.0',1,1,0,'MINOR',schema_profile,model_snapshot,schema_digest,digest_algorithm
            FROM dev_thing_model_version WHERE id=?
            """,next,data.model());
        owner.update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?",next,data.first());
        denied(() -> release(context,permit),409);assertThat(status(context)).isEqualTo("DISPATCHED");
    }
    @Test void deadlineOrEarlierPermitExpiryRejectsWithoutReturningContent() {
        var context=execution();var permit=AnalysisResultPermitFixture.issue(context,clock.instant().plusSeconds(5));
        doReturn(clock.instant().plusSeconds(5)).when(clock).instant();
        assertThatThrownBy(() -> release(context,permit)).isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
        assertThat(status(context)).isEqualTo("DISPATCHED");
        when(clock.instant()).thenReturn(context.call().deadline());
        denied(() -> release(context,permit),409);
    }
    @Test void missingDurableClaimRejectsEvenWithSyntheticPermit() {
        var context=execution();var permit=AnalysisResultPermitFixture.issue(context);
        owner.update("DELETE FROM assistant_analysis_slot WHERE call_id=?",context.call().id());
        denied(() -> release(context,permit),409);assertThat(status(context)).isEqualTo("DISPATCHED");
    }
    @Test void foreignAccountAndProjectCannotUsePermit() {
        var context=execution();var permit=AnalysisResultPermitFixture.issue(context);
        UUID originalActor=actor;actor=UUID.randomUUID();
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'synthetic','other')",actor,actor+"@example.test");
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OPERATOR')",UUID.randomUUID(),project,actor);
        denied(() -> release(context,permit),404);
        actor=originalActor;UUID originalProject=project;project=UUID.randomUUID();
        denied(() -> release(context,permit),404);
        project=originalProject;assertThat(status(context)).isEqualTo("DISPATCHED");
    }
    @Test void rolledBackCompletionBurnsPermitAndDoesNotPersistSuccess() {
        var context=execution();var permit=AnalysisResultPermitFixture.issue(context);
        doAnswer(invocation -> { invocation.callRealMethod();throw new IllegalStateException("synthetic write failure"); })
                .when(repository).transition(any(),eq(AnalysisCall.Status.SUCCEEDED),any());
        assertThatThrownBy(() -> release(context,permit)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(status(context)).isEqualTo("DISPATCHED");
        doCallRealMethod().when(repository).transition(any(),eq(AnalysisCall.Status.SUCCEEDED),any());
        assertThatThrownBy(() -> release(context,permit)).isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
    }
    @Test void expiryDuringStatusWriteRollsBackWithoutRenewingPermit() {
        var context=execution();var expires=clock.instant().plusSeconds(5);
        var permit=AnalysisResultPermitFixture.issue(context,expires);
        doAnswer(invocation -> {
            var updated=invocation.callRealMethod();doReturn(expires).when(clock).instant();return updated;
        }).when(repository).transition(any(),eq(AnalysisCall.Status.SUCCEEDED),any());
        assertThatThrownBy(() -> release(context,permit)).isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
        assertThat(status(context)).isEqualTo("DISPATCHED");
    }
    @Test void surroundingRollbackCannotReopenCommittedRelease() {
        var context=execution();var permit=AnalysisResultPermitFixture.issue(context);
        var result=as(() -> tx.execute(status -> {
            var view=calls.releaseResult(project,context,permit);status.setRollbackOnly();return view;
        }));
        assertThat(result.call().status()).isEqualTo(AnalysisCall.Status.SUCCEEDED);
        assertThat(status(context)).isEqualTo("SUCCEEDED");
        denied(() -> release(context,permit),409);
    }
    @Test void missingPermitCannotProduceSuccess() {
        var context=execution();denied(() -> release(context,null),400);
        assertThat(status(context)).isEqualTo("DISPATCHED");
    }
}
