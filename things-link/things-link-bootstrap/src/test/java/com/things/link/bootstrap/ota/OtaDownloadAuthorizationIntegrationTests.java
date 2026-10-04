package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
import com.zaxxer.hikari.HikariDataSource;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.VersioningConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实PG、MinIO与HTTP验证下载响应密文封存、当前资格围栏和有限秘密传输。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(OtaDownloadAuthorizationIntegrationTests.SigningConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaDownloadAuthorizationIntegrationTests extends com.things.link.testing.AbstractIntegrationTest {
    /** 仅本测试内存持有私钥，显式测试配置不进入生产装配。 */
    private static final java.security.KeyPair ROOT = keyPair(), RELEASE = keyPair(), ROTATED = keyPair();
    /** 每例根签名冻结的发布key到期；仅过期专项缩短，不能修改已登记有效期。 */
    private static long releaseNotAfter = 253402300799L;
    /** 在根配置初始化前冻结独占身份。 */
    private static final Fixture CONFIGURED = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 本类固定身份在多例间复用，owner连接也应复用并在类结束时关闭。 */
    private static final HikariDataSource OWNER_POOL = ownerPool();
    private static HikariDataSource ownerPool() {
        var source = new HikariDataSource();
        source.setJdbcUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        source.setMinimumIdle(0);
        source.setMaximumPoolSize(4);
        return source;
    }
    /** 本类独占信任域，避免与共享数据库其他验收互相授权。 */
    private static final String DOMAIN = "pub-" + UUID.randomUUID();
    /** 独立生成精确根签名包与发布manifest的规范字节。 */
    private static final com.things.link.ota.application.OtaCanonicalJson CANONICAL = new com.things.link.ota.application.OtaCanonicalJson();
    /** 只供独占容器使用的测试凭据。 */
    private static final String ACCESS = "download-authorization-http-test", SECRET = "download-authorization-http-secret";
    /** 本测试唯一桶，不触碰开发存储。 */
    private static final String BUCKET = "ota-auth-http-" + UUID.randomUUID();
    /** 独占真实存储，启动失败不降级。 */
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            "minio/minio:RELEASE.2025-04-22T22-12-26Z")
            .withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    /** Spring读取配置前先建立版本化私桶，失败时关闭本容器。 */
    static {
        MINIO.start();
        try (MinioClient admin = admin()) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET)
                    .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, null, null, null)).build());
        } catch (Exception failure) { MINIO.stop(); throw new ExceptionInInitializerError(failure); }
    }
    /** 真实适配器由四项配置装配，关闭自动领取以独立观察发布与上传状态。 */
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        OtaCompletionSourceAssertions.configure(registry);
        registry.add("things-link.storage.internal-endpoint", OtaDownloadAuthorizationIntegrationTests::endpoint);
        registry.add("things-link.storage.external-endpoint", OtaDownloadAuthorizationIntegrationTests::endpoint);
        registry.add("things-link.storage.access-key", () -> ACCESS);
        registry.add("things-link.storage.secret-key", () -> SECRET);
        registry.add("things-link.ota.storage.bucket", () -> BUCKET);
        registry.add("things-link.ota.type-baselines-json", OtaDownloadAuthorizationIntegrationTests::baselineSource);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
        registry.add("things-link.ota.download-authorization.enabled", () -> false);
        registry.add("things-link.ingestion.emqx-api.base-url", () -> "http://127.0.0.1:"+BROKER.getAddress().getPort());
        registry.add("things-link.ingestion.emqx-api.api-key", () -> "authorization-test");
        registry.add("things-link.ingestion.emqx-api.api-secret", () -> "authorization-test-secret");
        registry.add("things-link.ota.download-response.active-key-version", () -> "test-v1");
        registry.add("things-link.ota.download-response.keyring-json", () -> "{\"test-v1\":\""+java.util.Base64.getEncoder().encodeToString(new byte[32])+"\"}");
        registry.add("things-link.ota.upload.recovery-enabled", () -> false);
        registry.add("things-link.ota.publication.enabled", () -> false);
        registry.add("things-link.ota.campaign.runtime-enabled", () -> false);
        registry.add("things-link.ota.retry.runtime-enabled", () -> false);
        registry.add("things-link.ota.notification.enabled", () -> false);
        registry.add("things-link.ota.download.allow-insecure-loopback", () -> true);
        registry.add("things-link.ota.trust.anchors-json", () -> new String(CANONICAL.writeObject(java.util.Map.of(
                "anchors", List.of(java.util.Map.of("tenantId", CONFIGURED.tenantId().toString(),
                "projectId", CONFIGURED.projectId().toString(), "trustDomain", DOMAIN,
                "allowedDeviceTypeIds", List.of(CONFIGURED.typeId().toString()), "rootProfile", "TC_OTA_ED25519_V1",
                "rootSpki", b64(ROOT.getPublic().getEncoded()), "rootFingerprint", sha(ROOT.getPublic().getEncoded()),
                "policyRevision", 1L)))), StandardCharsets.UTF_8));
    }
    /** 真正随机监听端口。 */ @Value("${local.server.port}") private int port;
    /** JWT仍经过真实验签与数据库角色读取。 */ @Autowired private TokenIssuer tokens;
    /** 响应只作断言，不能伪造生产事实。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 每例自有项目图。 */ private final List<Fixture> fixtures = new ArrayList<>();


    /** 真实发布短事务服务，不以测试代码直接插入尝试。 */
    @Autowired private com.things.link.ota.application.OtaPublicationService publications;
    /** 生产签名与对象复验编排，测试显式调用受限领取结果。 */
    @Autowired private com.things.link.ota.application.OtaPublicationProcessor publicationProcessor;
    /** 真实上传身份与取消服务。 */
    @Autowired private com.things.link.ota.application.OtaUploadService uploads;
    /** 真实MinIO写入和固定版本验真编排。 */
    @Autowired private com.things.link.ota.application.OtaUploadProcessor uploadProcessor;
    /** 真实对象回收worker，不用删除数据库行冒充物理清理。 */
    @Autowired private com.things.link.ota.application.OtaUploadRecoveryWorker uploadRecovery;
    /** 根签名包授权及当前发布键资格。 */
    @Autowired private com.things.link.ota.application.OtaTrustService trust;
    /** 仅本类显式装配的可控JCA测试签名器。 */
    @Autowired private TestSigner signer;
    /** 项目受限清理批次所需的普通APP实际事务。 */
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    /** 当前普通APP连接，不能由owner连接代替生产清理入口。 */
    @Autowired private JdbcTemplate jdbc;
    /** JUnit独占上传临时目录，测试结束自动清理。 */
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    /** 每例重置调用计数及故障接缝，避免共享测试Bean泄漏上例行为。 */
    /** 每例冻结重试预算，零预算用于首次失败即终结验收。 */
    private static long downloadRetries=2;
    @org.junit.jupiter.api.BeforeEach void resetSigner() { downloadRetries=2; releaseNotAfter = 253402300799L; signer.calls.set(0); signer.beforeReturn = () -> { }; signer.unknown = false; signer.corrupt = false; dispatchedSeconds=120; BROKER_STATUS.set(202); LAST_BODY.set(null); org.mockito.Mockito.doCallRealMethod().when(responseCipher).configured(); }

    /** 报告接纳必须使用真实数据面事务代理。 */
    @Autowired private com.things.link.ota.application.OtaDeviceReportIngestionService reportIngestion;
    /** 当前配置的受控基线登记服务。 */
    @Autowired private com.things.link.ota.application.OtaTypeBaselineService baselines;
    /** 后台入口从真实持久租约取得scope，不传递或伪造管理账号。 */
    @Autowired private com.things.link.ota.application.OtaCampaignAdmissionService admission;
    /** 只对实际审计写入后故障施加接缝，不替换资格服务。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.things.link.support.audit.AuditLogService audit;

    /** 原认证数据面接纳代理，保持真实事务与RLS。 */
    @Autowired private com.things.link.ota.application.OtaDownloadRequestIngestionService requests;
    /** 实际授权短事务服务，签址和网络在事务外调用。 */
    @Autowired private com.things.link.ota.application.OtaDownloadAuthorizationService authorizations;
    /** 真正版本化私桶，签址不能用伪造URL替代。 */
    @Autowired private com.things.link.support.storage.VersionedPrivateObjectStorage storage;
    /** 真实本机HTTP适配器，接收端仅模拟Broker回执。 */
    @Autowired private com.things.link.ota.application.OtaDownloadResponsePublisher responsePublisher;
    /** 仅缺配置专项切换configured；加解密仍由真实AEAD实现。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.things.link.ota.application.OtaDownloadResponseCipher responseCipher;
    /** 最后观察到的测试HTTP正文，不输出到日志。 */
    private static final java.util.concurrent.atomic.AtomicReference<byte[]> LAST_BODY=new java.util.concurrent.atomic.AtomicReference<>();
    /** 测试受控Broker回执码。 */
    private static final java.util.concurrent.atomic.AtomicInteger BROKER_STATUS=new java.util.concurrent.atomic.AtomicInteger(202);
    /** 仅本类随机端口的真实HTTP接收端，不宣称实际MQTT设备接收。 */
    private static final com.sun.net.httpserver.HttpServer BROKER=broker();
    /** 在Spring属性读取前建立专用HTTP端点。 */
    private static com.sun.net.httpserver.HttpServer broker(){
        try{var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v5/publish",exchange->{LAST_BODY.set(exchange.getRequestBody().readAllBytes());exchange.sendResponseHeaders(BROKER_STATUS.get(),-1);exchange.close();});server.start();return server;
        }catch(java.io.IOException failure){throw new ExceptionInInitializerError(failure);}
    }
    /** 本例可缩短原始派发预算，绝不更新已冻结期限。 */
    private static long dispatchedSeconds=120;

    /** 完整真实签址和HTTP路径只存密文，阶段与原通知收束在封存事务完成。 */
    @Test void sealsEncryptedResponseAndPublishesActualHttpOnce() throws Exception {
        Running run=acceptedRequest();var original=job(run);var signing=signing();URI url=presign(signing);
        assertThat(authorizations.seal(signing.claim().authorizationId(),signing.claim().leaseToken(),url)).isTrue();
        var sealed=authorization(run);assertThat(sealed).containsEntry("status","SEALED").containsEntry("key_version","test-v1");
        assertThat(new String((byte[])sealed.get("ciphertext"),StandardCharsets.UTF_8)).doesNotContain(url.toString(),"X-Amz","downloadUrl");
        assertThat(job(run)).containsEntry("status","DOWNLOADING");
        assertThat(((java.sql.Timestamp)job(run).get("deadline_at")).toInstant()).isAfter(((java.sql.Timestamp)original.get("deadline_at")).toInstant());
        assertThat(owner().queryForObject("SELECT original_deadline FROM ota_download_request WHERE campaign_id=?",java.sql.Timestamp.class,run.campaign())).isEqualTo(original.get("deadline_at"));
        assertThat(owner().queryForObject("SELECT status FROM ota_notification_delivery WHERE campaign_id=?",String.class,run.campaign())).isEqualTo("SUPERSEDED");
        var sendClaim=claimSealed(signing);
        var sending=authorizations.prepareSend(sendClaim.authorizationId(),sendClaim.leaseToken()).orElseThrow();
        var decoded=new com.things.link.ota.application.OtaDownloadResponseCodec(true).decode(sending.canonical());
        assertThat(decoded.downloadUrl()).isEqualTo(url);assertThat(decoded.expiresAt()).isEqualTo(signing.expiresAt());
        assertThat(sending.toString()).doesNotContain(url.toString());
        try(var http=HttpClient.newHttpClient()){
            var bytes=http.send(HttpRequest.newBuilder(url).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertThat(bytes.statusCode()).isEqualTo(200);assertThat(bytes.body()).isEqualTo(new byte[]{9,8,7,6,5});
        }
        assertThat(com.things.link.shared.tenant.TenantContext.current()).isEmpty();
        var result=responsePublisher.publish(sending.route(),sending.canonical(),sending.expiresAt(),Duration.ofSeconds(5));
        assertThat(result.outcome()).isEqualTo(com.things.link.ota.application.OtaDownloadResponsePublisher.Outcome.BROKER_ACCEPTED);
        assertThat(authorizations.complete(sending.transport().id(),sending.transport().reservationToken(),result)).isTrue();
        assertThat(authorization(run)).containsEntry("status","BROKER_ACCEPTED");
        assertThat(authorizations.complete(sending.transport().id(),sending.transport().reservationToken(),result)).isFalse();
        var envelope=JSON.readTree(LAST_BODY.get());
        assertThat(envelope.path("topic").asText()).isEqualTo(sending.route().internalTopic(
                "tc/v1/"+sending.projectKey()+"/"+sending.deviceKey()+"/down/ota/download/response"));
        assertThat(java.util.Base64.getDecoder().decode(envelope.path("payload").asText())).isEqualTo(sending.canonical());
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.download.observed'",Long.class,sending.transport().id())).isEqualTo(1);
    }

    /** 失败退避后只解密原封存内容，不能再签址或延长地址与阶段期限。 */
    @Test void retriesIdenticalSealedResponseAndExpiry() throws Exception {
        Running run=acceptedRequest();var signed=signing();assertThat(authorizations.seal(signed.claim().authorizationId(),signed.claim().leaseToken(),presign(signed))).isTrue();
        var fixture=run.prepared().fixture();
        owner().update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,config_version) VALUES(?,?,?,'MQTT',7)",
                run.device(),fixture.tenantId(),fixture.projectId());
        var sendClaim=claimSealed(signed);
        var first=authorizations.prepareSend(sendClaim.authorizationId(),sendClaim.leaseToken()).orElseThrow();var before=job(run);
        assertThat(first.route().configVersion()).isEqualTo(7);
        owner().update("UPDATE dev_access_binding SET config_version=8 WHERE device_id=?",run.device());
        assertThat(first.route().internalTopic("tc/v1/"+first.projectKey()+"/"+first.deviceKey()+"/down/ota/download/response"))
                .startsWith("tc/private/device/"+run.device()+"/7/");
        assertThat(authorizations.complete(first.transport().id(),first.transport().reservationToken(),rejected())).isTrue();
        var next=awaitClaim();assertThat(authorizations.prepareSigning(next.authorizationId(),next.leaseToken())).isEmpty();
        var retry=authorizations.prepareSend(next.authorizationId(),next.leaseToken()).orElseThrow();
        assertThat(retry.route().configVersion()).isEqualTo(8);
        assertThat(retry.canonical()).isEqualTo(first.canonical());assertThat(retry.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(retry.transport().transportNo()).isEqualTo(2);assertThat(job(run)).usingRecursiveComparison().isEqualTo(before);
        assertThat(authorizations.complete(retry.transport().id(),retry.transport().reservationToken(),accepted())).isTrue();
    }

    /** 签址封存后配置失效仍拒绝新发送，不解封到物理网络且不消费传输次数。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"DISABLED","HTTP","COAP","TCP"})
    void rejectsConfigurationAfterSealingBeforeTransportReservation(String mode) throws Exception {
        Running run=acceptedRequest();var signed=signing();
        assertThat(authorizations.seal(signed.claim().authorizationId(),signed.claim().leaseToken(),presign(signed))).isTrue();
        var sendClaim=claimSealed(signed);var fixture=run.prepared().fixture();
        owner().update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,enabled) VALUES(?,?,?,?,?)",
                run.device(),fixture.tenantId(),fixture.projectId(),"DISABLED".equals(mode)?"MQTT":mode,!"DISABLED".equals(mode));
        assertThat(authorizations.prepareSend(sendClaim.authorizationId(),sendClaim.leaseToken())).isEmpty();
        assertThat(authorization(run)).containsEntry("transport_count",0);
        assertThat(LAST_BODY.get()).isNull();
    }

    /** 设备实际写锁导致事务失败，不能转换为发送成功，释放后原能力仍可使用。 */
    @Test void routeDatabaseFailurePreservesSealedAuthorization() throws Exception {
        Running run=acceptedRequest();var signed=signing();
        assertThat(authorizations.seal(signed.claim().authorizationId(),signed.claim().leaseToken(),presign(signed))).isTrue();
        var sendClaim=claimSealed(signed);
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM dev_device WHERE id=? FOR UPDATE")) {
                lock.setObject(1,run.device());try(var rows=lock.executeQuery()){assertThat(rows.next()).isTrue();}
            }
            assertThatThrownBy(()->authorizations.prepareSend(sendClaim.authorizationId(),sendClaim.leaseToken()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(authorization(run)).containsEntry("status","SEALED").containsEntry("transport_count",0);
            connection.rollback();
        }
        assertThat(authorizations.prepareSend(sendClaim.authorizationId(),sendClaim.leaseToken()).orElseThrow().route().configVersion()).isZero();
    }

    /**
     * D-161 正向：同一尝试的原冻结窗口内，设备重投被接纳为幂等重投，平台重新签址、重新封存并消耗第二次传输，
     * 而授权身份、申请身份、封存时刻、响应窗口与作业阶段事实全部不变。
     */
    @Test void reissuesSameIdentityWithinFrozenResponseWindow() throws Exception {
        Running run=acceptedRequest();
        var first=signing();
        URI firstUrl=presign(first);
        assertThat(authorizations.seal(first.claim().authorizationId(),first.claim().leaseToken(),firstUrl)).isTrue();
        var firstSendClaim=claimSealed(first);
        var firstSend=authorizations.prepareSend(firstSendClaim.authorizationId(),firstSendClaim.leaseToken()).orElseThrow();
        assertThat(authorizations.complete(firstSend.transport().id(),firstSend.transport().reservationToken(),accepted())).isTrue();
        Map<String,Object> acceptedOnce=authorization(run);
        UUID authorizationId=(UUID)acceptedOnce.get("id");
        UUID jobId=(UUID)job(run).get("id");
        UUID originalRequestId=owner().queryForObject("SELECT request_id FROM ota_download_request WHERE campaign_id=?",UUID.class,run.campaign());
        java.time.Instant frozenExpiry=((java.sql.Timestamp)acceptedOnce.get("response_expires_at")).toInstant();
        java.time.Instant sealedAt=((java.sql.Timestamp)acceptedOnce.get("sealed_at")).toInstant();

        // 设备重投同一尝试：只登记幂等标记，不新建申请/授权行，不重置身份或计数器。
        assertThat(accept(run,request(run,Uuid7.generate(),1,null)).name()).as("窗口内的重投必须被接纳为重投申请")
                .isEqualTo("REISSUE_REQUESTED");
        assertThat(requestCount(run)).as("重投绝不放大成第二条申请事实").isEqualTo(1L);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_authorization WHERE campaign_id=?",Long.class,run.campaign()))
                .as("重投绝不新建第二条授权").isEqualTo(1L);
        Map<String,Object> marked=authorization(run);
        assertThat(marked).containsEntry("status","BROKER_ACCEPTED");
        assertThat(marked.get("reissue_requested_at")).as("重投标记必须耐久登记").isNotNull();
        assertThat(((Number)marked.get("transport_count")).intValue()).as("重投登记不消耗传输预算").isEqualTo(1);
        assertThat(((java.sql.Timestamp)marked.get("response_expires_at")).toInstant()).as("重投登记不延长响应窗口")
                .isEqualTo(frozenExpiry);
        // 同一尝试的重复重投只做幂等确认，不再写一次标记。
        assertThat(accept(run,request(run,Uuid7.generate(),1,null)).name()).isEqualTo("REISSUE_CONFIRMED");
        assertThat(((Number)authorization(run).get("revision")).longValue())
                .as("幂等确认不得再产生第二次写入").isEqualTo(((Number)marked.get("revision")).longValue());

        // 后台按生产顺序重投：领取→重签址→真实MinIO预签名→再封存→发送预留→真实观察。
        var reissueClaim=awaitClaim();
        assertThat(reissueClaim.authorizationId()).isEqualTo(authorizationId);
        assertThat(reissueClaim.status()).as("重投必须由已接受授权领取").isEqualTo("BROKER_ACCEPTED");
        var reissueSigning=authorizations.prepareSigning(authorizationId,reissueClaim.leaseToken()).orElseThrow();
        assertThat(reissueSigning.expiresAt()).as("重投绝不延长原冻结响应窗口").isEqualTo(frozenExpiry);
        Map<String,Object> signing=authorization(run);
        assertThat(signing).containsEntry("status","SIGNING");
        assertThat(signing.get("reissue_requested_at")).as("第一次重投签址必须消费标记").isNull();
        assertThat(signing.get("reissue_signed_at")).as("第二次签址必须留下耐久证据").isNotNull();
        assertThat(signing.get("signing_reserved_at")).as("重投不新建签址预算，原预留时刻不变")
                .isEqualTo(acceptedOnce.get("signing_reserved_at"));
        URI secondUrl=presign(reissueSigning);
        // 重投刻意不移动冻结期限，因此同一对象版本的预签名地址可能与首次逐字节相同（MinIO按
        // 对象版本+到期秒确定性签名）。真正的要求是「发出去的那一刻仍然可用」，不是字节不同：
        // 过期窗口在登记前就被拒绝，所以这里只断言新地址在真实存储上真的能取到目标字节。
        assertThat(secondUrl).as("重投必须给出当时仍然可用的短期地址").isNotNull();
        assertThat(authorizations.seal(authorizationId,reissueClaim.leaseToken(),secondUrl)).isTrue();
        try(var http=HttpClient.newHttpClient()){
            var fetched=http.send(HttpRequest.newBuilder(secondUrl).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertThat(fetched.statusCode()).as("重投响应内嵌地址必须仍然可用").isEqualTo(200);
            assertThat(fetched.body()).isEqualTo(new byte[]{9,8,7,6,5});
        }
        assertThat(((java.sql.Timestamp)authorization(run).get("sealed_at")).toInstant())
                .as("重投再封存必须保留原封存时刻").isEqualTo(sealedAt);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? AND to_status='DOWNLOADING'",
                Long.class,jobId)).as("DOWNLOADING阶段只允许进入一次").isEqualTo(1L);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_authorization_outbox WHERE authorization_id=?",
                Long.class,authorizationId)).as("重投不得新增出箱事实").isEqualTo(1L);
        var secondSendClaim=claimSealed(reissueSigning);
        var secondSend=authorizations.prepareSend(secondSendClaim.authorizationId(),secondSendClaim.leaseToken()).orElseThrow();
        assertThat(secondSend.transport().transportNo()).as("重投消耗下一次传输预算").isEqualTo(2);
        assertThat(secondSend.expiresAt()).as("第二次传输仍绑原冻结窗口").isEqualTo(frozenExpiry);
        var reissued=new com.things.link.ota.application.OtaDownloadResponseCodec(true).decode(secondSend.canonical());
        assertThat(reissued.downloadUrl()).isEqualTo(secondUrl);
        assertThat(reissued.authorizationId()).as("重投响应必须保持授权身份不变").isEqualTo(authorizationId);
        assertThat(reissued.requestId()).as("重投响应必须保持申请身份不变").isEqualTo(originalRequestId);
        assertThat(reissued.jobId()).isEqualTo(jobId);
        assertThat(reissued.attemptNo()).isEqualTo(1);
        assertThat(reissued.expiresAt()).isEqualTo(frozenExpiry);
        assertThat(authorizations.complete(secondSend.transport().id(),secondSend.transport().reservationToken(),accepted())).isTrue();
        Map<String,Object> reaccepted=authorization(run);
        assertThat(reaccepted).containsEntry("status","BROKER_ACCEPTED");
        assertThat(((Number)reaccepted.get("transport_count")).intValue()).isEqualTo(2);
        assertThat(reaccepted.get("id")).isEqualTo(authorizationId);
        assertThat(((Number)reaccepted.get("revision")).longValue()).isGreaterThan(((Number)marked.get("revision")).longValue());
    }

    /** D-161 负向：活动已暂停时，重投申请仍然沿用今天的永久拒绝分类且不留下标记。 */
    @Test void refusesReissueWhileCampaignPaused() throws Exception {
        Running run=acceptedRequest();
        completeFirstDelivery(run);
        ok(send(run.prepared().fixture(),"POST",runtime(run)+"/pauses",key(),
                reason(execution(run).path("stateVersion").asText(),"停止下载秘密传输"),false),200);
        assertInvalid(run,request(run,Uuid7.generate(),1,null));
        assertThat(authorization(run).get("reissue_requested_at")).as("被拒绝的重投不得留下标记").isNull();
        assertThat(requestCount(run)).isEqualTo(1L);
    }

    /** D-161 负向：设备当前凭据代际已轮换时，重投申请仍然沿用今天的永久拒绝分类。 */
    @Test void refusesReissueAfterCredentialRotation() throws Exception {
        Running run=acceptedRequest();
        completeFirstDelivery(run);
        owner().update("UPDATE dev_device SET credential_version=2 WHERE id=?",run.device());
        assertInvalid(run,request(run,Uuid7.generate(),1,null));
        assertThat(authorization(run).get("reissue_requested_at")).as("资格不符的重投不得留下标记").isNull();
        assertThat(requestCount(run)).isEqualTo(1L);
    }

    /**
     * D-161 负向：传输预算已耗尽时，重投申请仍然沿用今天的永久拒绝分类。
     *
     * <p>计数器由夹具在关闭用户触发器的事务里置位，只构造条件、不放宽任何守卫；真实耗尽路径会先进入
     * terminal {@code EXHAUSTED}，同样不在可重投集合内。</p>
     */
    @Test void refusesReissueWhenTransportBudgetExhausted() throws Exception {
        Running run=acceptedRequest();
        completeFirstDelivery(run);
        UUID authorizationId=(UUID)authorization(run).get("id");
        fixture("UPDATE ota_download_authorization SET transport_count=3 WHERE id=?",authorizationId);
        assertInvalid(run,request(run,Uuid7.generate(),1,null));
        assertThat(authorization(run).get("reissue_requested_at")).as("预算耗尽的重投不得留下标记").isNull();
        assertThat(requestCount(run)).isEqualTo(1L);
    }

    /**
     * D-161 负向：原冻结响应窗口已经不足以完成一次重签址与发送时，重复申请仍然永久拒绝。
     *
     * <p>本用例刻意真实等待窗口余量耗尽，而不是改写任何期限字段：判定所依据的正是生产代码里的同一个
     * 余量条件（窗口必须还够一次重签址与发送）。</p>
     */
    @Test void refusesReissueAfterFrozenResponseWindowCloses() throws Exception {
        dispatchedSeconds=40;
        Running run=acceptedRequest();
        completeFirstDelivery(run);
        UUID authorizationId=(UUID)authorization(run).get("id");
        java.time.Instant frozenExpiry=((java.sql.Timestamp)authorization(run).get("response_expires_at")).toInstant();
        awaitUntil(()->!java.time.Instant.now().plusSeconds(16).isBefore(frozenExpiry),60);
        assertInvalid(run,request(run,Uuid7.generate(),1,null));
        Map<String,Object> after=authorization(run);
        assertThat(after).containsEntry("status","BROKER_ACCEPTED");
        assertThat(after.get("reissue_requested_at")).as("窗口关闭后不得登记重投标记").isNull();
        assertThat(((Number)after.get("transport_count")).intValue()).isEqualTo(1);
        assertThat(after.get("id")).isEqualTo(authorizationId);
        assertThat(requestCount(run)).isEqualTo(1L);
    }

    /** D-161：按生产顺序完成一次真实首次交付并落到BROKER_ACCEPTED（只记录真实观察，不伪造第二轮）。 */
    private void completeFirstDelivery(Running run) throws Exception {
        var signed=signing();
        assertThat(authorizations.seal(signed.claim().authorizationId(),signed.claim().leaseToken(),presign(signed))).isTrue();
        var sendClaim=claimSealed(signed);
        var sending=authorizations.prepareSend(sendClaim.authorizationId(),sendClaim.leaseToken()).orElseThrow();
        assertThat(authorizations.complete(sending.transport().id(),sending.transport().reservationToken(),accepted())).isTrue();
        assertThat(authorization(run)).containsEntry("status","BROKER_ACCEPTED");
    }

    /** 有界等待一个真实时间条件成立；超时以断言失败而不是静默通过。 */
    private static void awaitUntil(java.util.function.BooleanSupplier condition,long budgetSeconds) throws InterruptedException {
        java.time.Instant deadline=java.time.Instant.now().plusSeconds(budgetSeconds);
        while(java.time.Instant.now().isBefore(deadline)){
            if(condition.getAsBoolean()) return;
            Thread.sleep(200L);
        }
        throw new AssertionError("等待真实时间条件超时，预算="+budgetSeconds+"秒");
    }

    /** owner在关闭用户触发器的事务内构造夹具状态；只跳过夹具触发器，不修改任何CHECK。 */
    private static void fixture(String sql,Object...args){
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)).executeWithoutResult(status->{
            var jdbc=new JdbcTemplate(source);
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update(sql,args);
        });
    }

    /** 人工暂停立即撤销发送租约，迟到接受只能保留观察，不能恢复授权头。 */
    @Test void pauseRevokesSendingCapabilityAndLateReceiptCannotSettle() throws Exception {
        Running run=acceptedRequest();var signed=signing();assertThat(authorizations.seal(signed.claim().authorizationId(),signed.claim().leaseToken(),presign(signed))).isTrue();
        var sendClaim=claimSealed(signed);
        var sending=authorizations.prepareSend(sendClaim.authorizationId(),sendClaim.leaseToken()).orElseThrow();
        ok(send(run.prepared().fixture(),"POST",runtime(run)+"/pauses",key(),reason(execution(run).path("stateVersion").asText(),"停止下载秘密传输"),false),200);
        assertThat(authorizations.prepareSend(sendClaim.authorizationId(),sendClaim.leaseToken())).isEmpty();
        assertThat(authorizations.complete(sending.transport().id(),sending.transport().reservationToken(),accepted())).isTrue();
        assertThat(authorization(run).get("status")).isNotEqualTo("BROKER_ACCEPTED");
        assertThat(execution(run).path("pauseKind").asText()).isEqualTo("MANUAL");assertThat(LAST_BODY.get()).isNull();
    }

    /** 签址后凭据轮换，真实当前资格拒绝封存和发送，地址不进入数据库。 */
    @Test void credentialRotationDiscardsUnsignedDeliveryCapability() throws Exception {
        Running run=acceptedRequest();var signed=signing();URI url=presign(signed);
        owner().update("UPDATE dev_device SET credential_version=2 WHERE id=?",run.device());
        assertThat(authorizations.seal(signed.claim().authorizationId(),signed.claim().leaseToken(),url)).isFalse();
        assertThat(authorization(run).get("ciphertext")).isNull();assertThat(job(run)).containsEntry("status","DISPATCHED");
        assertThat(authorizations.prepareSend(signed.claim().authorizationId(),signed.claim().leaseToken())).isEmpty();assertThat(LAST_BODY.get()).isNull();
    }

    /** 发布物撤销立即使已封存响应失去发送资格，不删除已采用对象。 */
    @Test void revokedReleaseCannotSendSealedResponse() throws Exception {
        Running run=acceptedRequest();var signed=signing();assertThat(authorizations.seal(signed.claim().authorizationId(),signed.claim().leaseToken(),presign(signed))).isTrue();
        var sendClaim=claimSealed(signed);
        Fixture f=run.prepared().fixture();ok(send(f,"POST","/api/v1/projects/"+f.projectId()+"/ota/firmwares/"+run.prepared().firmware()+"/revocations",key(),reason("2","停止已授权漏洞版本"),false),200);
        assertThat(authorizations.prepareSend(sendClaim.authorizationId(),sendClaim.leaseToken())).isEmpty();
        assertThat(LAST_BODY.get()).isNull();assertObjectExists(run.prepared().upload());
    }

    /** 原始签址租约失效后不能采纳迟到地址，也不能用管理账号或随机能力接管。 */
    @Test void rejectsExpiredLeaseAndInheritedAccount() throws Exception {
        Running run=acceptedRequest();var signed=signing();URI url=presign(signed);
        assertThat(authorizations.prepareSigning(signed.claim().authorizationId(),Uuid7.generate())).isEmpty();
        assertThat(authorizations.prepareSend(Uuid7.generate(),signed.claim().leaseToken())).isEmpty();
        assertThatThrownBy(()->scoped(run.prepared().fixture(),authorizations::claimOne)).isInstanceOf(IllegalStateException.class).hasMessage("OTA下载授权不能继承管理账号");
        owner().update("UPDATE ota_download_authorization SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",signed.claim().authorizationId());
        assertThat(authorizations.seal(signed.claim().authorizationId(),signed.claim().leaseToken(),url)).isFalse();
        assertThat(authorization(run).get("ciphertext")).isNull();assertThat(LAST_BODY.get()).isNull();
    }

    /** 普通APP连接受真实项目RLS约束，后台能力不能由其他scope直接查询代替。 */
    @Test void projectRlsHidesAuthorizationFromForeignScope() throws Exception {
        Running run=acceptedRequest();Fixture f=run.prepared().fixture();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThat(OtaDownloadAuthorizationIntegrationTests.<Long>scoped(f,()->transaction.execute(status->jdbc.queryForObject("SELECT count(*) FROM ota_download_authorization WHERE campaign_id=?",Long.class,run.campaign())))).isEqualTo(1L);
        Long hidden=com.things.link.support.tenant.ScopedTenantWork.call(new com.things.link.shared.tenant.TenantScope(f.tenantId(),Uuid7.generate(),f.accountId()),
                ()->transaction.execute(status->jdbc.queryForObject("SELECT count(*) FROM ota_download_authorization WHERE campaign_id=?",Long.class,run.campaign())));
        assertThat(hidden).isZero();assertThat(com.things.link.shared.tenant.TenantContext.current()).isEmpty();
        assertThat(authorizations.claimOne()).isPresent();
    }

    /** 缺失独立密钥配置必须发生于签址额度预留之前。 */
    @Test void missingCipherDoesNotReserveSigningBudget() throws Exception {
        Running run=acceptedRequest();org.mockito.Mockito.doReturn(false).when(responseCipher).configured();var claim=authorizations.claimOne().orElseThrow();
        assertThat(authorizations.prepareSigning(claim.authorizationId(),claim.leaseToken())).isEmpty();
        assertThat(authorization(run).get("signing_reserved_at")).isNull();assertThat(authorization(run).get("ciphertext")).isNull();
        assertThat(execution(run).path("pauseKind").asText()).isEqualTo("SECURITY");assertThat(LAST_BODY.get()).isNull();
    }

    /** 已预留的一次签址未知永久保守收束，不重新签址或释放占位。 */
    @Test void unknownSigningCannotBeRepeated() throws Exception {
        Running run=acceptedRequest();var signed=signing();
        assertThat(authorizations.signingUnknown(signed.claim().authorizationId(),signed.claim().leaseToken(),"不能持久的供应商秘密")).isTrue();
        assertThat(authorization(run)).containsEntry("status","UNKNOWN");assertThat(authorization(run).get("reason")).isNotEqualTo("不能持久的供应商秘密");
        assertThat(authorizations.prepareSigning(signed.claim().authorizationId(),signed.claim().leaseToken())).isEmpty();
        assertThat(authorizations.prepareSend(signed.claim().authorizationId(),signed.claim().leaseToken())).isEmpty();
        assertThat(authorization(run).get("slot_retain_until")).isEqualTo(java.sql.Timestamp.from(signed.claim().slotRetainUntil()));
        var before=authorization(run);
        error(send(run.prepared().fixture(),"POST",runtime(run)+"/resumptions",key(),
                reason(execution(run).path("stateVersion").asText(),"未知签址不能解除责任"),false),409,70040);
        assertThat(authorization(run)).usingRecursiveComparison().isEqualTo(before);
        assertThat(execution(run).path("status").asText()).isEqualTo("PAUSED");
    }

    /** 签址预留、封存和回执的真实审计失败均回滚同事务事实，不吞首因。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SIGNING","SEAL","OBSERVE"})
    void auditFailureRollsBackAuthorizationTransaction(String stage) throws Exception {
        Running run=acceptedRequest();var claim=authorizations.claimOne().orElseThrow();
        var signed="SIGNING".equals(stage)?null:authorizations.prepareSigning(claim.authorizationId(),claim.leaseToken()).orElseThrow();
        URI url=signed==null?null:presign(signed);
        if("OBSERVE".equals(stage))assertThat(authorizations.seal(claim.authorizationId(),claim.leaseToken(),url)).isTrue();
        var sendClaim="OBSERVE".equals(stage)?claimSealed(signed):null;
        var sending=sendClaim==null?null:authorizations.prepareSend(sendClaim.authorizationId(),sendClaim.leaseToken()).orElseThrow();
        var before=authorization(run);var jobBefore=job(run);
        long outboxBefore=owner().queryForObject("SELECT count(*) FROM ota_download_authorization_outbox WHERE project_id=?",Long.class,run.prepared().fixture().projectId());
        org.mockito.Mockito.doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("测试授权审计写后失败");}).when(audit).record(org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(()->{
            if("SIGNING".equals(stage))authorizations.prepareSigning(claim.authorizationId(),claim.leaseToken());
            else if("SEAL".equals(stage))authorizations.seal(claim.authorizationId(),claim.leaseToken(),url);
            else authorizations.complete(sending.transport().id(),sending.transport().reservationToken(),accepted());
        }).isInstanceOf(IllegalStateException.class).hasMessage("测试授权审计写后失败");
        assertThat(authorization(run)).usingRecursiveComparison().isEqualTo(before);assertThat(job(run)).usingRecursiveComparison().isEqualTo(jobBefore);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_authorization_outbox WHERE project_id=?",Long.class,run.prepared().fixture().projectId())).isEqualTo(outboxBefore);
        org.mockito.Mockito.doCallRealMethod().when(audit).record(org.mockito.ArgumentMatchers.any());
    }

    /** 未签发授权的真实窗口耗尽衔接业务重试，提交前审计失败回滚所有失败事实。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void unissuedWindowExhaustionRetriesAtomically(boolean auditFailure) throws Exception {
        dispatchedSeconds = 15;
        Running run = acceptedRequest();
        var claim = authorizations.claimOne().orElseThrow();
        var before = job(run);
        if (auditFailure) {
            org.mockito.Mockito.doAnswer(invocation -> {
                invocation.callRealMethod();
                com.things.link.support.audit.AuditLogEntry entry = invocation.getArgument(0);
                if ("ota.retry.failure_observed".equals(entry.action())) throw new IllegalStateException("测试业务重试审计失败");
                return null;
            }).when(audit).record(org.mockito.ArgumentMatchers.any());
            assertThatThrownBy(() -> authorizations.prepareSigning(claim.authorizationId(), claim.leaseToken()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("测试业务重试审计失败");
            assertThat(job(run)).usingRecursiveComparison().isEqualTo(before);
            assertThat(authorization(run)).containsEntry("status", "WAITING");
            org.mockito.Mockito.doCallRealMethod().when(audit).record(org.mockito.ArgumentMatchers.any());
        }
        assertThat(authorizations.prepareSigning(claim.authorizationId(), claim.leaseToken())).isEmpty();
        assertThat(authorization(run)).containsEntry("status", "EXHAUSTED");
        assertThat(authorization(run).get("signing_reserved_at")).isNull();
        assertThat(authorization(run).get("sealed_at")).isNull();
        assertThat(job(run)).containsEntry("status", "RETRY_WAIT").containsEntry("attempt_no", 1)
                .containsEntry("failure_code", "DOWNLOAD_TRANSIENT_FAILURE");
        assertThat(job(run).get("id")).isEqualTo(before.get("id"));
        assertThat(execution(run).path("status").asText()).isEqualTo("RUNNING");
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.retry.failure_observed'",
                Integer.class, before.get("id"))).isEqualTo(1);
        assertThat(LAST_BODY.get()).isNull();
        assertThat(notifications.claimOne()).isEmpty();
        if (!auditFailure) {
            var worker = new com.things.link.ota.application.OtaBusinessRetryWorker(retries, true);
            worker.start();
            try {
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> {
                    worker.tick(); assertThat(job(run)).containsEntry("attempt_no", 2);
                });
            } finally { worker.stop(); }
            assertThat(notifications.claimOne().orElseThrow().jobAttemptNo()).isEqualTo(2);
            assertThat(authorizations.prepareSigning(claim.authorizationId(), claim.leaseToken())).isEmpty();
            assertThat(execution(run).path("status").asText()).isEqualTo("RUNNING");
        }
    }

    /** 原申请自然过期由prepareSigning直接收束，不进入签址或传输。 */
    @Test void expiredUnsignedRequestProducesCompletionWithoutSigning() throws Exception {
        dispatchedSeconds=15; downloadRetries=0;
        var run=acceptedRequest();
        var deadline=((java.sql.Timestamp)job(run).get("deadline_at")).toInstant();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(()->java.time.Instant.now().isAfter(deadline));
        var claim=authorizations.claimOne().orElseThrow();
        assertThat(authorizations.prepareSigning(claim.authorizationId(),claim.leaseToken())).isEmpty();
        assertThat(job(run)).containsEntry("status","TIMED_OUT");
        assertThat(authorization(run)).containsEntry("status","EXHAUSTED").containsEntry("signing_reserved_at",null);
        OtaCompletionSourceAssertions.verify(owner(),run.prepared().fixture().projectId());
    }

    /** 最后一次未签址窗口耗尽真实进入封闭终态，审计失败不能留下完成记录。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void finalUnissuedDownloadFailureProducesOneCompletion(boolean auditFailure) throws Exception {
        dispatchedSeconds=15; downloadRetries=0;
        var run=acceptedRequest(); var claim=authorizations.claimOne().orElseThrow(); var before=job(run);
        if(auditFailure) {
            org.mockito.Mockito.doAnswer(invocation->{
                invocation.callRealMethod();
                com.things.link.support.audit.AuditLogEntry entry=invocation.getArgument(0);
                if("ota.retry.failure_observed".equals(entry.action())) throw new IllegalStateException("完成来源审计回滚");
                return null;
            }).when(audit).record(org.mockito.ArgumentMatchers.any());
            try {
                assertThatThrownBy(()->authorizations.prepareSigning(claim.authorizationId(),claim.leaseToken()))
                        .hasMessage("完成来源审计回滚");
            } finally { org.mockito.Mockito.doCallRealMethod().when(audit).record(org.mockito.ArgumentMatchers.any()); }
            assertThat(job(run)).usingRecursiveComparison().isEqualTo(before);
            OtaCompletionSourceAssertions.verify(owner(),run.prepared().fixture().projectId());
        }
        assertThat(authorizations.prepareSigning(claim.authorizationId(),claim.leaseToken())).isEmpty();
        assertThat(job(run)).containsEntry("status","TIMED_OUT").containsEntry("failure_code","DOWNLOAD_TRANSIENT_FAILURE");
        assertThat(authorization(run)).containsEntry("status","EXHAUSTED").containsEntry("signing_reserved_at",null);
        OtaCompletionSourceAssertions.verify(owner(),run.prepared().fixture().projectId());
        var original=OtaCompletionSourceAssertions.sources(owner(),run.prepared().fixture().projectId());
        assertThat(authorizations.prepareSigning(claim.authorizationId(),claim.leaseToken())).isEmpty();
        assertThat(OtaCompletionSourceAssertions.sources(owner(),run.prepared().fixture().projectId())).containsExactlyElementsOf(original);
    }

    /** 全业务前置再接纳不可变设备下载申请，授权头由数据库触发器创建。 */
    private Running acceptedRequest() throws Exception {Running run=dispatched();assertThat(accept(run,request(run,Uuid7.generate(),1,null)).name()).isEqualTo("ACCEPTED");return run;}
    /** 原通知与业务调度真实事务，供后续尝试恢复验收。 */
    @Autowired private com.things.link.ota.application.OtaNotificationService notifications;
    @Autowired private com.things.link.ota.application.OtaBusinessRetryService retries;

    /** 通知失败后的第二次尝试仍可签发和封存真实下载授权；历史失败不能伪造成新失败。 */
    @Test void retriedAttemptCanSealAndSendAuthorization() throws Exception {
        Running run = dispatched();
        UUID jobId = (UUID) job(run).get("id");
        for (int index = 1; index <= 3; index++) {
            var claim = notifications.claimOne().orElseThrow();
            var sending = notifications.prepare(claim.eventId(), claim.leaseToken()).orElseThrow();
            assertThat(notifications.complete(sending.transport().id(), sending.transport().reservationToken(),
                    new com.things.link.ota.application.OtaNotificationPublisher.Result(
                            com.things.link.ota.application.OtaNotificationPublisher.Outcome.REJECTED, 503, "HTTP_REJECTED"))).isTrue();
            if (index < 3) owner().update("UPDATE ota_notification_delivery SET next_attempt_at=clock_timestamp(),updated_at=clock_timestamp(),revision=revision+1 WHERE event_id=? AND status='RETRY_WAIT'", claim.eventId());
        }
        var worker = new com.things.link.ota.application.OtaBusinessRetryWorker(retries, true);
        worker.start();
        try {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> {
                worker.tick(); assertThat(job(run)).containsEntry("attempt_no", 2).containsEntry("status", "DISPATCHED");
            });
        } finally { worker.stop(); }
        assertThat(accept(run, request(run, Uuid7.generate(), 2, null)).name()).isEqualTo("ACCEPTED");
        var signed = signing();
        assertThat(authorizations.seal(signed.claim().authorizationId(), signed.claim().leaseToken(), presign(signed))).isTrue();
        assertThat(job(run)).containsEntry("id", jobId).containsEntry("attempt_no", 2).containsEntry("status", "DOWNLOADING");
        var claim = claimSealed(signed);
        var sending = authorizations.prepareSend(claim.authorizationId(), claim.leaseToken()).orElseThrow();
        var result = responsePublisher.publish(sending.route(), sending.canonical(), sending.expiresAt(), Duration.ofSeconds(5));
        assertThat(authorizations.complete(sending.transport().id(), sending.transport().reservationToken(), result)).isTrue();
        assertThat(authorization(run)).containsEntry("status", "BROKER_ACCEPTED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? AND to_status='RETRY_WAIT'", Integer.class, jobId)).isEqualTo(1);
    }

    /** 真实可信租约和完整资格预留。 */
    private com.things.link.ota.application.OtaDownloadAuthorizationService.Signing signing(){var claim=authorizations.claimOne().orElseThrow();return authorizations.prepareSigning(claim.authorizationId(),claim.leaseToken()).orElseThrow();}
    /** 封存撤销原签址能力；发送必须领取独立SEALED租约且旧能力明确无效。 */
    private com.things.link.ota.domain.OtaDownloadAuthorizationRepository.Claim claimSealed(
            com.things.link.ota.application.OtaDownloadAuthorizationService.Signing signed){
        assertThat(authorizations.prepareSend(signed.claim().authorizationId(),signed.claim().leaseToken())).isEmpty();
        var claim=authorizations.claimOne().orElseThrow();assertThat(claim.status()).isEqualTo("SEALED");
        assertThat(claim.authorizationId()).isEqualTo(signed.claim().authorizationId());
        assertThat(claim.leaseToken()).isNotEqualTo(signed.claim().leaseToken());return claim;
    }
    /** 固定版本真实签址严格在数据库事务之外，不更新签址期限。 */
    private URI presign(com.things.link.ota.application.OtaDownloadAuthorizationService.Signing signed){
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return storage.presignGet(signed.version(),Duration.ofSeconds(signed.expiresAt().getEpochSecond()-java.time.Instant.now().getEpochSecond()),new com.things.link.support.storage.VersionedStorageControl(Duration.ofSeconds(5),()->false));
    }
    /** 只等待真实首次5秒退避，不修改生产期限、修订或预算。 */
    private com.things.link.ota.domain.OtaDownloadAuthorizationRepository.Claim awaitClaim(){
        var found=new java.util.concurrent.atomic.AtomicReference<com.things.link.ota.domain.OtaDownloadAuthorizationRepository.Claim>();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(()->{var value=authorizations.claimOne();value.ifPresent(found::set);return value.isPresent();});return found.get();
    }
    /** owner仅观察本活动持久授权事实。 */
    private static java.util.Map<String,Object> authorization(Running run){return owner().queryForMap("SELECT * FROM ota_download_authorization WHERE campaign_id=?",run.campaign());}
    /** 接收端HTTP接受不是设备接收证明。 */
    private static com.things.link.ota.application.OtaDownloadResponsePublisher.Result accepted(){return new com.things.link.ota.application.OtaDownloadResponsePublisher.Result(com.things.link.ota.application.OtaDownloadResponsePublisher.Outcome.BROKER_ACCEPTED,202,"HTTP_ACCEPTED");}
    /** 固定外部失败观察，不携带供应商响应正文。 */
    private static com.things.link.ota.application.OtaDownloadResponsePublisher.Result rejected(){return new com.things.link.ota.application.OtaDownloadResponsePublisher.Result(com.things.link.ota.application.OtaDownloadResponsePublisher.Outcome.REJECTED,503,"HTTP_REJECTED");}

    /** 持久业务结果来自真实数据面事务代理。 */
    private com.things.link.ota.application.OtaDownloadRequestIngestionService.Outcome accept(Running run,byte[] body){return requests.accept(identity(run.prepared().fixture(),run.device(),1),body,java.time.Instant.now());}
    /** 永久不合格精确断言，不吞基础设施异常。 */
    private void assertInvalid(Running run,byte[] body){assertThatThrownBy(()->accept(run,body)).isInstanceOf(IllegalArgumentException.class);}
    /** 只读完整作业用于断言阶段与期限不被接纳更新。 */
    private static java.util.Map<String,Object> job(Running run){return owner().queryForMap("SELECT * FROM ota_device_job WHERE campaign_id=?",run.campaign());}
    /** 不可变申请全部字段，bytea值按数组内容比较。 */
    private static java.util.Map<String,Object> receipt(Running run){return owner().queryForMap("SELECT * FROM ota_download_request WHERE campaign_id=?",run.campaign());}
    /** 当前活动接纳数量。 */
    private static long requestCount(Running run){return owner().queryForObject("SELECT count(*) FROM ota_download_request WHERE campaign_id=?",Long.class,run.campaign());}
    /** 正文五字段只用原通知非秘密身份，无认证自报字段。 */
    private static byte[] request(Running run,UUID id,int attempt,String hash){
        var outbox=owner().queryForMap("SELECT job_id,manifest_sha256 FROM ota_job_dispatch_outbox WHERE campaign_id=? ORDER BY attempt_no DESC LIMIT 1",run.campaign());
        return CANONICAL.writeObject(java.util.Map.of("contractVersion","tc-ota-download-request/v1","requestId",id.toString(),"jobId",outbox.get("job_id").toString(),"attemptNo",(long)attempt,"manifestSha256",hash==null?outbox.get("manifest_sha256"):hash));
    }

    /** 完整业务前置生成真正DISPATCHED通知意图，不直接插入delivery。 */
    private Running dispatched() throws Exception {
        Running run=running(true);var claim=admission.claimOne().orElseThrow();assertThat(admission.admit(claim.jobId(),claim.token())).isTrue();return run;
    }
    /** 完整受控前置都经真实服务，报告可选用于准确验证零分母。 */
    private Running running(boolean reported) throws Exception {
        Prepared p=published();Fixture f=p.fixture();UUID device=initialized(p);
        if(reported) reportIngestion.accept(identity(f,device,1),report(f,1,0,bundleHash(f)),java.time.Instant.now());
        UUID campaign=UUID.fromString(ok(send(f,"POST",campaigns(f),key(),plan(p.firmware(),List.of(device)),false),201).path("id").asText());
        String path=campaigns(f)+"/"+campaign;
        ok(send(f,"POST",path+"/scheduling",key(),revision("0"),false),200);
        ok(send(f,"POST",path+"/starting",key(),revision("1"),false),200);
        return new Running(p,device,campaign);
    }
    /** 当前运行事实每次真实读取并禁止HTTP缓存。 */
    private JsonNode execution(Running run) throws Exception {
        var response=send(run.prepared().fixture(),"GET",runtime(run)+"/execution",null,null,false);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");return ok(response,200);
    }
    /** 当前项目活动集合。 */
    private static String campaigns(Fixture f){return "/api/v1/projects/"+f.projectId()+"/ota/campaigns";}
    /** 运行命令和投影共享的活动身份。 */
    private static String runtime(Running run){return campaigns(run.prepared().fixture())+"/"+run.campaign();}
    /** 启动不接受隐式修订。 */
    private static byte[] revision(String value){return CANONICAL.writeObject(java.util.Map.of("expectedRevision",value));}
    /** 人工操作理由不能替代授权或安全复核。 */
    private static byte[] reason(String revision,String reason){return CANONICAL.writeObject(java.util.Map.of("expectedRevision",revision,"reason",reason));}
    /** 本例明确运行资源。
     * @param prepared 已发布固定版本
     * @param device 已冻结设备
     * @param campaign 活动身份
     */
    private record Running(Prepared prepared,UUID device,UUID campaign) { }


    /** 显式完整策略与稳定名单，旧时间表示立即可执行但本片仍不派发。 */
    private static byte[] plan(UUID firmware,List<UUID> devices) {
        var policy=new java.util.LinkedHashMap<String,Object>();
        policy.put("maxConcurrentDownloads",2L);policy.put("maxDownloadBytesPerSecond",1048576L);
        policy.put("downloadRetryLimit",downloadRetries);policy.put("retryBackoffSeconds",5L);policy.put("healthWindowSeconds",60L);
        policy.put("pauseMinEvaluated",2L);policy.put("pauseFailureCount",1L);policy.put("pauseFailureRateBps",5000L);
        policy.put("batchMinSuccessRateBps",10000L);policy.put("requireManualBatchApproval",true);
        var stages=new java.util.LinkedHashMap<String,Object>();
        for(String name:List.of("DISPATCHED","DOWNLOADING","VERIFYING","INSTALLING","REBOOTING","HEALTH_CHECKING","CONFIRMING","ROLLBACK_PENDING","ROLLING_BACK")) stages.put(name,"DISPATCHED".equals(name)?dispatchedSeconds:120L);
        policy.put("stageTimeoutSeconds",stages);
        return CANONICAL.writeObject(java.util.Map.of("contractVersion","tc-ota-campaign-plan/v1","firmwareId",firmware.toString(),
                "deviceIds",devices.stream().map(UUID::toString).toList(),"batchSize",2L,"notBefore","2026-09-12T00:00:00Z","executionPolicy",policy));
    }


    /** 声明来源只由运维配置提供，测试字段对应真实发布目标，不构造外部硬件证明。 */
    private static String baselineSource() {
        var value = new java.util.LinkedHashMap<String,Object>();
        value.put("contractVersion","tc-ota-type-baseline/v1"); value.put("tenantId",CONFIGURED.tenantId().toString());
        value.put("projectId",CONFIGURED.projectId().toString()); value.put("deviceTypeId",CONFIGURED.typeId().toString());
        value.put("productKey","product_"+CONFIGURED.typeId()); value.put("baselineVersion",1L);
        value.put("trustDomain",DOMAIN); value.put("rootFingerprint",sha(ROOT.getPublic().getEncoded()));
        value.put("hardware",java.util.Map.of("model","board-v1","boardRevisionMin",0L,"boardRevisionMax",1L));
        value.put("bootloader",java.util.Map.of("minimumVersion","1.0.0","maximumVersion","2.0.0"));
        value.put("signatureProfiles",List.of("TC_OTA_ED25519_V1")); value.put("maximumArtifactBytes",67108864L);
        value.put("availableRamBytes",1024L); value.put("availableFlashBytes",67108864L);
        value.put("supportsAbSlots",true); value.put("supportsRangeDownload",true); value.put("supportsResumeDownload",true);
        value.put("protectedSecurityCounterBits",53L); value.put("compressionAlgorithms",List.of("NONE"));
        value.put("deltaModes",List.of("NONE")); value.put("propertyProfile","TC_PROPERTY_COMPOSITE_V1");
        value.put("evidenceReference","test-fixture-only");
        return new String(CANONICAL.writeObject(java.util.Map.of("baselines",List.of(value))),StandardCharsets.UTF_8);
    }

    /** 完整合法报告正文只含运行声明，认证身份单独传入服务。 */
    private static byte[] report(Fixture fixture,long sequence,long committed,String bundleHash) {
        var value = new java.util.LinkedHashMap<String,Object>();
        value.put("contractVersion","tc-ota-device-report/v1"); value.put("reportSequence",sequence);
        value.put("hardware",java.util.Map.of("model","board-v1","boardRevision",0L));
        value.put("bootloaderVersion","1.0.0"); value.put("currentFirmwareVersion","factory-v1");
        value.put("currentFirmwareSha256","b".repeat(64)); value.put("currentSecurityVersion",committed);
        value.put("committedSecurityVersion",committed); value.put("thingModelVersionId",fixture.modelId().toString());
        value.put("thingModelSchemaDigestAlgorithm","PG_JSONB_TEXT_V1_SHA256"); value.put("thingModelSchemaDigest","a".repeat(64));
        value.put("propertyProfile","TC_PROPERTY_COMPOSITE_V1"); value.put("trustDomain",DOMAIN);
        value.put("rootFingerprint",sha(ROOT.getPublic().getEncoded())); value.put("trustBundleVersion",1L);
        value.put("trustBundleSha256",bundleHash); value.put("signatureProfiles",List.of("TC_OTA_ED25519_V1"));
        value.put("maximumArtifactBytes",67108864L); value.put("availableRamBytes",1024L); value.put("availableFlashBytes",67108864L);
        value.put("supportsAbSlots",true); value.put("supportsRangeDownload",true); value.put("supportsResumeDownload",true);
        value.put("protectedSecurityCounterBits",53L); value.put("compressionAlgorithms",List.of("NONE"));
        value.put("deltaModes",List.of("NONE")); value.put("activeSlot","A"); value.put("bootState","HEALTHY");
        return CANONICAL.writeObject(value);
    }

    /** 真实当前设备与基线，报告仍单独由认证入口接纳。 */
    private UUID initialized(Prepared prepared) {
        Fixture fixture = prepared.fixture(); UUID device = device(fixture);
        scoped(fixture,()->baselines.register(fixture.projectId(),fixture.typeId(),key(),"0")); return device;
    }
    /** 准备当前DIRECT设备绑定，绝不直接写报告。 */
    private static UUID device(Fixture fixture) {
        UUID device = Uuid7.generate();
        owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,thing_model_version_id) VALUES (?,?,?,?,?,'报告测试设备',?)",
                device,fixture.tenantId(),fixture.projectId(),fixture.typeId(),"device_"+device,fixture.modelId());
        owner().update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES (?,?,?,?,'ACCESS_TOKEN',?,'专项有效凭据')",
                Uuid7.generate(),fixture.tenantId(),fixture.projectId(),device,sha(device.toString().getBytes(StandardCharsets.UTF_8)));
        return device;
    }
    /** 可信代际来自参数身份，不进入正文。 */
    private static com.things.link.shared.message.AuthenticatedDeviceIdentity identity(Fixture fixture,UUID device,long generation) {
        return new com.things.link.shared.message.AuthenticatedDeviceIdentity(fixture.tenantId(),fixture.projectId(),device,generation);
    }
    /** owner仅观察持久事实，不代替生产接纳或资格权限。 */
    private static java.util.Map<String,Object> observed(UUID device) {
        return owner().queryForMap("SELECT credential_version,report_sequence,revision,committed_security_version,report_hash,broker_received_at,accepted_at FROM ota_device_report WHERE device_id=?",device);
    }
    /** 当前包摘要来自生产服务的受管理范围读取。 */
    private String bundleHash(Fixture fixture) { return scoped(fixture,()->trust.find(fixture.projectId(),DOMAIN).bundleSha256()); }
    /** 通过真实上传和签名完成发布。 */
    private Prepared published() throws Exception {
        Prepared prepared = prepare();
        ok(send(prepared.fixture(), "POST", prepared.path(), key(), prepared.body(), false), 202);
        publicationProcessor.process(publications.claim().orElseThrow());
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware WHERE id=?", String.class, prepared.firmware())).isEqualTo("READY");
        return prepared;
    }

    /** 建立真实发布与上传前置。 */
    private Prepared prepare() throws Exception {
        Fixture fixture = seed(ProjectRole.ADMIN);
        JsonNode firmware = ok(send(fixture, "POST", base(fixture), key(), body(fixture, "publication-v1").getBytes(StandardCharsets.UTF_8), false), 201);
        UUID firmwareId = UUID.fromString(firmware.path("id").asText());
        ok(send(fixture, "POST", "/api/v1/projects/" + fixture.projectId() + "/ota/trust-domains/" + DOMAIN + "/bundles",
                key(), trustEnvelope("0", 1, false), false), 200);
        byte[] content = new byte[] {9,8,7,6,5};
        var created = scoped(fixture, () -> uploads.create(fixture.projectId(), firmwareId, key(), content.length, sha(content)));
        var receiving = scoped(fixture, () -> uploads.prepare(fixture.projectId(), firmwareId, created.id()));
        var file = temporary.resolve(created.id() + ".bin"); java.nio.file.Files.write(file, content);
        var uploaded = scoped(fixture, () -> { try (var lease = uploadProcessor.begin(receiving)) {
            return uploadProcessor.process(receiving, file, lease, () -> false);
        }});
        assertThat(uploaded.status()).isEqualTo("VERIFIED");
        var manifest = new java.util.LinkedHashMap<String,Object>();
        manifest.put("contractVersion", "tc-ota-manifest/v1"); manifest.put("firmwareId", firmwareId.toString());
        manifest.put("firmwareVersion", "publication-v1"); manifest.put("trustDomain", DOMAIN);
        manifest.put("deviceTypeId", fixture.typeId().toString()); manifest.put("productKey", "product_" + fixture.typeId());
        manifest.put("hardware", java.util.Map.of("model", "board-v1", "boardRevisionMin", 0L, "boardRevisionMax", 1L));
        manifest.put("bootloaderMinimumVersion", "1.0.0"); manifest.put("artifactSize", (long) content.length);
        manifest.put("artifactSha256", sha(content)); manifest.put("compression", "NONE");
        manifest.put("delta", java.util.Map.of("mode", "NONE")); manifest.put("securityVersion", 1L);
        manifest.put("thingModelVersionId", fixture.modelId().toString()); manifest.put("thingModelSchemaDigestAlgorithm", "PG_JSONB_TEXT_V1_SHA256");
        manifest.put("thingModelSchemaDigest", "a".repeat(64)); manifest.put("allowedSourceThingModelVersionIds", List.of(fixture.modelId().toString()));
        manifest.put("requirements", java.util.Map.of("profile", "TC_PROPERTY_COMPOSITE_V1", "minimumRamBytes", 1L, "minimumFlashBytes", 1L,
                "requiresAbSlots", true, "requiresRangeDownload", true, "requiresProtectedSecurityCounter", true));
        manifest.put("signatureProfile", "TC_OTA_ED25519_V1"); manifest.put("signingKeyFingerprint", sha(RELEASE.getPublic().getEncoded()));
        manifest.put("minimumTrustBundleVersion", 1L);
        byte[] request = CANONICAL.writeObject(java.util.Map.of("expectedRevision", firmware.path("revision").asText(),
                "uploadSessionId", uploaded.id().toString(), "manifest", manifest));
        return new Prepared(fixture, firmwareId, uploaded, base(fixture) + "/" + firmwareId + "/publications", request);
    }
    /** 根签名包来自独立JCA，测试发布key不等于根。 */
    private static byte[] trustEnvelope(String revision, long version, boolean rotate) throws Exception {
        var keys = rotate ? List.of(trustKey(RELEASE, "release", "VERIFY_ONLY"), trustKey(ROTATED, "rotated", "ACTIVE"))
                : List.of(trustKey(RELEASE, "release", "ACTIVE"));
        var bundle = java.util.Map.<String,Object>of("contractVersion", "tc-ota-trust-bundle/v1", "trustDomain", DOMAIN, "bundleVersion", version, "keys", keys);
        var signature = java.security.Signature.getInstance("Ed25519"); signature.initSign(ROOT.getPrivate());
        signature.update("thingslink-ota-trust-bundle-v1\0".getBytes(StandardCharsets.UTF_8)); signature.update(CANONICAL.writeObject(bundle));
        return CANONICAL.writeObject(java.util.Map.of("expectedRevision", revision, "bundle", bundle, "signature", b64(signature.sign())));
    }
    /** 不可变公钥身份和有效期。 */
    private static java.util.Map<String,Object> trustKey(java.security.KeyPair pair, String version, String state) {
        return java.util.Map.of("keyVersion", version, "signatureProfile", "TC_OTA_ED25519_V1", "spki", b64(pair.getPublic().getEncoded()),
                "fingerprint", sha(pair.getPublic().getEncoded()), "state", state, "notBefore", 0L, "notAfter", pair == RELEASE ? releaseNotAfter : 253402300799L);
    }
    /** 物理对象身份仍存在，不把签名拒绝误作对象删除授权。 */
    private static void assertObjectExists(com.things.link.ota.domain.OtaUploadSession upload) throws Exception {
        try (MinioClient admin = admin()) {
            assertThat(admin.statObject(io.minio.StatObjectArgs.builder().bucket(BUCKET).object(upload.objectKey())
                    .versionId(upload.versionId()).build()).size()).isEqualTo(upload.expectedLength());
        }
    }
    /** 异步/服务调用都显式绑定本例的真实scope。 */
    private static <T> T scoped(Fixture f, java.util.function.Supplier<T> work) {
        return com.things.link.support.tenant.ScopedTenantWork.call(new com.things.link.shared.tenant.TenantScope(
                f.tenantId(), f.projectId(), f.accountId()), work);
    }
    /** 测试专属公私钥。 */
    private static java.security.KeyPair keyPair() {
        try { return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    /** 标准Base64。 */ private static String b64(byte[] bytes) { return java.util.Base64.getEncoder().encodeToString(bytes); }
    /** 完整字节SHA256。 */ private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    /** 仅测试显式装配signer，无生产默认或配置成功开关。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class SigningConfiguration {
        /** 只由本测试Import启用，生产扫描不提供本地私钥实现。 */
        @org.springframework.context.annotation.Bean TestSigner testSigner() { return new TestSigner(); }
    }
    /** 真实JCA测试签名器，回执由生产协调器验证。 */
    static class TestSigner implements com.things.link.ota.application.OtaControlledReleaseSigner {
        /** 实际签名调用次数，证明UNKNOWN和SIGNED恢复不重发。 */
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        /** 已签名未返回窗口的受控资格变更，不在签名事务内伪造状态。 */
        volatile Runnable beforeReturn = () -> { };
        /** 模拟供应商调用结果未知，不能解释为未执行。 */
        volatile boolean unknown;
        /** 注入实际签名字节损坏，以验证生产验签拒绝。 */
        volatile boolean corrupt;
        /** 使用测试私钥签真实域分离输入，再施加本例故障；不替代供应商物理取消验收。 */
        @Override public com.things.link.ota.application.OtaReleaseSigner.Response sign(
                com.things.link.ota.application.OtaReleaseSigner.Request request,
                com.things.link.ota.application.OtaSigningControl control) {
            calls.incrementAndGet();
            control.check();
            if (unknown) throw new IllegalStateException("测试供应商结果未知");
            try {
                var signature = java.security.Signature.getInstance("Ed25519"); signature.initSign(RELEASE.getPrivate());
                signature.update(request.signingInput()); byte[] signed = signature.sign();
                if (corrupt) signed[0] ^= 1;
                beforeReturn.run();
                return new com.things.link.ota.application.OtaReleaseSigner.Response(request.requestId(), request.keyVersion(),
                        request.profile(), RELEASE.getPublic().getEncoded(), signed, "test-receipt-" + request.requestId());
            } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("测试签名失败", failure); }
        }
    }
    /** 本例精确资源，不携带其他项目事实。
     * @param fixture 本例真实成员和项目
     * @param firmware 通过HTTP创建的固件身份
     * @param upload 已通过真实存储验真的上传
     * @param path 发布尝试HTTP集合路径
     * @param body 完整严格发布请求
     */
    private record Prepared(Fixture fixture, UUID firmware, com.things.link.ota.domain.OtaUploadSession upload,
                            String path, byte[] body) { }


    /** 完整HTTP/1.1往返预算，客户端关闭后不留连接线程。 */
    private HttpResponse<String> send(Fixture f, String method, String path, String key, byte[] body, boolean binary)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(45));
        if (f != null) builder.header("Authorization", "Bearer " + tokens.issue(
                new AuthenticatedPrincipal(f.accountId(), f.tenantId(), f.projectId())).value());
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", binary ? "application/octet-stream" : "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    /** 冻结真实长度和摘要。 */
    private static byte[] declaration(byte[] content) throws Exception {
        return ("{\"expectedLength\":" + content.length + ",\"expectedSha256\":\""
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }
    /** 唯一请求键。 */ private static String key() { return UUID.randomUUID().toString(); }
    /** 明确HTTP状态并保留响应首因。 */
    private static JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }
    /** HTTP状态和领域码均须匹配。 */
    private static void error(HttpResponse<String> response, int status, int code) {
        assertThat(ok(response, status).path("code").asInt()).isEqualTo(code);
    }
    /** 只连接独占测试存储。 */
    private static String endpoint() { return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000); }
    /** 管理端只用于前提和物理证据观察。 */
    private static MinioClient admin() { return MinioClient.builder().endpoint(endpoint()).credentials(ACCESS, SECRET).build(); }
    /** 测试全部结束后销毁自有容器和连接池。 */
    @AfterAll static void stopStorage() { OWNER_POOL.close(); MINIO.stop(); BROKER.stop(0); }
    /** 新OTA引用必须先清理，再删除本例模型与项目；不修改触发器或禁用外键。 */
    @AfterEach
    void clearOwnedFacts() throws Exception {
        try {
            for (Fixture fixture : fixtures) OtaCompletionSourceAssertions.verify(owner(),fixture.projectId());
        } finally {
        try (MinioClient admin = admin()) {
            for (var item : admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).includeVersions(true).build())) {
                var version = item.get();
                admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(version.objectName()).versionId(version.versionId()).build());
            }
        }
        JdbcTemplate owner = owner();
        for (Fixture fixture : fixtures) {
            owner.update("DELETE FROM sys_outbox_event WHERE project_id=?",fixture.projectId());
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(status -> {
                for(String table:List.of("ota_job_progress_outbox","ota_job_progress","ota_job_expiry","ota_job_execution_origin","ota_download_transport","ota_download_authorization_outbox","ota_download_authorization","ota_download_bandwidth","ota_download_request_outbox","ota_download_request","ota_notification_transport","ota_notification_delivery","ota_job_dispatch_outbox","ota_job_transition","ota_batch_transition",
                        "ota_campaign_outbox","ota_campaign_transition","ota_device_job","ota_campaign_batch",
                        "ota_campaign_creation_request","ota_campaign")) owner.update("DELETE FROM "+table+" WHERE project_id=?",fixture.projectId());
            });
            owner.update("DELETE FROM ota_firmware_release WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_publication WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_trust_bundle WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_trust_domain WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_upload_session WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_creation_request WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_device_report WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_access_binding WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_credential WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(status -> {
                owner.update("DELETE FROM ota_type_baseline_version WHERE project_id = ?", fixture.projectId());
                owner.update("DELETE FROM ota_type_baseline WHERE project_id = ?", fixture.projectId());
            });
            owner.update("DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_project_member WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_project WHERE id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", fixture.tenantId());
            owner.update("DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            owner.update("DELETE FROM sys_account WHERE id IN (?, ?)", fixture.accountId(), fixture.ownerId());
        }
        fixtures.clear();
        }
    }

    /** 独立身份骨架与已发布类型/不可变模型，owner只准备夹具。 */
    private Fixture seed(ProjectRole role) {
        Fixture fixture = CONFIGURED;
        fixtures.add(fixture);
        JdbcTemplate owner = owner();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA测试租户')", fixture.tenantId());
        for (UUID account : List.of(fixture.accountId(), fixture.ownerId())) {
            owner.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) VALUES (?,?,'{noop}unused','OTA测试',now())",
                    account, account + "@example.invalid");
            owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                    Uuid7.generate(), fixture.tenantId(), account);
        }
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA测试项目','sh-1',?)",
                fixture.projectId(), fixture.tenantId(), "ota_" + fixture.projectId().toString().replace("-", ""));
        UUID ownerId = role == ProjectRole.OWNER ? fixture.accountId() : fixture.ownerId();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), fixture.projectId(), ownerId);
        if (role != ProjectRole.OWNER) {
            owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,?)",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId(), role.name());
        }
        owner.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA测试类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
                """, fixture.typeId(), fixture.tenantId(), fixture.projectId(), "type_" + fixture.typeId(), "product_" + fixture.typeId());
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":{},"events":{},"commands":{}}'::jsonb,?,'PG_JSONB_TEXT_V1_SHA256')
                """, fixture.modelId(), fixture.tenantId(), fixture.projectId(), fixture.typeId(), "a".repeat(64));
        return fixture;
    }

    /** owner连接仅用于准备/清理与观察，不走生产读取断言。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(OWNER_POOL);
    }

    /** 原样拼接固定安全fixture UUID，保留版本反例中的JSON词法。 */
    private static String body(Fixture fixture, String version) {
        return "{\"deviceTypeId\":\"" + fixture.typeId() + "\",\"thingModelVersionId\":\""
                + fixture.modelId() + "\",\"firmwareVersion\":\"" + version + "\"}";
    }

    /** 唯一冻结资源根，不扩展到设备原生程序。 */
    private static String base(Fixture fixture) {
        return "/api/v1/projects/" + fixture.projectId() + "/ota/firmwares";
    }

    /** 每例全部归属，确保失败后也能精确清理。
     * @param tenantId 本例租户
     * @param projectId 本例项目
     * @param accountId 被测请求账号
     * @param ownerId 保留项目所有者的独立账号
     * @param typeId 发布类型
     * @param modelId 不可变模型版本
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID ownerId, UUID typeId, UUID modelId) { }
}
