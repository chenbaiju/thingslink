package com.things.link.bootstrap.ota;

import com.things.link.ota.application.OtaCampaignAdmissionService;
import com.things.link.ota.application.OtaControlledReleaseSigner;
import com.things.link.ota.application.OtaDeviceReportIngestionService;
import com.things.link.ota.application.OtaNotificationCodec;
import com.things.link.ota.application.OtaNotificationPublisher;
import com.things.link.ota.application.OtaNotificationService;
import com.things.link.ota.infrastructure.signing.HttpControlledReleaseSigner;
import com.things.link.shared.authz.ProjectRole;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.VersioningConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S13-4g：受控外部signer配置后，整条OTA派发链在真实PG+MinIO+真实MQTT Broker上首次接通。
 *
 * <p>本用例补齐的正是适配器交付前无法给出的证据：不是"发布能到READY"，也不是"准入函数返回true"，
 * 而是"同一次发布产生的READY发布物，真的经历活动创建→排程冻结→启动→租约准入→派发意图→
 * 通知投递→Broker接收"，且每一步都留在可独立观察的持久事实上。</p>
 *
 * <p>为什么必须用生产适配器而不是本地signer bean：ADR0139记录的失败历史就是"端口存在但没有任何
 * 生产适配器"，此时整条链在发布创建前按70016 fail-closed。只有在真实HTTP线格式下让
 * {@code things-link.ota.signing.*}生效，链路才算接通；本类因此只通过这三项配置装配
 * {@link HttpControlledReleaseSigner}，不注入任何本地私钥bean。</p>
 *
 * <p>为什么确定性由"关闭后台自动领取+显式调用同一生产入口"保证：活动准入与通知投递都是每秒轮询的
 * {@code SmartLifecycle} worker，若让它们与断言并发运行，"作业是否已DISPATCHED"会退化成时序竞态。
 * 本类统一关闭四个OTA worker的自动领取，再由用例显式调用{@link OtaCampaignAdmissionService}
 * 的领取/准入，并按同一顺序驱动{@link OtaNotificationService}的领取/预留/观察与
 * {@link OtaNotificationPublisher}的真实Broker发布；因此断言的是确定顺序，而不是"等一会儿大概就好了"。</p>
 *
 * <p>由此可解释"{@code OtaNotificationProcessor.tick()}在本上下文里什么都没领到"：{@code enabled=false}
 * 时{@code tick()}在{@code !enabled}分支直接返回，worker并未异常或失效，而是本类刻意关掉的自动领取；
 * 生产环境该属性为默认{@code true}，自动领取照常工作。用例因此不调用{@code tick()}，而是调用它内部
 * 完全相同的四个事务方法序列，失败时点名被等待的持久事实。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaRealDispatchWithExternalSignerTests extends OtaRealDispatchHarness {
    /** 独占真实存储，启动失败不降级；容器由本用例类自己持有，端点解析不再依赖类继承顺序。 */
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            "minio/minio:RELEASE.2025-04-22T22-12-26Z")
            .withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    /**
     * 真实隔离Broker：通知投递的物理证据必须来自真实MQTT，而不是受控回执。
     *
     * <p>匿名订阅与allow授权只服务本夹具，不冒充应用设备鉴权验收。</p>
     */
    private static final GenericContainer<?> BROKER = new GenericContainer<>(
            DockerImageName.parse("emqx/emqx:6.2.3"))
            .withExposedPorts(1883, 18083)
            .withCopyToContainer(Transferable.of((BROKER_KEY + ":" + BROKER_SECRET + ":publisher\n")
                    .getBytes(StandardCharsets.UTF_8), 0444), "/opt/emqx/etc/ota-real-dispatch-api-keys")
            .withCopyToContainer(Transferable.of("""
                    # 覆盖镜像base.hocon后须显式恢复Dashboard/API监听，不能以节点存活冒充API就绪。
                    dashboard.listeners.http.bind = 18083
                    # 仅匿名发布观察夹具：受控Client ID为设备UUID，固定验证合法存量配置0。
                    listeners.tcp.default.mountpoint = "tc/private/device/${clientid}/"
                    authentication = []
                    authorization { no_match = allow, sources = [] }
                    api_key.bootstrap_file = "/opt/emqx/etc/ota-real-dispatch-api-keys"
                    """.getBytes(StandardCharsets.UTF_8), 0444), "/opt/emqx/etc/base.hocon")
            .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    /** Spring读取配置前先建立版本化私桶与Broker，失败时关闭本容器。 */
    static {
        MINIO.start();
        try (MinioClient admin = MinioClient.builder().endpoint(
                "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials(ACCESS, SECRET).build()) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET)
                    .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, null, null, null))
                    .build());
        } catch (Exception failure) {
            MINIO.stop();
            throw new ExceptionInInitializerError(failure);
        }
        BROKER.start();
        storageEndpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        signer = startSigner();
    }

    /** 真实隔离Broker的MQTT主机，真实协议订阅夹具在断言前使用。 */
    static final String BROKER_HOST = BROKER.getHost();
    /** 真实隔离Broker的MQTT映射端口，真实协议订阅夹具在断言前使用。 */
    static final int BROKER_PORT = BROKER.getMappedPort(1883);

    /**
     * 真实适配器由存储与Broker配置装配，并关闭全部OTA后台自动领取以保证断言确定性。
     *
     * <p>为什么连活动准入与通知投递都关闭：它们是每秒轮询的worker，若与断言并发运行，
     * "作业是否已DISPATCHED"会变成时序竞态。关闭自动领取后由用例显式调用同一生产入口，
     * 顺序确定且仍执行真实生产代码。</p>
     */
    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("things-link.storage.internal-endpoint", () -> storageEndpoint);
        registry.add("things-link.storage.external-endpoint", () -> storageEndpoint);
        registry.add("things-link.storage.access-key", () -> ACCESS);
        registry.add("things-link.storage.secret-key", () -> SECRET);
        registry.add("things-link.ota.storage.bucket", () -> BUCKET);
        registry.add("things-link.ota.upload.recovery-enabled", () -> false);
        registry.add("things-link.ota.publication.enabled", () -> false);
        registry.add("things-link.ota.campaign.runtime-enabled", () -> false);
        registry.add("things-link.ota.campaign-advancement.enabled", () -> false);
        registry.add("things-link.ota.notification.enabled", () -> false);
        registry.add("things-link.ota.download.allow-insecure-loopback", () -> true);
        registry.add("things-link.ota.type-baselines-json", OtaRealDispatchHarness::baselineSource);
        registry.add("things-link.ota.trust.anchors-json", OtaRealDispatchHarness::anchors);
        registry.add("things-link.ingestion.emqx-api.base-url",
                () -> "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(18083));
        registry.add("things-link.ingestion.emqx-api.api-key", () -> BROKER_KEY);
        registry.add("things-link.ingestion.emqx-api.api-secret", () -> BROKER_SECRET);
    }

    /** 测试全部结束后销毁自有容器。 */
    @AfterAll
    static void stopStorage() {
        BROKER.stop();
        MINIO.stop();
    }

    /** 生产适配器只由这三项真实配置装配，测试不提供任何本地签名bean。 */
    @DynamicPropertySource
    static void signing(DynamicPropertyRegistry registry) {
        registry.add("things-link.ota.signing.endpoint", OtaRealDispatchHarness::signerEndpoint);
        registry.add("things-link.ota.signing.token", () -> SIGNING_TOKEN);
        registry.add("things-link.ota.signing.allow-insecure-loopback", () -> true);
    }

    /** 必须恰好一个受控signer，且就是属性门控的生产适配器。 */
    @Autowired private ObjectProvider<OtaControlledReleaseSigner> signers;
    /** 无账号租约准入服务，测试显式驱动生产入口并保留精确失败首因。 */
    @Autowired private OtaCampaignAdmissionService admission;
    /** 真实数据库通知事务入口：领取/预留/观察都走生产短事务。 */
    @Autowired private OtaNotificationService notifications;
    /** 真实Broker发布端口，执行真实MQTT HTTP发布。 */
    @Autowired private OtaNotificationPublisher publisher;
    /** 真实设备报告接纳服务，报告不是测试直写表。 */
    @Autowired private OtaDeviceReportIngestionService reportIngestion;

    /** 本上下文真实启动了对象存储，因此受控上传必须落在真实版本对象上。 */
    @Override
    boolean objectStorage() {
        return true;
    }

    /**
     * 受控signer配置生效时，一次发布依次驱动READY、活动派发、派发意图、通知投递与Broker接收。
     */
    @Test
    void controlledSignerDrivesWholeDispatchChainToBroker() throws Exception {
        assertThat(signers.orderedStream().toList()).as("配置生效时受控signer必须恰好一个").hasSize(1);
        assertThat(signers.getIfAvailable()).as("唯一受控signer必须是属性门控的生产适配器")
                .isInstanceOf(HttpControlledReleaseSigner.class);
        Fixture fixture = seed(ProjectRole.ADMIN);
        Device device = currentDevice(fixture);
        Running run = publishedAndRunning(fixture, device);

        // 1）发布真的到READY，且采用事实与不可变release同时存在：不是"看起来提交了"。
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware WHERE id=?", String.class, run.firmware()))
                .as("签名回执必须被生产协调器复验后把固件推进到READY").isEqualTo("READY");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware_release WHERE firmware_id=?",
                Long.class, run.firmware())).as("READY必须伴随恰一条不可变release事实").isEqualTo(1L);
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE id=?",
                String.class, run.uploadId())).as("发布提交必须把上传会话推进到ADOPTED").isEqualTo("ADOPTED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware_publication WHERE firmware_id=? "
                + "AND status='COMMITTED'", Long.class, run.firmware()))
                .as("必须保留恰一条COMMITTED发布尝试").isEqualTo(1L);
        assertThat(SIGNER_CALLS.get()).as("受控signer测试替身必须被物理调用恰一次且不重试").isEqualTo(1);
        assertThat(LAST_KEY_VERSION.get()).as("生产适配器必须按冻结线格式请求release密钥版本").isEqualTo("release");

        // 2）排程冻结与启动：两条真实持久转移先于任何派发发生。
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, run.job()))
                .as("启动后作业必须仍为PENDING，启动本身不代表已派发").isEqualTo("PENDING");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_dispatch_outbox WHERE job_id=?",
                Long.class, run.job())).as("启动前不得存在任何派发意图").isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_notification_delivery WHERE job_id=?",
                Long.class, run.job())).as("启动前不得存在任何通知投递事实").isZero();

        // 3）真实租约准入：按生产准入worker的同一顺序领取并准入，作业进入DISPATCHED。
        var pending = admission.claimOne().orElseThrow(() ->
                new AssertionError("等待中的待准入作业必须可被真实数据库领取，当前状态="
                        + owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class,
                        run.job())));
        assertThat(pending.jobId()).as("领取到的必须是本例刚启动的那条作业").isEqualTo(run.job());
        assertThat(admission.admit(pending.jobId(), pending.token()))
                .as("真实租约准入必须接受当前合格设备，失败原因="
                        + owner().queryForObject("SELECT failure_code FROM ota_device_job WHERE id=?", String.class,
                        run.job()))
                .isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, run.job()))
                .as("真实租约准入必须把作业推进到DISPATCHED").isEqualTo("DISPATCHED");
        assertThat(owner().queryForList("SELECT actor_kind,from_status,to_status,from_revision,to_revision,actor_id "
                        + "FROM ota_job_transition WHERE job_id=?", run.job()))
                .as("DISPATCHED必须伴随一条SYSTEM/PENDING→DISPATCHED的独立转移事实")
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("actor_kind", "SYSTEM").containsEntry("actor_id", null)
                        .containsEntry("from_status", "PENDING").containsEntry("to_status", "DISPATCHED")
                        .containsEntry("from_revision", 0L).containsEntry("to_revision", 1L));

        // 4）派发意图必须精确对齐该尝试的manifest与凭据代际，而不是"有行就行"。
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_dispatch_outbox WHERE job_id=?",
                Long.class, run.job())).as("首次派发必须只留下恰一条派发意图").isEqualTo(1L);
        Map<String, Object> dispatch = owner().queryForMap(
                "SELECT id,attempt_no,manifest_sha256,device_id,credential_version,published_at "
                        + "FROM ota_job_dispatch_outbox WHERE job_id=?", run.job());
        assertThat(dispatch).as("派发意图的尝试号、设备与凭据代际必须与准入结果一致")
                .containsEntry("attempt_no", 1).containsEntry("device_id", device.id())
                .containsEntry("credential_version", device.credentialVersion()).containsEntry("published_at", null);
        assertThat(dispatch.get("manifest_sha256")).as("派发意图必须携带该活动冻结的manifest摘要")
                .isEqualTo(owner().queryForObject("SELECT manifest_sha256 FROM ota_campaign WHERE id=?",
                        String.class, run.campaign()));
        assertThat(owner().queryForObject("SELECT qualified_credential_version FROM ota_device_job WHERE id=?",
                Long.class, run.job())).as("准入必须记录实际使用的凭据代际").isEqualTo(device.credentialVersion());

        // 5）通知投递事实在准入同事务内生成：此时还没有任何物理路由或传输预算。
        UUID event = (UUID) dispatch.get("id");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_notification_delivery WHERE job_id=?",
                Long.class, run.job())).as("派发意图必须触发恰一条通知投递事实").isEqualTo(1L);
        assertThat(owner().queryForMap("SELECT status,topic,canonical,transport_count,deadline_at "
                        + "FROM ota_notification_delivery WHERE event_id=?", event))
                .as("通知投递在物理发送前必须处于WAITING且没有路由与传输预算")
                .containsEntry("status", "WAITING").containsEntry("transport_count", 0)
                .containsEntry("topic", null).containsEntry("canonical", null);

        // 6）真实Broker：先订阅，再按生产worker的同一顺序驱动通知的四个真实事务方法，
        // 最后比对物理收到的规范字节。
        String topic = OtaNotificationCodec.topic("ota_" + fixture.projectId().toString().replace("-", ""),
                device.deviceKey());
        // 生产通知worker的自动领取被显式关闭以消除轮询竞态，这里按同一顺序驱动它的四个生产事务方法：
        // claimOne（数据库交付租约）→ prepare（当前资格复验并在网络前预留一次尝试）→ publish（真实HTTP）
        // → complete（只追加真实观察并推进投递）。测试不绕过任何资格、租约或持久围栏。
        var claim = notifications.claimOne().orElseThrow(() ->
                new AssertionError("待发送通知必须可被真实数据库交付租约领取，当前投递状态="
                        + owner().queryForObject("SELECT status FROM ota_notification_delivery WHERE event_id=?",
                        String.class, event)));
        var prepared = notifications.prepare(claim.eventId(), claim.leaseToken()).orElseThrow(() ->
                new AssertionError("当前合格设备必须能完成通知预留，投递状态="
                        + owner().queryForObject("SELECT status FROM ota_notification_delivery WHERE event_id=?",
                        String.class, event) + " 活动状态="
                        + owner().queryForObject("SELECT status FROM ota_campaign WHERE id=?",
                        String.class, run.campaign())));
        assertThat(prepared.projectKey()).as("通知路由的项目短标识必须来自权威项目")
                .isEqualTo("ota_" + fixture.projectId().toString().replace("-", ""));
        assertThat(prepared.deviceKey()).as("通知路由的设备短标识必须来自权威设备")
                .isEqualTo(device.deviceKey());
        assertThat(prepared.route().deviceId()).isEqualTo(device.id());
        var transport = prepared.transport();
        assertThat(transport.topic()).as("通知路由必须来自权威项目与设备短标识").isEqualTo(topic);
        assertThat(transport.transportNo()).as("一次成功投递必须从第一次传输尝试开始").isEqualTo(1);
        String routeClientId = prepared.route().deviceId() + "/" + prepared.route().configVersion();
        try (MqttSubscriber subscriber = new MqttSubscriber(BROKER_HOST, BROKER_PORT, topic, routeClientId)) {
            OtaNotificationPublisher.Result result = publisher.publish(prepared.route(),
                    transport.canonical(), Duration.ofSeconds(5));
            assertThat(result.outcome()).as("真实Broker必须接受本次MQTT发布，失败原因=" + result.reason())
                    .isEqualTo(OtaNotificationPublisher.Outcome.BROKER_ACCEPTED);
            assertThat(notifications.complete(transport.id(), transport.reservationToken(), result))
                    .as("真实Broker观察必须能推进当前投递").isTrue();
            assertThat(subscriber.message(topic))
                    .as("真实Broker必须实际交付与投递事实逐字节相同的规范通知")
                    .isEqualTo(transport.canonical());
        }
        assertThat(awaitDeliveryStatus(event, "BROKER_ACCEPTED"))
                .as("Broker接收后通知投递必须落为BROKER_ACCEPTED").isEqualTo("BROKER_ACCEPTED");
        Map<String, Object> settled = owner().queryForMap(
                "SELECT topic,canonical,transport_count FROM ota_notification_delivery WHERE event_id=?", event);
        assertThat(settled.get("topic")).as("通知投递必须固化为权威路由").isEqualTo(topic);
        assertThat(settled).as("一次成功投递必须只消耗一次传输预算").containsEntry("transport_count", 1);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_notification_transport WHERE event_id=?",
                Long.class, event)).as("一次成功投递必须只产生一次物理传输预留").isEqualTo(1L);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_notification_delivery WHERE job_id=?",
                Long.class, run.job())).as("通知投递不得因重试放大成多条事实").isEqualTo(1L);

        // 7）整条链的高危审计：从发布到派发与通知观察都必须留下真实行。
        for (String action : List.of("ota.firmware.created", "ota.publication.created", "ota.publication.signed",
                "ota.publication.committed", "ota.campaign.created", "ota.campaign.scheduled", "ota.campaign.started")) {
            assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action=?",
                    Long.class, fixture.projectId(), action)).as("审计必须保留整条链的动作 " + action)
                    .isGreaterThanOrEqualTo(1L);
        }
        assertThat(owner().queryForList("SELECT actor_account_id,details->>'actorKind' AS actor_kind "
                        + "FROM sys_audit_log WHERE target_id=? AND action='ota.campaign.admission'", run.job()))
                .as("准入审计必须恰好一条且以SYSTEM身份记录、不带管理账号")
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("actor_account_id", null)
                        .containsEntry("actor_kind", "SYSTEM"));
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE action='ota.notification.delivery' "
                + "AND target_id=? AND details->>'action'='RESERVED'", Long.class, event))
                .as("通知预留必须留下RESERVED审计").isEqualTo(1L);

        // 8）运行投影必须承认这是一次真实派发，而不是"作业表恰好变了"。
        JsonNode execution = execution(run);
        assertThat(execution.path("dispatchedCount").asLong()).as("活动投影必须报告一次已派发").isEqualTo(1L);
        assertThat(execution.path("pendingCount").asLong()).as("活动投影不得残留待派发作业").isZero();
        assertThat(execution.path("status").asText()).as("派发成功不得使活动暂停").isEqualTo("RUNNING");
    }
}

/**
 * 同一个文件里的兄弟类：完整前置造好后**不配置**{@code things-link.ota.signing.endpoint}，
 * 因此属性门控的生产适配器根本不存在；证明移除配置后链路整体失败关闭，而不是降级成别的签名器。
 *
 * <p>为什么不复用主用例的上下文：class级{@code @DynamicPropertySource}在Spring里的优先级高于
 * {@code @TestPropertySource}，只要继承带端点登记的上下文，端点就必然存在，无法表达"移除配置"。
 * 因此失败关闭必须由一个不登记该属性的独立上下文承载，而不是在测试里临时清空配置——
 * 那样只是伪造，不是证据。</p>
 *
 * <p>本上下文不启动MinIO：失败关闭用例只走到发布创建前的70016，物理对象网络从未发生，
 * 只证明"移除signer配置"这一件事，不把一次多余的容器启动混进证据。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaRealDispatchWithoutExternalSignerTests extends OtaRealDispatchHarness {
    /** 未配置端点时必须回到零个受控signer。 */
    @Autowired private ObjectProvider<OtaControlledReleaseSigner> signers;

    /** 存储端点只声明不连接：本用例不执行任何物理对象读写，也刻意不起第二个MinIO。 */
    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("things-link.storage.internal-endpoint", () -> "http://127.0.0.1:19000");
        registry.add("things-link.storage.external-endpoint", () -> "http://127.0.0.1:19000");
        registry.add("things-link.storage.access-key", () -> ACCESS);
        registry.add("things-link.storage.secret-key", () -> SECRET);
        registry.add("things-link.ota.storage.bucket", () -> BUCKET);
        registry.add("things-link.ota.upload.recovery-enabled", () -> false);
        registry.add("things-link.ota.publication.enabled", () -> false);
        registry.add("things-link.ota.campaign.runtime-enabled", () -> false);
        registry.add("things-link.ota.campaign-advancement.enabled", () -> false);
        registry.add("things-link.ota.notification.enabled", () -> false);
        registry.add("things-link.ota.download.allow-insecure-loopback", () -> true);
        registry.add("things-link.ota.type-baselines-json", OtaRealDispatchHarness::baselineSource);
        registry.add("things-link.ota.trust.anchors-json", OtaRealDispatchHarness::anchors);
    }

    /** 端点缺失时发布必须在创建前70016，且不得留下任何READY或活动事实。 */
    @Test
    void absentEndpointKeepsPublicationAndCampaignFailClosed() throws Exception {
        assertThat(signers.orderedStream().toList()).as("未配置端点时必须保持零个受控signer").isEmpty();
        Fixture fixture = seed(ProjectRole.ADMIN);
        Device device = currentDevice(fixture);
        Prepared prepared = preparePublication(fixture);
        // 基线先登记，排除"缺基线"成为本用例的拒绝原因；上传对象在本上下文从未被读取。
        registerBaseline(prepared);

        error(send(fixture, "POST", prepared.path(), key(), prepared.body(), false), 503, 70016);

        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware_publication WHERE firmware_id=?",
                Long.class, prepared.firmware())).as("70016拒绝不得创建任何发布尝试").isZero();
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware WHERE id=?",
                String.class, prepared.firmware())).as("缺少受控signer时固件必须停留在DRAFT").isEqualTo("DRAFT");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware_release WHERE firmware_id=?",
                Long.class, prepared.firmware())).as("没有发布尝试时不得存在release事实").isZero();
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE id=?",
                String.class, prepared.upload().id()))
                .as("发布被拒后上传会话状态不得被测试或拒绝路径改写")
                .isEqualTo(objectStorage() ? "VERIFIED" : "RECEIVING");
        assertThat(SIGNER_CALLS.get()).as("没有任何受控signer时可被物理调用").isZero();
        // 没有READY发布物就没有发布资格，活动创建必须在锁定设备前拒绝；
        // 用真实HTTP确认拒绝码，而不是靠人工观察"应该不会创建"。
        error(send(fixture, "POST", campaigns(fixture), key(), plan(prepared.firmware(), List.of(device.id())), false),
                404, 70021);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign WHERE project_id=?",
                Long.class, fixture.projectId())).as("缺少READY发布物时不得留下任何活动事实").isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_device_job WHERE project_id=?",
                Long.class, fixture.projectId())).as("缺少READY发布物时不得留下任何作业或设备预占").isZero();
    }
}
