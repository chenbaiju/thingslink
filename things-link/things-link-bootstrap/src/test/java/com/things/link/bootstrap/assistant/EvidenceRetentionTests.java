package com.things.link.bootstrap.assistant;

import com.things.link.assistant.application.EvidenceRetentionService;
import com.things.link.assistant.domain.EvidenceRetentionRepository;
import com.things.link.assistant.domain.EvidenceRetentionRepository.Scope;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvidenceRetentionTests extends AbstractIntegrationTest {
    @Autowired EvidenceRetentionService service;
    @MockitoSpyBean EvidenceRetentionRepository repository;
    @Autowired ProjectLifecycleAccessService lifecycle;
    @Autowired TransactionLocalRlsScope rls;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate app;
    JdbcTemplate owner;DataFixture data;Scope scope;
    @BeforeEach void seed() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        data=WebAppDataRuntimeFixture.seed(owner);scope=new Scope(data.runtime().tenantId(),data.runtime().projectId());
        assertThat(app.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);
    }
    @AfterEach void cleanup() { TenantContext.clear();owner.update("DELETE FROM assistant_evidence_record WHERE project_id=?",scope.projectId()); }
    UUID insert(int days) {
        UUID id=UUID.randomUUID();owner.update("""
            INSERT INTO assistant_evidence_record(id,tenant_id,project_id,created_by,device_id,model_version_id,
            created_at,expires_at,content_sha256,content)
            VALUES (?,?,?,?,?,?,statement_timestamp()-make_interval(days=>?),
              statement_timestamp()-make_interval(days=>?)+interval '30 days',?,'{}')
            """,id,scope.tenantId(),scope.projectId(),data.runtime().actorId(),data.first(),data.model(),days,days,"a".repeat(64));return id;
    }
    int count() { return owner.queryForObject("SELECT count(*) FROM assistant_evidence_record WHERE project_id=?",Integer.class,scope.projectId()); }
    @Test void identityOnlyCandidatesRespectExpiryAndCursorWithoutExposingContent() {
        UUID recent=insert(29);assertThat(service.candidates(null)).doesNotContain(scope);
        insert(31);assertThat(service.candidates(null)).contains(scope);
        assertThat(service.candidates(scope.projectId())).doesNotContain(scope);
        assertThat(service.purge(scope,500)).isEqualTo(1);assertThat(count()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT id FROM assistant_evidence_record WHERE project_id=?",UUID.class,scope.projectId())).isEqualTo(recent);
        assertThat(app.queryForList("SELECT * FROM assistant_evidence_retention_scopes(NULL)")).allSatisfy(row->assertThat(row.keySet()).containsExactlyInAnyOrder("tenant_id","project_id"));
    }
    @Test void physicalReclaimIsBoundedAndArchivedProjectsStillRetainLogicalContract() {
        UUID old=insert(31);
        owner.update("""
            INSERT INTO assistant_evidence_record SELECT (jsonb_populate_record(NULL::assistant_evidence_record,
            to_jsonb(r)||jsonb_build_object('id',gen_random_uuid()))).* FROM assistant_evidence_record r
            CROSS JOIN generate_series(1,500) WHERE r.id=?
            """,old);
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",scope.projectId());
        assertThat(service.candidates(null)).contains(scope);assertThat(service.purge(scope,500)).isEqualTo(500);
        assertThat(count()).isEqualTo(1);assertThat(service.purge(scope,500)).isEqualTo(1);assertThat(count()).isZero();
    }
    @Test void deletedProjectAndWrongTenantCannotUseOrdinaryMaintenance() {
        insert(31);assertThat(service.purge(new Scope(UUID.randomUUID(),scope.projectId()),500)).isZero();
        owner.update("UPDATE sys_project SET status='DELETING',deleted_at=statement_timestamp() WHERE id=?",scope.projectId());
        assertThat(service.candidates(null)).doesNotContain(scope);assertThat(service.purge(scope,500)).isZero();assertThat(count()).isEqualTo(1);
    }
    @Test void rollbackRestoresRowsAndAppCannotRewriteImmutableFacts() {
        UUID old=insert(31);
        tx.executeWithoutResult(status->{rls.establish(scope.tenantId(),scope.projectId());
            assertThat(lifecycle.lockReadableGeneration(scope.tenantId(),scope.projectId())).isPresent();
            assertThat(repository.deleteExpired(scope,500)).isEqualTo(1);status.setRollbackOnly();});
        assertThat(count()).isEqualTo(1);
        assertThatThrownBy(()->tx.executeWithoutResult(s->{rls.establish(scope.tenantId(),scope.projectId());app.update("UPDATE assistant_evidence_record SET content='{}' WHERE id=?",old);}))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.purge(scope,500)).isEqualTo(1);
    }
    @Test void lifecycleChangeWaitsForOriginalReclaimTransaction() throws Exception {
        insert(31);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var updateStarted=new CountDownLatch(1);
        EvidenceRetentionRepository target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(repository);
        doAnswer(call->{locked.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test release missing");return call.callRealMethod();})
                .when(target).deleteExpired(eq(scope),eq(500));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var cleanup=executor.submit(()->service.purge(scope,500));assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var change=executor.submit(()->{updateStarted.countDown();return owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",scope.projectId());});
            assertThat(updateStarted.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->change.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();assertThat(cleanup.get(10,TimeUnit.SECONDS)).isEqualTo(1);assertThat(change.get(10,TimeUnit.SECONDS)).isEqualTo(1);
        } finally { release.countDown(); }
    }
    @Test void invalidBudgetAndIdentityFailBeforeDatabaseAccess() {
        insert(31);
        for(int limit:List.of(-1,0,501)) assertThatThrownBy(()->service.purge(scope,limit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.purge(new Scope(null,scope.projectId()),500)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count()).isEqualTo(1);
    }
}
