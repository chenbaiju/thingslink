package com.things.link.bootstrap.ota;

import com.things.link.ota.domain.OtaNotificationRepository;
import com.things.link.ota.domain.OtaDownloadRequestRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaDownloadRequestRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaNotificationRepository;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaCampaignRepository;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.ota.domain.OtaPublication;
import com.things.link.ota.domain.OtaRelease;
import com.things.link.ota.infrastructure.persistence.JdbcOtaPublicationRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaUploadRepository;
import com.things.link.shared.id.Uuid7;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** 独占0710旧库升级真实申请的待授权头，不伪造签址或传输事实。 */
@Testcontainers
class OtaRollbackPreflightUpgradeTests {
    /** 独立数据库角色和历史版本环境。 */
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ota_preflight_upgrade").withUsername("thingslink").withPassword("thingslink");
    /** 与应用相同的完整迁移位置。 */
    private static final String[] LOCATIONS={"classpath:db/migration/support","classpath:db/migration/project",
            "classpath:db/migration/device","classpath:db/migration/telemetry","classpath:db/migration/alarm",
            "classpath:db/migration/task","classpath:db/migration/rule","classpath:db/migration/iam",
            "classpath:db/migration/enduser","classpath:db/migration/export","classpath:db/migration/dashboard",
            "classpath:db/migration/ota"};
    /** 已有来源和密文下载图原样升级，未配置受控扩展不能自动生成预检查询或准备裁决。 */
    @Test void upgradesOldExecutionWithoutInventingPreflightQualification() {
        flyway("20260912.0840").migrate();
        var jdbc = owner();
        Fixture old = ready();
        var campaign = scheduled(old, 1);
        runtime(old, r -> r.start(old.project(), campaign.id(), 1, old.account()));
        var admission = claimRuntime();
        app(old,j->{OtaExecutionReportFixture.seed(j,old.tenant(),old.project());return true;});
        runtime(old, r -> r.admit(admission, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()));
        var context = app(old, j -> new JdbcOtaDownloadRequestRepository(j).locate(admission.jobId()).orElseThrow());
        UUID requestId = Uuid7.generate();
        byte[] canonical = ("{\"attemptNo\":1,\"contractVersion\":\"tc-ota-download-request/v1\",\"jobId\":\"" + context.jobId()
                + "\",\"manifestSha256\":\"" + context.manifestSha256() + "\",\"requestId\":\"" + requestId + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Instant at = runtime(old, JdbcOtaCampaignRuntimeRepository::currentTime);
        var receipt = new OtaDownloadRequestRepository.Request(Uuid7.generate(),old.tenant(),old.project(),context.deviceId(),1,
                requestId,context.jobId(),campaign.id(),old.firmware(),1,context.manifestSha256(),canonical,hash(canonical),
                context.originalDeadline(),at,at,1,"a".repeat(64));
        app(old,j -> new JdbcOtaDownloadRequestRepository(j).create(receipt,context.jobRevision()));

        var initial=plain(j->new com.things.link.ota.infrastructure.persistence.JdbcOtaDownloadAuthorizationRepository(j).claimOne().orElseThrow());
        var signing=app(old,j->new com.things.link.ota.infrastructure.persistence.JdbcOtaDownloadAuthorizationRepository(j)
                .reserveSigning(initial,initial.jobRevision()).orElseThrow());
        app(old,j->new com.things.link.ota.infrastructure.persistence.JdbcOtaDownloadAuthorizationRepository(j)
                .seal(signing,signing.jobRevision(),"key-v1",new byte[12],new byte[32],"a".repeat(64),"tc/v1/project/device/down/ota/download/response"));
        var before=jdbc.queryForMap("SELECT j.status,j.state_version,j.deadline_at,j.downloading_at,o.report_hash,encode(o.canonical,'hex') AS origin_bytes,a.response_expires_at,encode(a.ciphertext,'hex') AS ciphertext "
                +"FROM ota_device_job j JOIN ota_job_execution_origin o ON o.job_id=j.id JOIN ota_download_authorization a ON a.job_id=j.id WHERE j.id=?",admission.jobId());
                assertThat(flyway("20260912.0850").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260912.0860").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260912.0860").migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForMap("SELECT j.status,j.state_version,j.deadline_at,j.downloading_at,o.report_hash,encode(o.canonical,'hex') AS origin_bytes,a.response_expires_at,encode(a.ciphertext,'hex') AS ciphertext "
                +"FROM ota_device_job j JOIN ota_job_execution_origin o ON o.job_id=j.id JOIN ota_download_authorization a ON a.job_id=j.id WHERE j.id=?",admission.jobId())).isEqualTo(before);
        for(String table:List.of("ota_rollback_preflight_query","ota_rollback_preflight_control","ota_rollback_preflight_report","ota_rollback_preflight_delivery","ota_rollback_preflight_transport")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
        }
    }
    /** 显式目标版本及容器内应用角色口令。 */
    private Flyway flyway(String target) {return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
            .locations(LOCATIONS).placeholders(Map.of("app_role_password","thingslink")).target(target).load();}
    /** 独占旧库普通应用角色，不能借owner验证业务写入。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 对应本独占容器迁移占位符。 */
    private static final String APP_ROLE_PASSWORD = "thingslink";
    /** 仅记录独占容器夹具身份，由容器生命周期清理。 */
    private final List<Fixture> fixtures = new ArrayList<>();
    /** 旧图预留足够迁移时间，仍保留原期限不刷新。 */
    private int notificationDeadlineSeconds = 120;
    /** 运行夹具全部目标位于第一批。 */
    private OtaCampaign scheduled(Fixture f, int count) {
        var ids = devices(f, count);
        var c = campaign(f, ids, count);
        campaignRun(f, r -> r.schedule(0, c, targets(f, ids), count, f.account(), c.createdAt()));
        return c;
    }
    /** 不建立管理ThreadLocal，直接用受限领取返回的真实范围。 */
    private OtaCampaignRuntimeRepository.Claim claimRuntime() {
        return plain(j -> new JdbcOtaCampaignRuntimeRepository(j).claimOne().orElseThrow());
    }
    /** 真实普通角色及控制锁持有整个最终事务。 */
    private static <T> T runtime(Fixture f, Function<JdbcOtaCampaignRuntimeRepository, T> work) {
        return app(f, j -> {
            var r = new JdbcOtaCampaignRuntimeRepository(j);
            r.controlLock(f.tenant(), f.project());
            return work.apply(r);
        });
    }

    /** 构造真实完整已采用发布父图，密码学与对象行为由其他专项覆盖。 */
    private Fixture ready() {
        Fixture f = seed();
        OtaPublication p = prepared(f);
        run(f, r -> r.recordSigned(p, p.leaseToken(), new byte[32], new byte[64], "campaign-fixture"));
        OtaPublication signed = publication(f, p);
        run(f, r -> r.commitRelease(signed, signed.leaseToken(), release(signed)));
        return f;
    }
    /** 稳定规范文本排序目标。 */
    private List<UUID> devices(Fixture f, int count) {
        List<UUID> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = Uuid7.generate();
            owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name)"
                    + " VALUES(?,?,?,?,?,'活动目标')", id, f.tenant(), f.project(), f.type(), "target_" + id);
            result.add(id);
        }
        return result.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
    }
    /** 仅冻结设备权威字段，未绑定模型允许为空。 */
    private List<OtaCampaignRepository.Target> targets(Fixture f, List<UUID> ids) {
        return ids.stream().map(id -> new OtaCampaignRepository.Target(id, f.type(), null, 1)).toList();
    }
    /** 最小数据库计划，只用于结构持久约束，完整闭集语法由合同专项验证。 */
    private OtaCampaign campaign(Fixture f, List<UUID> devices, int batchSize) {
        var release = run(f, r -> r.findRelease(f.project(), f.firmware()).orElseThrow());
        String ids = devices.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        byte[] plan = ("{\"batchSize\":" + batchSize + ",\"deviceIds\":[" + ids
                + "],\"executionPolicy\":{\"maxConcurrentDownloads\":1,\"maxDownloadBytesPerSecond\":1,"
                + "\"stageTimeoutSeconds\":{\"DOWNLOADING\":120,\"DISPATCHED\":"
                + notificationDeadlineSeconds + "}},\"firmwareId\":\""
                + f.firmware() + "\",\"notBefore\":\"2020-01-01T00:00:00Z\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OtaCampaign c = new OtaCampaign(Uuid7.generate(), f.tenant(), f.project(), f.firmware(), release.id(),
                f.account(), plan, hash(plan), release.canonicalManifest(), hash(release.canonicalManifest()), "DRAFT",
                0, 0, 0, now, now, null, null, null);
        campaignRun(f, r -> { r.create(c, hash(c.id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), c.planSha256()); return true; });
        return c;
    }
    /** 按实际字节计算摘要。 */
    private static String hash(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** 真实普通角色活动事务。 */
    private static <T> T campaignRun(Fixture f, Function<JdbcOtaCampaignRepository, T> work) {
        return app(f, j -> work.apply(new JdbcOtaCampaignRepository(j)));
    }

    /** 正常上传状态机建立固定版本DB证据，不伪造生产验签成功。 */
    private OtaPublication prepared(Fixture f) {
        OtaUploadSession u=create(f); UUID token=Uuid7.generate();
        upload(f,r->r.claimReceive(u,token)); upload(f,r->r.markWriting(u,token));
        upload(f,r->r.recordVersion(u,token,"version-one")); upload(f,r->r.finishVerified(u,token));
        OtaUploadSession verified=current(f,u); Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        OtaPublication p=new OtaPublication(Uuid7.generate(),f.tenant(),f.project(),f.firmware(),u.id(),f.account(),Uuid7.generate(),0,0,verified.revision(),("{\"artifactSize\":1,\"deviceTypeId\":\""+f.type()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),new byte[]{2},"PREPARED",0,null,null,null,null,null,null,now,now);
        run(f,r->{r.create(p);return true;}); OtaPublication claimed=claim(); assertThat(claimed.id()).isEqualTo(p.id()); return claimed;
    }
    /** 单条可信scope领取。 */
    private OtaPublication claim() { return plain(j->new JdbcOtaPublicationRepository(j).claimPreparedOrSigned().orElseThrow()); }
    /** owner仅模拟真实时间流逝，不更改状态或身份。 */
    private void expire(OtaPublication p) { owner().update("UPDATE ota_firmware_publication SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",p.id()); }
    /** 精确当前回执。 */
    private OtaPublication publication(Fixture f,OtaPublication p) { return run(f,r->r.find(f.project(),f.firmware(),p.id(),false).orElseThrow()); }
    /** 同一规范证据构造不可变release。 */
    private OtaRelease release(OtaPublication p) { return new OtaRelease(p.id(),p.tenantId(),p.projectId(),p.firmwareId(),p.uploadSessionId(),p.id(),p.canonicalManifest(),p.trustSnapshot(),p.spki(),p.signature(),p.receipt(),Instant.now()); }
    /** 普通app事务持久发布。 */
    private static <T> T run(Fixture f,Function<JdbcOtaPublicationRepository,T> work) { return app(f,j->work.apply(new JdbcOtaPublicationRepository(j))); }

    /** owner只提供合法固件和模型，不绕过上传会话业务写入。 */
    private Fixture seed() {
        Fixture f = new Fixture(Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate());
        fixtures.add(f);
        JdbcTemplate jdbc=owner();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'unused','OTA上传测试')",f.account(),f.account()+"@example.invalid");
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA上传租户')",f.tenant());
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'OTA上传项目',?)",f.project(),f.tenant(),"upload_"+f.project().toString().replace("-",""));
        jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol) VALUES (?,?,?,'upload-type','上传类型','DIRECT','STANDARD')",f.type(),f.tenant(),f.project());
        jdbc.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,version_major,
                    version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1','{}'::jsonb,repeat('a',64),'PG_JSONB_TEXT_V1_SHA256')
                """,f.model(),f.tenant(),f.project(),f.type());
        jdbc.update("""
                INSERT INTO ota_firmware(id,tenant_id,project_id,created_by,device_type_id,thing_model_version_id,
                    product_key,firmware_version,schema_digest_algorithm,schema_digest,schema_profile,status,revision,created_at)
                VALUES (?,?,?,?,?,?,'upload_product','1','PG_JSONB_TEXT_V1_SHA256',repeat('a',64),
                    'TC_PROPERTY_COMPOSITE_V1','DRAFT',0,now())
                """,f.firmware(),f.tenant(),f.project(),f.account(),f.type(),f.model());
        return f;
    }

    /** 固定远期恢复时间避免本例未领取会话干扰其他测试，需恢复时显式提前。 */
    private OtaUploadSession create(Fixture f) {
        return create(f,Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** 显式创建时钟用于验证JVM与DB时钟短暂偏差，不修改已冻结身份。 */
    private OtaUploadSession create(Fixture f,Instant now) {
        UUID id=Uuid7.generate();
        OtaUploadSession session=new OtaUploadSession(id,f.tenant(),f.project(),f.firmware(),f.account(),Uuid7.generate(),
                0,1,0,"a".repeat(64),"ota-test-bucket","attempt/"+id,"WAITING",null,null,
                id.toString().replace("-","").repeat(2),"b".repeat(64),now,now.plusSeconds(3600),null,null,null,null,null,
                now.plusSeconds(3600),null);
        upload(f,repo->{repo.create(session);return true;});
        return session;
    }

    /** 当前revision来自实际持久状态，避免用构造时快照掩盖CAS语义。 */
    private OtaUploadSession current(Fixture f,OtaUploadSession s) {
        return upload(f,repo->repo.find(f.project(),f.firmware(),s.id(),false).orElseThrow());
    }

    /** 普通app角色调用实际受限函数，未声称这里执行真实对象网络。 */
    private String cleanBatch(Fixture f,UUID token) {
        return plain(jdbc->jdbc.queryForObject("SELECT coalesce(blocked_reason,'OK') FROM ota_project_cleanup_batch(?,?,1,?)",
                String.class,f.tenant(),f.project(),token));
    }

    /** 在真实普通连接的原事务中建立项目RLS。 */
    private static <T> T app(Fixture f,Function<JdbcTemplate,T> work) {
        return plain(jdbc->{jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,f.tenant().toString());
            jdbc.queryForObject("SELECT set_config('app.project_id',?,true)",String.class,f.project().toString());
            return work.apply(jdbc);});
    }

    /** 仓储所有普通动作进入实际事务；不通过owner验证RLS。 */
    private static <T> T upload(Fixture f,Function<JdbcOtaUploadRepository,T> work) {
        return app(f,jdbc->work.apply(new JdbcOtaUploadRepository(jdbc)));
    }

    /** 无默认scope普通连接用于证明受限后台claim与查询不可见边界。 */
    private static <T> T plain(Function<JdbcTemplate,T> work) {
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),APP_ROLE,APP_ROLE_PASSWORD);
        var jdbc=new JdbcTemplate(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(5);
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            return work.apply(jdbc);
        });
    }

    /** owner限于夹具与独立最终观察。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }

    /** 本例独立父事实身份。 */
    private record Fixture(UUID tenant,UUID project,UUID account,UUID type,UUID model,UUID firmware) { }
}
