package com.things.link.bootstrap.ota;

import com.things.link.ota.application.OtaUploadService;
import com.things.link.ota.application.OtaUploadProcessor;
import com.things.link.ota.application.OtaUploadRecoveryWorker;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import io.minio.MinioClient;
import io.minio.MakeBucketArgs;
import io.minio.ListObjectsArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.VersioningConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实Spring事务、PostgreSQL及MinIO验证完整上传生命周期；不认领HTTP或固件发布资格。 */
class OtaUploadLifecycleIntegrationTests extends AbstractIntegrationTest {
    /** 独占本地测试身份，无真实环境用途。 */
    private static final String ACCESS="upload-life-test";
    /** 仅用于随机端口的隔离容器。 */
    private static final String SECRET="upload-life-test-secret";
    /** 本类唯一私桶，在配置注册阶段真实创建并开启版本化。 */
    private static final String BUCKET="ota-lifecycle-"+UUID.randomUUID();
    /** 真实存储与共享数据库配置分开，测试结束由容器管理器回收。 */
    @Container
    static final GenericContainer<?> MINIO=new GenericContainer<>(DockerImageName.parse(
            "minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withEnv("MINIO_ROOT_USER",ACCESS).withEnv("MINIO_ROOT_PASSWORD",SECRET)
            .withCommand("server","/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    /** Spring读取属性之前必须已建立真实存储前提。 */
    static {
        MINIO.start();
        try (MinioClient admin=admin()) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET)
                    .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null,null,null)).build());
        } catch(Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    /** 配置完整真实版本端口，不使用mock替代对象验真。 */
    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("things-link.storage.internal-endpoint",OtaUploadLifecycleIntegrationTests::endpoint);
        registry.add("things-link.storage.external-endpoint",OtaUploadLifecycleIntegrationTests::endpoint);
        registry.add("things-link.storage.access-key",()->ACCESS);
        registry.add("things-link.storage.secret-key",()->SECRET);
        registry.add("things-link.ota.storage.bucket",()->BUCKET);
        registry.add("things-link.ota.upload.recovery-enabled",()->"false");
    }
    /** 必须通过Spring代理执行权限、事务和RLS。 */
    @Autowired private OtaUploadService service;
    /** 网络编排在业务事务外执行。 */
    @Autowired private OtaUploadProcessor processor;
    /** 恢复走真实worker，与调度是否启用分离。 */
    @Autowired private OtaUploadRecoveryWorker recovery;
    /** 用于证明续租REQUIRES_NEW不随外层回滚。 */
    @Autowired private PlatformTransactionManager transactions;
    /** JUnit回收真实上传临时文件。 */
    @TempDir Path temporary;
    /** 所有测试仅操作自身项目图。 */
    private final List<Fixture> fixtures=new ArrayList<>();

    /** 清理完整对象版本后清自己数据库事实，不绕过生产状态触发器。 */
    @AfterEach
    void cleanup() throws Exception {
        TenantContext.clear();
        try (MinioClient admin=admin()) {
            for(var result:admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).includeVersions(true).recursive(true).build())) {
                var item=result.get();
                admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(item.objectName()).versionId(item.versionId()).build());
            }
        }
        JdbcTemplate jdbc=owner();
        for(Fixture f:fixtures) {
            jdbc.update("DELETE FROM ota_firmware_upload_session WHERE project_id=?",f.project());
            jdbc.update("DELETE FROM ota_firmware_creation_request WHERE project_id=?",f.project());
            jdbc.update("DELETE FROM ota_firmware WHERE project_id=?",f.project());
            jdbc.update("DELETE FROM dev_thing_model_version WHERE project_id=?",f.project());
            jdbc.update("DELETE FROM dev_type WHERE project_id=?",f.project());
            jdbc.update("DELETE FROM sys_project_member WHERE project_id=?",f.project());
            jdbc.update("DELETE FROM sys_project WHERE id=?",f.project());
            jdbc.update("DELETE FROM sys_tenant_member WHERE tenant_id=?",f.tenant());
            jdbc.update("DELETE FROM sys_tenant WHERE id=?",f.tenant());
            jdbc.update("DELETE FROM sys_account WHERE id IN (?,?)",f.account(),f.ownerAccount());
        }
    }

    /** 完整上传固定版本验真；重放创建恢复原身份，但不能重复消费正文或换key绕过活跃会话。 */
    @Test
    void verifiesUploadAndPreservesCreationReplayIdentity() throws Exception {
        Fixture f=seed(); byte[] body={1,2,3,4};
        OtaUploadSession created=create(f,"create-one",body);
        assertThat(create(f,"create-one",body).id()).isEqualTo(created.id());
        OtaUploadSession result=upload(f,created,body);
        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.versionId()).isNotBlank();
        assertThat(create(f,"create-one",body).versionId()).isEqualTo(result.versionId());
        assertThatThrownBy(()->scope(f,()->service.prepare(f.project(),f.firmware(),created.id())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->create(f,"new-key",body)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->create(f,"create-one",new byte[]{9})).isInstanceOf(BusinessException.class);
    }

    /** 存储成功但正文与创建承诺不同，不能采用；已知版本最终由真实worker回收。 */
    @Test
    void rejectsWrongContentAndReclaimsWrittenVersion() throws Exception {
        Fixture f=seed();
        OtaUploadSession created=create(f,"bad-content",new byte[]{1,2});
        assertThatThrownBy(()->upload(f,created,new byte[]{9,9})).isInstanceOf(BusinessException.class);
        OtaUploadSession pending=scope(f,()->service.find(f.project(),f.firmware(),created.id()));
        assertThat(pending.status()).isEqualTo("CLEANUP_PENDING");
        assertThat(pending.versionId()).isNotBlank();
        owner().update("UPDATE ota_firmware_upload_session SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",created.id());
        OtaUploadSession claim=service.claimRecovery().orElseThrow();
        assertThat(claim.id()).isEqualTo(created.id());
        scope(f,()->{ recovery.recover(claim); return null; });
        assertThat(scope(f,()->service.find(f.project(),f.firmware(),created.id())).status()).isEqualTo("CLEANED");
    }

    /** 权限和生命周期在消费前重新核对，不以创建时资格替代当前授权。 */
    @Test
    void refusesLostRoleAndChangedProjectGeneration() throws Exception {
        Fixture f=seed(); OtaUploadSession created=create(f,"access",new byte[]{1});
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",f.project(),f.account());
        assertThatThrownBy(()->scope(f,()->service.prepare(f.project(),f.firmware(),created.id())))
                .isInstanceOf(BusinessException.class);
        owner().update("UPDATE sys_project_member SET role='ADMIN' WHERE project_id=? AND account_id=?",f.project(),f.account());
        owner().update("UPDATE sys_project SET lifecycle_generation=lifecycle_generation+1 WHERE id=?",f.project());
        assertThatThrownBy(()->scope(f,()->service.prepare(f.project(),f.firmware(),created.id())))
                .isInstanceOf(BusinessException.class);
        assertThat(scope(f,()->service.find(f.project(),f.firmware(),created.id())).status()).isEqualTo("WAITING");
    }

    /** 已验真对象取消后由实际恢复worker删除版本，再持久完成清理。 */
    @Test
    void cleansVerifiedObjectAfterCancellation() throws Exception {
        Fixture f=seed(); byte[] body={4,5,6};
        OtaUploadSession verified=upload(f,create(f,"cancel",body),body);
        OtaUploadSession cancelled=scope(f,()->service.cancel(f.project(),f.firmware(),verified.id(),"cancel-key",Long.toString(verified.revision())));
        assertThat(cancelled.status()).isEqualTo("CLEANUP_PENDING");
        OtaUploadSession claim=service.claimRecovery().orElseThrow();
        assertThat(claim.id()).isEqualTo(verified.id());
        scope(f,()->{ recovery.recover(claim); return null; });
        assertThat(scope(f,()->service.find(f.project(),f.firmware(),verified.id())).status()).isEqualTo("CLEANED");
        try(MinioClient admin=admin()) {
            assertThat(admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(verified.objectKey())
                    .includeVersions(true).recursive(true).build()).iterator().hasNext()).isFalse();
        }
    }

    /** 租约过期后的新恢复token不能使旧调用采用；空库存仍留下UNKNOWN。 */
    @Test
    void retainsUnknownAndRejectsOldTokenAfterRecoveryClaim() throws Exception {
        Fixture f=seed(); OtaUploadSession created=create(f,"unknown",new byte[]{7});
        OtaUploadSession prepared=scope(f,()->service.prepare(f.project(),f.firmware(),created.id()));
        scope(f,()->{service.markWriting(prepared);return true;});
        owner().update("UPDATE ota_firmware_upload_session SET lease_until=clock_timestamp()-interval '1 second',"
                +"next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",prepared.id());
        OtaUploadSession claim=service.claimRecovery().orElseThrow();
        assertThat(claim.leaseToken()).isNotEqualTo(prepared.leaseToken());
        assertThat(OtaUploadLifecycleIntegrationTests.<Boolean>scope(f,()->service.recordVersion(prepared,"old-token-version"))).isFalse();
        scope(f,()->{ recovery.recover(claim); return null; });
        OtaUploadSession pending=scope(f,()->service.find(f.project(),f.firmware(),created.id()));
        assertThat(pending.status()).isEqualTo("UNKNOWN");
        assertThat(pending.cleanupCompletedAt()).isNull();
        assertThat(pending.versionId()).isNull();
    }

    /** 6MiB真实multipart上传完成但未登记回执，持久WRITING恢复后验真采用固定版本。 */
    @Test
    void recoversRealUploadWhoseReceiptWasNotPersisted() throws Exception {
        Fixture f=seed(); byte[] body=new byte[6*1024*1024]; body[0]=42;
        OtaUploadSession created=create(f,"unrecorded-receipt",body);
        OtaUploadSession prepared=scope(f,()->service.prepare(f.project(),f.firmware(),created.id()));
        scope(f,()->{service.markWriting(prepared);return true;});
        writeWithoutPersistingReceipt(prepared,body);
        makeRecoveryDue(prepared);
        OtaUploadSession claim=service.claimRecovery().orElseThrow();
        assertThat(claim.id()).isEqualTo(prepared.id());
        scope(f,()->{recovery.recover(claim);return true;});
        OtaUploadSession restored=scope(f,()->service.find(f.project(),f.firmware(),created.id()));
        assertThat(restored.status()).isEqualTo("VERIFIED");
        assertThat(restored.versionId()).isNotBlank();
    }

    /** 空UNKNOWN观察未收束；同请求的迟到真实版本仍能在下一轮被采用。 */
    @Test
    void adoptsLateVersionAfterEmptyUnknownObservation() throws Exception {
        lateVersion(false);
    }

    /** 取消后迟到版本只允许回收；不能因曾经空盘点或恢复成功而重新采用。 */
    @Test
    void reclaimsLateVersionAfterCancellationAndEmptyObservation() throws Exception {
        lateVersion(true);
    }

    /** 模拟服务端迟到完成，不让被测worker重新发起同key写入。 */
    private void lateVersion(boolean cancel) throws Exception {
        Fixture f=seed(); byte[] body=new byte[6*1024*1024]; body[body.length-1]=27;
        OtaUploadSession created=create(f,"late-version",body);
        OtaUploadSession prepared=scope(f,()->service.prepare(f.project(),f.firmware(),created.id()));
        scope(f,()->{service.markWriting(prepared);return true;});
        if(cancel) {
            OtaUploadSession writing=scope(f,()->service.find(f.project(),f.firmware(),created.id()));
            scope(f,()->service.cancel(f.project(),f.firmware(),created.id(),"late-cancel",Long.toString(writing.revision())));
        }
        makeRecoveryDue(prepared);
        OtaUploadSession firstClaim=service.claimRecovery().orElseThrow();
        assertThat(firstClaim.id()).isEqualTo(prepared.id());
        scope(f,()->{recovery.recover(firstClaim);return true;});
        OtaUploadSession emptyObserved=scope(f,()->service.find(f.project(),f.firmware(),created.id()));
        assertThat(emptyObserved.status()).isEqualTo("UNKNOWN");
        assertThat(emptyObserved.cleanupCompletedAt()).isNull();
        assertThat(emptyObserved.writeSettledAt()).isNull();
        writeWithoutPersistingReceipt(prepared,body);
        makeRecoveryDue(prepared);
        OtaUploadSession nextClaim=service.claimRecovery().orElseThrow();
        assertThat(nextClaim.id()).isEqualTo(prepared.id());
        assertThat(nextClaim.leaseToken()).isNotEqualTo(firstClaim.leaseToken());
        scope(f,()->{recovery.recover(nextClaim);return true;});
        OtaUploadSession restored=scope(f,()->service.find(f.project(),f.firmware(),created.id()));
        assertThat(restored.status()).isEqualTo(cancel?"CLEANED":"VERIFIED");
        assertThat(restored.versionId()).isNotBlank();
        if(cancel) {
            try(MinioClient admin=admin()) {
                assertThat(admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(prepared.objectKey())
                        .includeVersions(true).recursive(true).build()).iterator().hasNext()).isFalse();
            }
        }
    }

    /** 只让存储产生同requestId对象，故意不调用recordVersion模拟持久回执丢失。 */
    private void writeWithoutPersistingReceipt(OtaUploadSession session,byte[] bytes) throws Exception {
        Path file=temporary.resolve(session.id()+"-unrecorded.bin"); Files.write(file,bytes);
        service.requireStorage().upload(new com.things.link.support.storage.VersionedPrivateObjectStorage.WriteRequest(
                OtaUploadProcessor.identity(session),file),new com.things.link.support.storage.VersionedStorageControl(
                java.time.Duration.ofSeconds(30),()->false));
    }

    /** 仅推进测试时钟前提，不修改状态或绕过不可变事实触发器。 */
    private void makeRecoveryDue(OtaUploadSession session) {
        owner().update("UPDATE ota_firmware_upload_session SET next_attempt_at=clock_timestamp()-interval '1 second',"
                +"lease_until=CASE WHEN lease_token IS NULL THEN NULL ELSE clock_timestamp()-interval '1 second' END WHERE id=?",session.id());
    }

    /** 续租真实代理独立提交，外层事务回滚不得撤销心跳延长。 */
    @Test
    void renewalCommitsIndependentlyOfOuterRollback() throws Exception {
        Fixture f=seed(); OtaUploadSession created=create(f,"renew",new byte[]{8});
        OtaUploadSession prepared=scope(f,()->service.prepare(f.project(),f.firmware(),created.id()));
        owner().update("UPDATE ota_firmware_upload_session SET lease_until=clock_timestamp()+interval '20 seconds' WHERE id=?",prepared.id());
        Instant before=owner().queryForObject("SELECT lease_until FROM ota_firmware_upload_session WHERE id=?",java.sql.Timestamp.class,prepared.id()).toInstant();
        scope(f,()->new TransactionTemplate(transactions).execute(status->{
            assertThat(service.renew(prepared)).isTrue(); status.setRollbackOnly(); return true;
        }));
        Instant after=owner().queryForObject("SELECT lease_until FROM ota_firmware_upload_session WHERE id=?",java.sql.Timestamp.class,prepared.id()).toInstant();
        assertThat(after).isAfter(before.plusSeconds(60));
    }

    /** 同步网络只持本地文件和租约，不能持业务数据库事务。 */
    private OtaUploadSession upload(Fixture f,OtaUploadSession created,byte[] bytes) throws Exception {
        Path file=temporary.resolve(created.id()+".bin"); Files.write(file,bytes);
        OtaUploadSession prepared=scope(f,()->service.prepare(f.project(),f.firmware(),created.id()));
        return scope(f,()->{try(var lease=processor.begin(prepared)) {
            return processor.process(prepared,file,lease,()->false);
        }});
    }

    /** 内容承诺由实际字节计算，不使用对象ETag替代。 */
    private OtaUploadSession create(Fixture f,String key,byte[] body) throws Exception {
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        return scope(f,()->service.create(f.project(),f.firmware(),key,body.length,sha));
    }

    /** 只在测试调用边界设置真实账号scope，Spring代理仍核对持久权限。 */
    private static <T>T scope(Fixture f,Supplier<T> work) {
        TenantContext.set(new TenantScope(f.tenant(),f.project(),f.account()));
        try{return work.get();}finally{TenantContext.clear();}
    }

    /** owner建立独立模型/固件图，实际上传权限仍来自ADMIN成员事实。 */
    private Fixture seed() {
        Fixture f=new Fixture(Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate());
        fixtures.add(f); JdbcTemplate jdbc=owner();
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,'上传生命周期租户')",f.tenant());
        for(UUID account:List.of(f.account(),f.ownerAccount())) {
            jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'unused','上传生命周期')",account,account+"@example.invalid");
            jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",Uuid7.generate(),f.tenant(),account);
        }
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'上传生命周期项目',?)",f.project(),f.tenant(),"upload_"+f.project().toString().replace("-",""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER'),(?,?,?,'ADMIN')",Uuid7.generate(),f.project(),f.ownerAccount(),Uuid7.generate(),f.project(),f.account());
        jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,status,product_key,product_secret_hash) VALUES (?,?,?,'upload-type','上传类型','DIRECT','STANDARD','PUBLISHED','upload_product',repeat('a',64))",f.type(),f.tenant(),f.project());
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

    /** owner只用于建夹具、故障前提与最终独立观察。 */
    private static JdbcTemplate owner(){return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));}
    /** 独立管理客户端由调用方关闭。 */
    private static MinioClient admin(){return MinioClient.builder().endpoint(endpoint()).credentials(ACCESS,SECRET).build();}
    /** 本类随机端口。 */
    private static String endpoint(){return "http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000);}
    /** 完整独立租户项目和两个账号身份。 */
    private record Fixture(UUID tenant,UUID project,UUID account,UUID ownerAccount,UUID type,UUID model,UUID firmware){}
}
