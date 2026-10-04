package com.things.link.bootstrap.ota;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.ota.application.OtaDeviceReportIngestionService;
import com.things.link.ota.application.OtaPublicationProcessor;
import com.things.link.ota.application.OtaPublicationService;
import com.things.link.ota.application.OtaTrustService;
import com.things.link.ota.application.OtaUploadProcessor;
import com.things.link.ota.application.OtaUploadService;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.ScopedTenantWork;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多个上下文共享的真实夹具：受控前置、签名回执断言辅助与真实HTTP/数据库观察。
 *
 * <p>抽象夹具刻意不持有任何物理容器：Java会先初始化父类静态字段，若MinIO挂在父类上，
 * 兄弟用例类会各自触发一次父类静态初始化，并可能在容器真正started之前解析端点。容器因此
 * 由各有界用例类自己启动与关闭，夹具只保留纯常量、注入端口与业务辅助。</p>
 *
 * <p><b>为什么父类从 {@code AbstractIntegrationTest} 换成 {@link AbstractKafkaIntegrationTest}：</b>
 * S13-4d-2b-2b-1 要求在真实Kafka上验证生产 {@code RawUplinkKafkaConsumer}，而Java单继承使
 * 「既复用本夹具又获得共享Kafka容器」只能有一个父类。Kafka容器本身仍是JVM级共享单例，
 * 4g两个用例类的断言、流程与显式生产入口调用完全未变；它们只是多了一个不会被自动启动的
 * listener（{@code spring.kafka.listener.auto-startup=false}）。</p>
 */
abstract class OtaRealDispatchHarness extends AbstractKafkaIntegrationTest {

    /**
     * 每个用例前清零共享signer观察量。
     *
     * <p>为什么必须显式清零：{@code SIGNER_CALLS} 与 {@code LAST_KEY_VERSION} 是类静态字段，
     * 同一Surefire JVM里现在有三个用例类共享它们。若只依赖 {@code @AfterEach} 收尾，
     * 一旦前一个类在断言中失败，后续类的「signer被调用恰一次」就会继承脏计数；
     * {@code @BeforeEach} 把每个用例的起点钉死为零，失败也不跨用例传染。</p>
     */
    @BeforeEach
    void resetSharedSignerEvidence() {
        SIGNER_CALLS.set(0);
        LAST_KEY_VERSION.set(null);
    }

    /** 根密钥只签信任包，与发布密钥分离。 */
    static final KeyPair ROOT = keyPair();
    /** 发布密钥只在本地测试替身内持有私钥，用于证明协调器复验的是真实签名。 */
    static final KeyPair RELEASE = keyPair();
    /** 在根配置初始化前冻结独占身份。 */
    private static final Fixture CONFIGURED = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 本类独占信任域。 */
    static final String DOMAIN = "dispatch-" + UUID.randomUUID();
    /** 独立生成精确根签名包与发布manifest的规范字节。 */
    private static final OtaCanonicalJson CANONICAL = new OtaCanonicalJson();
    /** 只供独占MinIO容器使用的测试凭据。 */
    static final String ACCESS = "real-dispatch-test", SECRET = "real-dispatch-secret";
    /** 本测试唯一桶，不触碰开发存储。 */
    static final String BUCKET = "real-dispatch-" + UUID.randomUUID();
    /** 只供测试替身使用的固定认证凭据。 */
    static final String SIGNING_TOKEN = "bootstrap-real-dispatch-credential";
    /** 测试替身固定路径。 */
    private static final String SIGN_PATH = "/sign";
    /** EMQX夹具专用发布身份，无生产权限。 */
    static final String BROKER_KEY = "ota-real-dispatch-test";
    /** EMQX夹具专用API秘密。 */
    static final String BROKER_SECRET = "ota-real-dispatch-secret-only-for-tests";
    /** 响应只作断言，不伪造生产事实。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 测试替身物理调用次数，证明不重试。 */
    static final AtomicInteger SIGNER_CALLS = new AtomicInteger();
    /** 测试替身最近一次观察到的密钥版本。 */
    static final AtomicReference<String> LAST_KEY_VERSION = new AtomicReference<>();
    /** 具体用例类启动的真实本地signer测试替身。 */
    static HttpServer signer;
    /** 具体用例类启动并建立私桶后的真实存储端点。 */
    static String storageEndpoint;
    /** 真正随机监听端口。 */ @Value("${local.server.port}") private int port;
    /** JWT仍经过真实验签与数据库角色读取。 */ @Autowired private TokenIssuer tokens;
    /** 真实上传身份与取消服务。 */ @Autowired private OtaUploadService uploads;
    /** 真实MinIO写入和固定版本验真编排。 */ @Autowired private OtaUploadProcessor uploadProcessor;
    /** 真实发布短事务服务。 */ @Autowired private OtaPublicationService publications;
    /** 生产签名与对象复验编排。 */ @Autowired private OtaPublicationProcessor publicationProcessor;
    /** 当前信任包摘要，报告必须携带真实值而不是测试常量。 */ @Autowired private OtaTrustService trust;
    /** 真实设备报告接纳服务。 */ @Autowired private OtaDeviceReportIngestionService reportIngestion;
    /** JUnit独占上传临时目录。 */ @TempDir private java.nio.file.Path tempDir;
    /** 每例自有项目图。 */ private final List<Fixture> fixtures = new ArrayList<>();
    /** 当前实例是否执行物理对象上传；由真正启动对象存储的用例类覆盖为true。 */
    boolean objectStorage() {
        return false;
    }

    /**
     * 新OTA引用必须先清理，再删除本例模型与项目；不修改触发器或禁用外键。
     *
     * <p>清理顺序里必须包含下载申请与其下游表（{@code ota_download_request*}/
     * {@code ota_download_authorization*}/{@code ota_download_transport}/{@code ota_download_bandwidth}）：
     * S13-4d-2b-2b-1 会在真实MQTT边缘上产生下载申请事实，而该表以
     * {@code (tenant_id,project_id,campaign_id,job_id)} 外键引用 {@code ota_device_job}，
     * 又被下载授权引用。缺少它们时删作业/申请会撞外键，整个清理事务回滚并把上一例的持久事实
     * 留给下一例——那是清理缺陷，不是被放宽的断言。顺序按引用方向自底向上，而不是按字母序。</p>
     *
     * <p><b>S13-4d-2b-2b-2补充：</b>设备闭环用例会额外产生
     * {@code ota_job_progress}/{@code ota_health_receipt}/{@code ota_commit_permit}及其确认族事实。
     * 这些行既以 {@code authorization_id} 外键引用 {@code ota_download_authorization}，又经
     * {@code (tenant_id,project_id,campaign_id,job_id)} 引用 {@code ota_device_job}，因此必须排在
     * 下载授权与作业之前删除；原清单把 {@code ota_download_authorization} 排在 {@code ota_job_progress}
     * 之前，会让清理事务整体回滚（实测报错：{@code ota_job_progress_authorization_fk}）。
     * 这里补齐确认族并按引用方向重排，删除的仍是同一批本例事实，未放宽任何断言。</p>
     *
     * <p><b>S13-4d-2b-2b-5补充：</b>取消安全点用例会经生产取消入口产生
     * {@code ota_campaign_runtime_cancellation}（经 {@code (tenant_id,project_id,campaign_id)} 外键引用
     * {@code ota_campaign}），并按生产顺序创建 {@code ota_install_stop_*} 事实。原清单既没有该取消请求表，
     * 也只覆盖了 install-stop 族，于是删除 {@code ota_campaign} 时会被
     * {@code ota_campaign_runtime_cancellation_campaign_fk} 挡住（实测报错：清理事务整体回滚，随后本例
     * 夹具被下一用例继承并在 {@code seed} 撞 {@code sys_tenant_pkey}）。这里按引用方向把
     * {@code ota_campaign_runtime_cancellation} 放到 {@code ota_campaign} 之前，并一并补齐同样以
     * {@code ota_campaign}/{@code ota_device_job} 为父的回退/对账/前进族表；删除的仍是本例项目事实，
     * 不修改触发器、不禁用外键、不放宽任何断言。</p>
     */
    @AfterEach
    void clearOwnedFacts() throws Exception {
        if (objectStorage()) {
            try (MinioClient admin = admin()) {
                for (var item : admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET)
                        .recursive(true).includeVersions(true).build())) {
                    var version = item.get();
                    admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(version.objectName())
                            .versionId(version.versionId()).build());
                }
            }
        }
        JdbcTemplate owner = owner();
        for (Fixture fixture : fixtures) {
            new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()))
                    .executeWithoutResult(status -> {
                        for (String table : List.of("ota_install_stop_cancellation", "ota_install_stop_outbox",
                                "ota_install_stop_report", "ota_install_stop_transport", "ota_install_stop_delivery",
                                "ota_install_stop_control", "ota_install_stop_status_query",
                                "ota_install_stop_candidate_control", "ota_install_stop_operation",
                                "ota_rollback_cancellation", "ota_rollback_outbox", "ota_rollback_report",
                                "ota_rollback_transport", "ota_rollback_delivery", "ota_rollback_control",
                                "ota_rollback_status_query", "ota_rollback_candidate_control", "ota_rollback_operation",
                                "ota_rollback_preflight_report", "ota_rollback_preflight_transport",
                                "ota_rollback_preflight_delivery", "ota_rollback_preflight_control",
                                "ota_rollback_preflight_query", "ota_reconciliation_cancellation",
                                "ota_reconciliation_outbox", "ota_reconciliation_report", "ota_reconciliation_transport",
                                "ota_reconciliation_delivery", "ota_reconciliation_control", "ota_reconciliation_query",
                                "ota_confirmation_cancellation", "ota_confirmation_outbox", "ota_commit_receipt",
                                "ota_commit_transport", "ota_commit_delivery", "ota_commit_permit",
                                "ota_health_receipt", "ota_job_progress_outbox", "ota_job_progress",
                                "ota_job_expiry", "ota_job_execution_origin", "ota_download_transport",
                                "ota_download_authorization_outbox", "ota_download_authorization",
                                "ota_download_bandwidth", "ota_download_request_outbox", "ota_download_request",
                                "ota_notification_transport", "ota_notification_delivery", "ota_job_dispatch_outbox",
                                "ota_job_transition", "ota_batch_transition", "ota_campaign_advancement",
                                "ota_campaign_completion", "ota_campaign_outbox", "ota_campaign_transition",
                                "ota_device_job", "ota_campaign_batch", "ota_campaign_creation_request",
                                "ota_campaign_runtime_cancellation", "ota_campaign", "ota_type_baseline_version",
                                "ota_type_baseline")) {
                            owner.update("DELETE FROM " + table + " WHERE project_id=?", fixture.projectId());
                        }
                    });
            owner.update("DELETE FROM ota_firmware_release WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_publication WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_trust_bundle WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_trust_domain WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_upload_session WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_creation_request WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_device_report WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_credential WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_project_member WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_project WHERE id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", fixture.tenantId());
            owner.update("DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            owner.update("DELETE FROM sys_account WHERE id IN (?, ?)", fixture.accountId(), fixture.ownerId());
        }
        fixtures.clear();
        SIGNER_CALLS.set(0);
        LAST_KEY_VERSION.set(null);
    }

    /**
     * 真实HTTP设备：本片要求设备实例、一机一密凭据与模型绑定都是真实应用服务产物，
     * 不是测试直写业务行；设备类型与物模型版本属于发布前置身份骨架，由owner在{@link #seed}准备。
     *
     * <p>为什么凭据代际必须从数据库读回：真实凭据生成会把设备的{@code credential_version}推进一格，
     * 报告与准入都必须使用这个真实代际；写死"1"只会得到一个自欺的SKIP IDENTITY_CHANGED。</p>
     */
    Device currentDevice(Fixture fixture) throws Exception {
        return provisionDevice(fixture).device();
    }

    /**
     * 真实HTTP设备，并连同「只在生成响应里出现一次」的明文设备密钥一起返回。
     *
     * <p>为什么必须由真实凭据生成响应提供密钥：{@code dev_credential} 只保存摘要，数据库里再也读不回
     * 明文。S13-4d-2b-2b-1 要用真实MQTT设备连接证明身份来自生产认证回调；若密钥由测试自造，
     * 第一次CONNECT就会被真实的 {@code /api/v1/emqx/auth} 拒绝，证据链在第一步就断了。</p>
     *
     * @param fixture 本例真实项目图
     * @return 真实设备身份与一次性明文密钥
     */
    ProvisionedDevice provisionDevice(Fixture fixture) throws Exception {
        String deviceKey = "device" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var created = ok(send(fixture, "POST", devices(fixture), key(),
                CANONICAL.writeObject(Map.of("deviceTypeId", fixture.typeId().toString(), "deviceKey", deviceKey,
                        "name", "OTA派发真实设备")), false), 201);
        UUID device = UUID.fromString(created.path("id").asText());
        assertThat(owner().queryForObject("SELECT thing_model_version_id FROM dev_device WHERE id=?",
                UUID.class, device)).as("真实设备创建必须按已发布类型绑定最新物模型版本，否则报告无法通过模型一致性")
                .isEqualTo(fixture.modelId());
        var credential = ok(send(fixture, "POST", devices(fixture) + "/" + device + "/credentials", key(),
                null, false), 201);
        assertThat(owner().queryForObject("SELECT count(*) FROM dev_credential WHERE device_id=? "
                + "AND deleted_at IS NULL AND auth_type='ACCESS_TOKEN'", Long.class, device))
                .as("真实凭据生成必须恰好留一条有效ACCESS_TOKEN").isEqualTo(1L);
        long credentialVersion = owner().queryForObject("SELECT credential_version FROM dev_device WHERE id=?",
                Long.class, device);
        String accessToken = credential.path("plainSecret").asText();
        assertThat(accessToken).as("真实凭据生成响应必须暴露一次性明文密钥，否则无法建立真实设备连接")
                .isNotBlank();
        return new ProvisionedDevice(new Device(device, deviceKey, credentialVersion), accessToken);
    }

    /** 权威项目短标识，与 {@link #seed} 写入的 {@code sys_project.project_key} 及 OTA Topic 完全一致。 */
    static String projectKey(Fixture fixture) {
        return "ota_" + fixture.projectId().toString().replace("-", "");
    }

    /**
     * 完整受控前置：真实HTTP固件草稿、根签名信任包、受控上传、类型基线登记、真实设备报告，
     * 以及活动创建→排程冻结→启动。
     *
     * @param fixture 本例真实项目图
     * @param device 已通过真实API建立的设备及其真实凭据代际
     * @return 已READY且已启动的运行事实
     */
    Running publishedAndRunning(Fixture fixture, Device device) throws Exception {
        Prepared prepared = preparePublication(fixture);
        registerBaseline(prepared);
        reportIngestion.accept(new AuthenticatedDeviceIdentity(fixture.tenantId(), fixture.projectId(),
                        device.id(), device.credentialVersion()),
                report(fixture), Instant.now());
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_device_report WHERE device_id=?",
                Long.class, device.id())).as("真实报告接纳必须留下当前设备报告，否则准入只会SKIP REPORT_MISSING")
                .isEqualTo(1L);

        ok(send(fixture, "POST", prepared.path(), key(), prepared.body(), false), 202);
        var claimed = publications.claim().orElseThrow(
                () -> new IllegalStateException("等待中的发布尝试必须可被真实受限领取"));
        publicationProcessor.process(claimed);

        UUID campaign = UUID.fromString(ok(send(fixture, "POST", campaigns(fixture), key(),
                plan(prepared.firmware(), List.of(device.id())), false), 201).path("id").asText());
        String path = campaigns(fixture) + "/" + campaign;
        ok(send(fixture, "POST", path + "/scheduling", key(), revision("0"), false), 200);
        ok(send(fixture, "POST", path + "/starting", key(), revision("1"), false), 200);
        UUID job = owner().queryForObject("SELECT id FROM ota_device_job WHERE campaign_id=? AND device_id=?",
                UUID.class, campaign, device.id());
        return new Running(prepared, campaign, job);
    }

    /** 真实业务API建草稿、导入信任并完成受控文件上传，使发布准备不是测试伪造状态。 */
    Prepared preparePublication(Fixture fixture) throws Exception {
        var firmware = ok(send(fixture, "POST", firmwares(fixture), key(),
                body(fixture, "real-dispatch-v1").getBytes(StandardCharsets.UTF_8), false), 201);
        UUID firmwareId = UUID.fromString(firmware.path("id").asText());
        ok(send(fixture, "POST", "/api/v1/projects/" + fixture.projectId() + "/ota/trust-domains/"
                + DOMAIN + "/bundles", key(), trustEnvelope("0", 1), false), 200);
        byte[] content = new byte[] {7, 6, 5, 4, 3, 2, 1};
        var created = scoped(fixture, () -> uploads.create(fixture.projectId(), firmwareId, key(),
                content.length, sha(content)));
        var receiving = scoped(fixture, () -> uploads.prepare(fixture.projectId(), firmwareId, created.id()));
        var uploaded = uploaded(fixture, receiving, content);
        // 不启动对象存储的上下文从不读取对象，会话只停在RECEIVING；它的发布请求在70016创建前就被拒绝。
        assertThat(uploaded.status())
                .as(objectStorage() ? "受控上传必须先在真实MinIO上完成版本验真" : "不执行对象上传的上下文不得伪造VERIFIED")
                .isEqualTo(objectStorage() ? "VERIFIED" : "RECEIVING");
        byte[] request = publicationRequest(firmware.path("revision").asText(), fixture, firmwareId, uploaded,
                content);
        return new Prepared(fixture, firmwareId, uploaded,
                firmwares(fixture) + "/" + firmwareId + "/publications", request);
    }

    /**
     * 物理对象上传只由真实存储上下文执行；不启动对象存储的上下文跳过它，
     * 因为70016在创建发布尝试之前就拒绝，从未读取过任何对象。
     */
    private OtaUploadSession uploaded(Fixture fixture, OtaUploadSession receiving, byte[] content) throws Exception {
        if (!objectStorage()) return receiving;
        var file = tempDir.resolve(receiving.id() + ".bin");
        Files.write(file, content);
        return scoped(fixture, () -> {
            try (var lease = uploadProcessor.begin(receiving)) {
                return uploadProcessor.process(receiving, file, lease, () -> false);
            }
        });
    }

    /**
     * 登记受控类型基线：能力字段只来自运维配置，HTTP入口只接受修订号。
     *
     * <p>为什么不能把能力字段放进请求体：{@code OtaTypeBaselineRequestParser}是闭集单字段信封，
     * 客户端自选根、能力或来源标识一律malformed。真实边界是"配置声明基线 + HTTP登记修订"。</p>
     */
    void registerBaseline(Prepared prepared) throws Exception {
        Fixture fixture = prepared.fixture();
        ok(send(fixture, "POST", "/api/v1/projects/" + fixture.projectId() + "/ota/device-types/"
                + fixture.typeId() + "/baseline/registrations", key(), revision("0"), false), 200);
    }

    /**
     * 有界等待通知投递落到期望状态。
     *
     * <p>为什么需要等待：真实Broker发布与持久观察是两个独立短事务。这里只轮询数据库事实并有界失败，
     * 失败消息点名被等待的事实，绝不睡无界时长。</p>
     *
     * @param event 通知事件身份
     * @param expected 期望的投递状态
     * @return 实际观察到的状态
     */
    String awaitDeliveryStatus(UUID event, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        String status = null;
        while (System.nanoTime() < deadline) {
            status = owner().queryForObject("SELECT status FROM ota_notification_delivery WHERE event_id=?",
                    String.class, event);
            if (expected.equals(status)) return status;
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("等待通知投递状态=" + expected + "超时，最后观察到=" + status);
    }

    /** 当前运行事实每次真实读取并禁止HTTP缓存。 */
    JsonNode execution(Running run) throws Exception {
        var response = send(run.prepared().fixture(), "GET",
                campaigns(run.prepared().fixture()) + "/" + run.campaign() + "/execution", null, null, false);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                .as("运行事实当前读必须禁止HTTP缓存").contains("no-store");
        return ok(response, 200);
    }

    /** 唯一发布请求：完整manifest与真实上传身份，测试不伪造签名结果。 */
    private byte[] publicationRequest(String revision, Fixture fixture, UUID firmwareId, OtaUploadSession upload,
            byte[] content) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("contractVersion", "tc-ota-manifest/v1");
        manifest.put("firmwareId", firmwareId.toString());
        manifest.put("firmwareVersion", "real-dispatch-v1");
        manifest.put("trustDomain", DOMAIN);
        manifest.put("deviceTypeId", fixture.typeId().toString());
        manifest.put("productKey", "product_" + fixture.typeId());
        manifest.put("hardware", Map.of("model", "board-v1", "boardRevisionMin", 0L, "boardRevisionMax", 1L));
        manifest.put("bootloaderMinimumVersion", "1.0.0");
        manifest.put("artifactSize", (long) content.length);
        manifest.put("artifactSha256", sha(content));
        manifest.put("compression", "NONE");
        manifest.put("delta", Map.of("mode", "NONE"));
        manifest.put("securityVersion", 1L);
        manifest.put("thingModelVersionId", fixture.modelId().toString());
        manifest.put("thingModelSchemaDigestAlgorithm", "PG_JSONB_TEXT_V1_SHA256");
        manifest.put("thingModelSchemaDigest", "a".repeat(64));
        manifest.put("allowedSourceThingModelVersionIds", List.of(fixture.modelId().toString()));
        manifest.put("requirements", Map.of("profile", "TC_PROPERTY_COMPOSITE_V1", "minimumRamBytes", 1L,
                "minimumFlashBytes", 1L, "requiresAbSlots", true, "requiresRangeDownload", true,
                "requiresProtectedSecurityCounter", true));
        manifest.put("signatureProfile", "TC_OTA_ED25519_V1");
        manifest.put("signingKeyFingerprint", sha(RELEASE.getPublic().getEncoded()));
        manifest.put("minimumTrustBundleVersion", 1L);
        return CANONICAL.writeObject(Map.of("expectedRevision", revision,
                "uploadSessionId", upload.id().toString(), "manifest", manifest));
    }

    /** 根签名包来自独立JCA，测试发布key不等于根。 */
    private static byte[] trustEnvelope(String revision, long version) throws Exception {
        Map<String, Object> bundle = Map.of("contractVersion", "tc-ota-trust-bundle/v1", "trustDomain", DOMAIN,
                "bundleVersion", version, "keys", List.of(trustKey(RELEASE, "release", "ACTIVE")));
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(ROOT.getPrivate());
        signature.update("thingslink-ota-trust-bundle-v1\0".getBytes(StandardCharsets.UTF_8));
        signature.update(CANONICAL.writeObject(bundle));
        return CANONICAL.writeObject(Map.of("expectedRevision", revision, "bundle", bundle,
                "signature", b64(signature.sign())));
    }

    /** 不可变公钥身份和有效期。 */
    private static Map<String, Object> trustKey(KeyPair pair, String version, String state) {
        return Map.of("keyVersion", version, "signatureProfile", "TC_OTA_ED25519_V1",
                "spki", b64(pair.getPublic().getEncoded()), "fingerprint", sha(pair.getPublic().getEncoded()),
                "state", state, "notBefore", 0L, "notAfter", 253402300799L);
    }

    /**
     * 声明来源只由运维配置提供，测试字段对应真实发布目标，不构造外部硬件证明。
     *
     * <p>这不是"测试伪造基线"：生产装配同样只从该配置读取完全能力，HTTP登记只允许修订号。</p>
     */
    static String baselineSource() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", "tc-ota-type-baseline/v1");
        value.put("tenantId", CONFIGURED.tenantId().toString());
        value.put("projectId", CONFIGURED.projectId().toString());
        value.put("deviceTypeId", CONFIGURED.typeId().toString());
        value.put("productKey", "product_" + CONFIGURED.typeId());
        value.put("baselineVersion", 1L);
        value.put("trustDomain", DOMAIN);
        value.put("rootFingerprint", sha(ROOT.getPublic().getEncoded()));
        value.put("hardware", Map.of("model", "board-v1", "boardRevisionMin", 0L, "boardRevisionMax", 1L));
        value.put("bootloader", Map.of("minimumVersion", "1.0.0", "maximumVersion", "2.0.0"));
        value.put("signatureProfiles", List.of("TC_OTA_ED25519_V1"));
        value.put("maximumArtifactBytes", 67108864L);
        value.put("availableRamBytes", 1024L);
        value.put("availableFlashBytes", 67108864L);
        value.put("supportsAbSlots", true);
        value.put("supportsRangeDownload", true);
        value.put("supportsResumeDownload", true);
        value.put("protectedSecurityCounterBits", 53L);
        value.put("compressionAlgorithms", List.of("NONE"));
        value.put("deltaModes", List.of("NONE"));
        value.put("propertyProfile", "TC_PROPERTY_COMPOSITE_V1");
        value.put("evidenceReference", "test-fixture-only");
        return new String(CANONICAL.writeObject(Map.of("baselines", List.of(value))), StandardCharsets.UTF_8);
    }

    /** 受控根信任锚只由运维配置提供，测试不构造外部硬件证明。 */
    static String anchors() {
        return new String(CANONICAL.writeObject(Map.of(
                "anchors", List.of(Map.of("tenantId", CONFIGURED.tenantId().toString(),
                        "projectId", CONFIGURED.projectId().toString(), "trustDomain", DOMAIN,
                        "allowedDeviceTypeIds", List.of(CONFIGURED.typeId().toString()),
                        "rootProfile", "TC_OTA_ED25519_V1", "rootSpki", b64(ROOT.getPublic().getEncoded()),
                        "rootFingerprint", sha(ROOT.getPublic().getEncoded()), "policyRevision", 1L)))),
                StandardCharsets.UTF_8);
    }

    /** 完整合法报告正文只含运行声明，认证身份单独传入服务。 */
    private byte[] report(Fixture fixture) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", "tc-ota-device-report/v1");
        value.put("reportSequence", 1L);
        value.put("hardware", Map.of("model", "board-v1", "boardRevision", 0L));
        value.put("bootloaderVersion", "1.0.0");
        value.put("currentFirmwareVersion", "factory-v1");
        value.put("currentFirmwareSha256", "b".repeat(64));
        value.put("currentSecurityVersion", 0L);
        value.put("committedSecurityVersion", 0L);
        value.put("thingModelVersionId", fixture.modelId().toString());
        value.put("thingModelSchemaDigestAlgorithm", "PG_JSONB_TEXT_V1_SHA256");
        value.put("thingModelSchemaDigest", "a".repeat(64));
        value.put("propertyProfile", "TC_PROPERTY_COMPOSITE_V1");
        value.put("trustDomain", DOMAIN);
        value.put("rootFingerprint", sha(ROOT.getPublic().getEncoded()));
        value.put("trustBundleVersion", 1L);
        value.put("trustBundleSha256", scoped(fixture,
                () -> trust.find(fixture.projectId(), DOMAIN).bundleSha256()));
        value.put("signatureProfiles", List.of("TC_OTA_ED25519_V1"));
        value.put("maximumArtifactBytes", 67108864L);
        value.put("availableRamBytes", 1024L);
        value.put("availableFlashBytes", 67108864L);
        value.put("supportsAbSlots", true);
        value.put("supportsRangeDownload", true);
        value.put("supportsResumeDownload", true);
        value.put("protectedSecurityCounterBits", 53L);
        value.put("compressionAlgorithms", List.of("NONE"));
        value.put("deltaModes", List.of("NONE"));
        value.put("activeSlot", "A");
        value.put("bootState", "HEALTHY");
        return CANONICAL.writeObject(value);
    }

    /** 显式完整策略与稳定名单，旧时间表示立即可执行。 */
    static byte[] plan(UUID firmware, List<UUID> devices) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("maxConcurrentDownloads", 2L);
        policy.put("maxDownloadBytesPerSecond", 1048576L);
        policy.put("downloadRetryLimit", 2L);
        policy.put("retryBackoffSeconds", 5L);
        policy.put("healthWindowSeconds", 60L);
        policy.put("pauseMinEvaluated", 2L);
        policy.put("pauseFailureCount", 1L);
        policy.put("pauseFailureRateBps", 5000L);
        policy.put("batchMinSuccessRateBps", 10000L);
        policy.put("requireManualBatchApproval", true);
        Map<String, Object> stages = new LinkedHashMap<>();
        for (String name : List.of("DISPATCHED", "DOWNLOADING", "VERIFYING", "INSTALLING", "REBOOTING",
                "HEALTH_CHECKING", "CONFIRMING", "ROLLBACK_PENDING", "ROLLING_BACK")) {
            stages.put(name, 120L);
        }
        policy.put("stageTimeoutSeconds", stages);
        return CANONICAL.writeObject(Map.of("contractVersion", "tc-ota-campaign-plan/v1",
                "firmwareId", firmware.toString(), "deviceIds", devices.stream().map(UUID::toString).toList(),
                "batchSize", 2L, "notBefore", "2026-09-12T00:00:00Z", "executionPolicy", policy));
    }

    /** 修订信封同时用于基线登记与活动启动，两者都拒绝隐式修订。 */
    private static byte[] revision(String value) {
        return CANONICAL.writeObject(Map.of("expectedRevision", value));
    }

    /** 唯一冻结资源根，不扩展到设备原生程序。 */
    private static String firmwares(Fixture fixture) {
        return "/api/v1/projects/" + fixture.projectId() + "/ota/firmwares";
    }

    /** 当前项目活动集合。 */
    static String campaigns(Fixture fixture) {
        return "/api/v1/projects/" + fixture.projectId() + "/ota/campaigns";
    }

    /** 当前项目设备集合。 */
    private static String devices(Fixture fixture) {
        return "/api/v1/projects/" + fixture.projectId() + "/devices";
    }

    /** 异步/服务调用都显式绑定本例的真实scope。 */
    static <T> T scoped(Fixture fixture, java.util.function.Supplier<T> work) {
        return ScopedTenantWork.call(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()),
                work);
    }

    /** 属性门控的生产适配器端点，指向具体用例类自己启动的真实本地测试替身。 */
    static String signerEndpoint() {
        return "http://127.0.0.1:" + signer.getAddress().getPort() + SIGN_PATH;
    }

    /** 真实本地signer测试替身；只签名平台给出的字节，不代表KMS/HSM资格。 */
    static HttpServer startSigner() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext(SIGN_PATH, OtaRealDispatchHarness::handleSign);
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** 记录冻结请求字段并返回真实Ed25519回执；失败时不泄露任何请求正文。 */
    private static void handleSign(HttpExchange exchange) throws IOException {
        SIGNER_CALLS.incrementAndGet();
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        int status = 200;
        byte[] response;
        try {
            var request = JSON.readTree(requestBody);
            LAST_KEY_VERSION.set(request.path("keyVersion").asText());
            byte[] signingInput = Base64.getDecoder().decode(request.path("signingInput").asText());
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(RELEASE.getPrivate());
            signature.update(signingInput);
            response = JSON.writeValueAsString(Map.of(
                    "contractVersion", "tc-ota-sign-response/v1",
                    "requestId", request.path("requestId").asText(),
                    "keyVersion", request.path("keyVersion").asText(),
                    "signatureProfile", request.path("signatureProfile").asText(),
                    "spkiBase64", b64(RELEASE.getPublic().getEncoded()),
                    "signatureBase64", b64(signature.sign()),
                    "receipt", "test-double-receipt-" + SIGNER_CALLS.get())).getBytes(StandardCharsets.UTF_8);
        } catch (Exception failure) {
            status = 500;
            response = "{\"error\":\"test-double-failure\"}".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        } finally {
            exchange.close();
        }
    }

    /** 完整HTTP/1.1往返预算，客户端关闭后不留连接线程。 */
    HttpResponse<String> send(Fixture fixture, String method, String path, String idempotencyKey,
            byte[] body, boolean binary) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(45));
        if (fixture != null) {
            builder.header("Authorization", "Bearer " + tokens.issue(
                    new AuthenticatedPrincipal(fixture.accountId(), fixture.tenantId(), fixture.projectId()))
                    .value());
        }
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        if (body != null) builder.header("Content-Type", binary ? "application/octet-stream" : "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** 唯一请求键。 */
    static String key() {
        return UUID.randomUUID().toString();
    }

    /** 明确HTTP状态并保留响应首因。 */
    static JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    /** HTTP状态和领域码均须匹配。 */
    static void error(HttpResponse<String> response, int status, int code) {
        assertThat(ok(response, status).path("code").asInt()).isEqualTo(code);
    }

    /** 管理端只用于前提和物理证据观察。 */
    static MinioClient admin() {
        return MinioClient.builder().endpoint(storageEndpoint).credentials(ACCESS, SECRET).build();
    }

    /** owner连接仅用于准备/清理与观察，不走生产读取断言。 */
    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    /** 独立身份骨架与已发布类型/不可变模型，owner只准备夹具。 */
    Fixture seed(ProjectRole role) {
        Fixture fixture = CONFIGURED;
        fixtures.add(fixture);
        JdbcTemplate owner = owner();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA派发测试租户')", fixture.tenantId());
        for (UUID account : List.of(fixture.accountId(), fixture.ownerId())) {
            owner.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) "
                    + "VALUES (?,?,'{noop}unused','OTA派发测试',now())", account, account + "@example.invalid");
            owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                    Uuid7.generate(), fixture.tenantId(), account);
        }
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) "
                        + "VALUES (?,?,'OTA派发测试项目','sh-1',?)",
                fixture.projectId(), fixture.tenantId(),
                "ota_" + fixture.projectId().toString().replace("-", ""));
        UUID ownerId = role == ProjectRole.OWNER ? fixture.accountId() : fixture.ownerId();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), fixture.projectId(), ownerId);
        if (role != ProjectRole.OWNER) {
            owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,?)",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId(), role.name());
        }
        owner.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA派发测试类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
                """, fixture.typeId(), fixture.tenantId(), fixture.projectId(), "type_" + fixture.typeId(),
                "product_" + fixture.typeId());
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":{},"events":{},"commands":{}}'::jsonb,?,'PG_JSONB_TEXT_V1_SHA256')
                """, fixture.modelId(), fixture.tenantId(), fixture.projectId(), fixture.typeId(), "a".repeat(64));
        return fixture;
    }

    /** 原样拼接固定安全fixture UUID，保留版本反例中的JSON词法。 */
    private static String body(Fixture fixture, String version) {
        return "{\"deviceTypeId\":\"" + fixture.typeId() + "\",\"thingModelVersionId\":\""
                + fixture.modelId() + "\",\"firmwareVersion\":\"" + version + "\"}";
    }

    /** 标准Base64。 */
    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 完整字节SHA256。 */
    static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 测试专属公私钥。 */
    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /**
     * 最小MQTT3.1.1订阅夹具：只用于把"平台真的发布了"落到真实Broker的物理报文上。
     *
     * <p>不复用其他测试类的私有夹具，也不放宽为"订阅后等一会儿"：连接、SUBACK与PUBLISH
     * 都是真实的，读取有界且超时有限。</p>
     */
    static final class MqttSubscriber implements AutoCloseable {
        /** 真实Broker连接。 */ private final java.net.Socket socket;
        /** 有界网络输入。 */ private final java.io.DataInputStream input;
        /** MQTT编码输出。 */ private final java.io.DataOutputStream output;
        /** 建立匿名协议夹具连接并等待SUBACK，避免订阅竞态。 */
        MqttSubscriber(String brokerHost, int brokerPort, String topic, String clientId) throws IOException {
            socket = new java.net.Socket(brokerHost, brokerPort);
            socket.setSoTimeout(5000);
            input = new java.io.DataInputStream(socket.getInputStream());
            output = new java.io.DataOutputStream(socket.getOutputStream());
            var connect = new java.io.ByteArrayOutputStream();
            var body = new java.io.DataOutputStream(connect);
            writeUtf(body, "MQTT");
            body.writeByte(4);
            body.writeByte(2);
            body.writeShort(30);
            writeUtf(body, clientId);
            send(0x10, connect.toByteArray());
            var ack = read();
            assertThat(ack.header()).as("MQTT CONNECT必须被Broker确认").isEqualTo(0x20);
            var subscribe = new java.io.ByteArrayOutputStream();
            body = new java.io.DataOutputStream(subscribe);
            body.writeShort(1);
            writeUtf(body, topic);
            body.writeByte(1);
            send(0x82, subscribe.toByteArray());
            ack = read();
            assertThat(ack.header()).as("MQTT SUBSCRIBE必须被Broker确认").isEqualTo(0x90);
            assertThat(ack.body()).as("订阅必须被授予QoS1").isEqualTo(new byte[] {0, 1, 1});
        }

        /** 实际PUBLISH必须QoS1且非retained，返回精确剩余载荷。 */
        byte[] message(String expectedTopic) throws IOException {
            var packet = read();
            assertThat(packet.header()).as("MQTT下行必须是QoS1 PUBLISH").isEqualTo(0x32);
            var body = new java.io.DataInputStream(new java.io.ByteArrayInputStream(packet.body()));
            int topicLength = body.readUnsignedShort();
            assertThat(new String(body.readNBytes(topicLength), StandardCharsets.UTF_8))
                    .as("物理报文必须落在权威通知Topic上").isEqualTo(expectedTopic);
            int id = body.readUnsignedShort();
            byte[] payload = body.readAllBytes();
            send(0x40, new byte[] {(byte) (id >>> 8), (byte) id});
            return payload;
        }

        /** 剩余长度最多四字节且报文最多64KiB，坏协议立即失败。 */
        private Packet read() throws IOException {
            int header = input.readUnsignedByte();
            int size = 0;
            int multiplier = 1;
            for (int index = 0; index < 4; index++) {
                int encoded = input.readUnsignedByte();
                size += (encoded & 127) * multiplier;
                if (size > 65536) throw new IOException("MQTT测试报文超限");
                if ((encoded & 128) == 0) {
                    byte[] bytes = input.readNBytes(size);
                    if (bytes.length != size) throw new IOException("MQTT测试报文截断");
                    return new Packet(header, bytes);
                }
                multiplier *= 128;
            }
            throw new IOException("MQTT测试长度无效");
        }

        /** MQTT固定头与可变剩余长度。 */
        private void send(int header, byte[] body) throws IOException {
            output.writeByte(header);
            int remaining = body.length;
            do {
                int encoded = remaining % 128;
                remaining /= 128;
                output.writeByte(encoded | (remaining == 0 ? 0 : 128));
            } while (remaining != 0);
            output.write(body);
            output.flush();
        }

        /** MQTT采用UTF8字节长度，不能使用Java修改UTF格式。 */
        private static void writeUtf(java.io.DataOutputStream target, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            target.writeShort(bytes.length);
            target.write(bytes);
        }

        /** 确保测试成功或失败都关闭物理连接。 */
        @Override public void close() throws IOException {
            socket.close();
        }

        /** 有界协议包。
         * @param header 固定头
         * @param body 剩余正文
         */
        private record Packet(int header, byte[] body) { }
    }

    /** 本例精确资源，不携带其他项目事实。
     * @param fixture 本例真实成员和项目
     * @param firmware 通过HTTP创建的固件身份
     * @param upload 已通过真实存储验真的上传
     * @param path 发布尝试HTTP集合路径
     * @param body 完整严格发布请求
     */
    record Prepared(Fixture fixture, UUID firmware, OtaUploadSession upload, String path, byte[] body) {
    }

    /** 已成功派发的运行身份。
     * @param prepared 已READY的发布前置
     * @param campaign 已启动活动
     * @param job 已DISPATCHED的作业
     */
    record Running(Prepared prepared, UUID campaign, UUID job) {
        /** 便捷访问已READY固件。 */
        UUID firmware() {
            return prepared.firmware();
        }

        /** 便捷访问已采用上传身份。 */
        UUID uploadId() {
            return prepared.upload().id();
        }
    }

    /** 真实HTTP设备身份、短标识与凭据代际。
     * @param id 真实设备身份
     * @param deviceKey 真实创建时冻结的设备短标识，通知Topic与路由都必须用它
     * @param credentialVersion 真实凭据生成后的设备代际
     */
    record Device(UUID id, String deviceKey, long credentialVersion) {
    }

    /** 真实设备身份与只在生成响应中出现一次的明文密钥。
     * @param device 真实设备身份与代际
     * @param accessToken 一次性明文设备密钥，只用于建立真实MQTT连接
     */
    record ProvisionedDevice(Device device, String accessToken) {
    }

    /** 每例全部归属，确保失败后也能精确清理。
     * @param tenantId 本例租户
     * @param projectId 本例项目
     * @param accountId 被测请求账号
     * @param ownerId 保留项目所有者的独立账号
     * @param typeId 发布类型
     * @param modelId 不可变模型版本
     */
    record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID ownerId, UUID typeId, UUID modelId) {
    }
}
