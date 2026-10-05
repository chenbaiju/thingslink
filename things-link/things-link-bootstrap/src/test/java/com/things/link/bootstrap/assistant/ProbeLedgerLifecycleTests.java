package com.things.link.bootstrap.assistant;

import com.things.link.assistant.application.*;
import com.things.link.assistant.domain.ProbeLedger.*;
import com.things.link.assistant.domain.ProbeLedgerRepository;
import com.things.link.assistant.infrastructure.persistence.JdbcProbeLedgerRepository;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.testing.AbstractIntegrationTest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 隔离真实APP/RLS，不使用真实供应商Key，也不发送任何网络模型请求。 */
class ProbeLedgerLifecycleTests extends AbstractIntegrationTest {
    static final String MASTER=ModelConfigurationApiTests.master();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("things-link.assistant.credentials.active-key-id",()->"probe-test");
        r.add("things-link.assistant.credentials.keys.probe-test",()->MASTER);
    }
    @Autowired ProbeLedgerService probes;
    @Autowired ProbeLedgerRepository ledger;
    @Autowired ModelConfigurationService models;
    @Autowired AssistantProjectCleanupContributor cleanup;
    @Autowired JdbcTemplate app;
    @Autowired TransactionTemplate tx;
    @Autowired TransactionLocalRlsScope rls;
    @MockitoBean ProbeAuthorizationProvider authorizations;
    @MockitoBean(name="ruleClock") Clock clock;
    JdbcTemplate owner;
    UUID project,tenant,actor;
    ProbeAuthorization binding;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        var f=WebAppDataRuntimeFixture.seed(owner).runtime();
        project=f.projectId();tenant=f.tenantId();actor=f.actorId();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'ADMIN')",UUID.randomUUID(),project,actor);
        as(()->models.replaceAndEnable(project,new ModelCredentialInput("0","synthetic-probe-ledger-only")));
        role("OPERATOR");
        binding=new ProbeAuthorization("grant-"+project,"isolated-test",project,2,"a".repeat(64));
        when(authorizations.find(any())).thenAnswer(i->Objects.equals(i.getArgument(0),binding.id())?Optional.of(binding):Optional.empty());
        assertThat(app.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);
    }
    <T> T as(Supplier<T> action) { return as(tenant,project,actor,action); }
    <T> T as(UUID t,UUID p,UUID a,Supplier<T> action) {
        TenantContext.set(new TenantScope(t,p,a));try{return action.get();}finally{TenantContext.clear();}
    }
    <T> T raw(UUID t,UUID p,Supplier<T> action) { return as(t,p,actor,()->tx.execute(s->{rls.establish(t,p);return action.get();})); }
    Attempt claim(int index) { return as(()->probes.claim(project,binding.id(),index)); }
    void role(String role) { owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",role,project,actor); }
    void denied(Runnable action,int status) {
        var error=catchThrowableOfType(action::run,BusinessException.class);assertThat(error).isNotNull();assertThat(error.errorCode().httpStatus()).isEqualTo(status);
    }
    int count() { return owner.queryForObject("SELECT count(*) FROM assistant_probe_attempt WHERE project_id=?",Integer.class,project); }
    @Test void threeFixedSamplesAreSharedDurableAndNeverReclaimed() {
        int i=1;
        for(var state:List.of(Status.SUCCEEDED,Status.FAILED,Status.UNKNOWN)) {
            int index=i++;var attempt=claim(index);
            assertThat(as(()->probes.finish(project,attempt.id(),state)).status()).isEqualTo(state);
            denied(()->claim(index),409);
        }
        assertThat(count()).isEqualTo(3);denied(()->claim(0),400);denied(()->claim(4),400);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_probe_batch WHERE project_id=?",Integer.class,project)).isEqualTo(1);
        String row=owner.queryForObject("SELECT row_to_json(a)::text FROM assistant_probe_attempt a WHERE project_id=? LIMIT 1",String.class,project);
        assertThat(row).doesNotContain("synthetic-probe-ledger-only","ciphertext","device_id","model_version_id","answer");
    }
    @Test void emptyOrUnknownAuthorizationDoesNotCreateAnything() {
        denied(()->as(()->probes.claim(project,"not-authorized",1)),409);assertThat(count()).isZero();
        when(authorizations.find(any())).thenReturn(Optional.empty());denied(()->claim(1),409);assertThat(count()).isZero();
    }
    @Test void independentRepositoryAndOuterRollbackCannotResetCommittedClaim() {
        var first=claim(1);
        assertThat(raw(tenant,project,()->new JdbcProbeLedgerRepository(app).claim(first))).isFalse();
        assertThatThrownBy(()->as(()->tx.execute(s->{claim(2);throw new IllegalStateException("outer rollback");}))).isInstanceOf(IllegalStateException.class);
        denied(()->claim(2),409);assertThat(count()).isEqualTo(2);
    }
    @Test void parallelSameSampleHasOnlyOneWinner() throws Exception {
        try(var pool=Executors.newFixedThreadPool(4)) {
            var barrier=new CyclicBarrier(4);
            Callable<Boolean> work=()->{barrier.await(5,TimeUnit.SECONDS);try{claim(1);return true;}catch(BusinessException e){assertThat(e.errorCode().httpStatus()).isEqualTo(409);return false;}};
            var results=new ArrayList<Future<Boolean>>();for(int i=0;i<4;i++)results.add(pool.submit(work));
            int won=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))won++;
            assertThat(won).isEqualTo(1);assertThat(count()).isEqualTo(1);
        }
    }
    @Test void collaboratorsShareBudgetButCannotReadEachOthersAttempt() {
        var first=claim(1);var other=WebAppDataRuntimeFixture.seed(owner).runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'OPERATOR')",UUID.randomUUID(),project,other.actorId());
        denied(()->as(other.tenantId(),project,other.actorId(),()->probes.read(project,first.id())),404);
        denied(()->as(other.tenantId(),project,other.actorId(),()->probes.claim(project,binding.id(),1)),409);
        var second=as(other.tenantId(),project,other.actorId(),()->probes.claim(project,binding.id(),2));
        assertThat(second.tenantId()).isEqualTo(tenant);assertThat(count()).isEqualTo(2);
        denied(()->as(()->probes.read(project,second.id())),404);
    }
    @Test void viewerRevocationWrongProjectAndArchiveAreRejectedBeforeClaim() {
        role("VIEWER");denied(()->claim(1),403);assertThat(count()).isZero();role("OPERATOR");
        denied(()->as(tenant,UUID.randomUUID(),actor,()->probes.claim(project,binding.id(),1)),404);
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",project,actor);
        denied(()->claim(1),404);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'ADMIN')",UUID.randomUUID(),project,actor);
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",project);
        denied(()->claim(1),403);assertThat(count()).isZero();
    }
    @Test void stopAndKeyReplacementCannotResetAuthorizationOrExistingAttempt() {
        var first=claim(1);role("ADMIN");as(()->models.enable(project,"2",false));
        denied(()->claim(2),409);assertThat(as(()->probes.finish(project,first.id(),Status.UNKNOWN)).status()).isEqualTo(Status.UNKNOWN);
        as(()->models.replaceAndEnable(project,new ModelCredentialInput("3","synthetic-replacement")));
        binding=new ProbeAuthorization(binding.id(),binding.environmentId(),project,5,binding.manifestSha256());
        denied(()->claim(2),409);assertThat(count()).isEqualTo(1);
    }
    @Test void environmentAndManifestChangesDoNotMintAnotherBatch() {
        claim(1);var original=binding;
        binding=new ProbeAuthorization(original.id(),"different-environment",project,2,original.manifestSha256());denied(()->claim(2),409);
        binding=new ProbeAuthorization(original.id(),original.environmentId(),project,2,"b".repeat(64));denied(()->claim(2),409);
        assertThat(count()).isEqualTo(1);
    }
    @Test void rlsAndDatabaseGuardsPreventErasingOrMutatingBudget() {
        var a=claim(1);
        assertThat(raw(UUID.randomUUID(),project,()->app.queryForObject("SELECT count(*) FROM assistant_probe_attempt",Integer.class))).isZero();
        assertThat(raw(tenant,UUID.randomUUID(),()->app.queryForObject("SELECT count(*) FROM assistant_probe_batch",Integer.class))).isZero();
        for(String sql:List.of("DELETE FROM assistant_probe_attempt","DELETE FROM assistant_probe_batch","TRUNCATE assistant_probe_attempt",
                "UPDATE assistant_probe_attempt SET sample_index=2","UPDATE assistant_probe_attempt SET deadline=deadline+interval '1 second'",
                "UPDATE assistant_probe_attempt SET status='SUCCEEDED'","UPDATE assistant_probe_batch SET environment_id='changed'"))
            assertThatThrownBy(()->raw(tenant,project,()->app.update(sql))).isInstanceOf(DataAccessException.class);
        as(()->probes.finish(project,a.id(),Status.SUCCEEDED));
        denied(()->as(()->probes.finish(project,a.id(),Status.UNKNOWN)),409);
        assertThatThrownBy(()->raw(tenant,project,()->app.update("UPDATE assistant_probe_attempt SET status='CLAIMED',finished_at=NULL"))).isInstanceOf(DataAccessException.class);
    }
    @Test void deadlineProducesUnknownAndStillConsumesSample() {
        var a=claim(1);when(clock.instant()).thenReturn(a.deadline().plusSeconds(1));
        denied(()->as(()->probes.finish(project,a.id(),Status.SUCCEEDED)),409);
        assertThat(as(()->probes.read(project,a.id())).status()).isEqualTo(Status.UNKNOWN);
        denied(()->claim(1),409);assertThat(count()).isEqualTo(1);
    }
    @Test void authorizedProjectCleanupCountsAllRowsAndRollbackPreservesBudget() {
        var fixture=new ModelConfigurationCleanupTests();fixture.owner=owner;var f=fixture.seed();
        UUID batch=UUID.randomUUID();
        owner.update("INSERT INTO assistant_probe_batch VALUES(?,?,?,?,'cleanup-test',1,repeat('a',64),now())",batch,f.tenant(),f.project(),"cleanup-"+batch);
        owner.update("""
            INSERT INTO assistant_probe_attempt SELECT gen_random_uuid(),?,?,?,gen_random_uuid(),i,'CLAIMED',now(),now()+interval '60 seconds',NULL
            FROM generate_series(1,3) i
            """,batch,f.tenant(),f.project());
        var c=fixture.claim(f);
        assertThatThrownBy(()->tx.execute(s->{cleanup.clean(c);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_probe_attempt WHERE batch_id=?",Integer.class,batch)).isEqualTo(3);
        var bad=new ProjectCleanupClaim(f.tenant(),f.project(),1,"ASSISTANT",UUID.randomUUID(),c.leaseUntil(),false);
        assertThatThrownBy(()->tx.execute(s->cleanup.clean(bad))).isInstanceOf(DataAccessException.class);
        var result=tx.execute(s->cleanup.clean(c));assertThat(result.complete()).isTrue();assertThat(result.deletedRows()).isEqualTo(5);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_probe_batch WHERE id=?",Integer.class,batch)).isZero();
    }
    @Test void cleanupNeverExceedsFiveHundredAcrossBothNewTables() {
        var fixture=new ModelConfigurationCleanupTests();fixture.owner=owner;var f=fixture.seed();
        owner.update("""
            INSERT INTO assistant_probe_batch
            SELECT gen_random_uuid(),?,?,'bulk-'||gen_random_uuid()::text,'cleanup-test',1,repeat('a',64),now()
            FROM generate_series(1,250)
            """,f.tenant(),f.project());
        owner.update("""
            INSERT INTO assistant_probe_attempt
            SELECT gen_random_uuid(),id,tenant_id,project_id,gen_random_uuid(),1,'CLAIMED',now(),now()+interval '60 seconds',NULL
            FROM assistant_probe_batch WHERE project_id=?
            """,f.project());
        var first=tx.execute(s->cleanup.clean(fixture.claim(f)));
        assertThat(first.deletedRows()).isEqualTo(500);assertThat(first.complete()).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_probe_attempt WHERE project_id=?",Integer.class,f.project())).isZero();
        var second=tx.execute(s->cleanup.clean(fixture.claim(f)));
        assertThat(second.deletedRows()).isEqualTo(1);assertThat(second.complete()).isTrue();
    }
}
