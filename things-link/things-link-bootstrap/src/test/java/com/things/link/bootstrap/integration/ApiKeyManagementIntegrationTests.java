package com.things.link.bootstrap.integration;

import com.things.link.integration.application.ApiKeyManagementService;
import com.things.link.integration.domain.ApiKeyCredential;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** ADR0169：真实PG原子管理与秘密保留边界，无HTTP或公开认证资格。 */
class ApiKeyManagementIntegrationTests extends AbstractIntegrationTest {
    @Autowired ApiKeyManagementService service;
    @Autowired TenantProvisioning tenants;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    JdbcTemplate owner;
    UUID tenant,project,account;
    @BeforeEach void seed() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        tenant=tx.execute(s->tenants.createTenant("integration-key"));account=Uuid7.generate();project=Uuid7.generate();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'{noop}unused','integration')",account,account+"@example.com");
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",Uuid7.generate(),tenant,account);
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'integration','sh-1',?)",project,tenant,"ki"+project.toString().replace("-",""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",Uuid7.generate(),project,account);
    }
    @AfterEach void cleanup() {
        TenantContext.clear();
        owner.update("DELETE FROM integ_api_key WHERE tenant_id=?",tenant);
        owner.update("DELETE FROM sys_project_member WHERE project_id=?",project);
        owner.update("DELETE FROM sys_project WHERE id=?",project);
        owner.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",tenant);
        owner.update("DELETE FROM sys_tenant_member WHERE tenant_id=?",tenant);
        owner.update("DELETE FROM sys_tenant WHERE id=?",tenant);
        owner.update("DELETE FROM sys_account WHERE id=?",account);
        // 不可变操作/审计无业务FK，保留至测试容器回收。
    }
    ApiKeyManagementService.Spec spec() {
        return new ApiKeyManagementService.Spec(" test ",List.of("device:read"),List.of("127.0.0.1/32","2001:db8::/32"),
                Instant.now().plus(30,ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS));
    }
    ApiKeyManagementService.Result issue(UUID op,ApiKeyManagementService.Spec spec) {
        return service.issue(tenant,project,account,op,spec);
    }
    int keyCount() {return owner.queryForObject("SELECT count(*) FROM integ_api_key WHERE project_id=?",Integer.class,project);}
    int operations() {return owner.queryForObject("SELECT count(*) FROM integ_api_key_operation WHERE project_id=?",Integer.class,project);}
    @Test void issueStoresOnlyDigestAndRecoveryNeverReturnsSecret() {
        UUID op=Uuid7.generate();var spec=spec();var first=issue(op,spec);var replay=issue(op,spec);
        assertThat(first.secret()).isNotBlank();assertThat(replay.secret()).isNull();assertThat(replay.replayed()).isTrue();
        assertThat(replay.key()).isEqualTo(first.key());assertThat(service.recover(tenant,project,account,op)).isEqualTo(first.key());
        String hash=owner.queryForObject("SELECT secret_hash FROM integ_api_key WHERE id=?",String.class,first.key().id());
        assertThat(ApiKeyCredential.parse(first.secret()).orElseThrow().matchesDigest(hash)).isTrue();
        String stored=owner.queryForObject("SELECT row_to_json(k)::text FROM integ_api_key k WHERE id=?",String.class,first.key().id());
        String operation=owner.queryForObject("SELECT row_to_json(o)::text FROM integ_api_key_operation o WHERE project_id=?",String.class,project);
        String audit=owner.queryForObject("SELECT details::text FROM sys_audit_log WHERE target_id=?",String.class,first.key().id());
        assertThat(stored).doesNotContain(first.secret());assertThat(operation+audit).doesNotContain(first.secret(),hash);
        assertThat(first.toString()).doesNotContain(first.secret());assertThat(keyCount()).isEqualTo(1);assertThat(operations()).isEqualTo(1);
    }
    @Test void changedRequestOrOperationKindCannotReuseReceipt() {
        UUID op=Uuid7.generate();var first=issue(op,spec());
        assertThatThrownBy(()->issue(op,new ApiKeyManagementService.Spec("other",List.of("alarm:read"),List.of("0.0.0.0/0"),first.key().expiresAt())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.revoke(tenant,project,account,op,first.key().id())).isInstanceOf(BusinessException.class);
        assertThat(keyCount()).isEqualTo(1);assertThat(operations()).isEqualTo(1);
    }
    @Test void rotationIsAtomicAndOldReplayDoesNotReviveRevokedKey() {
        UUID issueOp=Uuid7.generate(),rotateOp=Uuid7.generate();var spec=spec();var first=issue(issueOp,spec);
        var rotated=service.rotate(tenant,project,account,rotateOp,first.key().id(),spec);
        assertThat(rotated.key().id()).isNotEqualTo(first.key().id());
        assertThat(issue(issueOp,spec).key().status()).isEqualTo("REVOKED");
        assertThat(service.rotate(tenant,project,account,rotateOp,first.key().id(),spec).secret()).isNull();
        service.revoke(tenant,project,account,Uuid7.generate(),rotated.key().id());
        service.revoke(tenant,project,account,Uuid7.generate(),rotated.key().id());
        assertThat(service.recover(tenant,project,account,rotateOp).revision()).isEqualTo(1);
        assertThatThrownBy(()->service.rotate(tenant,project,account,Uuid7.generate(),rotated.key().id(),spec)).isInstanceOf(BusinessException.class);
    }
    @Test void outerFailureRollsBackRotationAndReceiptThenSameOperationCanSucceed() {
        var first=issue(Uuid7.generate(),spec());UUID op=Uuid7.generate();var spec=spec();
        assertThatThrownBy(()->tx.execute(s->{service.rotate(tenant,project,account,op,first.key().id(),spec);throw new IllegalStateException("fault");}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(keyCount()).isEqualTo(1);assertThat(operations()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status FROM integ_api_key WHERE id=?",String.class,first.key().id())).isEqualTo("ACTIVE");
        assertThat(service.rotate(tenant,project,account,op,first.key().id(),spec).secret()).isNotNull();
    }
    @Test void invalidScopesCidrsAndLifetimeDoNotPersistFacts() {
        for(var spec:List.of(new ApiKeyManagementService.Spec("test",List.of("device:write"),List.of("127.0.0.1/32"),Instant.now().plusSeconds(60)),
                new ApiKeyManagementService.Spec("test",List.of("device:read"),List.of(),Instant.now().plusSeconds(60)),
                new ApiKeyManagementService.Spec("test",List.of("device:read"),List.of("10.1.2.3/8"),Instant.now().plusSeconds(60)),
                new ApiKeyManagementService.Spec("test",List.of("device:read"),List.of("localhost/32"),Instant.now().plusSeconds(60)),
                new ApiKeyManagementService.Spec("test",List.of("device:read"),List.of("::/0"),Instant.now().minusSeconds(1)),
                new ApiKeyManagementService.Spec("test",List.of("device:read"),List.of("::/0"),Instant.now().plus(367,ChronoUnit.DAYS)))) {
            assertThatThrownBy(()->issue(Uuid7.generate(),spec)).isInstanceOf(BusinessException.class);
        }
        assertThat(keyCount()).isZero();assertThat(operations()).isZero();
    }
    @Test void actorMembershipActivityTenantAndProjectStateAreAuthoritative() {
        assertThatThrownBy(()->service.issue(UUID.randomUUID(),project,account,Uuid7.generate(),spec())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.issue(tenant,project,UUID.randomUUID(),Uuid7.generate(),spec())).isInstanceOf(BusinessException.class);
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=?",project);
        assertThatThrownBy(()->issue(Uuid7.generate(),spec())).isInstanceOf(BusinessException.class);
        owner.update("UPDATE sys_project_member SET role='OWNER' WHERE project_id=?",project);
        owner.update("UPDATE sys_account SET status='DISABLED' WHERE id=?",account);
        assertThatThrownBy(()->issue(Uuid7.generate(),spec())).isInstanceOf(BusinessException.class);
        owner.update("UPDATE sys_account SET status='ACTIVE' WHERE id=?",account);
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",project);
        assertThatThrownBy(()->issue(Uuid7.generate(),spec())).isInstanceOf(BusinessException.class);
        assertThat(keyCount()).isZero();
    }
    @Test void databaseRejectsIdentityMutationAndOperationTampering() {
        var key=issue(Uuid7.generate(),spec());
        assertThatThrownBy(()->owner.update("UPDATE integ_api_key SET name='changed' WHERE id=?",key.key().id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->owner.update("UPDATE integ_api_key_operation SET kind='REVOKE' WHERE project_id=?",project)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->owner.update("DELETE FROM integ_api_key_operation WHERE project_id=?",project)).isInstanceOf(DataAccessException.class);
        assertThat(keyCount()).isEqualTo(1);assertThat(operations()).isEqualTo(1);
    }
    @Test void appRoleRlsRequiresBothAxesAndCannotDeleteCredentials() throws Exception {
        issue(Uuid7.generate(),spec());
        try(var c=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),APP_ROLE,APP_ROLE_PASSWORD)) {
            c.setAutoCommit(false);
            try(var st=c.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id','"+tenant+"',true),set_config('app.project_id','"+project+"',true)");
                try(var r=st.executeQuery("SELECT count(*) FROM integ_api_key")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
                st.execute("SELECT set_config('app.tenant_id','"+UUID.randomUUID()+"',true)");
                try(var r=st.executeQuery("SELECT count(*) FROM integ_api_key")){r.next();assertThat(r.getInt(1)).isZero();}
                st.execute("SELECT set_config('app.tenant_id','"+tenant+"',true),set_config('app.project_id','"+UUID.randomUUID()+"',true)");
                try(var r=st.executeQuery("SELECT count(*) FROM integ_api_key_operation")){r.next();assertThat(r.getInt(1)).isZero();}
                assertThatThrownBy(()->st.execute("DELETE FROM integ_api_key")).isInstanceOf(java.sql.SQLException.class);
            } finally {c.rollback();}
        }
    }
    @Test void auditFailureRollsBackKeyAndOperation() {
        String function="integ_test_audit_"+project.toString().replace("-", "");
        owner.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected audit failure'; END; $$");
        owner.execute("CREATE TRIGGER "+function+" BEFORE INSERT ON sys_audit_log FOR EACH ROW WHEN (NEW.project_id='"+project+"'::uuid) EXECUTE FUNCTION "+function+"()");
        UUID op=Uuid7.generate();var spec=spec();
        try {
            assertThatThrownBy(()->issue(op,spec)).isInstanceOf(DataAccessException.class);
            assertThat(keyCount()).isZero();assertThat(operations()).isZero();
        } finally {
            owner.execute("DROP TRIGGER "+function+" ON sys_audit_log");
            owner.execute("DROP FUNCTION "+function+"()");
        }
        assertThat(issue(op,spec).secret()).isNotNull();
    }
    @Test void capacityRejectsNewIssueButPermitsAtomicRotationAndExpiredKeysDoNotCount() {
        var first=issue(Uuid7.generate(),spec());
        owner.update("""
                INSERT INTO integ_api_key(id,tenant_id,project_id,project_generation,issuer_account_id,name,secret_hash,
                    scopes,ip_cidrs,status,created_at,expires_at,revision)
                SELECT gen_random_uuid(),tenant_id,project_id,project_generation,issuer_account_id,name,secret_hash,
                    scopes,ip_cidrs,status,created_at,expires_at,revision FROM integ_api_key CROSS JOIN generate_series(1,99)
                    WHERE id=?
                """,first.key().id());
        assertThatThrownBy(()->issue(Uuid7.generate(),spec())).isInstanceOf(BusinessException.class);
        var rotated=service.rotate(tenant,project,account,Uuid7.generate(),first.key().id(),spec());
        assertThat(rotated.secret()).isNotNull();
        service.revoke(tenant,project,account,Uuid7.generate(),rotated.key().id());
        owner.update("""
                INSERT INTO integ_api_key(id,tenant_id,project_id,project_generation,issuer_account_id,name,secret_hash,
                    scopes,ip_cidrs,status,created_at,expires_at,revision)
                SELECT gen_random_uuid(),tenant_id,project_id,project_generation,issuer_account_id,name,secret_hash,
                    scopes,ip_cidrs,'ACTIVE',clock_timestamp()-interval '2 hours',clock_timestamp()-interval '1 hour',0
                    FROM integ_api_key WHERE id=?
                """,first.key().id());
        assertThat(issue(Uuid7.generate(),spec()).secret()).isNotNull();
    }
    @Test void previousGenerationCannotRotateAndNewIssueUsesCurrentGeneration() {
        UUID op=Uuid7.generate();var spec=spec();var first=issue(op,spec);
        owner.update("UPDATE sys_project SET lifecycle_generation=lifecycle_generation+1 WHERE id=?",project);
        assertThatThrownBy(()->service.rotate(tenant,project,account,Uuid7.generate(),first.key().id(),spec)).isInstanceOf(BusinessException.class);
        assertThat(issue(Uuid7.generate(),spec).key().projectGeneration()).isEqualTo(first.key().projectGeneration()+1);
        assertThat(issue(op,spec).key().projectGeneration()).isEqualTo(first.key().projectGeneration());
        assertThat(issue(op,spec).secret()).isNull();
    }
    @Test void twoRotationsWaitingForRealProjectLockCannotBothReplaceSameKey() throws Exception {
        var first=issue(Uuid7.generate(),spec());var spec=spec();
        try(var lock=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
            var executor=Executors.newFixedThreadPool(2)) {
            lock.setAutoCommit(false);
            try(var st=lock.prepareStatement("SELECT id FROM sys_project WHERE id=? FOR UPDATE")){st.setObject(1,project);st.executeQuery().close();}
            Callable<Boolean> rotate=()->{try{service.rotate(tenant,project,account,Uuid7.generate(),first.key().id(),spec);return true;}catch(BusinessException rejected){return false;}};
            var a=executor.submit(rotate);var b=executor.submit(rotate);
            try {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->owner.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%FOR UPDATE OF p%'",Integer.class)>=2);
            } finally {lock.commit();}
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(keyCount()).isEqualTo(2);assertThat(operations()).isEqualTo(2);
    }
    @Test void concurrentSameOperationOnlyReturnsOneSecretAndOneFact() throws Exception {
        UUID op=Uuid7.generate();var spec=spec();
        try(var executor=Executors.newFixedThreadPool(2)) {
            CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
            Callable<ApiKeyManagementService.Result> task=()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException();return issue(op,spec);};
            var a=executor.submit(task);var b=executor.submit(task);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            var results=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter(r->r.secret()!=null).count()).isEqualTo(1);
            assertThat(results.get(0).key().id()).isEqualTo(results.get(1).key().id());
        }
        assertThat(keyCount()).isEqualTo(1);assertThat(operations()).isEqualTo(1);
    }
}
