package com.things.link.bootstrap.assistant;

import com.things.link.assistant.application.*;
import com.things.link.assistant.domain.ModelCredentialCipher;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.*;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisRunServiceTests extends AbstractIntegrationTest {
    static final String MASTER=ModelConfigurationApiTests.master();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("things-link.assistant.credentials.active-key-id",()->"run-test");
        r.add("things-link.assistant.credentials.keys.run-test",()->MASTER);
    }
    @Autowired AnalysisRunService runs;
    @Autowired ModelConfigurationService models;
    @Autowired TransactionTemplate tx;
    @MockitoBean AnalysisTransport transport;
    @MockitoSpyBean ModelCredentialCipher cipher;
    @MockitoSpyBean DeviceEvidenceService evidence;
    @MockitoSpyBean AnalysisCallService calls;
    JdbcTemplate owner;
    DataFixture data;
    UUID project,actor;
    AnalysisRequest request;
    byte[] seen;
    AtomicInteger sent;
    @BeforeEach void setup() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        data=WebAppDataRuntimeFixture.seed(owner);project=data.runtime().projectId();actor=data.runtime().actorId();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",UUID.randomUUID(),project,actor);
        as(()->models.replace(project,new ModelCredentialInput("0","synthetic-run-only")));
        as(()->models.enable(project,"1",true));
        request=new AnalysisRequest(data.first(),data.model(),List.of("temperature"),PreparedModelEvidence.Template.STATUS_SUMMARY);
        sent=new AtomicInteger();seen=null;
        when(transport.ready()).thenReturn(true);
        when(transport.execute(any(),any(),any())).thenAnswer(invocation->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sent.incrementAndGet();seen=invocation.getArgument(2);
            assertThat(seen).isEqualTo("synthetic-run-only".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            PreparedModelEvidence.Input input=invocation.getArgument(1);
            assertThat(input.deviceAlias()).isEqualTo("device-1");assertThat(input.readings()).isEmpty();
            return AnalysisTransport.Receipt.UNQUALIFIED;
        });
    }
    <T> T as(Supplier<T> action) {
        TenantContext.set(new TenantScope(data.runtime().tenantId(),project,actor));
        try{return action.get();}finally{TenantContext.clear();}
    }
    AnalysisRunService.View run(String key) {return as(()->runs.run(project,key,request));}
    String key(){return Uuid7.generate().toString();}
    int slots(){return owner.queryForObject("SELECT count(*) FROM assistant_analysis_slot WHERE project_id=?",Integer.class,project);}
    void denied(Runnable action,int status) {
        var e=catchThrowableOfType(action::run,BusinessException.class);
        assertThat(e).isNotNull();assertThat(e.errorCode().httpStatus()).isEqualTo(status);
    }
    @Test void closedTransportAuthorizesButDoesNotReserveReadEvidenceOrDecrypt() {
        when(transport.ready()).thenReturn(false);
        assertThat(run(key()).category()).isEqualTo(AnalysisRunService.Category.UNAVAILABLE);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_analysis_call WHERE project_id=?",Integer.class,project)).isZero();
        verify(evidence,never()).read(any(),any(),any(),any());verify(cipher,never()).deliver(any(),any());
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",project,actor);
        denied(()->run(key()),403);assertThat(sent).hasValue(0);
    }
    @Test void unqualifiedReceiptNeverBecomesSuccessAndReplayDoesNotSendOrKeepSecret() {
        String key=key();var view=run(key);
        assertThat(view.category()).isEqualTo(AnalysisRunService.Category.UNQUALIFIED);
        assertThat(view.call().status()).isEqualTo(com.things.link.assistant.domain.AnalysisCall.Status.UNKNOWN);
        assertThat(slots()).isZero();assertThat(seen).isEqualTo(new byte[seen.length]);
        assertThat(run(key).category()).isEqualTo(AnalysisRunService.Category.REPLAY);assertThat(sent).hasValue(1);
        String row=owner.queryForObject("SELECT row_to_json(c)::text FROM assistant_analysis_call c WHERE id=?",String.class,view.call().id());
        assertThat(row).doesNotContain("synthetic-run-only","temperature","e-device","summary","ciphertext");
        assertThat(view.toString()).doesNotContain("synthetic-run-only","temperature");
    }
    @Test void unknownTransportRetainsSlotAndDoesNotEchoOrRetry() {
        doAnswer(invocation->{sent.incrementAndGet();seen=invocation.getArgument(2);throw new IllegalStateException("private supplier response");})
                .when(transport).execute(any(),any(),any());
        String key=key();var view=run(key);
        assertThat(view.category()).isEqualTo(AnalysisRunService.Category.TRANSPORT_UNKNOWN);
        assertThat(view.toString()).doesNotContain("private","supplier");
        assertThat(slots()).isEqualTo(1);assertThat(seen).isEqualTo(new byte[seen.length]);
        assertThat(run(key).category()).isEqualTo(AnalysisRunService.Category.REPLAY);assertThat(sent).hasValue(1);
    }
    @Test void revocationDuringTransportRejectsReturnAndDoesNotBypassReleaseAuthorization() {
        doAnswer(invocation->{sent.incrementAndGet();seen=invocation.getArgument(2);
            owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",project,actor);
            return AnalysisTransport.Receipt.UNQUALIFIED;
        }).when(transport).execute(any(),any(),any());
        denied(()->run(key()),403);assertThat(sent).hasValue(1);assertThat(slots()).isEqualTo(1);
        assertThat(seen).isEqualTo(new byte[seen.length]);
    }
    @Test void configurationChangeDuringTransportRejectsReturnButSettledWorkerCanRelease() {
        doAnswer(invocation->{sent.incrementAndGet();models.enable(project,"2",false);return AnalysisTransport.Receipt.UNQUALIFIED;})
                .when(transport).execute(any(),any(),any());
        denied(()->run(key()),409);assertThat(sent).hasValue(1);assertThat(slots()).isZero();
    }
    @Test void evidenceFailureNeverDecryptsAndRemainsReplayProtected() {
        doThrow(new IllegalStateException("private evidence error")).when(evidence).read(any(),any(),any(),any());
        String key=key();var view=run(key);
        assertThat(view.category()).isEqualTo(AnalysisRunService.Category.TRANSPORT_UNKNOWN);
        verify(cipher,never()).deliver(any(),any());assertThat(slots()).isZero();assertThat(sent).hasValue(0);
        assertThat(run(key).category()).isEqualTo(AnalysisRunService.Category.REPLAY);
    }
    @Test void configurationChangedBeforeFinalSendCheckDoesNotDecrypt() {
        doAnswer(invocation->{models.enable(project,"2",false);return invocation.callRealMethod();})
                .when(calls).verifyExecution(any(),any());
        denied(()->run(key()),409);verify(cipher,never()).deliver(any(),any());assertThat(sent).hasValue(0);
    }
    @Test void outerTransactionIsSuspendedForAllCredentialAndNetworkWork() {
        var view=as(()->tx.execute(status->runs.run(project,key(),request)));
        assertThat(view.category()).isEqualTo(AnalysisRunService.Category.UNQUALIFIED);assertThat(sent).hasValue(1);
    }
    @Test void concurrentSameKeyRunsAtMostOneTransport() throws Exception {
        String key=key();var start=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<AnalysisRunService.View> work=()->{start.await(3,TimeUnit.SECONDS);return run(key);};
            var first=pool.submit(work);var second=pool.submit(work);
            assertThat(List.of(first.get(10,TimeUnit.SECONDS).category(),second.get(10,TimeUnit.SECONDS).category()))
                    .containsExactlyInAnyOrder(AnalysisRunService.Category.UNQUALIFIED,AnalysisRunService.Category.REPLAY);
        }
        assertThat(sent).hasValue(1);
    }
    @Test void missingReceiptCannotReleaseSlotOrCreateSuccess() {
        doAnswer(invocation->{seen=invocation.getArgument(2);return null;}).when(transport).execute(any(),any(),any());
        assertThat(run(key()).category()).isEqualTo(AnalysisRunService.Category.TRANSPORT_UNKNOWN);
        assertThat(slots()).isEqualTo(1);assertThat(seen).isEqualTo(new byte[seen.length]);
    }
}
