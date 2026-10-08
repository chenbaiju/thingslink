package com.things.link.bootstrap.assistant;
import com.things.link.assistant.application.AssistantProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupStage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
/** 最新迁移实际APP角色清理，仅修改本测试独立项目。 */
class ModelConfigurationCleanupTests extends AbstractAssistantIntegrationTest {
    @Autowired AssistantProjectCleanupContributor cleanup;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate app;
    JdbcTemplate owner;
    @BeforeEach void setup() { owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())); }
    record Fixture(UUID tenant,UUID project,UUID token) {}
    Fixture seed() {
        var f=new Fixture(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'agent-cleanup')",f.tenant());
        owner.update("""
            INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at,cleanup_stage,
                cleanup_lease_token,cleanup_lease_until,cleanup_started_at,cleanup_next_attempt_at)
            VALUES (?,?,'agent-cleanup',?,'PURGING',1,now()-interval '31 days','ASSISTANT',?,clock_timestamp()+interval '5 minutes',now(),now())
            """,f.project(),f.tenant(),"ag"+f.project().toString().replace("-",""),f.token());
        owner.update("""
            INSERT INTO assistant_model_configuration VALUES (gen_random_uuid(),?,?,'deepseek-chat',1,1,false,
                decode(repeat('01',32),'hex'),decode(repeat('02',12),'hex'),'synthetic',gen_random_uuid(),now())
            """,f.tenant(),f.project());return f;
    }
    ProjectCleanupClaim claim(Fixture f) { return new ProjectCleanupClaim(f.tenant(),f.project(),1,"ASSISTANT",f.token(),Instant.now().plusSeconds(300),false); }
    int count(Fixture f) { return owner.queryForObject("SELECT count(*) FROM assistant_model_configuration WHERE project_id=?",Integer.class,f.project()); }
    @Test void physicalCleanupKeepsNeighborAndUsesRegisteredOrder() {
        var a=seed();var b=seed();var result=tx.execute(s->cleanup.clean(claim(a)));
        assertThat(result.complete()).isTrue();assertThat(result.deletedRows()).isEqualTo(1);
        assertThat(count(a)).isZero();assertThat(count(b)).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isZero();
        assertThat(ProjectCleanupStage.INTEGRATION.next()).isEqualTo(ProjectCleanupStage.ASSISTANT);
        assertThat(ProjectCleanupStage.ASSISTANT.next()).isEqualTo(ProjectCleanupStage.TELEMETRY);
    }
    @Test void rejectsWrongTokenTenantGenerationStageAndExpiredLease() {
        var a=seed();var normal=claim(a);
        for(var bad:List.of(new ProjectCleanupClaim(a.tenant(),a.project(),1,"ASSISTANT",UUID.randomUUID(),normal.leaseUntil(),false),
            new ProjectCleanupClaim(UUID.randomUUID(),a.project(),1,"ASSISTANT",a.token(),normal.leaseUntil(),false),
            new ProjectCleanupClaim(a.tenant(),a.project(),0,"ASSISTANT",a.token(),normal.leaseUntil(),false)))
            assertThatThrownBy(()->tx.execute(s->cleanup.clean(bad))).isInstanceOf(DataAccessException.class);
        owner.update("UPDATE sys_project SET cleanup_stage='TELEMETRY' WHERE id=?",a.project());
        assertThatThrownBy(()->tx.execute(s->cleanup.clean(normal))).isInstanceOf(DataAccessException.class);
        owner.update("UPDATE sys_project SET cleanup_stage='ASSISTANT',cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?",a.project());
        assertThatThrownBy(()->tx.execute(s->cleanup.clean(normal))).isInstanceOf(DataAccessException.class);assertThat(count(a)).isEqualTo(1);
    }
    @Test void rollbackRestoresCiphertext() {
        var f=seed();assertThatThrownBy(()->tx.execute(s->{cleanup.clean(claim(f));throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(count(f)).isEqualTo(1);
    }
    void seedCalls(Fixture f,int rows) {
        owner.update("""
            INSERT INTO assistant_analysis_call(id,tenant_id,project_id,created_by,device_id,model_version_id,key_hash,request_hash,
                configuration_revision,status,created_at,deadline,expires_at)
            SELECT gen_random_uuid(),?,?,gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),
                encode(digest(i::text,'sha256'),'hex'),repeat('b',64),1,'RESERVED',now(),now()+interval '60 seconds',now()+interval '24 hours'
            FROM generate_series(1,?) i
            """,f.tenant(),f.project(),rows);
    }
    int calls(Fixture f) { return owner.queryForObject("SELECT count(*) FROM assistant_analysis_call WHERE project_id=?",Integer.class,f.project()); }
    @Test void combinedCleanupBatchesAreBoundedAndRetainOtherProjects() {
        var a=seed();var b=seed();seedCalls(a,503);seedCalls(b,2);
        var first=tx.execute(s->cleanup.clean(claim(a)));
        assertThat(first.deletedRows()).isEqualTo(500);assertThat(first.complete()).isFalse();
        assertThat(count(a)).isZero();assertThat(calls(a)).isEqualTo(4);
        var second=tx.execute(s->cleanup.clean(claim(a)));
        assertThat(second.deletedRows()).isEqualTo(4);assertThat(second.complete()).isTrue();
        assertThat(calls(b)).isEqualTo(2);assertThat(count(b)).isEqualTo(1);
    }
    @Test void callCleanupRollbackAndExpiredLeasePreserveMetadata() {
        var f=seed();seedCalls(f,3);
        assertThatThrownBy(()->tx.execute(s->{cleanup.clean(claim(f));throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(calls(f)).isEqualTo(3);assertThat(count(f)).isEqualTo(1);
        owner.update("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?",f.project());
        assertThatThrownBy(()->tx.execute(s->cleanup.clean(claim(f)))).isInstanceOf(DataAccessException.class);
        assertThat(calls(f)).isEqualTo(3);
    }

    @Test void leaseExpiringDuringDeletionRollsBackBothResources() {
        var f=seed();seedCalls(f,1);
        String name="assistant_test_delay_"+f.project().toString().replace("-","");
        owner.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.project_id='"+f.project()+"'::uuid THEN PERFORM pg_sleep(1.5); END IF; RETURN OLD; END $$");
        owner.execute("CREATE TRIGGER "+name+" BEFORE DELETE ON assistant_analysis_call FOR EACH ROW EXECUTE FUNCTION "+name+"()");
        try {
            owner.update("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()+interval '1 second' WHERE id=?",f.project());
            long started=System.nanoTime();
            assertThatThrownBy(()->tx.execute(s->cleanup.clean(claim(f)))).isInstanceOf(DataAccessException.class);
            assertThat(System.nanoTime()-started).isGreaterThan(1_200_000_000L);
            assertThat(calls(f)).isEqualTo(1);assertThat(count(f)).isEqualTo(1);
        } finally {
            owner.execute("DROP TRIGGER "+name+" ON assistant_analysis_call");
            owner.execute("DROP FUNCTION "+name+"()");
        }
    }

    void seedSlots(Fixture f) {
        owner.update("""
            INSERT INTO assistant_analysis_slot(call_id,tenant_id,project_id,created_by,project_slot,token,acquired_at,deadline)
            SELECT id,tenant_id,project_id,created_by,(row_number() OVER(ORDER BY id))::smallint,
                gen_random_uuid(),created_at,deadline FROM assistant_analysis_call WHERE project_id=? ORDER BY id LIMIT 2
            """,f.project());
    }
    int slots(Fixture f) {return owner.queryForObject("SELECT count(*) FROM assistant_analysis_slot WHERE project_id=?",Integer.class,f.project());}
    @Test void allThreeResourcesShareFiveHundredRowBudgetAndRollbackTogether() {
        var a=seed();var b=seed();seedCalls(a,503);seedCalls(b,2);seedSlots(a);seedSlots(b);
        assertThatThrownBy(()->tx.execute(s->{cleanup.clean(claim(a));throw new IllegalStateException("rollback slots");})).isInstanceOf(IllegalStateException.class);
        assertThat(slots(a)).isEqualTo(2);assertThat(count(a)).isEqualTo(1);assertThat(calls(a)).isEqualTo(503);
        var first=tx.execute(s->cleanup.clean(claim(a)));assertThat(first.deletedRows()).isEqualTo(500);assertThat(first.complete()).isFalse();
        assertThat(slots(a)).isZero();assertThat(count(a)).isZero();assertThat(calls(a)).isEqualTo(6);
        var last=tx.execute(s->cleanup.clean(claim(a)));assertThat(last.deletedRows()).isEqualTo(6);assertThat(last.complete()).isTrue();
        assertThat(slots(b)).isEqualTo(2);assertThat(count(b)).isEqualTo(1);assertThat(calls(b)).isEqualTo(2);
    }

    @Test void personalEvidenceCleanupIsLeaseBoundedAndCannotTouchNeighbor() {
        var a=seed();var b=seed();
        for(var f:List.of(a,b)) owner.update("""
            INSERT INTO assistant_evidence_record(id,tenant_id,project_id,created_by,device_id,model_version_id,content_sha256,content)
            SELECT gen_random_uuid(),?,?,gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),repeat('0',64),'{}'
              FROM generate_series(1,501)
            """,f.tenant(),f.project());
        var first=tx.execute(s->cleanup.clean(claim(a)));
        assertThat(first.deletedRows()).isEqualTo(500);assertThat(first.complete()).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_evidence_record WHERE project_id=?",Integer.class,a.project())).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_evidence_record WHERE project_id=?",Integer.class,b.project())).isEqualTo(501);
        var second=tx.execute(s->cleanup.clean(claim(a)));
        assertThat(second.deletedRows()).isEqualTo(2);assertThat(second.complete()).isTrue();
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isZero();
    }
    @RepeatedTest(8) void personalEvidenceCleanupIsBoundedWithRescanningQueryPlans(RepetitionInfo repetition) {
        var a=seed();var b=seed();
        for(var f:List.of(a,b)) owner.update("""
            INSERT INTO assistant_evidence_record(id,tenant_id,project_id,created_by,device_id,model_version_id,content_sha256,content)
            SELECT gen_random_uuid(),?,?,gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),repeat('0',64),'{}'
              FROM generate_series(1,501)
            """,f.tenant(),f.project());
        var first=tx.execute(s->{
            // 合法的循环扫描计划也必须遵守同一批次上限，参数只作用于本事务。
            int mode=repetition.getCurrentRepetition()-1;
            app.execute("SET LOCAL enable_hashjoin="+((mode&1)==0?"on":"off"));
            app.execute("SET LOCAL enable_mergejoin="+((mode&2)==0?"on":"off"));
            app.execute("SET LOCAL enable_material=off");
            app.execute("SET LOCAL plan_cache_mode="+((mode&4)==0?"force_custom_plan":"force_generic_plan"));
            return cleanup.clean(claim(a));
        });
        assertThat(first.deletedRows()).isEqualTo(500);assertThat(first.complete()).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_evidence_record WHERE project_id=?",Integer.class,a.project())).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_evidence_record WHERE project_id=?",Integer.class,b.project())).isEqualTo(501);
        assertThat(tx.execute(s->cleanup.clean(claim(a))).deletedRows()).isEqualTo(2);
    }
    @Test void controlledKnowledgeSharesLeaseBudgetAndDoesNotTouchNeighbor() {
        var a=seed(); var b=seed();
        for(var f:List.of(a,b)) owner.update("""
            INSERT INTO assistant_knowledge_document(id,tenant_id,project_id,created_by,source_key,version_number,content_sha256,content)
            SELECT gen_random_uuid(),?,?,gen_random_uuid(),'source_'||(i/20),i%20+1,
                encode(digest('灌溉说明','sha256'),'hex'),'灌溉说明' FROM generate_series(0,500)i
            """,f.tenant(),f.project());
        assertThatThrownBy(() -> tx.execute(s -> {cleanup.clean(claim(a));throw new IllegalStateException("rollback knowledge");}))
            .isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_knowledge_document WHERE project_id=?",Integer.class,a.project())).isEqualTo(501);
        var first=tx.execute(s -> cleanup.clean(claim(a)));
        assertThat(first.deletedRows()).isEqualTo(500); assertThat(first.complete()).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_knowledge_document WHERE project_id=?",Integer.class,a.project())).isEqualTo(2);
        var last=tx.execute(s -> cleanup.clean(claim(a)));
        assertThat(last.deletedRows()).isEqualTo(2); assertThat(last.complete()).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_knowledge_document WHERE project_id=?",Integer.class,b.project())).isEqualTo(501);
    }

}
