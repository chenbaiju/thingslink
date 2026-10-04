package com.things.link.bootstrap.ota;

import com.things.link.ota.domain.OtaTypeBaselineState;
import com.things.link.ota.infrastructure.persistence.JdbcOtaTypeBaselineRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0122真实普通角色证明头历史原子性与清理，基线完整语法由合同专项负责。 */
class OtaTypeBaselinePersistenceTests extends AbstractIntegrationTest {
    /** 每例独立范围。 */ private final List<Fixture> fixtures=new ArrayList<>();
    /** 在同一owner夹具事务移除完整图，不关闭生产守卫。 */
    @AfterEach void cleanup() {
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        var jdbc=new JdbcTemplate(source);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
            for(Fixture f:fixtures) {
                jdbc.update("DELETE FROM ota_type_baseline_version WHERE project_id=?",f.project());
                jdbc.update("DELETE FROM ota_type_baseline WHERE project_id=?",f.project());
                jdbc.update("DELETE FROM dev_type WHERE project_id=?",f.project());
                jdbc.update("DELETE FROM sys_project WHERE id=?",f.project());
                jdbc.update("DELETE FROM sys_tenant WHERE id=?",f.tenant());
            }
        });
    }
    /** 新旧版本原子推进，旧CAS不追加未采用历史，数组不能被外部改写。 */
    @Test void atomicallyRegistersAndAdvancesExactHistory() {
        Fixture f=seed(); OtaTypeBaselineState first=state(f,1,1,"product");
        run(f,r->{r.create(first);return true;});
        OtaTypeBaselineState next=state(f,2,2,"product");
        assertThat(OtaTypeBaselinePersistenceTests.<Boolean>run(f,r->r.replace(1,next))).isTrue();
        assertThat(OtaTypeBaselinePersistenceTests.<Boolean>run(f,r->r.replace(1,state(f,3,3,"product")))).isFalse();
        OtaTypeBaselineState read=run(f,r->r.find(f.project(),f.type(),true,false).orElseThrow());
        assertThat(read.baselineVersion()).isEqualTo(2);
        assertThat(read.canonical()).isEqualTo(next.canonical());
        byte[] copy=read.canonical();copy[0]=0;assertThat(read.canonical()).isEqualTo(next.canonical());
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline_version WHERE project_id=?",Long.class,f.project())).isEqualTo(2);
    }
    /** 孤立历史、缺失当前指针、换不可变身份均在事务边界拒绝。 */
    @Test void rejectsOrphanHistoryCorruptPointerAndIdentityChanges() {
        Fixture f=seed(); OtaTypeBaselineState first=state(f,1,1,"product");
        run(f,r->{r.create(first);return true;});
        assertThatThrownBy(()->run(f,r->r.replace(1,state(f,2,2,"changed"))))
                .hasStackTraceContaining("history identity rejected");
        assertThatThrownBy(()->app(f,j->j.update("UPDATE ota_type_baseline SET revision=2,baseline_version=2 WHERE project_id=?",f.project())))
                .hasStackTraceContaining("exact history pointer missing");
        OtaTypeBaselineState unadopted=state(f,2,2,"product");
        assertThatThrownBy(()->app(f,j->j.update("INSERT INTO ota_type_baseline_version(tenant_id,project_id,device_type_id,baseline_version,baseline_hash,canonical,created_at) VALUES (?,?,?,?,?,?,clock_timestamp())",
                f.tenant(),f.project(),f.type(),2,unadopted.baselineHash(),unadopted.canonical())))
                .hasStackTraceContaining("not atomically adopted");
        assertThatThrownBy(()->app(f,j->j.update("UPDATE ota_type_baseline_version SET baseline_hash=repeat('b',64) WHERE project_id=?",f.project())))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(()->app(f,j->j.update("DELETE FROM ota_type_baseline_version WHERE project_id=?",f.project())))
                .hasStackTraceContaining("permission denied");
    }
    /** 设备类型必须真实存在且属于同项目，不能只以UUID和项目外键冒充完整归属。 */
    @Test void rejectsMissingAndForeignDeviceTypes() {
        Fixture f=seed(),other=seed();
        for(UUID type:List.of(Uuid7.generate(),other.type())) {
            Fixture mismatched=new Fixture(f.tenant(),f.project(),type,f.created());
            assertThatThrownBy(()->run(f,r->{r.create(state(mismatched,1,1,"product"));return true;}))
                    .hasStackTraceContaining("ota_type_baseline_type_fk");
        }
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline WHERE project_id=?",Long.class,f.project())).isZero();
    }

    /** 真实共享锁阻止换版，跨项目查询看不到明确已知身份。 */
    @Test void sharedSnapshotBlocksUpdateAndScopeDoesNotLeak() throws Exception {
        Fixture f=seed();Fixture other=seed();run(f,r->{r.create(state(f,1,1,"product"));return true;});
        assertThat(OtaTypeBaselinePersistenceTests.<java.util.Optional<OtaTypeBaselineState>>run(other,r->r.find(f.project(),f.type(),false,false))).isEmpty();
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var reader=pool.submit(()->run(f,r->{r.find(f.project(),f.type(),false,true).orElseThrow();locked.countDown();
                try { if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("测试锁未释放"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return true;}));
            assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->app(f,j->{j.execute("SET LOCAL lock_timeout='150ms'");return new JdbcOtaTypeBaselineRepository(j).replace(1,state(f,2,2,"product"));}))
                        .hasStackTraceContaining("lock timeout");
            } finally { release.countDown(); }
            assertThat(reader.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(true);
        }
    }
    /** 历史跨批清理允许头暂时缺包，但查询必须显式失败而非当成不存在。 */
    @Test void cleansFiveHundredBoundAndRejectsMissingHistoryRead() {
        Fixture f=seed();run(f,r->{r.create(state(f,1,1,"product"));return true;});
        // 独立提交501个版本，使每条历史曾真实成为当前版本，未绕过采用守卫。
        for(int i=2;i<=501;i++){final int version=i;run(f,r->r.replace(version-1,state(f,version,version,"product")));}
        UUID token=Uuid7.generate();owner().update("UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',cleanup_stage='OTA',cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,cleanup_lease_until=now()+interval '120 seconds' WHERE id=?",token,f.project());
        assertThat(batch(f,token)).isEqualTo(500);
        assertThat(batch(f,token)).isEqualTo(1);
        assertThatThrownBy(()->run(f,r->r.find(f.project(),f.type(),false,false))).hasStackTraceContaining("基线缺失精确历史");
        assertThat(batch(f,token)).isEqualTo(1);
        assertThat(batch(f,token)).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline WHERE project_id=?",Long.class,f.project())).isZero();
    }
    /** 普通角色调用受限项目清理，不具备直接表删除权限。 */
    private int batch(Fixture f,UUID token){return plain(j->j.queryForObject("SELECT deleted_rows FROM ota_project_cleanup_batch(?,?,1,?)",Integer.class,f.tenant(),f.project(),token));}
    /** 仅父项目由owner准备；不替代device端口和业务基线合同验证。 */
    private Fixture seed(){Fixture f=new Fixture(Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Instant.now().truncatedTo(ChronoUnit.MICROS));fixtures.add(f);
        owner().update("INSERT INTO sys_tenant(id,name) VALUES (?,'基线租户')",f.tenant());
        owner().update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'基线项目',?)",f.project(),f.tenant(),"baseline_"+f.project().toString().replace("-",""));
        owner().update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol) VALUES (?,?,?,'baseline-type','基线设备类型','DIRECT','STANDARD')",f.type(),f.tenant(),f.project());return f;}
    /** 构造数据库身份子集字节，仅用于持久关系测试，不冒称完整基线合同通过。 */
    private static OtaTypeBaselineState state(Fixture f,long revision,long version,String product){
        byte[] bytes=("{\"contractVersion\":\"tc-ota-type-baseline/v1\",\"tenantId\":\""+f.tenant()+"\",\"projectId\":\""+f.project()+"\",\"deviceTypeId\":\""+f.type()+"\",\"baselineVersion\":"+version+",\"productKey\":\""+product+"\",\"trustDomain\":\"test-domain\",\"rootFingerprint\":\""+"a".repeat(64)+"\",\"hardware\":{\"model\":\"board\"}}").getBytes(StandardCharsets.UTF_8);
        try{return new OtaTypeBaselineState(f.tenant(),f.project(),f.type(),revision,version,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes,f.created(),f.created().plusSeconds(version));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    /** 同事务普通角色基线仓储。 */
    private static <T>T run(Fixture f,Function<JdbcOtaTypeBaselineRepository,T> work){return app(f,j->work.apply(new JdbcOtaTypeBaselineRepository(j)));}
    /** 显式RLS范围不依赖连接残留。 */
    private static <T>T app(Fixture f,Function<JdbcTemplate,T> work){return plain(j->{j.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,f.tenant().toString());j.queryForObject("SELECT set_config('app.project_id',?,true)",String.class,f.project().toString());return work.apply(j);});}
    /** 每个断言真实应用连接与事务。 */
    private static <T>T plain(Function<JdbcTemplate,T> work){var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),APP_ROLE,APP_ROLE_PASSWORD);return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(s->work.apply(new JdbcTemplate(source)));}
    /** owner只供父夹具和最终观察。 */
    private static JdbcTemplate owner(){return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));}
    /** 隔离作用域及不变创建时间。 */
    private record Fixture(UUID tenant,UUID project,UUID type,Instant created) { }
}
