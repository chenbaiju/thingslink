package com.things.link.bootstrap.ota;

import com.things.link.ota.domain.OtaTrustState;
import com.things.link.ota.infrastructure.persistence.JdbcOtaTrustRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0118：真实普通数据库身份验证范围、历史不可变、共享锁和有界项目清理。 */
class OtaTrustPersistenceIntegrationTests extends AbstractIntegrationTest {
    /** 每个测试独占项目，只清自己的记录。 */
    private final List<Fixture> fixtures=new ArrayList<>();
    /** 夹具清理不禁用生产触发器或外键。 */
    @AfterEach void cleanup() {
        JdbcTemplate jdbc=owner();
        for(Fixture f:fixtures){
            jdbc.update("DELETE FROM ota_trust_bundle WHERE project_id=?",f.project());
            jdbc.update("DELETE FROM ota_trust_domain WHERE project_id=?",f.project());
            jdbc.update("DELETE FROM sys_project WHERE id=?",f.project());
            jdbc.update("DELETE FROM sys_tenant WHERE id=?",f.tenant());
        }
    }
    /** 原子替换、CAS失败无历史孤儿、历史不可变和scope隔离。 */
    @Test void protectsHistoryAndExactScope() {
        Fixture first=seed(); Fixture second=seed(); OtaTrustState initial=state(first,1);
        app(first,jdbc->{new JdbcOtaTrustRepository(jdbc).create(initial);return true;});
        assertThat(OtaTrustPersistenceIntegrationTests.<java.util.Optional<OtaTrustState>>app(second,jdbc->
                new JdbcOtaTrustRepository(jdbc).find(first.project(),first.domain(),false,false))).isEmpty();
        assertThat(OtaTrustPersistenceIntegrationTests.<Boolean>app(first,jdbc->
                new JdbcOtaTrustRepository(jdbc).replace(0,state(first,2)))).isFalse();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_trust_bundle WHERE project_id=?",Long.class,first.project())).isEqualTo(1);
        assertThat(OtaTrustPersistenceIntegrationTests.<Boolean>app(first,jdbc->
                new JdbcOtaTrustRepository(jdbc).replace(1,state(first,2)))).isTrue();
        for(String table:List.of("ota_trust_bundle","ota_trust_domain")) {
            assertThatThrownBy(()->app(first,jdbc->jdbc.update("DELETE FROM "+table+" WHERE project_id=?",first.project())))
                    .hasStackTraceContaining("permission denied");
        }
        assertThatThrownBy(()->app(first,jdbc->jdbc.update("UPDATE ota_trust_bundle SET signature=signature WHERE project_id=?",first.project())))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(()->owner().update("UPDATE ota_trust_bundle SET signature=signature WHERE project_id=?",first.project()))
                .hasStackTraceContaining("immutable");
        assertThatThrownBy(()->app(first,jdbc->jdbc.update("UPDATE ota_trust_domain SET policy_revision=policy_revision-1 WHERE project_id=?",first.project())))
                .hasStackTraceContaining("rejected");
    }
    /** 共享资格锁持有期间，真实另一事务导入必须等待，释放后才可原子替换。 */
    @Test void sharedPublicationLockBlocksReplacement() throws Exception {
        Fixture f=seed(); app(f,jdbc->{new JdbcOtaTrustRepository(jdbc).create(state(f,1));return true;});
        CountDownLatch locked=new CountDownLatch(1); CountDownLatch release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var reader=executor.submit(()->app(f,jdbc->{
                new JdbcOtaTrustRepository(jdbc).find(f.project(),f.domain(),false,true).orElseThrow(); locked.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("共享锁测试未释放");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
                return true;
            }));
            try {
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                var writer=executor.submit(()->app(f,jdbc->new JdbcOtaTrustRepository(jdbc).replace(1,state(f,2))));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!Boolean.TRUE.equals(owner().queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE "
                        +"datname=current_database() AND wait_event_type='Lock' AND query LIKE '%ota_trust_domain%')",Boolean.class))) {
                    if(System.nanoTime()>=deadline)throw new AssertionError("真实导入未进入共享锁等待");
                    Thread.sleep(10);
                }
                assertThat(writer.isDone()).isFalse(); release.countDown();
                assertThat(writer.get(5,TimeUnit.SECONDS)).isTrue();
                assertThat(reader.get(5,TimeUnit.SECONDS)).isTrue();
            }finally{release.countDown();}
        }
    }
    /** 缺精确历史包必须报完整性异常，不能被INNER JOIN掩盖成未登记。 */
    @Test void refusesMissingCurrentBundleAndCopiesArrays() {
        Fixture f=seed(); OtaTrustState state=state(f,1); byte[] copy=state.canonicalBundle();copy[0]=99;
        assertThat(state.canonicalBundle()[0]).isNotEqualTo((byte)99);
        app(f,jdbc->{new JdbcOtaTrustRepository(jdbc).create(state);return true;});
        owner().update("DELETE FROM ota_trust_bundle WHERE project_id=?",f.project());
        assertThatThrownBy(()->app(f,jdbc->new JdbcOtaTrustRepository(jdbc).find(f.project(),f.domain(),false,false)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    /** 历史先于域按500行边界清理，邻居域和历史不被误删。 */
    @Test void cleansHistoryBeforeDomainWithBoundedProjectLease() {
        Fixture f=seed(); Fixture neighbor=seed();
        app(f,jdbc->{new JdbcOtaTrustRepository(jdbc).create(state(f,1));return true;});
        app(neighbor,jdbc->{new JdbcOtaTrustRepository(jdbc).create(state(neighbor,1));return true;});
        owner().update("""
                INSERT INTO ota_trust_bundle(tenant_id,project_id,trust_domain,bundle_version,bundle_sha256,canonical_bundle,signature,created_at)
                SELECT tenant_id,project_id,trust_domain,n,repeat('a',64),convert_to('{}','UTF8'),decode(repeat('00',64),'hex'),now()
                FROM ota_trust_domain CROSS JOIN generate_series(2,501) n WHERE project_id=?
                """,f.project());
        UUID token=Uuid7.generate();
        owner().update("""
                UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',cleanup_stage='OTA',
                    cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,cleanup_lease_until=now()+interval '120 seconds'
                WHERE id=?
                """,token,f.project());
        for(int count:List.of(500,1,1,0)) {
            assertThat(OtaTrustPersistenceIntegrationTests.<Integer>app(f,jdbc->jdbc.queryForObject(
                    "SELECT deleted_rows FROM ota_project_cleanup_batch(?,?,1,?)",Integer.class,f.tenant(),f.project(),token))).isEqualTo(count);
        }
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_trust_domain WHERE project_id=?",Long.class,f.project())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_trust_bundle WHERE project_id=?",Long.class,neighbor.project())).isEqualTo(1);
    }
    /** 数据层测试使用合法长度但不宣称签名通过，密码学由上层单独验收。 */
    private static OtaTrustState state(Fixture f,long version) {
        return new OtaTrustState(f.tenant(),f.project(),f.domain(),"TC_OTA_ED25519_V1","a".repeat(64),
                1,"b".repeat(64),version,version,"c".repeat(64),new byte[]{123,125},new byte[64],f.created(),f.created().plusSeconds(version));
    }
    /** owner只建立本例项目骨架。 */
    private Fixture seed(){
        Fixture f=new Fixture(Uuid7.generate(),Uuid7.generate(),"trust-"+Uuid7.generate(),Instant.now().truncatedTo(ChronoUnit.MICROS));
        fixtures.add(f); owner().update("INSERT INTO sys_tenant(id,name) VALUES (?,'信任测试租户')",f.tenant());
        owner().update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'信任测试项目',?)",f.project(),f.tenant(),"trust_"+f.project().toString().replace("-",""));
        return f;
    }
    /** 普通应用角色实际事务建立RLS，所有仓储调用加入此事务。 */
    private static <T>T app(Fixture f,Function<JdbcTemplate,T> work){
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),APP_ROLE,APP_ROLE_PASSWORD);var jdbc=new JdbcTemplate(source);
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,f.tenant().toString());
            jdbc.queryForObject("SELECT set_config('app.project_id',?,true)",String.class,f.project().toString());return work.apply(jdbc);
        });
    }
    /** 只建夹具、观察真实锁和清理。 */
    private static JdbcTemplate owner(){return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));}
    /** 本例不可变范围与创建时刻。 */
    private record Fixture(UUID tenant,UUID project,String domain,Instant created){}
}
