package com.things.link.bootstrap.ota;

import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaCampaignRepository;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.ota.domain.OtaPublication;
import com.things.link.ota.domain.OtaRelease;
import com.things.link.ota.infrastructure.persistence.JdbcOtaPublicationRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaUploadRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 活动完整事实真实PG边界，发布父图只作为持久夹具，不声称密码学或网络资格。 */
class OtaCampaignPersistenceTests extends AbstractIntegrationTest {
    /** 复用夹具连接，千目标插入不能为每条记录新建宿主 TCP 连接。 */
    private static final HikariDataSource APP_SOURCE = fixtureSource("ota-campaign-persistence-app", APP_ROLE,
            APP_ROLE_PASSWORD);
    private static final HikariDataSource OWNER_SOURCE = fixtureSource("ota-campaign-persistence-owner",
            POSTGRES.getUsername(), POSTGRES.getPassword());
    /** 每例隔离项目。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 关闭本类独占的夹具连接池。 */
    @AfterAll static void closeFixtureSources() {
        try {
            APP_SOURCE.close();
        } finally {
            OWNER_SOURCE.close();
        }
    }

    /** owner按子先父清理全部夹具，完整删除事务不留下临时断图。 */
    @AfterEach void cleanup() {
        for (Fixture f : fixtures) {
            new TransactionTemplate(new DataSourceTransactionManager(OWNER_SOURCE)).execute(status -> {
                var j = new JdbcTemplate(OWNER_SOURCE);
                for (String table : List.of("ota_campaign_outbox", "ota_campaign_transition", "ota_device_job",
                        "ota_campaign_batch", "ota_campaign_creation_request", "ota_campaign", "ota_firmware_release",
                        "ota_firmware_publication", "ota_firmware_upload_session", "ota_firmware_creation_request",
                        "ota_firmware", "dev_device", "dev_thing_model_version", "dev_type")) {
                    j.update("DELETE FROM " + table + " WHERE project_id=?", f.project());
                }
                j.update("DELETE FROM sys_project WHERE id=?", f.project());
                j.update("DELETE FROM sys_tenant WHERE id=?", f.tenant());
                j.update("DELETE FROM sys_account WHERE id=?", f.account());
                return true;
            });
        }
    }

    /** 稳定批次、取消中间事实及设备预占释放属于一个完整事务。 */
    @Test void freezesStableBatchesAndCancelsAllUndispatchedFacts() {
        Fixture f = ready();
        List<UUID> devices = devices(f, 3);
        OtaCampaign c = campaign(f, devices, 2);
        assertThat(OtaCampaignPersistenceTests.<List<OtaCampaignRepository.Job>>campaignRun(f, r -> r.jobs(f.project(), c.id()))).isEmpty();
        assertThat(OtaCampaignPersistenceTests.<Boolean>campaignRun(f, r -> r.schedule(0, c,
                targets(f, devices), 2, f.account(), c.createdAt().plusSeconds(1)))).isTrue();
        var jobs = campaignRun(f, r -> r.jobs(f.project(), c.id()));
        assertThat(jobs).extracting(OtaCampaignRepository.Job::batchNumber).containsExactly(1, 1, 2);
        assertThat(jobs).extracting(OtaCampaignRepository.Job::deviceId).containsExactlyElementsOf(devices);
        assertThatThrownBy(() -> jobs.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(OtaCampaignPersistenceTests.<Boolean>campaignRun(f, r -> r.cancelUndispatched(0, c,
                f.account(), c.createdAt().plusSeconds(2), "取消"))).isFalse();
        assertThat(OtaCampaignPersistenceTests.<Boolean>campaignRun(f, r -> r.cancelUndispatched(1, c,
                f.account(), c.createdAt().plusSeconds(2), "取消"))).isTrue();
        var cancelled = campaignRun(f, r -> r.find(f.project(), c.id(), false).orElseThrow());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.stateVersion()).isEqualTo(3);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign_transition WHERE campaign_id=?",
                Integer.class, c.id())).isEqualTo(4);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign_outbox WHERE campaign_id=? AND published_at IS NULL",
                Integer.class, c.id())).isEqualTo(4);
        OtaCampaign replacement = campaign(f, devices, 2);
        assertThat(OtaCampaignPersistenceTests.<Boolean>campaignRun(f, r -> r.schedule(0, replacement,
                targets(f, devices), 2, f.account(), replacement.createdAt().plusSeconds(1)))).isTrue();
    }

    /** 真实事务并发抢同设备，失败活动不留下批次、作业或事件。 */
    @Test void reservesPendingDeviceAcrossConcurrentCampaigns() throws Exception {
        Fixture f = ready();
        List<UUID> devices = devices(f, 1);
        OtaCampaign first = campaign(f, devices, 1);
        OtaCampaign second = campaign(f, devices, 1);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var winner = executor.submit(() -> campaignRun(f, r -> {
                r.schedule(0, first, targets(f, devices), 1, f.account(), first.createdAt().plusSeconds(1));
                locked.countDown();
                try {
                    if (!finish.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return true;
            }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> app(f, j -> {
                    j.execute("SET LOCAL lock_timeout='150ms'");
                    return new JdbcOtaCampaignRepository(j).schedule(0, second, targets(f, devices), 1,
                            f.account(), second.createdAt().plusSeconds(1));
                })).hasStackTraceContaining("lock timeout");
            } finally { finish.countDown(); }
            assertThat(winner.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(true);
        }
        assertThatThrownBy(() -> campaignRun(f, r -> r.schedule(0, second, targets(f, devices), 1,
                f.account(), second.createdAt().plusSeconds(1))))
                .isInstanceOf(OtaCampaignRepository.TargetOccupiedException.class);
        assertThat(campaignRun(f, r -> r.find(f.project(), second.id(), false).orElseThrow()).status()).isEqualTo("DRAFT");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign_transition WHERE campaign_id=?",
                Integer.class, second.id())).isEqualTo(1);
        assertThat(OtaCampaignPersistenceTests.<List<OtaCampaignRepository.Job>>campaignRun(f, r -> r.jobs(f.project(), second.id()))).isEmpty();
    }

    /** 最大名单与最细合法批次包含提交检查，不能只测SQL调用耗时。 */
    @Test void schedulesAndCancelsThousandSingleDeviceBatchesWithinBudget() {
        Fixture f = ready();
        var devices = devices(f, 1000);
        var c = campaign(f, devices, 1);
        long started = System.nanoTime();
        assertThat(OtaCampaignPersistenceTests.<Boolean>campaignRun(f, r -> r.schedule(0, c,
                targets(f, devices), 1, f.account(), c.createdAt().plusSeconds(1)))).isTrue();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(10));
        started = System.nanoTime();
        assertThat(OtaCampaignPersistenceTests.<Boolean>campaignRun(f, r -> r.cancelUndispatched(1, c,
                f.account(), c.createdAt().plusSeconds(2), "千目标取消"))).isTrue();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(10));
        assertThat(OtaCampaignPersistenceTests.<List<OtaCampaignRepository.Job>>campaignRun(f, r -> r.jobs(f.project(), c.id()))).hasSize(1000)
                .allMatch(j -> j.status().equals("CANCELLED"));
    }

    /** DB拒绝快照篡改、断图提交、假发布以及跨项目读取。 */
    @Test void rejectsIncompleteGraphsMutationAndCrossScope() {
        Fixture f = ready();
        var devices = devices(f, 1);
        var c = campaign(f, devices, 1);
        Fixture other = seed();
        assertThat(OtaCampaignPersistenceTests.<java.util.Optional<OtaCampaign>>campaignRun(other,
                r -> r.find(f.project(), c.id(), false))).isEmpty();
        assertThatThrownBy(() -> app(f, j -> j.update("DELETE FROM ota_campaign WHERE id=?", c.id())))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app(f, j -> j.update("UPDATE ota_campaign SET canonical_plan='{}'::bytea WHERE id=?", c.id())))
                .hasStackTraceContaining("immutable snapshot");
        assertThatThrownBy(() -> app(f, j -> j.update("UPDATE ota_campaign_outbox SET published_at=now() WHERE campaign_id=?", c.id())))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app(f, j -> j.update("UPDATE ota_campaign SET status='SCHEDULED',state_version=1,"
                + "target_count=1,batch_count=1,scheduled_at=now(),updated_at=now() WHERE id=?", c.id())))
                .hasStackTraceContaining("graph incomplete");
        assertThat(campaignRun(f, r -> r.find(f.project(), c.id(), false).orElseThrow()).status()).isEqualTo("DRAFT");
        assertThat(OtaCampaignPersistenceTests.<Boolean>campaignRun(f, r -> r.cancelUndispatched(0, c,
                f.account(), c.createdAt().plusSeconds(1), "草稿取消"))).isTrue();
        assertThat(campaignRun(f, r -> r.find(f.project(), c.id(), false).orElseThrow()).stateVersion()).isEqualTo(1);
    }

    /** 对象未收束时不删除引用，收束后501个作业按500+1删除且租约仍有效。 */
    @Test void cleansCampaignGraphOnlyAfterObjectSettlement() {
        Fixture f = ready();
        var devices = devices(f, 501);
        var c = campaign(f, devices, 500);
        campaignRun(f, r -> r.schedule(0, c, targets(f, devices), 500, f.account(), c.createdAt().plusSeconds(1)));
        UUID token = Uuid7.generate();
        owner().update("UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',"
                + "cleanup_stage='OTA',cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,"
                + "cleanup_lease_until=now()+interval '120 seconds' WHERE id=?", token, f.project());
        assertThatThrownBy(() -> cleanBatch(f, Uuid7.generate())).hasStackTraceContaining("authorization rejected");
        assertThat(cleanBatch(f, token)).isEqualTo("OTA_UPLOAD_CANCELLATION_REQUESTED");
        assertThat(cleanBatch(f, token)).isEqualTo("OTA_UPLOAD_RECOVERY_PENDING");
        // 仅数据库专项：用真实当前恢复租约确认已知写入的元数据清理，不声称执行存储网络。
        OtaUploadSession claim = plain(j -> new JdbcOtaUploadRepository(j).claimRecovery().orElseThrow());
        assertThat(claim.projectId()).isEqualTo(f.project());
        upload(f, r -> r.finishCleanup(claim, claim.leaseToken()));
        assertThat(cleanBatch(f, token)).isEqualTo("OK");
        assertThat(cleanBatch(f, token)).isEqualTo("OK");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_device_job WHERE campaign_id=?", Integer.class, c.id()))
                .isEqualTo(501);
        assertThat(cleanBatch(f, token)).isEqualTo("OK");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_device_job WHERE campaign_id=?", Integer.class, c.id()))
                .isEqualTo(1);
        for (int i = 0; i < 12; i++) cleanBatch(f, token);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign WHERE project_id=?", Integer.class, f.project()))
                .isZero();
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
                + "],\"firmwareId\":\"" + f.firmware() + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
        OtaPublication p=new OtaPublication(Uuid7.generate(),f.tenant(),f.project(),f.firmware(),u.id(),f.account(),Uuid7.generate(),0,0,verified.revision(),("{\"deviceTypeId\":\""+f.type()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),new byte[]{2},"PREPARED",0,null,null,null,null,null,null,now,now);
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
        var jdbc=new JdbcTemplate(APP_SOURCE);
        return new TransactionTemplate(new DataSourceTransactionManager(APP_SOURCE))
                .execute(status->work.apply(jdbc));
    }

    /** owner限于夹具与独立最终观察。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(OWNER_SOURCE);
    }

    private static HikariDataSource fixtureSource(String name, String user, String password) {
        // Spring/Flyway 创建普通角色后才首次取连接；此处不得提前启动连接池。
        var source = new HikariDataSource();
        source.setPoolName(name);
        source.setJdbcUrl(POSTGRES.getJdbcUrl());
        source.setUsername(user);
        source.setPassword(password);
        source.setMaximumPoolSize(2);
        source.setMinimumIdle(0);
        return source;
    }

    /** 本例独立父事实身份。 */
    private record Fixture(UUID tenant,UUID project,UUID account,UUID type,UUID model,UUID firmware) { }
}
