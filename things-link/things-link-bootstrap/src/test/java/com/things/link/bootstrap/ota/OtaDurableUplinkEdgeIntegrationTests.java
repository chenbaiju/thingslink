package com.things.link.bootstrap.ota;

import com.things.link.ota.application.OtaBusinessRetryService;
import com.things.link.ota.application.OtaCampaignAdmissionService;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.ota.application.OtaCommitPermitDeliveryService;
import com.things.link.ota.application.OtaCommitPermitPublisher;
import com.things.link.ota.application.OtaCommitReceiptCodec;
import com.things.link.ota.application.OtaDeviceReportIngestionService;
import com.things.link.ota.application.OtaDownloadAuthorizationService;
import com.things.link.ota.application.OtaDownloadResponsePublisher;
import com.things.link.ota.application.OtaInstallStopDeliveryService;
import com.things.link.ota.application.OtaInstallStopOperationService;
import com.things.link.ota.application.OtaInstallStopPublisher;
import com.things.link.ota.application.OtaNotificationPublisher;
import com.things.link.ota.application.OtaNotificationService;
import com.things.link.ota.application.OtaTypeBaselineCodec;
import com.things.link.ota.domain.OtaCommitPermitDeliveryRepository;
import com.things.link.ota.domain.OtaDownloadAuthorizationRepository;
import com.things.link.ota.domain.OtaInstallStopDeliveryRepository;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.simulator.application.MqttDeviceClient;
import com.things.link.simulator.application.MqttDeviceMessageHandler;
import com.things.link.simulator.application.ota.OtaArtifactException;
import com.things.link.simulator.application.ota.OtaArtifactSource;
import com.things.link.simulator.application.ota.OtaFaultPlan;
import com.things.link.simulator.application.ota.OtaJournalRecord;
import com.things.link.simulator.application.ota.OtaReasonCode;
import com.things.link.simulator.application.ota.OtaStage;
import com.things.link.simulator.application.ota.contract.OtaDeviceDownloadRequestCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceHealthCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceInstallStopReportCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceTopics;
import com.things.link.simulator.application.ota.session.OtaDeviceRuntime;
import com.things.link.simulator.application.ota.session.OtaSimulatorEvidence;
import com.things.link.simulator.infrastructure.mqtt.PahoMqttDeviceClientFactory;
import com.things.link.simulator.infrastructure.ota.FileOtaStateJournal;
import com.things.link.simulator.infrastructure.ota.HttpRangeArtifactSource;
import com.things.link.simulator.infrastructure.ota.SystemOtaClock;
import com.things.link.support.mqtt.BrokerIngressProperties;
import com.things.link.support.mqtt.BrokerIngressReadiness;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.storage.VersionedStorageControl;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.VersioningConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S13-4d-2b-2b-1：生产「设备上行边缘」首次端到端接通，而不是再一次证明某一段单元行为。
 *
 * <p><b>本片补的是哪一段证据：</b>已有用例分别证明了设备侧接线（真实Broker上的设备状态机）、
 * ingress的manual-ACK顺序、{@code RawUplinkKafkaConsumer}的持久分流。但没有任何用例把两端接起来，
 * 于是「真实设备用真实一机一密凭据连上EMQX，经生产HTTP认证回调取得权威身份，经生产ACL与
 * {@code tc_durable_uplink}规则重投到内部Topic，再经生产durable ingress进Kafka，最后经生产
 * raw消费者落成PG事实」这条链从未被端到端验证。本类就是这条链的完整证据。</p>
 *
 * <p><b>为什么必须用真实设备而不是构造信封：</b>本片的核心断言是持久事实里的
 * {@code credential_version}等于凭据API返回的真实代际。若测试自己拼 {@code RawUplinkMessage}，
 * 认证身份就是测试输入的，断言只能证明「数据库会存我给的数字」。只有让EMQX在CONNECT时回调生产
 * {@code /api/v1/emqx/auth}，由EMQX把{@code client_attrs}写进规则引擎的信封，身份才可能来自生产
 * 代码。设备密钥因此必须取自凭据生成响应（数据库中只有摘要，读不回明文）。</p>
 *
 * <p><b>生产配置的复制来源与唯一改动：</b>EMQX的{@code authentication}/{@code authorization}/
 * {@code rule_engine.rules.tc_durable_uplink}文本逐字复制自{@code deploy/emqx/base.hocon}
 * 第43-115行（连同同文件的{@code durable_sessions}/{@code cluster.discovery_strategy}/
 * {@code dashboard.listeners.http.bind}/{@code mqtt.session_expiry_interval}，因为非clean会话的
 * durable ingress与v5 REST API都依赖它们）。<b>唯一改动</b>是：两处回调URL从固定的
 * {@code :8080}改为本用例静态选定并真实监听的端口，回调共享密钥改为本用例自己的测试字面量
 * （并同时通过{@code DynamicPropertySource}注入后端，两边逐字节一致）。<b>刻意省略</b>的是
 * 同文件的{@code connectors.http.tc_device_lifecycle}与两条在线/离线规则：本片不断言在线态事实，
 * 省略它们既不会放宽认证与ACL，也不会改变上行接管路径。</p>
 *
 * <p><b>为什么应用端口必须先于容器确定：</b>EMQX在读取{@code base.hocon}时就要把回调地址写死，
 * 因此回调URL里的端口必须在容器启动前可知。这里用{@code ServerSocket(0)}在静态初始化时选一个空闲
 * 端口，用{@code DEFINED_PORT}让Spring真的监听它；用例再显式断言该端口真的可连，避免「配置写了
 * 一个没人监听的端口」被误当成通过。</p>
 *
 * <p><b>为什么先等{@code BrokerIngressReadiness}再连设备：</b>{@code EmqxAuthService}在ingress
 * 尚未完成内部Topic订阅时拒绝一切设备连接。等它既是用例消抖，也是本片要证明的生产顺序：
 * 设备绝不在接管者出现之前被放行。</p>
 *
 * <p><b>signer测试替身的资格边界：</b>复用4g夹具的{@code HttpControlledReleaseSigner}测试替身
 * （本地HTTP {@link com.sun.net.httpserver.HttpServer}，用测试私钥按冻结线格式签真实Ed25519）。
 * 它是<b>外部服务的测试替身，不是KMS/HSM，也不构成G3硬件资格证据</b>。</p>
 *
 * <p><b>S13-4d-2b-2b-2补的那一段：</b>{@link #realDownloadDeliveryDrivesDeviceClosedLoopToConfirming()}
 * 在同一真实边缘上加上生产下载投递顺序，让真实设备用真实MinIO预签名地址走真实HTTP Range取字节，
 * 并让进度/健康经同一条边缘回到平台，使作业由{@code DISPATCHED}收敛到{@code CONFIRMING}。
 * <b>S13-4d-2b-2b-3再次强化：</b>该用例里的重启后{@code HEALTH_CHECKING}进度不再由用例代发——运行时
 * 在真实重启边界轮换启动身份并自己发出该帧，平台必须将其采用为{@code AUTHENTICATED_PROGRESS}（关闭D-156）；
 * 健康心跳仍按{@code OtaSimulatorEvidence}的设计由掌握事实的调用方显式构造并经会话健康接口发布。
 * <b>S13-4d-2b-2b-4关闭D-155：</b>该用例在同一真实边缘上继续按生产顺序投递唯一提交许可，
 * 让真实设备运行时消费许可并在同一条已认证连接上发出{@code tc-ota-commit-receipt/v1}，
 * 平台必须把作业由{@code CONFIRMING}采用为{@code SUCCEEDED}，至此设备驱动闭环的最后一跳有真实边缘证据。</p>
 *
 * <p><b>S13-4d-2b-2b-5补的那一段（取消安全点），S13-4d-2b-2b-6修复D-157并去掉封存变通：</b>
 * {@link #realCampaignCancellationDrivesDeviceStopWinsOverProductionEdge()}把「管理端取消」也接到同一真实边缘上：
 * 平台经生产HTTP取消入口进入{@code CANCELLING}，按生产顺序创建并投递唯一安装前停止操作（真实EMQX下行），
 * 真实{@code OtaDeviceRuntime}消费该操作、只依据自己的耐久日志裁决出「尚未进入安全阶段」并发出真实
 * {@code STOPPED}报告，平台再把报告采用为{@code CANCELLED}（理由
 * {@code ATTEMPT_DURABLY_STOPPED_BEFORE_INSTALL}），作业与活动收敛而没有任何伪造的进度/成功事实。
 * 设备真实下载申请会在平台侧同事务生成一条<b>未封存</b>下载授权，作业因此仍停在{@code DISPATCHED}；
 * S13-4d-2b-2b-5曾实测到{@code OtaInstallStopOperationService#seedOne}只携带
 * {@code OtaJobProgressRepository#locate}返回的已封存授权（SQL条件{@code sealed_at IS NOT NULL}），
 * 而{@code ota_install_stop_create}要求命令携带作业<b>全部</b>下载授权
 * （{@code array_agg(id ORDER BY id) FROM ota_download_authorization WHERE job_id=...}），于是该窗口的停止创建
 * 被数据库以{@code install stop immutable command tuple invalid}（SQLSTATE 23514）整事务拒绝，后台每秒重试
 * 永久失败（D-157）。修复后{@code seedOne}经安装停止仓储按{@code ORDER BY id}读取全部授权id（与DB函数同序），
 * 因此本用例<b>不再做任何签址/封存变通</b>：它在{@code DISPATCHED}且授权未封存的最短窗口里直接取消，
 * 并要求整条停止环仍然完成。</p>
 *
 * <p><b>S13-4d-2b-2b-7的六场景覆盖表（可审计）：</b>六个设备场景现在分别由本类的哪一个用例负责，逐条列在这里。</p>
 * <ol>
 *   <li><b>断点续传（断电后重投同一封存响应）</b>：{@link #realDownloadReissueAfterPowerLossResumesFromConfirmedOffset()}
 *       ——S13-4d-2b-2b-9补齐设备侧耐久受理身份，S13-4d-2b-2b-12补齐平台侧「同身份同冻结窗口」重投，本片
 *       首次在真实边缘上证明从已确认偏移续传并收敛到{@code SUCCEEDED}（关闭D-159/D-161平台半）。</li>
 *   <li><b>断电于{@code INSTALLING}</b>：{@link #faultedDeviceRunsNeverProducePlatformSuccess()}的场景2——
 *       真实耐久日志停在{@code INSTALLING}/{@code POWER_LOST}且结论为{@code SAFETY_PAUSED}；
 *       重启后才出现的{@code INSTALL_OUTCOME_UNKNOWN}观察现由独立启动路径验证，下载重投限制见下面
 *       「INSTALL_OUTCOME_UNKNOWN为什么仍不可达」段落。</li>
 *   <li><b>首错安全暂停</b>：同上的场景3——{@code FIRST_CHUNK_FAILED_SAFETY_PAUSE}，只取字节一次。</li>
 *   <li><b>失败阈值耗尽</b>：同上的场景4——{@code FAILED}/{@code DOWNLOAD_FAILURE_BUDGET_EXHAUSTED}。</li>
 *   <li><b>恢复拒绝</b>：同上的场景5——保持已确认偏移并写下{@code RESUME_REJECTED}，绝不从0重来。</li>
 *   <li><b>取消安全点/停止先赢</b>：
 *       {@link #realCampaignCancellationDrivesDeviceStopWinsOverProductionEdge()}。</li>
 * </ol>
 * <p>下载投递闭环（至{@code CONFIRMING}/{@code SUCCEEDED}）由
 * {@link #realDownloadDeliveryDrivesDeviceClosedLoopToConfirming()}覆盖；真实设备上行边缘由
 * {@link #realDeviceUplinkBecomesPlatformFactThroughProductionEdge()}覆盖。</p>
 *
 * <p><b>跨重启断点续传如何被接上（D-159设备半 + D-161平台半）：</b>设备侧把「已接纳下载响应」的七个身份字段
 * 耐久写入{@code &lt;deviceDir&gt;/accepted-download}，重投时必须逐字段一致且耐久日志停在非终态才从已确认偏移
 * 继续；平台侧对同一{@code (jobId,attemptNo)}的第二份申请不再无条件拒绝，而是当活动仍RUNNING、作业仍在同一
 * 尝试的{@code DOWNLOADING}、设备当前资格与执行来源仍合格、原冻结响应窗口仍有余量且{@code transport_count<3}
 * 时登记一次幂等重投，由后台重新签址、再封存同一身份的新密文并消耗第二次传输预算；身份、封存时刻、响应窗口、
 * DOWNLOADING阶段事实与全部DEFERRABLE一致性不变量都不改变（见迁移
 * {@code V20260913_1030__ota_download_reissue_same_identity_same_window.sql}的冻结设计说明）。</p>
 *
 * <p><b>安装后恢复：</b>原下载重投仍受DOWNLOADING与原响应窗口约束，不能用于安装后的再次交付。
 * ADR0212选择独立的启动耐久观察，不重新取得下载能力；
 * {@link #realInstallationRestartRecordsUnknownWithoutNewDownloadAuthorization()}从真实派发与安装中断开始，
 * 验证运行时重建自然记录INSTALL_OUTCOME_UNKNOWN且没有第二次下载、传输或成功上报。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// D-160：重试到期worker必须关闭，否则它会与用例显式驱动的生产重试归类竞争同一到期租约。
@TestPropertySource(properties = "things-link.ota.retry.runtime-enabled=false")
class OtaDurableUplinkEdgeIntegrationTests extends OtaRealDispatchHarness {

    /** EMQX容器内的API密钥引导文件路径，只服务本夹具的v5 REST发布。 */
    private static final String API_KEY_FILE = "/opt/emqx/etc/ota-durable-uplink-api-keys";

    /**
     * 静态选定的应用监听端口。
     *
     * <p>必须先于EMQX容器建立：容器读取的HOCON里回调URL是字面量，没有第二次机会改写。</p>
     */
    static final int APP_PORT = freePort();

    /** 经 Testcontainers 显式暴露的宿主后端回调地址，Linux Runner 与桌面 Docker 共用。 */
    private static final String CALLBACK_BASE = "http://host.testcontainers.internal:" + APP_PORT;

    /** 本用例自己的Broker回调共享密钥；必须≥32字节，且与写入EMQX头部的字面量逐字节一致。 */
    private static final String CALLBACK_SECRET = "durable-uplink-edge-broker-callback-secret";

    /** 生产ingress服务身份；与{@code application.yml}默认值一致，不含斜杠。 */
    private static final String INGRESS_USERNAME = "thingslink-uplink-ingress";

    /** ingress服务密码；必须≥32字节，否则应用按生产不变量拒绝启动。 */
    private static final String INGRESS_PASSWORD = "durable-uplink-edge-ingress-password-32b";

    /**
     * 等待生产ingress完成内部Topic订阅的预算。
     *
     * <p><b>实测现象（多次复现，日志可核对）：</b>Tomcat开始监听后第一次CONNECT立刻到达
     * {@code DurableUplinkMqttIngress}，但此时EMQX的HTTP认证后端对
     * 容器回调宿主应用时得不到allow，
     * Paho因此抛 {@code MqttSecurityException}；生产ingress按1/2/4/8秒有界退避重试，随后连上并完成
     * SUBACK。两次独立运行的失败次数不同：一次为3次（约9秒后成功），一次为4次（约16秒后成功），
     * 应用日志显示首个真正进入DispatcherServlet的回调正是最后那次成功尝试，说明此前的尝试根本没有
     * 到达MVC边界。#6 Linux Runner 使用未显式暴露的 host.docker.internal 时始终不能就绪，
     * 因此本夹具改用 Testcontainers 的宿主端口代理；等待预算取45秒是为了覆盖更长退避级数
     * （1+2+4+8+16=31秒再加连接时间），而不是放宽顺序：设备连接仍然严格发生在该等待返回之后。</p>
     */
    private static final Duration INGRESS_READY_BUDGET = Duration.ofSeconds(45);

    /** 响应只作断言，不能伪造生产事实。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 本用例独占真实存储，启动失败不降级。 */
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            "minio/minio:RELEASE.2025-04-22T22-12-26Z")
            .withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    /**
     * 本用例独占的真实EMQX：认证、授权与durable上行规则都按生产配置生效。
     *
     * <p>与4g类不同，这里刻意<b>不</b>放行匿名：认证链只有HTTP后端，授权{@code no_match=deny}。
     * 4g类只需要「能发布」，本片要的是「谁被允许发布」。</p>
     */
    private static final GenericContainer<?> BROKER = new GenericContainer<>(DockerImageName.parse("emqx/emqx:6.2.3"))
            .withExposedPorts(1883, 18083)
            .withCopyToContainer(Transferable.of((BROKER_KEY + ":" + BROKER_SECRET + ":publisher\n")
                    .getBytes(StandardCharsets.UTF_8), 0444), API_KEY_FILE)
            .withCopyToContainer(Transferable.of(emqxConfiguration().getBytes(StandardCharsets.UTF_8), 0444),
                    "/opt/emqx/etc/base.hocon")
            .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    /** Spring读取配置前先建立版本化私桶与真实Broker，失败时关闭本容器。 */
    static {
        Testcontainers.exposeHostPorts(APP_PORT);
        MINIO.start();
        try (MinioClient admin = MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
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

    /**
     * 直接读取设备Broker部署单源，避免手抄认证属性、缓存、挂载点和上行规则发生漂移。
     * 仅替换本轮回调地址、秘密及独占API身份，保留全部生产规则。
     * @return 当前部署配置对应的独占测试节点配置
     */
    private static String emqxConfiguration() {
        try {
            return Files.readString(Path.of("../../deploy/emqx/base.hocon"))
                    .replace("http://host.docker.internal:8080", CALLBACK_BASE)
                    .replace("dev-only-broker-callback-secret-do-not-use-in-production", CALLBACK_SECRET)
                    + "\napi_key.bootstrap_file = \"" + API_KEY_FILE + "\"\n";
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /**
     * 真实适配器、真实ingress与真实Kafka都由本用例的容器装配，并关闭全部OTA后台自动领取。
     *
     * <p>关闭后台自动领取的理由与4g一致：每秒轮询的worker与断言并发会让「作业是否已DISPATCHED」
     * 退化成时序竞态。除4g已关闭的四个worker外，这里还关闭
     * {@code things-link.ota.download-authorization.enabled}：该worker会自动领取排队意图并签发
     * 对象地址，既超出本片「只发布通知、不投递下载响应」的范围，也会让「排队意图必须保持未发布」
     * 这条断言变成与时序赛跑。用例因此按生产顺序显式驱动领取/准入/通知四个生产事务方法，
     * 并另外把{@code spring.kafka.listener.auto-startup}显式钉为false，由用例手动启动raw监听器。</p>
     *
     * @param registry Spring测试上下文的动态属性注册表
     */
    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> APP_PORT);
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
        // 本片只发布通知、不投递下载响应；下载授权worker会自动领取排队意图并签发对象地址，
        // 那既超出本片范围，也会让「排队意图必须保持未发布」这条断言变成与时序赛跑。
        registry.add("things-link.ota.download-authorization.enabled", () -> false);
        // 提交许可worker同样显式关闭：许可交付由本用例按生产顺序显式驱动，后台抢占会让
        // 「设备只在显式投递后才提交」这条顺序断言变成竞态。
        registry.add("things-link.ota.commit-permit.enabled", () -> false);
        registry.add("things-link.ota.download.allow-insecure-loopback", () -> true);
        // 下载响应的秘密信封必须有可用密钥环：缺配置时生产 prepareSigning 会以
        // DOWNLOAD_DEPENDENCY_UNAVAILABLE 暂停授权，本片就无法证明任何投递顺序。
        registry.add("things-link.ota.download-response.active-key-version", () -> "test-v1");
        registry.add("things-link.ota.download-response.keyring-json",
                () -> "{\"test-v1\":\"" + java.util.Base64.getEncoder().encodeToString(new byte[32]) + "\"}");
        registry.add("things-link.ota.type-baselines-json", OtaRealDispatchHarness::baselineSource);
        registry.add("things-link.ota.trust.anchors-json", OtaRealDispatchHarness::anchors);
        // S13-4d-2b-2b-5：取消安全点需要受控安装前停止扩展基线。该配置只由运维声明（与父基线同源），
        // 与OtaInstallStopIntegrationTests的stopSource同一构造方式；缺它时生产停止操作只会退避而不创建。
        registry.add("things-link.ota.install-stop.type-baselines-json",
                OtaDurableUplinkEdgeIntegrationTests::stopBaselineSource);
        registry.add("things-link.ingestion.emqx-api.base-url",
                () -> "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(18083));
        registry.add("things-link.ingestion.emqx-api.api-key", () -> BROKER_KEY);
        registry.add("things-link.ingestion.emqx-api.api-secret", () -> BROKER_SECRET);
        // 回调密钥两边必须逐字节一致：这里是后端，EMQX的headers里是同一个字面量。
        registry.add("things-link.security.broker-callback.secret", () -> CALLBACK_SECRET);
        registry.add("things-link.ingress.handoff.enabled", () -> true);
        registry.add("things-link.ingress.handoff.broker-uri",
                () -> "tcp://" + BROKER.getHost() + ":" + BROKER.getMappedPort(1883));
        registry.add("things-link.ingress.handoff.username", () -> INGRESS_USERNAME);
        registry.add("things-link.ingress.handoff.password", () -> INGRESS_PASSWORD);
        registry.add("things-link.ingress.handoff.qualification.enabled", () -> false);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
    }

    /** 生产适配器只由这三项真实配置装配，测试不提供任何本地签名bean。 */
    @DynamicPropertySource
    static void signing(DynamicPropertyRegistry registry) {
        registry.add("things-link.ota.signing.endpoint", OtaRealDispatchHarness::signerEndpoint);
        registry.add("things-link.ota.signing.token", () -> SIGNING_TOKEN);
        registry.add("things-link.ota.signing.allow-insecure-loopback", () -> true);
    }

    /** 无账号租约准入服务，测试显式驱动生产入口并保留精确失败首因。 */
    @Autowired private OtaCampaignAdmissionService admission;
    /** 真实数据库通知事务入口：领取/预留/观察都走生产短事务。 */
    @Autowired private OtaNotificationService notifications;
    /** 真实Broker发布端口，通知经EMQX v5 REST API发出。 */
    @Autowired private OtaNotificationPublisher publisher;
    /** 生产ingress订阅就绪闸门；设备连接前必须为真。 */
    @Autowired private BrokerIngressReadiness ingressReadiness;
    /** 仅启动生产raw监听器，不禁用生产消费者。 */
    @Autowired private KafkaListenerEndpointRegistry listeners;
    /** 生产下载授权短事务入口：领取/签址预留/封存/发送预留/观察都走生产事务方法。 */
    @Autowired private OtaDownloadAuthorizationService authorizations;
    /**
     * 生产设备报告接纳入口：同一项目里的第二个及以后设备不能再走 {@code publishedAndRunning} 里的信任包
     * 导入与类型基线登记（两者的 {@code expectedRevision=0} CAS围栏只允许成功一次），因此由本用例先显式
     * 接纳各设备的真实报告，再为它建立同固件活动。
     */
    @Autowired private OtaDeviceReportIngestionService deviceReports;
    /** 生产Broker下载响应发布端口，响应经EMQX v5 REST API发出。 */
    @Autowired private OtaDownloadResponsePublisher downloadResponses;
    /** 生产提交许可短事务入口：领取/预留路由/真实观察都走生产事务方法。 */
    @Autowired private OtaCommitPermitDeliveryService commitPermits;
    /** 生产Broker提交许可发布端口，唯一许可经EMQX v5 REST API发出。 */
    @Autowired private OtaCommitPermitPublisher commitPermitPublisher;
    /** 生产安装前停止操作短事务入口：后台worker在测试profile关闭，用例显式驱动唯一创建。 */
    @Autowired private OtaInstallStopOperationService stopOperations;
    /** 生产业务有限重试归类服务：领取到期事实并按冻结预算派发新尝试。 */
    @Autowired private OtaBusinessRetryService retries;
    /** 生产安装前停止交付短事务入口：领取/预留/真实观察都走生产事务方法。 */
    @Autowired private OtaInstallStopDeliveryService stopDeliveries;
    /** 生产Broker安装前停止发布端口，停止命令经EMQX v5 REST API落到设备真实订阅的下行Topic。 */
    @Autowired private OtaInstallStopPublisher stopPublisher;
    /** 生产版本化私桶端口；本用例不装配任何存储替身，预签名地址必须来自真实MinIO。 */
    @Autowired private ObjectProvider<VersionedPrivateObjectStorage> storage;
    /** JUnit独占设备目录：staging.bin/ota.log/boot-id都真实落盘。 */
    @TempDir private Path journalDirectory;

    /** 本上下文真实启动了对象存储，因此受控上传必须落在真实版本对象上。 */
    @Override
    boolean objectStorage() {
        return true;
    }

    /** 测试全部结束后销毁自有容器。 */
    @AfterAll
    static void stopOwnedContainers() {
        BROKER.stop();
        MINIO.stop();
    }

    /**
     * 主证据：真实设备上行穿过整条生产边缘，落成带真实凭据代际的PG事实。
     *
     * @throws Exception 真实HTTP/数据库/容器任一步失败时直接抛出，不吞首因
     */
    @Test
    void realDeviceUplinkBecomesPlatformFactThroughProductionEdge() throws Exception {
        EdgeScenario edge = openDeviceUplinkEdge();
        try {
            UUID run = edge.run().job();
            UUID event = edge.event();
            Map<String, Object> dispatch = edge.dispatch();
            Map<String, Object> receipt = edge.receipt();

            // 8）排队意图必须与同一次接纳、同一作业/尝试/清单严格关联，且本片不得交付下载授权。
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_request_outbox WHERE receipt_id=?",
                    Long.class, receipt.get("id"))).as("一次接纳必须恰好留下一条排队意图").isEqualTo(1L);
            Map<String, Object> outbox = owner().queryForMap(
                    "SELECT event_type,published_at FROM ota_download_request_outbox WHERE receipt_id=?",
                    receipt.get("id"));
            assertThat(outbox).as("排队意图必须固定为接纳事件且本片绝不交付下载授权")
                    .containsEntry("event_type", "OTA_DOWNLOAD_REQUEST_ACCEPTED")
                    .containsEntry("published_at", null);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_request_outbox o "
                            + "JOIN ota_download_request r ON r.id=o.receipt_id "
                            + "WHERE r.job_id=? AND r.attempt_no=? AND r.manifest_sha256=?",
                    Long.class, run, 1, dispatch.get("manifest_sha256")))
                    .as("排队意图必须与ota_job_dispatch_outbox同作业/尝试/清单关联").isEqualTo(1L);

            // 9）下行自检：运行投影承认这次真实派发，且没有第二条申请被凭空放大。
            JsonNode execution = execution(edge.run());
            assertThat(execution.path("dispatchedCount").asLong()).as("活动投影必须报告一次已派发")
                    .isEqualTo(1L);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_request WHERE device_id=?",
                    Long.class, edge.device().id())).as("同一作业尝试不得被放大成多条申请事实").isEqualTo(1L);
            assertThat(event).as("派发意图身份必须非空").isNotNull();
        } finally {
            edge.close();
        }
    }

    /**
     * S13-4d-2b-2b-2（S13-4d-2b-2b-3 强化）：真实下载投递驱动的设备闭环——作业从DISPATCHED收敛到CONFIRMING。
     *
     * <p><b>本片补的是哪一段证据：</b>S13-4d-2b-2b-1只证明了「设备申请经真实边缘落成平台事实」，
     * 刻意不投递下载响应，因此设备的取字节、验摘要与分阶段进度从未被真实派发驱动过。本用例沿用同一
     * 真实边缘（EMQX认证/ACL/durable规则→ingress→Kafka→生产raw消费者→生产OTA处理器），加上生产
     * 下载授权处理器逐字的法律顺序：领取→预留签址→真实MinIO预签名→封存→预留发送→真实Broker→观察。</p>
     *
     * <p><b>为什么必须按生产处理器而不是调用更少的服务方法：</b>“平台会下发一份结构合法的响应”
     * 与“平台按ADR0129在期限内只签一次址、只发一次秘密、并把结果落成授权事实”是两件事。
     * 这里逐条复刻{@code OtaDownloadAuthorizationProcessor.process}的预算规则（
     * {@code seconds∈[1,54]}、{@code VersionedStorageControl(min(5s, 剩余期限), 取消检查)}），
     * 封存后断言作业被{@code ota_download_seal}推进到{@code DOWNLOADING}且理由恰为
     * {@code DOWNLOAD_AUTHORIZED}——该转移由数据库函数在封存事务内写入，与设备进度无关。</p>
     *
     * <p><b>设备侧真实执行：</b>运行中的真实{@code OtaDeviceRuntime}已在同一连接上订阅
     * {@code down/ota/download/response}；它用响应里的MinIO预签名地址走真实HTTP
     * {@code Range: bytes=0-}取字节、写真实暂存区与fsync耐久日志、核对完整SHA-256，
     * 并把阶段转换编成{@code up/ota/progress}。用例断言的是设备自己的耐久日志（存在、阶段序列、
     * 终态{@code COMMITTED}、暂存区字节数等于artifactSize），而不是内存里的返回值。</p>
     *
     * <p><b>重启边界为什么现在由运行时自己产生（关闭D-156）：</b>ADR0131第57行要求
     * “首条VERIFYING冻结本次bootId；INSTALLING/REBOOTING保持该bootId，HEALTH_CHECKING必须为新的
     * bootId”。修复前{@code OtaDeviceRuntime}的{@code boot-id}在构造时生成一次并跨重启复用，
     * 它自己以{@code assertHealth=true}发出的HEALTH_CHECKING必然与首条VERIFYING同bootId，被平台以
     * {@code BOOT_SESSION_MISMATCH}拒绝并转{@code RECOVERY_REQUIRED}，因此旧版本用例只能由用例用
     * 生产编解码器与生产证据构建器代发那一帧（见{@code docs/DEBT.md}的D-156）。本片让运行时在
     * {@code REBOOTING}帧发出之后、{@code HEALTH_CHECKING}证据构造之前真实轮换并耐久落盘新的启动身份，
     * 因此这里改成只等待并断言<b>运行时自己发出的</b>四条进度：前三阶段带{@code originBootId}
     * （VERIFYING冻结值），HEALTH_CHECKING带轮换后的新bootId，且四条都必须被采用为
     * {@code AUTHENTICATED_PROGRESS}。没有任何一帧由测试代发，也未放宽任何断言。</p>
     *
     * <p><b>健康心跳为什么仍由调用方提供：</b>{@code OtaSimulatorEvidence}把自检/看门狗健康定义为
     * “必须由掌握该事实的调用方显式构造”的输入，模拟器没有受保护计数器与真实自检，因此运行时只搬运
     * 事实、不发明心跳。本用例按真实观察构造心跳，并经运行时暴露的会话健康接口
     * （{@code OtaDeviceRuntime#publishHealth}→{@code OtaDeviceSession#publishHealth}）在同一条真实
     * 已认证连接上发布；这正是D-156要求明确的“谁负责按计划窗口产生健康心跳”——是调用方，而不是模拟器。</p>
     *
     * <p><b>健康窗口为什么需要多条心跳：</b>活动计划冻结的{@code healthWindowSeconds=60}要求
     * 首条与当前健康观察的真实Broker时间间隔≥60秒，且相邻观察间隔≤30秒、设备声明的
     * {@code uptime}/{@code healthyFor}增量相等。用例因此按25秒间隔发送4条健康心跳（末条距首条约75秒），
     * 末条才把作业采用为{@code CONFIRMING}。</p>
     *
     * <p><b>SUCCEEDED那一跳（S13-4d-2b-2b-4关闭D-155）：</b>健康窗口确认时平台已把唯一提交许可
     * 与作业状态、健康回执放在同一事务里持久。用例随后按生产{@code OtaCommitPermitProcessor}的
     * 领取→预留→真实Broker→观察顺序显式驱动许可投递（后台worker在测试profile里关闭），
     * 由真实设备运行时在同一条已认证连接上消费许可、构造十九字段已提交证据并发回
     * {@code tc-ota-commit-receipt/v1}。平台必须把它采用为{@code SUCCEEDED}（理由
     * {@code DEVICE_COMMITTED_AND_BOUND}），至此设备驱动的作业首次在真实边缘上走完整条闭环。</p>
     *
     * @throws Exception 真实HTTP/MQTT/Kafka/数据库任一步失败时直接抛出，不吞首因
     */
    @Test
    void realDownloadDeliveryDrivesDeviceClosedLoopToConfirming() throws Exception {
        EdgeScenario edge = openDeviceUplinkEdge();
        try {
            UUID job = edge.run().job();
            String manifestSha256 = (String) edge.dispatch().get("manifest_sha256");
            // 首条VERIFYING会冻结这个身份；安装前的三个阶段都必须保持它（ADR0131）。
            UUID originBootId = edge.runtime().bootId();

            // 1）生产投递顺序（领取→签址预留→真实MinIO预签名→封存→重新领取SEALED→发送预留→真实Broker→观察）
            //    由共享辅助方法逐字执行；三个用例因此建立在完全相同的真实投递事实上。
            DownloadDelivery delivery = deliverDownloadResponse(edge);
            UUID authorizationId = delivery.authorizationId();
            assertThat(delivery.job()).as("共享投递辅助必须作用于本例真实作业").isEqualTo(job);
            assertThat(delivery.manifestSha256()).as("共享投递辅助必须绑定本例冻结清单摘要")
                    .isEqualTo(manifestSha256);

            // 4）设备侧：真实HTTP Range取字节、验摘要、耐久日志走到COMMITTED。
            List<OtaJournalRecord> records = awaitDeviceConclusion(edge.runtime(), Duration.ofSeconds(60));
            assertThat(edge.runtime().journalFile()).as("设备耐久日志必须真实落盘").exists();
            assertThat(records).as("设备耐久日志必须记录真实阶段序列")
                    .extracting(OtaJournalRecord::stage)
                    .containsSubsequence(OtaStage.DOWNLOADING, OtaStage.VERIFYING, OtaStage.INSTALLING,
                            OtaStage.REBOOTING, OtaStage.HEALTH_CHECKING, OtaStage.COMMITTED);
            OtaJournalRecord committed = records.getLast();
            assertThat(committed.stage()).as("健康运行必须在设备侧走到终态COMMITTED，实际阶段序列=" + stages(records))
                    .isEqualTo(OtaStage.COMMITTED);
            assertThat(committed.confirmedDigestSoFar()).as("设备确认摘要必须等于目标artifact摘要")
                    .isEqualTo(committed.artifactSha256());
            assertThat(Files.size(edge.runtime().stagingFile())).as("暂存区字节数必须等于清单artifactSize")
                    .isEqualTo(committed.artifactSize());

            // 5）设备真实进度（含运行时自己在重启边界后发出的HEALTH_CHECKING）经同一条边缘被平台采用。
            //    四条进度由同一个真实运行时按VERIFYING→INSTALLING→REBOOTING→HEALTH_CHECKING顺序发出，
            //    因此这里等待四个阶段各留一条事实，而不是等待某一个中间作业状态（后者会与真实到达竞态）。
            awaitProgressStages(job, List.of("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING"),
                    Duration.ofSeconds(30));
            List<Map<String, Object>> progress = owner().queryForList(
                    "SELECT progress_seq,stage,boot_id,adopted_status FROM ota_job_progress WHERE job_id=? ORDER BY progress_seq",
                    job);
            assertThat(progress).as("四个设备阶段必须各留一条已采用进度事实，实际=" + progress)
                    .extracting(row -> row.get("stage"))
                    .containsExactly("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING");
            assertThat(progress).as("四条设备进度都必须被采用为其真实阶段（与AUTHENTICATED_PROGRESS转移同事务），实际="
                            + progress)
                    .extracting(row -> row.get("adopted_status"))
                    .containsExactly("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING");

            // 6）ADR0131的重启边界由运行时自己的真实上行产生：前三阶段保持VERIFYING冻结的bootId，
            //    HEALTH_CHECKING携带安装后真实重启产生的新bootId；这正是D-156关闭的证据。
            UUID rebootedBootId = edge.runtime().bootId();
            assertThat(rebootedBootId).as("运行时必须越过真实重启边界轮换启动身份，重启前后不能是同一bootId")
                    .isNotEqualTo(originBootId);
            assertThat(progress).as("安装前三阶段必须保持首条VERIFYING冻结的bootId，HEALTH_CHECKING必须是"
                            + "重启后的新bootId；运行时当前bootId=" + rebootedBootId + "，实际=" + progress)
                    .extracting(row -> row.get("boot_id"))
                    .containsExactly(originBootId, originBootId, originBootId, rebootedBootId);
            awaitJobStatus(job, "HEALTH_CHECKING", Duration.ofSeconds(30));

            // 7）健康心跳：真实Broker时间跨过计划冻结的60秒窗口后才允许采用CONFIRMING。
            //    心跳仍由调用方按真实观察显式构造，并经运行时暴露的会话健康接口发布（见
            //    OtaDeviceRuntime#publishHealth / OtaDeviceSession#publishHealth）：模拟器只搬运事实，
            //    不发明自检与看门狗观察。
            publishHealthWindow(edge, authorizationId, rebootedBootId, records, manifestSha256);
            awaitJobStatus(job, "CONFIRMING", Duration.ofSeconds(90));

            // 8）平台侧经真实边缘收敛到的完整事实。
            assertThat(jobStatus(job)).as("健康窗口确认后设备闭环必须先把作业停在CONFIRMING，再等待平台唯一许可")
                    .isEqualTo("CONFIRMING");
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? "
                            + "AND reason='AUTHENTICATED_PROGRESS' AND to_status IN "
                            + "('VERIFYING','INSTALLING','REBOOTING','HEALTH_CHECKING')",
                    Long.class, job)).as("四个设备阶段必须各留一条AUTHENTICATED_PROGRESS转移").isEqualTo(4L);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_health_receipt WHERE job_id=?",
                    Long.class, job)).as("健康上行必须逐条落成ota_health_receipt事实").isEqualTo(4L);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_health_receipt WHERE job_id=? "
                            + "AND adopted_status='CONFIRMING'", Long.class, job))
                    .as("健康窗口确认必须由最后一条健康观察采用为CONFIRMING").isEqualTo(1L);
            assertThat(owner().queryForObject("SELECT boot_id FROM ota_health_receipt WHERE job_id=? "
                    + "AND adopted_status='CONFIRMING'", UUID.class, job))
                    .as("被采用的健康观察必须携带重启后的新bootId").isEqualTo(rebootedBootId);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? "
                            + "AND to_status='CONFIRMING' AND reason='HEALTH_WINDOW_CONFIRMED'", Long.class, job))
                    .as("CONFIRMING必须由健康窗口确认转移产生").isEqualTo(1L);

            // 9）真实提交许可投递顺序（关闭D-155）：领取→预留→真实Broker→观察。
            //    许可由健康窗口确认事务持久；后台许可worker在测试profile里显式关闭，因此这里按生产
            //    OtaCommitPermitProcessor.processOwned 的预算规则显式驱动，顺序与下载响应完全同构。
            var permitClaim = awaitCommitPermitClaim(job, Duration.ofSeconds(15));
            UUID permitId = permitClaim.permit().id();
            assertThat(permitClaim.permit().jobId()).as("可领取的提交许可必须绑定本例真实作业").isEqualTo(job);
            assertThat(permitClaim.permit().attemptNo()).isEqualTo(1);
            assertThat(permitClaim.permit().authorizationId()).as("许可必须绑定平台真实授权")
                    .isEqualTo(authorizationId);
            assertThat(permitClaim.permit().manifestSha256()).as("许可必须绑定平台冻结清单摘要")
                    .isEqualTo(manifestSha256);
            assertThat(permitClaim.permit().bootId()).as("许可必须绑定健康窗口确认时的重启后启动身份")
                    .isEqualTo(rebootedBootId);
            var permitPrepared = commitPermits.prepare(permitId, permitClaim.leaseToken())
                    .orElseThrow(() -> new AssertionError("真实提交许可必须能预留一次发送，当前许可状态="
                            + permitClaim.status() + " 作业状态=" + jobStatus(job)
                            + " 活动状态=" + campaignStatus(edge.run().campaign())));
            assertThat(permitPrepared.projectKey()).as("许可路由的项目短标识必须来自权威项目")
                    .isEqualTo(edge.projectKey());
            assertThat(permitPrepared.deviceKey()).as("许可路由的设备短标识必须来自权威设备")
                    .isEqualTo(edge.device().deviceKey());
            // 与生产处理器逐字同规则：物理交换预算不越过许可原期限，也不越过本次交付租约。
            Instant permitExpiry = Instant.ofEpochSecond(
                    permitPrepared.transport().permit().deadlineAt().getEpochSecond());
            Instant permitLimit = permitExpiry.isBefore(permitPrepared.leaseUntil())
                    ? permitExpiry : permitPrepared.leaseUntil();
            OtaCommitPermitPublisher.Result permitResult = commitPermitPublisher.publish(permitPrepared.route(), permitPrepared.transport().permit().canonical(), permitExpiry,
                    permitLimit, budget(Instant.now(), permitExpiry, permitPrepared.leaseUntil()));
            assertThat(permitResult.outcome()).as("真实Broker必须接受本次提交许可，失败原因=" + permitResult.reason())
                    .isEqualTo(OtaCommitPermitPublisher.Outcome.BROKER_ACCEPTED);
            assertThat(commitPermits.complete(permitPrepared.transport().id(),
                    permitPrepared.transport().reservationToken(), permitResult))
                    .as("真实Broker观察必须能推进本次许可传输").isTrue();

            // 10）设备运行时必须在同一条已认证连接上消费许可并发出真实提交回执。
            //     字节由测试装饰器从真实客户端出口旁路复制，回执用平台权威解码器复核。
            String commitReceiptTopic = OtaDeviceTopics.forDevice(OtaDeviceTopics.COMMIT_RECEIPT,
                    edge.projectKey(), edge.device().deviceKey());
            byte[] commitReceiptUplink = edge.recording().awaitUplink(commitReceiptTopic, Duration.ofSeconds(20));
            OtaCommitReceiptCodec.Receipt commitReceipt =
                    new OtaCommitReceiptCodec().decode(commitReceiptUplink).value();
            assertThat(commitReceipt.permitId()).as("设备回执必须引用平台签发的唯一许可").isEqualTo(permitId);
            assertThat(commitReceipt.jobId()).as("设备回执必须绑定本例真实作业").isEqualTo(job);
            assertThat(commitReceipt.attemptNo()).isEqualTo(1);
            assertThat(commitReceipt.authorizationId()).as("设备回执必须回显平台真实授权")
                    .isEqualTo(authorizationId);
            assertThat(commitReceipt.manifestSha256()).as("设备回执必须回显平台冻结清单摘要")
                    .isEqualTo(manifestSha256);
            assertThat(commitReceipt.bootId()).as("设备回执必须携带重启后的真实启动身份")
                    .isEqualTo(rebootedBootId);
            assertThat(commitReceipt.evidence().committedSecurityVersion())
                    .as("提交回执必须声明已提交安全版本等于目标安全版本")
                    .isEqualTo(commitReceipt.evidence().securityVersion());

            // 11）平台必须把真实提交回执采用为SUCCEEDED，并留下精确可核对的不可变事实。
            awaitJobStatus(job, "SUCCEEDED", Duration.ofSeconds(30));
            assertThat(jobStatus(job)).as("真实许可+真实提交回执必须把作业采用为SUCCEEDED")
                    .isEqualTo("SUCCEEDED");
            Map<String, Object> adoptedReceipt = owner().queryForMap(
                    "SELECT receipt_id,permit_id,boot_id,manifest_sha256,committed_security_version,"
                            + "adopted_status,canonical FROM ota_commit_receipt WHERE job_id=?", job);
            assertThat(adoptedReceipt.get("permit_id")).as("持久回执的许可身份必须等于平台签发的许可")
                    .isEqualTo(permitId);
            assertThat(adoptedReceipt.get("boot_id")).as("持久回执的启动身份必须等于设备重启后的真实身份")
                    .isEqualTo(rebootedBootId);
            assertThat(adoptedReceipt.get("manifest_sha256")).as("持久回执必须绑定平台冻结清单摘要")
                    .isEqualTo(manifestSha256);
            assertThat(adoptedReceipt.get("committed_security_version"))
                    .as("持久回执的已提交安全版本必须等于目标安全版本")
                    .isEqualTo(commitReceipt.evidence().securityVersion());
            assertThat(adoptedReceipt.get("adopted_status")).as("平台必须把该回执采用为SUCCEEDED")
                    .isEqualTo("SUCCEEDED");
            assertThat(adoptedReceipt.get("receipt_id")).as("设备生成的回执身份必须被平台如实持久")
                    .isEqualTo(commitReceipt.receiptId());
            assertThat((byte[]) adoptedReceipt.get("canonical"))
                    .as("持久规范字节必须与设备真实发送的回执字节逐字节相同")
                    .isEqualTo(commitReceiptUplink);
            // ota_commit_receipt没有授权列；授权事实直接从平台保存的设备原字节复核，避免改写成测试输入。
            assertThat(new OtaCommitReceiptCodec().decode((byte[]) adoptedReceipt.get("canonical")).value()
                    .authorizationId()).as("持久回执的授权身份必须等于平台真实授权").isEqualTo(authorizationId);
            Map<String, Object> successTransition = owner().queryForMap("SELECT from_status,to_status,reason "
                    + "FROM ota_job_transition WHERE job_id=? AND to_status='SUCCEEDED'", job);
            assertThat(successTransition).as("SUCCEEDED必须由设备真实提交转移产生")
                    .containsEntry("from_status", "CONFIRMING").containsEntry("to_status", "SUCCEEDED")
                    .containsEntry("reason", "DEVICE_COMMITTED_AND_BOUND");
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? "
                            + "AND to_status='SUCCEEDED'", Long.class, job))
                    .as("真实提交只允许产生一条SUCCEEDED转移").isEqualTo(1L);
        } finally {
            edge.close();
        }
    }

    /** ADR0212：真实平台派发后在安装中断，启动只形成未知观察，不签发第二份下载能力。 */
    @Test
    void realInstallationRestartRecordsUnknownWithoutNewDownloadAuthorization() throws Exception {
        EdgeScenario edge = openDeviceUplinkEdge(OtaFaultPlan.builder().powerLossAt(OtaStage.INSTALLING).build(),
                HttpRangeArtifactSource::new);
        try {
            DownloadDelivery delivery = deliverDownloadResponse(edge);
            List<OtaJournalRecord> before = awaitJournal(edge.runtime(), Duration.ofSeconds(30),
                    rows -> !rows.isEmpty() && rows.getLast().stage() == OtaStage.INSTALLING
                            && rows.getLast().reasonCode() == OtaReasonCode.POWER_LOST,
                    "真实安装阶段中断");
            awaitProgressCount(edge.run().job(), 3L, Duration.ofSeconds(30), "中断前进度已由平台采用");
            Map<String, Object> authorization = owner().queryForMap(
                    "SELECT id,response_expires_at,sealed_at,transport_count FROM ota_download_authorization WHERE id=?",
                    delivery.authorizationId());
            byte[] staging = java.nio.file.Files.readAllBytes(edge.runtime().stagingFile());
            Map<String, Integer> outbound = new java.util.HashMap<>();
            edge.recording().outbound.forEach((topic, queue) -> outbound.put(topic, queue.size()));
            java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();
            java.util.function.Supplier<OtaArtifactSource> deniedSource = () -> (uri, offset, length, budget) -> {
                downloads.incrementAndGet();
                throw new AssertionError("安装未知恢复不得读取任何artifact字节");
            };
            OtaDeviceRuntime restarted = new OtaDeviceRuntime(edge.projectKey(), edge.device().deviceKey(),
                    edge.recording(), edge.runtime().deviceDirectory(), edge.evidence(), true, true,
                    new SystemOtaClock(), deniedSource, OtaFaultPlan.NONE);
            restarted.start();
            restarted.start();
            new OtaDeviceRuntime(edge.projectKey(), edge.device().deviceKey(), edge.recording(),
                    edge.runtime().deviceDirectory(), edge.evidence(), true, true, new SystemOtaClock(),
                    deniedSource, OtaFaultPlan.NONE).start();
            List<OtaJournalRecord> after = new FileOtaStateJournal(restarted.journalFile()).read();
            assertThat(after).hasSize(before.size() + 1);
            assertThat(after.getLast().reasonCode()).isEqualTo(OtaReasonCode.INSTALL_OUTCOME_UNKNOWN);
            assertThat(after.getLast().conclusion()).isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(downloads).hasValue(0);
            assertThat(java.nio.file.Files.readAllBytes(restarted.stagingFile())).isEqualTo(staging);
            Map<String, Integer> outboundAfter = new java.util.HashMap<>();
            edge.recording().outbound.forEach((topic, queue) -> outboundAfter.put(topic, queue.size()));
            assertThat(outboundAfter).as("启动和重复启动均不发送下载申请、进度或成功帧").isEqualTo(outbound);
            assertThat(owner().queryForMap(
                    "SELECT id,response_expires_at,sealed_at,transport_count FROM ota_download_authorization WHERE id=?",
                    delivery.authorizationId())).isEqualTo(authorization);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_authorization WHERE job_id=?",
                    Long.class, edge.run().job())).isEqualTo(1L);
            assertNoFabricatedSuccess(edge.run().job(), edge.run().campaign(), reportableStages(before),
                    "安装中断后自然启动观察");
        } finally {
            edge.close();
        }
    }

    /**
     * S13-4d-2b-2b-12（关闭D-161平台半）：真实边缘上的「下载中断电→同目录重启→设备自己重投→平台重签重投→
     * 设备从耐久已确认偏移续传并收敛」。
     *
     * <p><b>本片补的那一段：</b>S13-4d-2b-2b-9已证明设备侧能凭 {@code accepted-download} 与耐久日志识别同一份
     * 已受理响应并从已确认偏移续传，但平台侧当时无法合法地再发一次：授权在 {@code BROKER_ACCEPTED} 后终态、
     * {@code ota_download_reserve_signing} 只接受 {@code WAITING}、同一尝试的第二份申请被
     * {@code ota_download_request_job_attempt_uk} 与 {@code findByJobAttempt→invalid()} 拒绝（D-159/D-161）。本用例
     * 在同一真实边缘上走完整条链：首次真实投递→设备只取到3字节后真的掉电→同目录新运行时重启并自己重投同一尝试
     * 的下载申请→平台把它登记为<b>同身份同冻结窗口</b>的幂等重投→后台重新签址/再封存/第二次发送→设备从
     * {@code bytes=3-} 续传并走到 {@code COMMITTED}→四条进度与健康心跳经真实边缘被采用→作业收敛到
     * {@code SUCCEEDED}。全程没有测试自造授权、没有延长地址期限、没有放宽设备fail-closed判定。</p>
     *
     * <p><b>为什么分片上限刻意设为3字节：</b>{@code HttpRangeArtifactSource(3, ...)} 让首次执行在第一个真实HTTP
     * 分片后就被 {@code powerLossAt(DOWNLOADING)} 打断，于是耐久日志留下一个<b>严格小于artifactSize</b>的已确认
     * 偏移；重启后的续传必须真实发出 {@code bytes=3-}，这让「从已确认偏移续传」可被直接观测，而不是靠
     * 「偏移恰好等于全长的巧合」。</p>
     *
     * <p><b>为什么重启复用同一条已认证连接：</b>重现的是<b>进程重启</b>边界——设备目录、耐久日志、暂存区与
     * 已受理响应记录都跨重启复用，新的 {@code OtaDeviceRuntime} 重新订阅同一批下行Topic（处理器被替换，
     * 旧实例不再收到任何报文）。掉线重连路径本身由既有重连用例覆盖，本用例不重复证明它。</p>
     *
     * <p><b>INSTALL_OUTCOME_UNKNOWN为什么仍不由本机制可达（如实记录，不伪造）：</b>该场景要求设备在
     * {@code INSTALLING} 断电后重启仍能被同一份响应驱动到「安装结果未知」的耐久结论；但重投被<b>原封冻响应
     * 窗口</b>与 {@code ota_download_current} 的 {@code j.status='DOWNLOADING'} 双重限定，设备一旦越过下载阶段，
     * 作业不再是 {@code DOWNLOADING}、窗口也已关闭；要重新打开就必须放宽 {@code ota_download_consistency} 里
     * 「活动传输行期限必须等于授权期限」这条DEFERRABLE不变量（传输行是不可改写证据），那属于ADR级决定。
     * 因此本片只把「下载阶段断电后重启续传」接到真实边缘上，不宣称安装阶段未知已可达。</p>
     *
     * @throws Exception 真实HTTP/MQTT/Kafka/数据库任一步失败时直接抛出，不吞首因
     */
    @Test
    void realDownloadReissueAfterPowerLossResumesFromConfirmedOffset() throws Exception {
        EdgeScenario edge = openDeviceUplinkEdge(OtaFaultPlan.builder().powerLossAt(OtaStage.DOWNLOADING).build(),
                () -> new HttpRangeArtifactSource(3L,
                        com.things.link.simulator.application.ota.OtaDownloadAssignment.MAX_ARTIFACT_SIZE));
        try {
            UUID job = edge.run().job();
            String manifestSha256 = (String) edge.dispatch().get("manifest_sha256");
            String uplinkTopic = OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, edge.projectKey(),
                    edge.device().deviceKey());

            // 1）首次真实投递（与既有用例同一顺序）后，设备只取到3字节就真的掉电。
            DownloadDelivery delivery = deliverDownloadResponse(edge);
            UUID authorizationId = delivery.authorizationId();
            List<OtaJournalRecord> interrupted = awaitJournal(edge.runtime(), Duration.ofSeconds(30),
                    records -> !records.isEmpty() && records.getLast().reasonCode() == OtaReasonCode.POWER_LOST
                            && records.getLast().stage() == OtaStage.DOWNLOADING,
                    "下载阶段真实掉电");
            long confirmedOffset = interrupted.getLast().downloadedBytes();
            assertThat(confirmedOffset).as("掉电点必须留下真实且严格小于artifactSize的已确认前缀偏移，实际="
                    + confirmedOffset + " 阶段序列=" + stages(interrupted)).isEqualTo(3L);
            assertThat(interrupted.getLast().conclusion()).as("掉电结论必须是设备侧安全暂停")
                    .isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(authorizationStatus(authorizationId)).as("首次真实发送被Broker接受后授权必须落到已接受终态")
                    .isEqualTo("BROKER_ACCEPTED");

            // 2）设备进程重启：同目录新运行时（同一已认证连接，处理器被重新订阅替换）。
            //    分片上限仍是3字节，因此「从已确认偏移续传」与「从0重来」在耐久日志上留下不同条数：
            //    续传只会有 offset=6/7 两条分片记录，从0重来会留下 3/6/7 三条。
            OtaDeviceRuntime restarted = new OtaDeviceRuntime(edge.projectKey(), edge.device().deviceKey(),
                    edge.recording(), edge.runtime().deviceDirectory(), edge.evidence(), true, true,
                    new SystemOtaClock(),
                    () -> new HttpRangeArtifactSource(3L,
                            com.things.link.simulator.application.ota.OtaDownloadAssignment.MAX_ARTIFACT_SIZE),
                    OtaFaultPlan.NONE);
            restarted.start();

            // 3）设备重启后自己重投同一尝试的下载申请，并且经整条真实边缘落成平台重投登记。
            byte[] reRequest = edge.recording().awaitUplink(uplinkTopic, Duration.ofSeconds(30));
            OtaDeviceDownloadRequestCodec.Request reRequested = OtaDeviceDownloadRequestCodec.decode(reRequest).value();
            assertThat(reRequested.jobId()).as("重投必须指向同一作业").isEqualTo(job);
            assertThat(reRequested.attemptNo()).as("重投必须指向同一尝试").isEqualTo(1);
            assertThat(reRequested.manifestSha256()).as("重投必须携带同一冻结清单摘要").isEqualTo(manifestSha256);
            assertThat(reRequested.requestId()).as("重投必须生成新的请求标识，而尝试身份不变")
                    .isNotEqualTo(edge.decoded().value().requestId());
            awaitReissueRequested(authorizationId, Duration.ofSeconds(30));
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_request WHERE device_id=?",
                    Long.class, edge.device().id())).as("重投绝不放大成第二条申请事实").isEqualTo(1L);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_authorization WHERE job_id=?",
                    Long.class, job)).as("重投绝不新建第二条授权").isEqualTo(1L);

            // 4）平台按生产顺序重投：领取（已接受+重投标记）→重签址→真实MinIO预签名→再封存→发送预留→真实Broker→观察。
            var reissueClaim = awaitDownloadClaim(Duration.ofSeconds(15));
            assertThat(reissueClaim.authorizationId()).as("可领取的重投必须是同一条授权").isEqualTo(authorizationId);
            assertThat(reissueClaim.status()).as("重投必须由仍处已接受终态的授权领取").isEqualTo("BROKER_ACCEPTED");
            var reissueSigning = authorizations.prepareSigning(authorizationId, reissueClaim.leaseToken())
                    .orElseThrow(() -> new AssertionError("已接受授权必须在冻结窗口内可重签址，当前状态="
                            + authorizationStatus(authorizationId) + " 作业状态=" + jobStatus(job)
                            + " 活动状态=" + campaignStatus(edge.run().campaign())));
            assertThat(reissueSigning.expiresAt()).as("重投绝不延长原冻结响应窗口")
                    .isEqualTo(delivery.expiresAt());
            URI reissueUrl = presignDownload(reissueSigning);
            assertThat(authorizations.seal(authorizationId, reissueClaim.leaseToken(), reissueUrl))
                    .as("重投必须能再封存一次密文，当前状态=" + authorizationStatus(authorizationId)).isTrue();
            var resendClaim = awaitDownloadClaim(Duration.ofSeconds(15));
            var resending = authorizations.prepareSend(authorizationId, resendClaim.leaseToken())
                    .orElseThrow(() -> new AssertionError("重投封存后必须能预留第二次发送能力，当前状态="
                            + authorizationStatus(authorizationId)));
            assertThat(resending.transport().transportNo()).as("重投必须消耗冻结的第二次传输预算").isEqualTo(2);
            assertThat(resending.expiresAt()).as("第二次传输仍绑原冻结窗口").isEqualTo(delivery.expiresAt());
            OtaDownloadResponsePublisher.Result resendResult = downloadResponses.publish(resending.route(), resending.canonical(), resending.expiresAt(),
                    budget(Instant.now(), resending.expiresAt(), resending.leaseUntil()));
            assertThat(resendResult.outcome()).as("真实Broker必须接受重投响应，失败原因=" + resendResult.reason())
                    .isEqualTo(OtaDownloadResponsePublisher.Outcome.BROKER_ACCEPTED);
            assertThat(authorizations.complete(resending.transport().id(), resending.transport().reservationToken(),
                    resendResult)).as("真实Broker观察必须能推进重投传输").isTrue();

            // 5）设备必须从掉电前已确认的偏移续传并走到耐久COMMITTED；续传证据来自设备自己的日志。
            List<OtaJournalRecord> completed = awaitDeviceConclusion(restarted, Duration.ofSeconds(60));
            assertThat(completed).as("重启后的真实执行必须记录完整阶段序列，实际=" + stages(completed))
                    .extracting(OtaJournalRecord::stage)
                    .containsSubsequence(OtaStage.DOWNLOADING, OtaStage.VERIFYING, OtaStage.INSTALLING,
                            OtaStage.REBOOTING, OtaStage.HEALTH_CHECKING, OtaStage.COMMITTED);
            OtaJournalRecord committed = completed.getLast();
            assertThat(committed.stage()).as("健康运行必须在设备侧走到终态COMMITTED，实际阶段序列=" + stages(completed))
                    .isEqualTo(OtaStage.COMMITTED);
            assertThat(committed.confirmedDigestSoFar()).as("设备确认摘要必须等于目标artifact摘要")
                    .isEqualTo(committed.artifactSha256());
            assertThat(Files.size(restarted.stagingFile())).as("暂存区必须等于完整artifactSize")
                    .isEqualTo(committed.artifactSize());
            List<OtaJournalRecord> afterRestart = completed.subList(interrupted.size(), completed.size());
            assertThat(afterRestart).as("重启后必须有新的耐久记录").isNotEmpty();
            List<Long> resumedChunkOffsets = afterRestart.stream()
                    .filter(record -> record.stage() == OtaStage.DOWNLOADING
                            && record.reasonCode() == OtaReasonCode.NONE)
                    .map(OtaJournalRecord::downloadedBytes).toList();
            assertThat(resumedChunkOffsets)
                    .as("续传必须从掉电前已确认的偏移继续：重启后分片上限同样是3字节，因此只应有"
                            + confirmedOffset + "+3与artifactSize两条分片记录；任何从0重来的执行都会多留下"
                            + confirmedOffset + "那一条。实际=" + resumedChunkOffsets)
                    .containsExactly(confirmedOffset + 3L, committed.artifactSize());
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_transport WHERE authorization_id=?",
                    Long.class, authorizationId)).as("两次真实传输必须各自留一条不可变传输事实").isEqualTo(2L);
            Map<String, Object> reissued = owner().queryForMap(
                    "SELECT id,transport_count,status,response_expires_at,sealed_at FROM ota_download_authorization WHERE id=?",
                    authorizationId);
            assertThat(((Number) reissued.get("transport_count")).intValue())
                    .as("重投只递增传输预算，不重置任何计数器").isEqualTo(2);
            assertThat(reissued.get("id")).as("重投绝不改变授权身份").isEqualTo(authorizationId);
            assertThat(reissued.get("status")).isEqualTo("BROKER_ACCEPTED");
            assertThat(((java.sql.Timestamp) reissued.get("response_expires_at")).toInstant())
                    .as("重投绝不改变原冻结响应窗口").isEqualTo(delivery.expiresAt());

            // 6）四条设备进度与健康心跳仍经同一条真实边缘被平台采用，作业收敛到SUCCEEDED。
            awaitProgressStages(job, List.of("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING"),
                    Duration.ofSeconds(30));
            UUID rebootedBootId = restarted.bootId();
            publishHealthWindow(edge, authorizationId, rebootedBootId, completed, manifestSha256);
            awaitJobStatus(job, "CONFIRMING", Duration.ofSeconds(90));
            var permitClaim = awaitCommitPermitClaim(job, Duration.ofSeconds(15));
            var permitPrepared = commitPermits.prepare(permitClaim.permit().id(), permitClaim.leaseToken())
                    .orElseThrow(() -> new AssertionError("真实提交许可必须能预留一次发送，当前许可状态="
                            + permitClaim.status() + " 作业状态=" + jobStatus(job)));
            Instant permitExpiry = Instant.ofEpochSecond(
                    permitPrepared.transport().permit().deadlineAt().getEpochSecond());
            Instant permitLimit = permitExpiry.isBefore(permitPrepared.leaseUntil())
                    ? permitExpiry : permitPrepared.leaseUntil();
            OtaCommitPermitPublisher.Result permitResult = commitPermitPublisher.publish(permitPrepared.route(), permitPrepared.transport().permit().canonical(), permitExpiry,
                    permitLimit, budget(Instant.now(), permitExpiry, permitPrepared.leaseUntil()));
            assertThat(permitResult.outcome()).as("真实Broker必须接受本次提交许可，失败原因=" + permitResult.reason())
                    .isEqualTo(OtaCommitPermitPublisher.Outcome.BROKER_ACCEPTED);
            assertThat(commitPermits.complete(permitPrepared.transport().id(),
                    permitPrepared.transport().reservationToken(), permitResult))
                    .as("真实Broker观察必须能推进本次许可传输").isTrue();
            String commitReceiptTopic = OtaDeviceTopics.forDevice(OtaDeviceTopics.COMMIT_RECEIPT,
                    edge.projectKey(), edge.device().deviceKey());
            byte[] commitReceiptUplink = edge.recording().awaitUplink(commitReceiptTopic, Duration.ofSeconds(20));
            OtaCommitReceiptCodec.Receipt commitReceipt =
                    new OtaCommitReceiptCodec().decode(commitReceiptUplink).value();
            assertThat(commitReceipt.authorizationId()).as("重投路径上的提交回执必须回显同一条平台授权")
                    .isEqualTo(authorizationId);
            assertThat(commitReceipt.bootId()).as("提交回执必须携带重启后真实启动身份")
                    .isEqualTo(rebootedBootId);
            awaitJobStatus(job, "SUCCEEDED", Duration.ofSeconds(30));
            assertThat(jobStatus(job)).as("真实重投+真实续传+真实提交必须把作业采用为SUCCEEDED")
                    .isEqualTo("SUCCEEDED");
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? "
                            + "AND reason='DOWNLOAD_AUTHORIZED'", Long.class, job))
                    .as("重投绝不重复写入DOWNLOADING阶段转移").isEqualTo(1L);
        } finally {
            edge.close();
        }
    }

    /** 有界等待平台把设备重投申请登记为幂等重投标记；超时打印真实授权事实，绝不把“还没到”当成“已登记”。 */
    private void awaitReissueRequested(UUID authorizationId, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        Map<String, Object> observed = Map.of();
        while (System.nanoTime() < deadline) {
            observed = owner().queryForMap("SELECT status,transport_count,reissue_requested_at,reissue_signed_at "
                    + "FROM ota_download_authorization WHERE id=?", authorizationId);
            if (observed.get("reissue_requested_at") != null) {
                return;
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待平台登记设备重投申请超时，预算=" + budget + "，最后授权事实=" + observed);
    }

    /**
     * S13-4d-2b-2b-5：真实活动取消经整条生产边缘到达真实设备，设备按耐久日志判「停止先赢」并把真实停止报告送回平台。
     *
     * <p><b>本片补的是哪一段证据：</b>已有用例分别证明了停止事务服务的原子性（本地HTTP替身）与真实设备上行边缘，
     * 但没有任何一条证据把「管理端取消」接到真实设备上。本用例在同一真实边缘上要求：生产取消入口让活动进入
     * {@code CANCELLING}；平台按生产顺序创建并投递唯一安装前停止操作；真实{@code OtaDeviceRuntime}消费该操作、
     * 只读自己的耐久日志裁决出「尚未进入安全阶段」并发出真实{@code STOPPED}报告；平台把这份经真实EMQX ACL/
     * durable规则回流的报告采用为作业{@code CANCELLED}。这正是六个OTA设备场景里第一个（取消安全点）的平台可见结果。</p>
     *
     * <p><b>为什么设备停在安全阶段之前，以及为什么最短窗口就是「未封存即取消」：</b>
     * {@link #openDeviceUplinkEdge()}只投递通知、不投递下载响应，设备真实下载申请会在平台侧同事务生成一条
     * 未封存下载授权；设备耐久日志在此时为空（没有任何{@code INSTALLING/REBOOTING/HEALTH_CHECKING/CONFIRMING}
     * 记录，也没有{@code INSTALL_OUTCOME_UNKNOWN}），{@code OtaDeviceRuntime.journalDecision()}因此必然给出
     * 「停止先赢」。本片刻意不投递下载响应：投递它就会把设备推入VERIFYING/INSTALLING，反而验证不了「取消安全点」。
     * <b>D-157修复前</b>，该作业停在{@code DISPATCHED}且授权未封存时无法创建停止操作：
     * {@code ota_install_stop_create}要求停止操作携带作业的全部下载授权
     * （{@code array_agg(id ORDER BY id) FROM ota_download_authorization WHERE job_id=...}），而
     * {@code OtaInstallStopOperationService#seedOne}只携带{@code OtaJobProgressRepository#locate}返回的
     * 已封存授权（SQL条件{@code sealed_at IS NOT NULL}），于是创建会被数据库以
     * {@code install stop immutable command tuple invalid}（SQLSTATE 23514，{@code ota_install_stop_create}第81行）
     * 整事务拒绝，生产后台停止worker每秒重试并永久失败。S13-4d-2b-2b-5因此曾按生产顺序把该授权签址并封存
     * （{@code claim→prepareSigning→真实MinIO预签名→seal}）后才取消，作为绕开缺口的变通。
     * <b>D-157修复后该变通已删除</b>：{@code seedOne}按{@code ORDER BY id}携带作业全部授权id（含未封存），
     * 本用例直接在{@code DISPATCHED}/未封存窗口取消，证明紧急停止在该窗口可用。</p>
     *
     * <p><b>为什么停止操作创建与投递都由用例显式驱动：</b>bootstrap测试profile已经把
     * {@code things-link.ota.install-stop.enabled}钉为false（见{@code application-test.yml}），
     * 与其它worker一致，避免后台抢占待断言能力。这里显式调用生产的
     * {@code OtaInstallStopOperationService#seedOne}与{@code OtaInstallStopDeliveryService}的
     * 领取→预留→真实Broker→观察顺序（与{@link #realDownloadDeliveryDrivesDeviceClosedLoopToConfirming()}
     * 驱动下载/许可投递完全同构），断言因此钉在「这一次投递」上而不是与后台轮询赛跑。</p>
     *
     * <p><b>失败关闭对照为什么不在本片做（不伪造）：</b>本片原本要求的对照是「异作业/尝试、或基线不匹配的停止操作
     * 被设备拒绝且零报告」。阅读生产模拟器可确认这不是该设备真实存在的行为：{@code OtaDeviceRuntime.acceptStopOperation}
     * （第510行起）只做三件事——把操作记入内存、按<b>自己的耐久日志</b>裁决、用操作自带字段编码报告；
     * {@code journalDecision()}（第530行起）只读{@code FileOtaStateJournal}，从不读取{@code jobId}/
     * {@code attemptNo}/{@code stopBaselineSha256}，也没有任何平台作业绑定可比对。设备侧唯一的永久拒绝是
     * 非规范合同字节（{@code OtaDeviceInstallStopOperationCodec}抛{@code IllegalArgumentException}）与
     * 引用未知操作的只读状态查询（{@code onInstallStopStatusQuery}抛{@code IllegalStateException}）。
     * 因此一份结构合法的异作业停止操作会被设备<b>接受</b>（追加一条停止日志并发出报告），真正拒绝它的是平台
     * {@code OtaInstallStopIngestionService.requireAxes}与{@code OtaInstallStopRepository#find}的认证轴校验——
     * 那条边界已由{@code OtaInstallStopIntegrationTests}用真实PG覆盖。若这里强行断言「设备拒绝」，
     * 只会证明一个模拟器并不具备的作业绑定，属于伪造证据，故本片只记录该边界并保持零伪造。
     * S13-4d-2b-2b-5实际观察到的平台侧失败关闭边界（未封存授权时{@code ota_install_stop_create}整事务拒绝）
     * 已在S13-4d-2b-2b-6作为真实产品缺口D-157修复，因此不再是本用例的期望负向证据。</p>
     *
     * @throws Exception 真实HTTP/MQTT/Kafka/数据库任一步失败时直接抛出，不吞首因
     */
    @Test
    void realCampaignCancellationDrivesDeviceStopWinsOverProductionEdge() throws Exception {
        EdgeScenario edge = openDeviceUplinkEdge();
        try {
            UUID job = edge.run().job();
            UUID campaign = edge.run().campaign();
            String projectKey = edge.projectKey();
            String deviceKey = edge.device().deviceKey();

            // 0）前置：设备尚未进入任何安全阶段，因此「停止先赢」的裁决只能来自空/非安全日志。
            List<OtaJournalRecord> beforeCancel = readJournalQuietly(
                    new FileOtaStateJournal(edge.runtime().journalFile()), List.of());
            assertThat(beforeCancel).as("本片刻意不投递下载响应，设备耐久日志在取消前不得出现任何安全阶段，实际="
                    + stages(beforeCancel)).noneMatch(record -> record.stage().isSafetyStage());

            // 0b）停止窗口的必要前置（D-157修复后的最短窗口）：设备真实下载申请已在平台侧同事务
            //     生成一条<b>未封存</b>下载授权，作业因此仍停在DISPATCHED，且设备从未收到下载响应。
            //     这正是此前创建不了停止操作的窗口：修复前seedOne只携带locate(...)返回的已封存授权
            //     （sealed_at IS NOT NULL），而ota_install_stop_create要求命令携带作业<b>全部</b>授权
            //     （array_agg(id ORDER BY id)），于是创建被SQLSTATE 23514整事务拒绝、紧急停止不可用。
            //     修复后本用例不再做任何签址/封存变通，断言直接钉在这个窗口上。
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_authorization WHERE job_id=?",
                    Long.class, job)).as("设备真实下载申请必须已经落成恰一条下载授权").isEqualTo(1L);
            Map<String, Object> beforeAuthorization = owner().queryForMap(
                    "SELECT id,status,sealed_at FROM ota_download_authorization WHERE job_id=?", job);
            UUID beforeAuthorizationId = (UUID) beforeAuthorization.get("id");
            assertThat(beforeAuthorization.get("sealed_at"))
                    .as("取消前下载授权必须仍未封存，否则本用例没有覆盖D-157窗口，授权=" + beforeAuthorization)
                    .isNull();
            assertThat(beforeAuthorization.get("status"))
                    .as("未封存授权状态必须不是SEALED，否则本用例没有覆盖D-157窗口，授权=" + beforeAuthorization)
                    .isNotEqualTo("SEALED");
            assertThat(jobStatus(job)).as("未封存授权窗口内作业必须仍停在DISPATCHED，实际=" + jobStatus(job))
                    .isEqualTo("DISPATCHED");
            assertThat(readJournalQuietly(new FileOtaStateJournal(edge.runtime().journalFile()), List.of()))
                    .as("本片不投递下载响应，设备的耐久日志不得出现任何安全阶段")
                    .noneMatch(record -> record.stage().isSafetyStage());

            // 1）生产管理取消入口：HTTP CAS 修订必须来自运行事实投影，请求本身不表示设备已停止。
            JsonNode running = execution(edge.run());
            String expectedRevision = running.path("stateVersion").asText();
            String cancelReason = "停止安装前责任-边缘停止先赢";
            HttpResponse<String> cancellation = send(edge.fixture(), "POST",
                    campaigns(edge.fixture()) + "/" + campaign + "/cancellation", key(),
                    JSON.writeValueAsBytes(Map.of("expectedRevision", expectedRevision, "reason", cancelReason)), false);
            JsonNode requested = ok(cancellation, 200);
            assertThat(requested.path("status").asText()).as("生产取消入口必须先把活动推进到CANCELLING，响应="
                    + requested).isEqualTo("CANCELLING");
            assertThat(campaignStatus(campaign)).as("持久活动状态必须是CANCELLING而不是CANCELLED，否则平台不能下发停止操作")
                    .isEqualTo("CANCELLING");
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign_runtime_cancellation WHERE campaign_id=?",
                    Long.class, campaign)).as("一次管理取消必须留下恰一条不可变取消请求").isEqualTo(1L);

            // 2）平台创建唯一安装前停止操作：生产短事务入口，后台worker在测试profile关闭。
            assertThat(stopOperations.seedOne()).as("CANCELLING活动下已派发作业必须能创建唯一停止操作，当前作业状态="
                    + jobStatus(job) + " 活动状态=" + campaignStatus(campaign)).isTrue();
            Map<String, Object> operation = owner().queryForMap(
                    "SELECT * FROM ota_install_stop_operation WHERE job_id=?", job);
            UUID operationId = (UUID) operation.get("id");
            assertThat(operation.get("device_id")).as("停止操作必须绑定认证设备").isEqualTo(edge.device().id());
            assertThat(operation.get("campaign_id")).as("停止操作必须绑定本例真实活动").isEqualTo(campaign);
            List<UUID> expectedAuthorizationIds = owner().queryForList(
                    "SELECT id FROM ota_download_authorization WHERE job_id=? ORDER BY id", UUID.class, job);
            assertThat(expectedAuthorizationIds).as("D-157窗口必须恰有一条未封存授权作为全量有序集合")
                    .containsExactly(beforeAuthorizationId);
            UUID[] storedAuthorizationIds = owner().queryForObject(
                    "SELECT authorization_ids FROM ota_install_stop_operation WHERE job_id=?",
                    (rs, row) -> (UUID[]) rs.getArray(1).getArray(), job);
            assertThat(Arrays.asList(storedAuthorizationIds))
                    .as("停止操作持久化的授权id必须等于DB函数array_agg(id ORDER BY id)的全量有序集合（含未封存），实际="
                            + Arrays.toString(storedAuthorizationIds) + " 期望=" + expectedAuthorizationIds)
                    .containsExactlyElementsOf(expectedAuthorizationIds);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_install_stop_operation WHERE job_id=?",
                    Long.class, job)).as("同一作业尝试只允许存在一条停止操作").isEqualTo(1L);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_install_stop_delivery WHERE event_id=?",
                    Long.class, operationId)).as("停止操作创建必须同事务留下恰一条投递事实").isEqualTo(1L);

            // 3）生产投递顺序：领取→预留→真实EMQX发布→真实观察。
            var stopClaim = awaitInstallStopClaim(job, Duration.ofSeconds(15));
            assertThat(stopClaim.envelope().id()).as("可领取的停止投递必须是本例刚创建的停止操作").isEqualTo(operationId);
            assertThat(stopClaim.envelope().kind()).as("首次交付必须是从未发送过的停止命令").isEqualTo("OPERATION");
            OtaInstallStopDeliveryService.Prepared prepared = stopDeliveries
                    .prepare(operationId, stopClaim.leaseToken())
                    .orElseThrow(() -> new AssertionError("真实停止命令必须能预留一次发送，当前投递状态="
                            + stopClaim.status() + " 活动状态=" + campaignStatus(campaign)
                            + " 作业状态=" + jobStatus(job) + " 停止控制=" + stopControl(operationId)));
            assertThat(prepared.projectKey()).as("停止路由的项目短标识必须来自权威项目").isEqualTo(projectKey);
            assertThat(prepared.deviceKey()).as("停止路由的设备短标识必须来自权威设备").isEqualTo(deviceKey);
            String operationTopic = OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_OPERATION,
                    projectKey, deviceKey);
            assertThat(prepared.transport().topic()).as("停止命令必须落在设备真实订阅的下行Topic上")
                    .isEqualTo(operationTopic);
            var envelope = prepared.transport().envelope();
            Instant operationExpiry = Instant.ofEpochSecond(envelope.deadlineAt().getEpochSecond());
            Instant operationLimit = operationExpiry.isBefore(prepared.leaseUntil())
                    ? operationExpiry : prepared.leaseUntil();
            // 与生产OtaInstallStopProcessor.processOwned逐字同规则：交换预算不越过操作原期限，也不越过本次交付租约。
            OtaInstallStopPublisher.Result published = stopPublisher.publish("OPERATION",prepared.route(), envelope.canonical(), operationExpiry, operationLimit,
                    budget(Instant.now(), operationExpiry, prepared.leaseUntil()));
            assertThat(published.outcome()).as("真实Broker必须接受本次停止命令，失败原因=" + published.reason())
                    .isEqualTo(OtaInstallStopPublisher.Outcome.BROKER_ACCEPTED);
            assertThat(stopDeliveries.complete(prepared.transport().id(), prepared.transport().reservationToken(),
                    published)).as("真实Broker观察必须能推进本次停止传输").isTrue();

            // 4）真实设备消费停止命令：只依据耐久日志裁决，并在同一条已认证连接上发出真实停止报告。
            String reportTopic = OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_OPERATION_REPORT,
                    projectKey, deviceKey);
            byte[] reportBytes = edge.recording().awaitUplink(reportTopic, Duration.ofSeconds(30));
            OtaDeviceInstallStopReportCodec.Report report = OtaDeviceInstallStopReportCodec.decode(reportBytes).value();
            assertThat(report.status()).as("设备耐久日志尚未进入安全阶段，真实报告状态必须是STOPPED而不是INSTALL_WON")
                    .isEqualTo("STOPPED");
            assertThat(report.operationId()).as("设备报告必须绑定平台真实下发的停止操作").isEqualTo(operationId);
            assertThat(report.jobId()).as("设备报告必须绑定本例真实作业").isEqualTo(job);
            assertThat(report.attemptNo()).isEqualTo(1);
            assertThat(report.manifestSha256()).as("设备报告必须回显平台冻结清单摘要")
                    .isEqualTo(edge.dispatch().get("manifest_sha256"));
            assertThat(report.operationSha256()).as("设备报告必须回显停止操作的规范摘要")
                    .isEqualTo((String) operation.get("payload_hash"));
            assertThat(report.evidence().writeState()).as("停止先赢必须声明写入已静止QUIESCENT")
                    .isEqualTo("QUIESCENT");
            assertThat(report.evidence().installOperations()).as("停止先赢不得包含任何安装接纳日志").isEmpty();
            assertThat(report.evidence().stopOperations()).as("停止先赢必须恰含一条本操作的停止日志").hasSize(1);
            assertThat(report.evidence().stopOperations().getFirst().operationId())
                    .as("设备停止日志必须绑定平台真实停止操作").isEqualTo(operationId);
            assertThat(report.evidence().stopOperations().getFirst().acceptedBootId())
                    .as("设备停止日志必须携带设备当前真实启动身份").isEqualTo(edge.runtime().bootId());

            // 5）设备耐久裁决：日志必须真实落盘一条「安装前被取消」记录，且全程没有安全阶段。
            List<OtaJournalRecord> journal = awaitStopDecision(edge.runtime(), Duration.ofSeconds(20));
            OtaJournalRecord stopRecord = journal.getLast();
            assertThat(stopRecord.reasonCode()).as("耐久日志最后一条必须是安装前取消结论，实际阶段序列=" + stages(journal))
                    .isEqualTo(OtaReasonCode.CANCELLED_BEFORE_SAFETY_STAGE);
            assertThat(stopRecord.cancelAccepted()).as("该记录必须声明这次干净取消已被设备接受").isTrue();
            assertThat(stopRecord.conclusion()).as("原因码结论必须是设备终态CANCELLED").isEqualTo(OtaStage.CANCELLED);
            assertThat(journal).as("整个停止裁决过程中设备不得出现任何安全阶段，实际=" + stages(journal))
                    .noneMatch(record -> record.stage().isSafetyStage());

            // 6）停止报告经真实边缘回流并被平台采用。
            Map<String, Object> accepted = awaitInstallStopReport(operationId, job, Duration.ofSeconds(30));
            assertThat(accepted.get("status")).as("平台持久报告状态必须是设备真实发出的STOPPED").isEqualTo("STOPPED");
            assertThat(accepted.get("disposition")).as("安全窗口内的耐久停止必须被采用为CANCELLED").isEqualTo("CANCELLED");
            assertThat(accepted.get("reason")).as("采用理由必须点名设备耐久停止事实")
                    .isEqualTo("ATTEMPT_DURABLY_STOPPED_BEFORE_INSTALL");
            assertThat(accepted.get("job_id")).as("持久停止报告必须绑定本例真实作业").isEqualTo(job);
            assertThat(accepted.get("device_id")).as("持久停止报告必须绑定认证设备").isEqualTo(edge.device().id());
            assertThat(accepted.get("report_id")).as("设备生成的报告身份必须被平台如实持久")
                    .isEqualTo(report.reportId());
            assertThat((byte[]) accepted.get("canonical")).as("持久规范字节必须与设备真实发送字节逐字节相同")
                    .isEqualTo(reportBytes);
            assertThat(accepted.get("payload_hash")).as("持久摘要必须等于设备真实发送字节的SHA-256")
                    .isEqualTo(sha(reportBytes));
            assertThat(accepted.get("broker_received_at")).as("原始Broker接收时间必须被真实记录").isNotNull();
            Map<String, Object> control = owner().queryForMap("SELECT accepted_report_id,stopped_report_id,"
                    + "install_won_report_id,conflicted_at FROM ota_install_stop_control WHERE operation_id=?",
                    operationId);
            assertThat(control.get("accepted_report_id")).as("停止控制必须记录已接纳的原报告")
                    .isEqualTo(accepted.get("id"));
            assertThat(control.get("stopped_report_id")).as("停止控制必须记录停止先赢的报告")
                    .isEqualTo(accepted.get("id"));
            assertThat(control.get("install_won_report_id")).as("停止先赢不得留下安装先赢报告").isNull();
            assertThat(control.get("conflicted_at")).as("停止先赢不得形成方向矛盾").isNull();

            // 7）平台可见终态：作业CANCELLED、活动CANCELLED，且没有任何被伪造的进度或成功事实。
            awaitJobStatus(job, "CANCELLED", Duration.ofSeconds(30));
            assertThat(jobStatus(job)).as("真实停止报告必须把作业采用为CANCELLED").isEqualTo("CANCELLED");
            Map<String, Object> cancelledTransition = owner().queryForMap("SELECT from_status,to_status,reason "
                    + "FROM ota_job_transition WHERE job_id=? AND to_status='CANCELLED'", job);
            assertThat(cancelledTransition).as("CANCELLED必须由数据库停止函数以设备耐久停止理由写入")
                    .containsEntry("from_status", "DISPATCHED").containsEntry("to_status", "CANCELLED")
                    .containsEntry("reason", "ATTEMPT_DURABLY_STOPPED_BEFORE_INSTALL");
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? "
                            + "AND to_status='CANCELLED'", Long.class, job))
                    .as("一次停止先赢只允许产生一条CANCELLED转移").isEqualTo(1L);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_install_stop_report WHERE job_id=?",
                    Long.class, job)).as("一次真实停止只允许一条持久停止报告").isEqualTo(1L);
            assertThat(campaignStatus(campaign)).as("唯一作业取消后活动必须收敛到CANCELLED").isEqualTo("CANCELLED");
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_progress WHERE job_id=?",
                    Long.class, job)).as("停止先赢路径不得伪造任何ota_job_progress事实").isZero();
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? "
                            + "AND to_status='SUCCEEDED'", Long.class, job))
                    .as("停止先赢路径不得出现任何SUCCEEDED转移").isZero();
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? "
                            + "AND reason='AUTHENTICATED_PROGRESS'", Long.class, job))
                    .as("停止先赢路径不得出现任何设备进度采用").isZero();
        } finally {
            edge.close();
        }
    }

    /**
     * D-160 + D-158 + D-162：真实重试作业（同一个{@code ota_device_job}行，{@code attempt_no}前进）必须同时具备
     * <b>当前尝试</b>的执行来源，停止命令又必须只携带<b>当前尝试</b>的授权；并且当同一个作业真的持有<b>两条</b>
     * 下载授权（历史第一次尝试 + 当前第二次尝试）时，作业仍必须能被更新与收敛——这正是D-162关闭之处。
     *
     * <p><b>D-162的缺陷与本次修复：</b>修复前{@code ota_download_consistency}在{@code ota_device_job}事件上用
     * 标量子查询{@code (SELECT id ... WHERE job_id=NEW.id)}定位授权；一个作业持有两条授权时该子查询在任何作业
     * 更新上抛SQLSTATE 21000，于是「两条授权」形状无法在真实更新下存活，D-158收窄到当前尝试的断言也就无法端到端
     * 被行使（S13-4d-2b-2b-10只能退回「历史授权在、当前尝试集合为空」的形状）。追加迁移
     * {@code V20260913_1020__ota_download_consistency_current_attempt_authorization.sql}把定位改为按共享身份连接
     * {@code ota_download_request}并过滤{@code r.attempt_no=NEW.attempt_no}，当前尝试至多一条授权
     * （{@code ota_download_request_job_attempt_uk UNIQUE(job_id,attempt_no)}），历史授权不再参与检查。本用例现在
     * 同时断言：两条授权并存时作业仍可更新（21000消失）、未封存的当前尝试授权仍被同一错误码拒绝推进、停止命令
     * 仍只携带当前尝试的单一授权。</p>
     *
     * <p><b>本用例如何构造重试形状（如实声明）：</b>第一次尝试是真实设备边缘（真实下载申请→平台未封存授权）。
     * 此后<b>第二次尝试与它的执行来源全部由生产代码产生</b>：生产{@code OtaBusinessRetryService}的领取+归类调用
     * 真实受限函数{@code ota_job_dispatch_retry}，D-160修复前它不会为新尝试写入
     * {@code ota_job_execution_origin}，停止候选查询因缺少当前尝试来源而永久为空。</p>
     *
     * <p><b>夹具与证据边界：</b>本例聚焦重试后的停止授权集合，首次RETRY_WAIT及第二次下载申请由夹具补齐，
     * 不证明实际传输耗尽到业务重试的闭环。ADR0205/0206现在允许已耗尽且未预留签名的授权进入重试，
     * 模拟器也已支持attemptNo大于1；旧注释所称“生产不可能重试”与“协议仅支持1”不再成立。
     * 真实HTTP失败交接及第二次申请/签发由OtaNotificationHttpIntegrationTests和OtaDownloadAuthorizationIntegrationTests验收。
     * 第二次申请仍经过完整数据库守卫；停止创建、命令收窄、真实投递、设备回执与最终CANCELLED走生产路径。
     * 负向推进探针的EXHAUSTED事实仅存在于必回滚事务中，实际作业UPDATE恢复全部生产触发器。</p>
     *
     * @throws Exception 真实HTTP/MQTT/Kafka/数据库任一步失败时直接抛出，不吞首因
     */
    @Test
    void retriedJobStopCarriesOnlyCurrentAttemptAuthorization() throws Exception {
        EdgeScenario edge = openDeviceUplinkEdge();
        try {
            Fixture fixture = edge.fixture();
            UUID job = edge.run().job();
            UUID campaign = edge.run().campaign();
            // 第一次尝试：设备真实下载申请已在平台侧落成一条未封存授权（D-157窗口）。
            Map<String, Object> attempt1Row = owner().queryForMap(
                    "SELECT id,sealed_at FROM ota_download_authorization WHERE job_id=?", job);
            UUID attempt1 = (UUID) attempt1Row.get("id");
            assertThat(attempt1Row.get("sealed_at")).as("第一次尝试授权必须未封存").isNull();

            // 1）生产重试路径：夹具只把作业置于重试等待，新尝试及其执行来源随后全部由生产
            //    OtaBusinessRetryService的领取+归类调用真实ota_job_dispatch_retry产生。
            seedRetryWait(job);
            assertThat(jobStatus(job)).as("重试等待夹具必须把同一个作业行推进到RETRY_WAIT")
                    .isEqualTo("RETRY_WAIT");
            var due = retries.claimDue().orElseThrow(() -> new AssertionError(
                    "重试到期事实必须可被生产服务领取，当前作业状态=" + jobStatus(job)));
            assertThat(due.jobId()).as("领取到的到期事实必须是本例作业").isEqualTo(job);
            assertThat(retries.classify(due, "RETRY_BACKOFF_ELAPSED"))
                    .as("生产归类必须按冻结预算派发第二次尝试").isTrue();
            assertThat(jobStatus(job)).as("生产重试必须把同一个作业行推进到第二次尝试的DISPATCHED")
                    .isEqualTo("DISPATCHED");
            assertThat(owner().queryForObject("SELECT attempt_no FROM ota_device_job WHERE id=?",
                    Integer.class, job)).as("重试必须推进同一个作业行的尝试号").isEqualTo(2);

            // 2）D-160：生产重试必须为新尝试留下与当前尝试号一致的原执行来源，且只前移既有来源，不回填当前报告。
            Map<String, Object> origin1 = owner().queryForMap(
                    "SELECT * FROM ota_job_execution_origin WHERE job_id=? AND attempt_no=1", job);
            Map<String, Object> origin2 = owner().queryForMap(
                    "SELECT * FROM ota_job_execution_origin WHERE job_id=? AND attempt_no=2", job);
            assertThat(origin2.get("attempt_no")).as("新尝试来源必须绑定当前尝试号").isEqualTo(2);
            assertThat((byte[]) origin2.get("canonical")).as("新尝试来源必须逐字节前移原准入报告")
                    .isEqualTo((byte[]) origin1.get("canonical"));
            assertThat(origin2.get("report_hash")).as("摘要、修订与凭据代际必须与既有来源一致")
                    .isEqualTo(origin1.get("report_hash"));
            assertThat(origin2.get("report_revision")).isEqualTo(origin1.get("report_revision"));
            assertThat(origin2.get("credential_version")).isEqualTo(origin1.get("credential_version"));
            assertThat(owner().queryForObject("SELECT o.attempt_no=j.attempt_no AND o.captured_at=j.dispatched_at"
                    + " AND o.dispatched_revision=j.state_version FROM ota_job_execution_origin o"
                    + " JOIN ota_device_job j ON j.id=o.job_id WHERE o.job_id=? AND o.attempt_no=2",
                    Boolean.class, job)).as("来源必须绑定当前尝试号、本次派发时钟与状态修订").isTrue();
            // 2b）负向探针（非状态构造）：把与当前尝试同号、但报告字节与既有来源不符的伪造行直接写进来源表，
            //     来源守卫必须以原错误码23514整行拒绝；这不放宽任何校验，只证明围栏仍在。
            assertThatThrownBy(() -> owner().update(
                    "INSERT INTO ota_job_execution_origin(tenant_id,project_id,campaign_id,job_id,device_id,"
                    + "attempt_no,credential_version,report_revision,report_sequence,report_hash,canonical,"
                    + "broker_received_at,accepted_at,captured_at,dispatched_revision)"
                    + " SELECT tenant_id,project_id,campaign_id,job_id,device_id,attempt_no,credential_version,"
                    + "report_revision,report_sequence,encode(sha256(convert_to('{\"forged\":true}','UTF8')),'hex'),"
                    + "convert_to('{\"forged\":true}','UTF8'),broker_received_at,accepted_at,captured_at,"
                    + "dispatched_revision FROM ota_job_execution_origin WHERE job_id=? AND attempt_no=2", job))
                    .rootCause().isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("execution origin must match original current admission")
                    .extracting(failure -> ((java.sql.SQLException) failure).getSQLState()).isEqualTo("23514");

            // 3）D-162两条授权形状（历史第一次尝试 + 当前第二次尝试）：夹具按真实形状为第二次尝试补一条
            //    下载申请，数据库ota_download_request_guard完整生效，授权由ota_download_initialize触发器初始化。
            UUID attempt2 = seedCurrentAttemptDownloadRequest(job);
            assertThat(owner().queryForList("SELECT id FROM ota_download_authorization WHERE job_id=? ORDER BY id",
                    UUID.class, job)).as("作业必须同时持有历史第一次尝试与当前第二次尝试两条授权")
                    .containsExactlyInAnyOrder(attempt1, attempt2);
            assertThat(owner().queryForObject("SELECT r.attempt_no FROM ota_download_authorization a"
                    + " JOIN ota_download_request r ON r.id=a.id WHERE a.id=?", Integer.class, attempt2))
                    .as("新增授权必须绑定当前尝试号2").isEqualTo(2);

            // 3b）D-162聚焦探针（真实PostgreSQL、不提交）：把作业侧下载一致性触发器改为立即触发，
            //     验证两条授权并存时作业阶段更新不再抛SQLSTATE 21000；随后验证未封存的当前尝试授权
            //     仍以同一错误码拒绝推进。探针事务始终回滚，不改变后续生产路径依赖的作业状态。
            SQLException advanced = probeJobDownloadConsistency(
                    "UPDATE ota_device_job SET status='RECOVERY_REQUIRED',state_version=state_version+1 WHERE id=?",
                    job, false);
            assertThat((Object) advanced).as("两条下载授权并存时作业更新必须成功（D-162的SQLSTATE 21000必须消失），实际="
                    + describeSqlFailure(advanced)).isNull();
            SQLException blocked = probeJobDownloadConsistency(
                    "UPDATE ota_device_job SET status='RETRY_WAIT',state_version=state_version+1,deadline_at=NULL,"
                    + "failure_code='DISPATCH_TRANSIENT_FAILURE',next_attempt_at=clock_timestamp() WHERE id=?", job, true);
            assertThat((Object) blocked).as("当前尝试存在未封存授权时作业仍不得进入RETRY_WAIT").isNotNull();
            assertThat(blocked.getSQLState()).as("未封存当前尝试仍必须以原错误码23514拒绝推进")
                    .isEqualTo("23514");
            assertThat(blocked.getMessage()).as("必须以未封存作业推进错误拒绝，实际=" + describeSqlFailure(blocked))
                    .contains("download unsealed authorization cannot advance job");

            // 4）真实管理取消入口：活动进入CANCELLING并留下唯一取消请求。
            JsonNode running = execution(edge.run());
            HttpResponse<String> cancellation = send(fixture, "POST",
                    campaigns(fixture) + "/" + campaign + "/cancellation", key(),
                    JSON.writeValueAsBytes(Map.of("expectedRevision", running.path("stateVersion").asText(),
                            "reason", "重试尝试停止先赢")), false);
            ok(cancellation, 200);
            assertThat(campaignStatus(campaign)).as("真实取消入口必须把活动推进到CANCELLING")
                    .isEqualTo("CANCELLING");

            // 5）真实停止创建：来源存在使候选可见，授权集合收窄到当前第二次尝试的单一授权，历史授权被排除。
            assertThat(stopOperations.seedOne()).as("有当前尝试来源的重试作业必须能创建停止操作，当前作业状态="
                    + jobStatus(job) + " 活动状态=" + campaignStatus(campaign)).isTrue();
            Map<String, Object> operation = owner().queryForMap(
                    "SELECT * FROM ota_install_stop_operation WHERE job_id=?", job);
            UUID operationId = (UUID) operation.get("id");
            assertThat(operation.get("attempt_no")).as("停止操作必须绑定当前尝试号").isEqualTo(2);
            UUID[] stored = owner().queryForObject(
                    "SELECT authorization_ids FROM ota_install_stop_operation WHERE job_id=?",
                    (rs, row) -> (UUID[]) rs.getArray(1).getArray(), job);
            assertThat(Arrays.asList(stored)).as("停止命令必须只携带当前第二次尝试的单一授权，实际="
                            + Arrays.toString(stored) + " 当前=" + attempt2 + " 历史=" + attempt1)
                    .containsExactly(attempt2);
            var command = new com.things.link.ota.application.OtaInstallStopOperationCodec()
                    .decode((byte[]) operation.get("canonical")).value();
            assertThat(command.attemptNo()).as("规范命令字节必须绑定当前尝试号").isEqualTo(2);
            assertThat(command.authorizationIds()).as("规范命令字节必须只携带当前尝试授权，实际="
                    + command.authorizationIds()).containsExactly(attempt2);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_install_stop_operation WHERE job_id=?",
                    Long.class, job)).as("同一重试作业只允许存在一条停止操作").isEqualTo(1L);

            // 6）真实投递与设备回执：停止命令经EMQX下行到真实设备，设备发STOPPED，平台收敛为CANCELLED。
            //    这里的DISPATCHED→CANCELLED真实作业更新正是在两条授权并存下发生的，是D-162端到端的主要正向证据。
            var stopClaim = awaitInstallStopClaim(job, Duration.ofSeconds(15));
            assertThat(stopClaim.envelope().id()).as("可领取的停止投递必须是本例刚创建的停止操作").isEqualTo(operationId);
            OtaInstallStopDeliveryService.Prepared prepared = stopDeliveries
                    .prepare(operationId, stopClaim.leaseToken())
                    .orElseThrow(() -> new AssertionError("真实停止命令必须能预留一次发送，当前投递状态="
                            + stopClaim.status() + " 作业状态=" + jobStatus(job)));
            var envelope = prepared.transport().envelope();
            Instant operationExpiry = Instant.ofEpochSecond(envelope.deadlineAt().getEpochSecond());
            Instant operationLimit = operationExpiry.isBefore(prepared.leaseUntil())
                    ? operationExpiry : prepared.leaseUntil();
            OtaInstallStopPublisher.Result published = stopPublisher.publish("OPERATION",prepared.route(), envelope.canonical(), operationExpiry, operationLimit,
                    budget(Instant.now(), operationExpiry, prepared.leaseUntil()));
            assertThat(published.outcome()).as("真实Broker必须接受本次停止命令，失败原因=" + published.reason())
                    .isEqualTo(OtaInstallStopPublisher.Outcome.BROKER_ACCEPTED);
            assertThat(stopDeliveries.complete(prepared.transport().id(), prepared.transport().reservationToken(),
                    published)).as("真实Broker观察必须能推进本次停止传输").isTrue();
            String reportTopic = OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_OPERATION_REPORT,
                    projectKey(fixture), edge.device().deviceKey());
            byte[] reportBytes = edge.recording().awaitUplink(reportTopic, Duration.ofSeconds(30));
            OtaDeviceInstallStopReportCodec.Report report = OtaDeviceInstallStopReportCodec.decode(reportBytes).value();
            assertThat(report.status()).as("设备耐久日志尚未进入安全阶段，真实报告状态必须是STOPPED")
                    .isEqualTo("STOPPED");
            assertThat(report.attemptNo()).as("设备报告必须回显平台下发的当前尝试号").isEqualTo(2);
            awaitJobStatus(job, "CANCELLED", Duration.ofSeconds(30));
            assertThat(jobStatus(job)).as("重试尝试的真实停止报告必须把作业采用为CANCELLED").isEqualTo("CANCELLED");
            assertThat(owner().queryForList("SELECT id FROM ota_download_authorization WHERE job_id=? ORDER BY id",
                    UUID.class, job)).as("CANCELLED采用后两条下载授权必须仍然并存，证明D-162的21000没有阻止这次作业更新")
                    .containsExactlyInAnyOrder(attempt1, attempt2);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? "
                    + "AND to_status='CANCELLED'", Long.class, job)).isEqualTo(1L);
            assertThat(campaignStatus(campaign)).as("唯一作业取消后活动必须收敛到CANCELLED").isEqualTo("CANCELLED");
        } finally {
            edge.close();
        }
    }

    /**
     * 夹具：owner在关闭用户触发器的事务内把真实作业置为{@code RETRY_WAIT}（首次尝试），并补齐不可变转移与期限事实。
     *
     * <p>这里只为当前尝试停止范围构造前置状态，不作为ADR0205/0206真实耗尽交接证据；
     * 生产真实失败交接另由通知HTTP及下载授权集成测试覆盖。重试派发与执行来源仍由生产函数产生。</p>
     */
    private void seedRetryWait(UUID jobId) {
        rawReplica(jdbc -> {
            int changed = jdbc.update("""
                    UPDATE ota_device_job SET status='RETRY_WAIT',state_version=state_version+1,
                        deadline_at=NULL,failure_code='DISPATCH_TRANSIENT_FAILURE',
                        next_attempt_at=clock_timestamp(),lease_token=NULL,lease_until=NULL,downloading_at=NULL
                    WHERE id=? AND status='DISPATCHED' AND attempt_no=1
                    """, jobId);
            if (changed != 1) throw new IllegalStateException("作业不处于可进入重试等待的首次尝试DISPATCHED");
            jdbc.update("DELETE FROM ota_job_expiry WHERE job_id=?", jobId);
            jdbc.update("""
                    INSERT INTO ota_job_transition(id,tenant_id,project_id,campaign_id,job_id,from_status,to_status,
                        from_revision,to_revision,actor_kind,actor_id,occurred_at,reason)
                    SELECT gen_random_uuid(),tenant_id,project_id,campaign_id,id,'DISPATCHED','RETRY_WAIT',
                        state_version-1,state_version,'SYSTEM',NULL,clock_timestamp(),'DISPATCH_NOT_ACKNOWLEDGED'
                    FROM ota_device_job WHERE id=?
                    """, jobId);
            return true;
        });
    }

    /** owner在关闭用户触发器的事务内补齐夹具状态；只跳过夹具触发器，不修改任何CHECK。 */
    private void rawReplica(java.util.function.Function<JdbcTemplate, Boolean> work) {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("SET LOCAL session_replication_role=replica");
            work.apply(jdbc);
        });
    }

    /** owner在完整生产触发器下写入夹具事实；与rawReplica相反，这里不关闭任何守卫。 */
    private void ownerTransaction(java.util.function.Consumer<JdbcTemplate> work) {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status ->
                work.accept(new JdbcTemplate(source)));
    }

    /**
     * D-162夹具：为生产重试产生的当前尝试补一条真实形状的下载申请，并返回由此初始化出的授权身份。
     *
     * <p>申请与排队事实在同一事务写入，数据库{@code ota_download_request_guard}完整生效（当前DISPATCHED作业、
     * 同一派发意图、同清单/凭据/期限与RUNNING活动），授权由{@code ota_download_initialize}触发器生成。为什么
     * 第二次尝试的申请只能由夹具补齐，见{@link #retriedJobStopCarriesOnlyCurrentAttemptAuthorization()}的说明。</p>
     *
     * @param jobId 当前处于第二次尝试{@code DISPATCHED}的真实作业
     * @return 申请与授权共享的身份（{@code ota_download_authorization.id = ota_download_request.id}）
     */
    private UUID seedCurrentAttemptDownloadRequest(UUID jobId) {
        UUID requestId = Uuid7.generate();
        UUID deviceRequestId = Uuid7.generate();
        UUID outboxId = Uuid7.generate();
        ownerTransaction(jdbc -> {
            Map<String, Object> job = jdbc.queryForMap("SELECT tenant_id,project_id,campaign_id,device_id,attempt_no,"
                    + "state_version,deadline_at,report_revision,report_hash FROM ota_device_job WHERE id=?", jobId);
            int attempt = ((Number) job.get("attempt_no")).intValue();
            Map<String, Object> dispatch = jdbc.queryForMap("SELECT manifest_sha256,credential_version "
                    + "FROM ota_job_dispatch_outbox WHERE job_id=? AND attempt_no=?", jobId, attempt);
            UUID campaignId = (UUID) job.get("campaign_id");
            UUID firmwareId = jdbc.queryForObject("SELECT firmware_id FROM ota_campaign WHERE id=?",
                    UUID.class, campaignId);
            byte[] canonical = new OtaCanonicalJson().writeObject(new LinkedHashMap<String, Object>(Map.of(
                    "contractVersion", "tc-ota-download-request/v1",
                    "requestId", deviceRequestId.toString(),
                    "jobId", jobId.toString(),
                    "attemptNo", (long) attempt,
                    "manifestSha256", (String) dispatch.get("manifest_sha256"))));
            java.sql.Timestamp acceptedAt = jdbc.queryForObject("SELECT clock_timestamp()",
                    java.sql.Timestamp.class);
            jdbc.update("INSERT INTO ota_download_request(id,tenant_id,project_id,device_id,credential_version,"
                    + "request_id,job_id,campaign_id,firmware_id,attempt_no,manifest_sha256,canonical,"
                    + "canonical_sha256,original_deadline,broker_received_at,accepted_at,report_revision,"
                    + "report_hash,job_revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    requestId, job.get("tenant_id"), job.get("project_id"), job.get("device_id"),
                    dispatch.get("credential_version"), deviceRequestId, jobId, campaignId, firmwareId, attempt,
                    dispatch.get("manifest_sha256"), canonical, sha(canonical), job.get("deadline_at"),
                    acceptedAt, acceptedAt, job.get("report_revision"), job.get("report_hash"),
                    job.get("state_version"));
            jdbc.update("INSERT INTO ota_download_request_outbox(id,tenant_id,project_id,receipt_id,event_type,"
                    + "created_at,published_at) VALUES(?,?,?,?, 'OTA_DOWNLOAD_REQUEST_ACCEPTED',?,NULL)",
                    outboxId, job.get("tenant_id"), job.get("project_id"), requestId, acceptedAt);
        });
        return requestId;
    }

    /**
     * D-162聚焦探针：在一个始终回滚的真实事务里把作业侧下载一致性触发器改为立即触发，执行一次作业更新，
     * 返回真实{@link SQLException}（成功为{@code null}）。只让该触发器参与裁决，不依赖其它延迟图的完整提交。
     */
    private SQLException probeJobDownloadConsistency(String updateSql, UUID jobId, boolean exhaustedNotification) {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    if (exhaustedNotification) {
                        // 仅在回滚探针内构造通知失败前置事实；实际作业更新恢复全部生产触发器。
                        statement.execute("SET LOCAL session_replication_role=replica");
                        try (PreparedStatement failure = connection.prepareStatement("""
                                UPDATE ota_notification_delivery SET status='EXHAUSTED',
                                    revision=revision+1,updated_at=clock_timestamp(),exhausted_at=clock_timestamp(),
                                    accepted_at=NULL,reason='TRANSIENT_FAILURE',lease_token=NULL,lease_until=NULL
                                WHERE job_id=? AND job_attempt_no=(SELECT attempt_no FROM ota_device_job WHERE id=?)
                                """)) {
                            failure.setObject(1, jobId); failure.setObject(2, jobId);
                            assertThat(failure.executeUpdate()).isEqualTo(1);
                        }
                        statement.execute("SET LOCAL session_replication_role=origin");
                    }
                    statement.execute("SET CONSTRAINTS ota_device_job_download_consistency_trg IMMEDIATE");
                }
                try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                    update.setObject(1, jobId);
                    update.executeUpdate();
                    return null;
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            return failure;
        }
    }

    /** 只用于失败消息的稳定描述，避免把整段堆栈塞进断言输出。 */
    private static String describeSqlFailure(SQLException failure) {
        return failure == null ? "成功" : failure.getSQLState() + " " + failure.getMessage();
    }

    /**
     * S13-4d-2b-2b-7负向组（场景2-5）：真实故障计划下的设备运行绝不在平台上产生虚假成功。
     *
     * <p><b>本方法覆盖六个设备场景里的第2~5个：</b></p>
     * <ul>
     *   <li><b>场景2 断电于{@code INSTALLING}</b>：设备停在安全暂停且绝不声称安装成功；</li>
     *   <li><b>场景3 首错安全暂停（{@code failFirstChunk}）</b>：停在
     *       {@code FIRST_CHUNK_FAILED_SAFETY_PAUSE}，不自动重试；</li>
     *   <li><b>场景4 失败阈值耗尽</b>：以运行时固定的默认下载失败预算收敛到{@code FAILED}/
     *       {@code DOWNLOAD_FAILURE_BUDGET_EXHAUSTED}；</li>
     *   <li><b>场景5 恢复拒绝</b>：停在已确认偏移，绝不从0重来。</li>
     * </ul>
     *
     * <p><b>为什么四个场景共用一个夹具：</b>生产信任包导入与类型基线登记都以
     * {@code expectedRevision=0}作为CAS围栏（{@code OtaTrustService#importBundle}、
     * {@code OtaTypeBaselineService#register}），同一项目只允许成功一次；因此本方法只{@code seed}一次、
     * 只完整发布一次，其余三个设备经 {@link #runningOnExistingFirmware}复用同一个READY固件各自建立活动。
     * 每个场景仍拥有自己的设备、活动、作业与耐久日志目录。</p>
     *
     * @throws Exception 真实HTTP/MQTT/Kafka/数据库任一步失败时直接抛出，不吞首因
     */
    @Test
    void faultedDeviceRunsNeverProducePlatformSuccess() throws Exception {
        // 生产信任包导入与类型基线登记以expectedRevision=0作为CAS围栏，同一项目只允许成功一次，
        // 因此这里只seed一次、只完整发布一次；其余设备复用同一个READY固件各自建立活动。
        Fixture fixture = seed(ProjectRole.ADMIN);
        ProvisionedDevice first = provisionDevice(fixture);
        Running firmwareRun = publishedAndRunning(fixture, first.device());
        byte[] acceptedReport = owner().queryForObject(
                "SELECT canonical FROM ota_device_report WHERE device_id=?", byte[].class, first.device().id());
        assertThat(acceptedReport).as("完整公开路径必须为第一个设备留下平台已接纳的真实报告，否则其余活动无法启动")
                .isNotNull();

        List<FaultedScenario> observations = new ArrayList<>();
        // 场景2：断电于INSTALLING（第一个设备走完整公开路径）。
        observations.add(assertPowerLossDuringInstalling(fixture, first, firmwareRun));
        // 场景3：首错安全暂停。
        ProvisionedDevice third = provisionDevice(fixture);
        observations.add(assertFirstChunkFailureStaysPaused(fixture, third,
                runningOnExistingFirmware(fixture, third.device(), firmwareRun, acceptedReport)));
        // 场景5：恢复拒绝。
        ProvisionedDevice fifth = provisionDevice(fixture);
        observations.add(assertResumeRefusalKeepsConfirmedOffset(fixture, fifth,
                runningOnExistingFirmware(fixture, fifth.device(), firmwareRun, acceptedReport)));
        // 场景4放在最后：它在设备侧有两秒有界重试退避，放在中间会把发布器连接冷却到下一次通知投递之前。
        ProvisionedDevice fourth = provisionDevice(fixture);
        observations.add(assertDownloadFailureBudgetExhausted(fixture, fourth,
                runningOnExistingFirmware(fixture, fourth.device(), firmwareRun, acceptedReport)));

        // 平台侧「零是期望值」的观察窗口与「不得伪造成功」断言统一放在四个设备都执行完之后：
        // 既给每个作业完整的观察窗口，又不让窗口在通知投递之间制造长时间空闲连接。生产通知发布器
        // 刻意关闭OkHttp隐式重发（retryOnConnectionFailure(false)），空闲keep-alive连接被Broker
        // 关闭后首次复用会以TRANSPORT_UNKNOWN失败——这是发布器的既定行为，不是本片要断言的场景事实，
        // 因此这里既不放宽它，也不让它决定用例结构。
        for (FaultedScenario observation : observations) {
            if (observation.deviceReportedStages().isEmpty()) {
                awaitNoProgressWithin(observation.job(), Duration.ofSeconds(5), observation.name());
            }
            assertNoFabricatedSuccess(observation.job(), observation.campaign(),
                    observation.deviceReportedStages(), observation.name());
        }
    }

    /** 一个负向场景在执行完成后交给平台侧统一断言的事实。 */
    private record FaultedScenario(String name, UUID job, UUID campaign, Set<String> deviceReportedStages) {
    }

    /**
     * 场景2：断电于{@code INSTALLING}必须停在安全暂停，平台不得出现任何成功/健康事实。
     *
     * <p><b>为什么这里没有{@code INSTALL_OUTCOME_UNKNOWN}（实测阻断）：</b>该原因码只在
     * {@code OtaDeviceExecution#run()}看到「日志最后一条是安全阶段暂停」时、由<b>下一次执行</b>追加
     * （第163-167行）。要让「下一次执行」发生，设备必须在重启后再次收到同一份已受理响应。S13-4d-2b-2b-12
     * 之后设备侧确实会重投、平台也确实能对同一身份再签一次址，但重投被原冻结响应窗口与
     * {@code ota_download_current} 的 {@code j.status='DOWNLOADING'} 双重限定：本场景的设备已经越过下载阶段，
     * 作业不再是 {@code DOWNLOADING}、窗口也随原派发期限关闭，因此重投在这里必然被拒绝（见本类Javadoc的
     * INSTALL_OUTCOME_UNKNOWN段落）。这里如实断言第一次执行真实写下的
     * {@code INSTALLING}/{@code POWER_LOST}/{@code SAFETY_PAUSED}：它已经表达「安装结果不可证明」，
     * 且平台上没有任何被伪造的成功。</p>
     */
    private FaultedScenario assertPowerLossDuringInstalling(Fixture fixture, ProvisionedDevice provisioned, Running run)
            throws Exception {
        EdgeScenario edge = openConnectedEdge(fixture, provisioned, run,
                OtaFaultPlan.builder().powerLossAt(OtaStage.INSTALLING).build(), HttpRangeArtifactSource::new);
        try {
            deliverDownloadResponse(edge);
            List<OtaJournalRecord> journal = awaitJournal(edge.runtime(), Duration.ofSeconds(30),
                    records -> !records.isEmpty() && records.getLast().reasonCode() == OtaReasonCode.POWER_LOST
                            && records.getLast().stage() == OtaStage.INSTALLING,
                    "设备在INSTALLING阶段掉电并写下POWER_LOST");
            OtaJournalRecord interrupted = journal.getLast();
            assertThat(interrupted.conclusion()).as("INSTALLING阶段掉电必须是安全暂停而不是终态成功")
                    .isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(interrupted.cancelAccepted()).as("掉电不是设备接受的干净取消").isFalse();
            assertThat(journal).as("设备必须真实越过下载与校验后才在安装阶段掉电，实际阶段序列=" + stages(journal))
                    .extracting(OtaJournalRecord::stage)
                    .containsSubsequence(OtaStage.DOWNLOADING, OtaStage.VERIFYING, OtaStage.INSTALLING);
            assertThat(journal).as("安装阶段掉电不得留下任何COMMITTED/CANCELLED/FAILED终态，实际=" + stages(journal))
                    .noneMatch(record -> record.stage() == OtaStage.COMMITTED
                            || record.stage() == OtaStage.CANCELLED || record.stage() == OtaStage.FAILED);

            // 掉电前设备真实上报VERIFYING与INSTALLING（含掉电记录本身对同阶段的一次上报），平台必须逐条采用。
            awaitProgressCount(edge.run().job(), 3L, Duration.ofSeconds(30),
                    "INSTALLING阶段掉电之前设备真实上报的三个可报进度阶段");
            return new FaultedScenario("断电于INSTALLING场景", edge.run().job(), edge.run().campaign(),
                    reportableStages(journal));
        } finally {
            edge.close();
        }
    }

    /** 场景3：首分片失败必须首错安全暂停、不自动重试，绝不进入验证/安装。 */
    private FaultedScenario assertFirstChunkFailureStaysPaused(Fixture fixture, ProvisionedDevice provisioned,
            Running run) throws Exception {
        EdgeScenario edge = openConnectedEdge(fixture, provisioned, run,
                OtaFaultPlan.builder().failFirstChunk().build(), HttpRangeArtifactSource::new);
        try {
            deliverDownloadResponse(edge);
            List<OtaJournalRecord> journal = awaitJournal(edge.runtime(), Duration.ofSeconds(30),
                    records -> !records.isEmpty()
                            && records.getLast().reasonCode() == OtaReasonCode.FIRST_CHUNK_FAILED_SAFETY_PAUSE,
                    "设备写下首分片失败的首错安全暂停");
            OtaJournalRecord terminal = journal.getLast();
            assertThat(terminal.stage()).as("首错发生在下载阶段").isEqualTo(OtaStage.DOWNLOADING);
            assertThat(terminal.conclusion()).as("首错必须停在安全暂停而不是失败或成功")
                    .isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(terminal.downloadedBytes()).as("首错安全暂停必须发生在任何字节被耐久确认之前")
                    .isZero();
            assertThat(journal).as("首错不得自动重试：任何一次重试都会真实命中MinIO并留下分片记录，"
                    + "因此耐久日志只允许一条DOWNLOADING记录（暂停本身），实际阶段序列=" + stages(journal))
                    .filteredOn(record -> record.stage() == OtaStage.DOWNLOADING).hasSize(1);
            assertThat(Files.size(edge.runtime().stagingFile())).as("首错安全暂停时暂存区必须为空").isZero();
            assertThat(journal).as("首错安全暂停不得出现验证或安全阶段，实际=" + stages(journal))
                    .noneMatch(record -> record.stage() == OtaStage.VERIFYING || record.stage().isSafetyStage()
                            || record.stage() == OtaStage.COMMITTED);
            return new FaultedScenario("首错安全暂停场景", edge.run().job(), edge.run().campaign(),
                    reportableStages(journal));
        } finally {
            edge.close();
        }
    }

    /**
     * 场景4：可重试失败耗尽运行时固定的下载失败预算后必须{@code FAILED}。
     *
     * <p><b>为什么由取字节端口而不是故障计划注入：</b>{@code OtaDeviceRuntime}与{@code OtaDeviceSession}
     * 只暴露「取字节端口工厂」，下载失败预算被固定在
     * {@code OtaDeviceExecution.DEFAULT_MAX_DOWNLOAD_FAILURES}=2（{@code OtaDeviceSession}第225行），
     * 没有任何公开构造器可调。这里因此取<b>能到达该预算的最小合法配置</b>：真实生产执行路径 + 一个始终以
     * 可重试传输失败（{@code Kind.TRANSPORT}）完成的取字节端口，第三次失败必然耗尽默认预算并写下
     * {@code DOWNLOAD_FAILURE_BUDGET_EXHAUSTED}。没有放宽任何断言，也没有伪造平台事实。</p>
     */
    private FaultedScenario assertDownloadFailureBudgetExhausted(Fixture fixture, ProvisionedDevice provisioned,
            Running run) throws Exception {
        EdgeScenario edge = openConnectedEdge(fixture, provisioned, run, OtaFaultPlan.NONE,
                OtaDurableUplinkEdgeIntegrationTests::alwaysFailingArtifactSource);
        try {
            deliverDownloadResponse(edge);
            List<OtaJournalRecord> journal = awaitJournal(edge.runtime(), Duration.ofSeconds(30),
                    records -> !records.isEmpty()
                            && records.getLast().reasonCode() == OtaReasonCode.DOWNLOAD_FAILURE_BUDGET_EXHAUSTED,
                    "设备写下失败阈值耗尽的终态");
            OtaJournalRecord terminal = journal.getLast();
            assertThat(terminal.stage()).isEqualTo(OtaStage.DOWNLOADING);
            assertThat(terminal.conclusion()).as("失败阈值耗尽必须是判定性失败").isEqualTo(OtaStage.FAILED);
            assertThat(terminal.downloadedBytes()).as("三次取字节全部失败，必须没有任何已确认字节").isZero();
            assertThat(Files.size(edge.runtime().stagingFile())).as("失败阈值耗尽时暂存区必须为空").isZero();
            assertThat(journal).as("失败阈值耗尽不得进入验证或安装，实际=" + stages(journal))
                    .noneMatch(record -> record.stage() == OtaStage.VERIFYING || record.stage().isSafetyStage()
                            || record.stage() == OtaStage.COMMITTED);
            return new FaultedScenario("失败阈值耗尽场景", edge.run().job(), edge.run().campaign(),
                    reportableStages(journal));
        } finally {
            edge.close();
        }
    }

    /**
     * 场景5：恢复被拒必须停在已确认偏移，绝不从0重来。
     *
     * <p><b>为什么用取字节端口而不是{@code OtaFaultPlan.rejectResume()}：</b>该故障计划只在执行开始时
     * 「日志里已有大于0的已确认偏移」才生效（{@code OtaDeviceExecution}第203行），因此使用它必然要求
     * 第二次投递；而第二次投递被设备会话的重复响应fail-closed判定阻断（见
     * 本类Javadoc的D-159段落实测记录）。这里取能到达同一终态事实的
     * 最小合法配置：首次取字节仍走真实MinIO预签名地址并只保留前缀，任何偏移大于0的续传请求立即以
     * {@link OtaArtifactException.Kind#RESUME_REJECTED}失败——这正是真实
     * {@code HttpRangeArtifactSource}在服务器忽略Range并从0重发时采用的同一分类。终端事实因此与
     * {@code rejectResume()}完全一致：{@code SAFETY_PAUSED}/{@code RESUME_REJECTED}且已确认偏移被保留。</p>
     */
    private FaultedScenario assertResumeRefusalKeepsConfirmedOffset(Fixture fixture, ProvisionedDevice provisioned,
            Running run) throws Exception {
        EdgeScenario edge = openConnectedEdge(fixture, provisioned, run, OtaFaultPlan.NONE,
                () -> resumeRefusingSource(2L));
        try {
            deliverDownloadResponse(edge);
            List<OtaJournalRecord> journal = awaitJournal(edge.runtime(), Duration.ofSeconds(30),
                    records -> !records.isEmpty()
                            && records.getLast().reasonCode() == OtaReasonCode.RESUME_REJECTED,
                    "设备写下续传被拒的安全暂停");
            OtaJournalRecord terminal = journal.getLast();
            assertThat(terminal.stage()).isEqualTo(OtaStage.DOWNLOADING);
            assertThat(terminal.conclusion()).as("续传被拒必须停在安全暂停").isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(terminal.downloadedBytes()).as("续传被拒必须保持已耐久确认的前缀偏移，而不是0")
                    .isEqualTo(2L);
            List<Long> offsets = journal.stream().map(OtaJournalRecord::downloadedBytes).toList();
            assertThat(offsets).as("下载偏移必须单调不减，续传被拒不得触发从0重来，实际偏移序列=" + offsets).isSorted();
            assertThat(Files.size(edge.runtime().stagingFile())).as("暂存区必须精确等于已确认偏移")
                    .isEqualTo(2L);
            return new FaultedScenario("恢复拒绝场景", edge.run().job(), edge.run().campaign(),
                    reportableStages(journal));
        } finally {
            edge.close();
        }
    }

    /**
     * 在同一个已READY固件上为另一个设备建立真实活动并启动。
     *
     * <p><b>为什么不能对第二个设备调用{@code publishedAndRunning}：</b>它内部的信任包导入与类型基线登记
     * 都以{@code expectedRevision=0}作为CAS围栏，并且拒绝同版本重复导入，同一项目只允许成功一次。
     * 这里只补该设备真实缺失的那一步——接纳一次真实设备报告——然后创建同固件活动、排程并启动。
     * 设备报告字节直接复用平台已接纳过的规范报告（同一类型/模型/信任域的固定内容），不构造新的设备声明。</p>
     *
     * @param fixture 本例真实项目图
     * @param device 本例新设备
     * @param template 已完整发布并启动过的运行事实，提供同一个READY固件与发布前置
     * @param acceptedReportCanonical 平台已接纳过的规范设备报告字节
     * @return 与新设备对应的已READY且已启动运行事实
     * @throws Exception 真实HTTP/数据库任一步失败时直接抛出
     */
    private Running runningOnExistingFirmware(Fixture fixture, Device device, Running template,
            byte[] acceptedReportCanonical) throws Exception {
        deviceReports.accept(new AuthenticatedDeviceIdentity(fixture.tenantId(), fixture.projectId(),
                device.id(), device.credentialVersion()), acceptedReportCanonical, Instant.now());
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_device_report WHERE device_id=?",
                Long.class, device.id()))
                .as("真实报告接纳必须为新设备留下当前报告，否则活动启动只会SKIP REPORT_MISSING").isEqualTo(1L);
        UUID campaign = UUID.fromString(ok(send(fixture, "POST", campaigns(fixture), key(),
                plan(template.firmware(), List.of(device.id())), false), 201).path("id").asText());
        String path = campaigns(fixture) + "/" + campaign;
        ok(send(fixture, "POST", path + "/scheduling", key(), expectedRevision("0"), false), 200);
        ok(send(fixture, "POST", path + "/starting", key(), expectedRevision("1"), false), 200);
        UUID job = owner().queryForObject("SELECT id FROM ota_device_job WHERE campaign_id=? AND device_id=?",
                UUID.class, campaign, device.id());
        return new Running(template.prepared(), campaign, job);
    }

    /** 基线与活动生命周期都拒绝隐式修订；与夹具使用同一规范编码构造方式。 */
    private static byte[] expectedRevision(String value) {
        return new OtaCanonicalJson().writeObject(Map.of("expectedRevision", value));
    }

    /**
     * 逐条复刻生产下载授权处理器的投递顺序并返回本次投递事实。
     *
     * <p>搬运自 {@link #realDownloadDeliveryDrivesDeviceClosedLoopToConfirming()} 第1~3步的
     * <b>同一顺序与同一断言</b>：领取→签址预留→真实MinIO预签名→封存→重新领取SEALED→发送预留→真实Broker→观察。
     * 本方法不新建授权、不重复签址、不放宽任何条件；任何等待都有界，失败消息点名被等待的事实。</p>
     *
     * @param edge 已建立的完整边缘场景
     * @return 本次真实投递的作业、授权身份、冻结清单摘要、规范响应字节与响应期限
     */
    private DownloadDelivery deliverDownloadResponse(EdgeScenario edge) {
        UUID job = edge.run().job();
        String manifestSha256 = (String) edge.dispatch().get("manifest_sha256");

        // 1）生产投递顺序：领取→签址预留→真实MinIO预签名→封存。
        var claim = awaitDownloadClaim(Duration.ofSeconds(15));
        UUID authorizationId = claim.authorizationId();
        assertThat(owner().queryForObject("SELECT job_id FROM ota_download_authorization WHERE id=?",
                UUID.class, authorizationId)).as("可领取的下载授权必须绑定本例真实作业").isEqualTo(job);
        var signing = authorizations.prepareSigning(authorizationId, claim.leaseToken())
                .orElseThrow(() -> new AssertionError("真实下载授权必须能在当前资格下预留签址，当前授权状态="
                        + authorizationStatus(authorizationId) + " 作业状态=" + jobStatus(job)
                        + " 活动状态=" + campaignStatus(edge.run().campaign())));
        URI downloadUrl = presignDownload(signing);
        assertThat(downloadUrl.getHost()).as("预签名地址必须指向真实MinIO主机，地址=" + sanitized(downloadUrl))
                .isEqualTo(MINIO.getHost());
        assertThat(downloadUrl.getPort()).as("预签名地址必须指向真实MinIO映射端口，地址=" + sanitized(downloadUrl))
                .isEqualTo(MINIO.getMappedPort(9000));
        assertThat(downloadUrl.getPath()).as("预签名地址必须落在本用例独占私桶内，地址=" + sanitized(downloadUrl))
                .startsWith("/" + BUCKET + "/");
        assertThat(authorizations.seal(authorizationId, claim.leaseToken(), downloadUrl))
                .as("真实MinIO预签名地址必须能被封存，当前授权状态=" + authorizationStatus(authorizationId)).isTrue();

        // 2）封存与作业阶段推进同事务，理由固定为DOWNLOAD_AUTHORIZED。
        assertThat(jobStatus(job)).as("封存下载授权必须把作业推进到DOWNLOADING").isEqualTo("DOWNLOADING");
        Map<String, Object> sealTransition = owner().queryForMap(
                "SELECT from_status,to_status,reason FROM ota_job_transition WHERE job_id=? AND to_status='DOWNLOADING'",
                job);
        assertThat(sealTransition).as("DOWNLOADING必须由DOWNLOAD_AUTHORIZED封存转移产生，而不是设备进度")
                .containsEntry("from_status", "DISPATCHED").containsEntry("to_status", "DOWNLOADING")
                .containsEntry("reason", "DOWNLOAD_AUTHORIZED");
        Map<String, Object> sealed = owner().queryForMap(
                "SELECT status,topic FROM ota_download_authorization WHERE id=?", authorizationId);
        assertThat(sealed.get("status")).as("封存后授权必须处于SEALED，当前=" + sealed.get("status"))
                .isEqualTo("SEALED");
        assertThat(sealed.get("topic")).as("封存必须绑定设备真实订阅的下载响应Topic")
                .isEqualTo("tc/v1/" + edge.projectKey() + "/" + edge.device().deviceKey()
                        + "/down/ota/download/response");

        // 3）发送预留→真实Broker→观察。
        //    封存会把授权上的签址租约置空（ota_download_seal：lease_token=NULL），因此发送阶段必须像
        //    生产处理器那样在下一轮 claimOne() 重新领取独立SEALED租约；旧能力直接发送必须为空。
        assertThat(authorizations.prepareSend(authorizationId, claim.leaseToken()))
                .as("封存必须撤销原签址租约，旧能力不得直接进入发送阶段").isEmpty();
        var sealedClaim = awaitDownloadClaim(Duration.ofSeconds(15));
        assertThat(sealedClaim.authorizationId()).as("重新领取的必须是同一条封存授权").isEqualTo(authorizationId);
        assertThat(sealedClaim.status()).as("重新领取时授权必须处于SEALED，当前="
                + authorizationStatus(authorizationId)).isEqualTo("SEALED");
        var sending = authorizations.prepareSend(authorizationId, sealedClaim.leaseToken())
                .orElseThrow(() -> new AssertionError("封存后必须能预留一次发送能力，当前授权状态="
                        + authorizationStatus(authorizationId)));
        assertThat(sending.projectKey()).as("下载响应路由的项目短标识必须来自权威项目")
                .isEqualTo(edge.projectKey());
        assertThat(sending.deviceKey()).as("下载响应路由的设备短标识必须来自权威设备")
                .isEqualTo(edge.device().deviceKey());
        Duration sendBudget = budget(Instant.now(), sending.expiresAt(), sending.leaseUntil());
        OtaDownloadResponsePublisher.Result result = downloadResponses.publish(sending.route(), sending.canonical(), sending.expiresAt(), sendBudget);
        assertThat(result.outcome()).as("真实Broker必须接受本次下载响应，失败原因=" + result.reason())
                .isEqualTo(OtaDownloadResponsePublisher.Outcome.BROKER_ACCEPTED);
        assertThat(authorizations.complete(sending.transport().id(), sending.transport().reservationToken(), result))
                .as("真实Broker观察必须能推进本次下载传输").isTrue();
        assertThat(authorizationStatus(authorizationId)).as("真实Broker接受后授权必须落到BROKER_ACCEPTED")
                .isEqualTo("BROKER_ACCEPTED");
        return new DownloadDelivery(job, authorizationId, manifestSha256, sending.canonical(), sending.expiresAt());
    }

    /** 一次真实下载响应投递的不可变事实；规范字节防御复制。 */
    private record DownloadDelivery(UUID job, UUID authorizationId, String manifestSha256, byte[] canonical,
            Instant expiresAt) {

        /** 冻结规范响应字节。 */
        private DownloadDelivery {
            canonical = canonical.clone();
        }

        /** @return 独立规范响应字节副本 */
        @Override
        public byte[] canonical() {
            return canonical.clone();
        }
    }

    /**
     * 首次取字节只保留前缀、任何续传请求都明确{@code RESUME_REJECTED}的取字节端口。
     *
     * <p>分类与真实{@code HttpRangeArtifactSource}在服务器忽略Range并从0重发时给出的
     * {@link OtaArtifactException.Kind#RESUME_REJECTED}一致；攻击面与顺序都没有放宽：续传请求根本不会
     * 到达网络，设备只能保持已确认偏移。</p>
     *
     * @param prefixBytes 首次取字节必须保留的前缀字节数
     * @return 取字节端口
     */
    private static OtaArtifactSource resumeRefusingSource(long prefixBytes) {
        return new OtaArtifactSource() {
            /** 真实HTTP Range端口；只有首个分片经它落成真实网络事实。 */
            private final OtaArtifactSource delegate = new HttpRangeArtifactSource();

            @Override
            public OtaArtifactSource.Chunk fetch(String uri, long startOffset, long expectedLength,
                    Duration budget) {
                if (startOffset > 0L) {
                    throw new OtaArtifactException(OtaArtifactException.Kind.RESUME_REJECTED,
                            "受控取字节端口明确拒绝从已确认偏移续传");
                }
                OtaArtifactSource.Chunk chunk = delegate.fetch(uri, startOffset, expectedLength, budget);
                long length = Math.min(prefixBytes, chunk.payload().length);
                return new OtaArtifactSource.Chunk(Arrays.copyOf(chunk.payload(), (int) length),
                        chunk.contentRangeAccepted(),
                        chunk.contentRangeStart(), chunk.totalLength());
            }
        };
    }

    /** 始终以可重试传输失败完成的取字节端口；用于让运行时固定的下载失败预算真实耗尽。 */
    private static OtaArtifactSource alwaysFailingArtifactSource() {
        return (uri, startOffset, expectedLength, budget) -> {
            throw new OtaArtifactException(OtaArtifactException.Kind.TRANSPORT,
                    "受控取字节端口始终报告可重试传输失败");
        };
    }

    /** 设备耐久日志里真正可上报给平台的阶段集合（与运行时的进度闭集同源语义）。 */
    private static final Set<String> REPORTABLE_PROGRESS_STAGES =
            Set.of("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING");

    /** 从耐久日志推导设备真实上报过的可报阶段集合。 */
    private static Set<String> reportableStages(List<OtaJournalRecord> records) {
        Set<String> stages = new LinkedHashSet<>();
        for (OtaJournalRecord record : records) {
            if (REPORTABLE_PROGRESS_STAGES.contains(record.stage().name())) {
                stages.add(record.stage().name());
            }
        }
        return stages;
    }

    /**
     * 有界等待耐久日志满足给定终态谓词。
     *
     * <p>日志逐行fsync追加，读取可能撞上未写完的最后一行；那属于物理竞态，丢弃该次读取继续等待，
     * 而不是把它当成设备失败。超时消息给出最后阶段序列、最后原因码与暂存区长度。</p>
     *
     * @param runtime 运行中的真实设备运行时
     * @param budget 最长等待预算
     * @param terminal 终态谓词
     * @param awaited 被等待事实的中文描述
     * @return 满足谓词的完整耐久记录
     */
    private List<OtaJournalRecord> awaitJournal(OtaDeviceRuntime runtime, Duration budget,
            Predicate<List<OtaJournalRecord>> terminal, String awaited) {
        FileOtaStateJournal journal = new FileOtaStateJournal(runtime.journalFile());
        long deadline = System.nanoTime() + budget.toNanos();
        List<OtaJournalRecord> records = List.of();
        while (System.nanoTime() < deadline) {
            records = readJournalQuietly(journal, records);
            if (terminal.test(records)) {
                return records;
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待" + awaited + "超时，预算=" + budget + "，最后阶段序列=" + stages(records)
                + "，最后原因=" + (records.isEmpty() ? "<none>" : records.getLast().reasonCode().name())
                + "，暂存区字节=" + stagingSize(runtime));
    }

    /**
     * 有界等待平台把设备真实上报的可报阶段逐条采用为进度事实。
     *
     * @param job 真实作业身份
     * @param expected 期望达到的进度事实条数
     * @param budget 最长等待预算
     * @param awaited 被等待事实的中文描述
     */
    private void awaitProgressCount(UUID job, long expected, Duration budget, String awaited) {
        long deadline = System.nanoTime() + budget.toNanos();
        long observed = -1;
        while (System.nanoTime() < deadline) {
            observed = owner().queryForObject("SELECT count(*) FROM ota_job_progress WHERE job_id=?",
                    Long.class, job);
            if (observed >= expected) {
                return;
            }
            if ("RECOVERY_REQUIRED".equals(jobStatus(job))) {
                break;
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待" + awaited + "超时，预算=" + budget + "，最后观察到ota_job_progress行数="
                + observed + "，进度事实=" + progressChain(job) + "，作业转移=" + transitionChain(job));
    }

    /**
     * 在观察窗口内确认平台没有出现任何设备进度事实。
     *
     * <p>这是一个「零是期望值」的窗口：窗口内一旦出现非零立即失败，窗口结束后仍为零才算通过；
     * 窗口长度有限且被点名，不是无界等待。</p>
     *
     * @param job 真实作业身份
     * @param window 观察窗口
     * @param scenario 场景名，只用于失败消息
     */
    private void awaitNoProgressWithin(UUID job, Duration window, String scenario) {
        long deadline = System.nanoTime() + window.toNanos();
        long observed = 0;
        while (System.nanoTime() < deadline) {
            observed = owner().queryForObject("SELECT count(*) FROM ota_job_progress WHERE job_id=?",
                    Long.class, job);
            if (observed != 0) {
                break;
            }
            sleepQuietly(100);
        }
        assertThat(observed).as(scenario + "：设备耐久日志没有上报任何可报阶段，平台不得在观察窗口 " + window
                + " 内出现任何ota_job_progress事实，实际行数=" + observed + " 进度事实=" + progressChain(job)).isZero();
    }

    /**
     * 平台侧「没有任何虚假成功」断言：进度/采用不得越过设备耐久日志真实记录的阶段，且不得有健康回执、
     * 提交许可、提交回执或成功/取消转移。
     *
     * @param job 真实作业身份
     * @param campaign 本例真实活动身份
     * @param deviceReportedStages 设备耐久日志真实记录过的可报阶段集合
     * @param scenario 场景名，只用于失败消息
     */
    private void assertNoFabricatedSuccess(UUID job, UUID campaign, Set<String> deviceReportedStages,
            String scenario) {
        List<Map<String, Object>> progress = owner().queryForList(
                "SELECT progress_seq,stage,adopted_status FROM ota_job_progress WHERE job_id=? ORDER BY progress_seq",
                job);
        assertThat(progress).as(scenario + "：平台不得出现设备耐久日志从未记录过的进度阶段；设备记录了"
                + deviceReportedStages + "，实际=" + progress)
                .allSatisfy(row -> assertThat(deviceReportedStages).contains((String) row.get("stage")));
        assertThat(progress).as(scenario + "：设备从未上报健康阶段，平台不得出现健康/确认阶段进度，实际=" + progress)
                .extracting(row -> row.get("stage")).doesNotContain("HEALTH_CHECKING", "CONFIRMING");
        List<String> adopted = owner().queryForList("SELECT to_status FROM ota_job_transition WHERE job_id=? "
                + "AND reason='AUTHENTICATED_PROGRESS' ORDER BY to_revision", String.class, job);
        assertThat(adopted).as(scenario + "：被采用的设备进度不得越过设备真实上报的阶段，实际=" + adopted)
                .allSatisfy(status -> assertThat(deviceReportedStages).contains(status));
        assertThat(adopted).as(scenario + "：设备未上报健康阶段，平台不得采用HEALTH_CHECKING/CONFIRMING")
                .doesNotContain("HEALTH_CHECKING", "CONFIRMING");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_health_receipt WHERE job_id=?",
                Long.class, job)).as(scenario + "：设备从未报告健康，平台不得有ota_health_receipt").isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_commit_permit WHERE job_id=?",
                Long.class, job)).as(scenario + "：设备从未进入CONFIRMING，平台不得签发ota_commit_permit").isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_commit_receipt WHERE job_id=?",
                Long.class, job)).as(scenario + "：设备从未提交，平台不得有ota_commit_receipt").isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? "
                + "AND to_status='SUCCEEDED'", Long.class, job))
                .as(scenario + "：设备未验证或安装成功的作业不得出现SUCCEEDED转移").isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? "
                + "AND to_status='CANCELLED'", Long.class, job))
                .as(scenario + "：本场景没有取消事实，不得出现CANCELLED转移").isZero();
        assertThat(jobStatus(job)).as(scenario + "：作业不得被声明为SUCCEEDED").isNotEqualTo("SUCCEEDED");
        assertThat(jobStatus(job)).as(scenario + "：作业不得被声明为CANCELLED").isNotEqualTo("CANCELLED");
        assertThat(campaignStatus(campaign)).as(scenario + "：唯一作业未收敛，活动必须保持RUNNING")
                .isEqualTo("RUNNING");
    }

    /**
     * 以默认「无故障计划 + 真实HTTP Range取字节端口」打开完整公开路径的真实边缘。
     *
     * @return 已建立的完整边缘场景（设备连接、运行时、raw监听器、平台申请事实）
     * @throws Exception 真实HTTP/MQTT/Kafka/数据库任一步失败时直接抛出
     */
    private EdgeScenario openDeviceUplinkEdge() throws Exception {
        return openDeviceUplinkEdge(OtaFaultPlan.NONE, HttpRangeArtifactSource::new);
    }

    /**
     * 以指定故障计划与取字节端口工厂打开完整公开路径的真实边缘。
     *
     * <p><b>为什么同一条测试方法里最多只能调用一次：</b>完整公开路径里的生产信任包导入与类型基线登记都以
     * {@code expectedRevision=0}作为CAS围栏（{@code OtaTrustService#importBundle}、
     * {@code OtaTypeBaselineService#register}），同一项目只允许成功一次。需要多个独立故障设备时，
     * 只有第一个设备走这里，其余设备用 {@link #runningOnExistingFirmware}复用同一个READY固件。</p>
     *
     * @param faultPlan 注入下一次下载执行的故障计划（{@link OtaFaultPlan#NONE}表示不注入）
     * @param artifactSourceFactory 取字节端口工厂；每次下载执行取一个端口
     * @return 已建立的完整边缘场景（设备连接、运行时、raw监听器、平台申请事实）
     * @throws Exception 真实HTTP/MQTT/Kafka/数据库任一步失败时直接抛出
     */
    private EdgeScenario openDeviceUplinkEdge(OtaFaultPlan faultPlan,
            Supplier<OtaArtifactSource> artifactSourceFactory) throws Exception {
        Fixture fixture = seed(ProjectRole.ADMIN);
        ProvisionedDevice provisioned = provisionDevice(fixture);
        Running run = publishedAndRunning(fixture, provisioned.device());
        return openConnectedEdge(fixture, provisioned, run, faultPlan, artifactSourceFactory);
    }

    /**
     * 复现S13-4d-2b-2b-1主证据的共享前缀：真实设备经整条生产边缘把一条下载申请落成平台事实。
     *
     * <p>本方法只搬运原用例第1~7步的<b>同一顺序与同一断言</b>，不改写任何被等待的事实或放宽任何条件；
     * 两个用例因此建立在完全相同的真实前置上。运行时以 {@code assertHealth=true} 装配：本片要证明
     * 运行时自己在真实重启边界轮换启动身份后发出的 {@code HEALTH_CHECKING} 能被平台采用（S13-4d-2b-2b-3）；
     * 由于本前缀不投递下载响应，这一开关在两个用例里都不会提前产生任何进度事实。资源在成功时交给
     * 调用方关闭，失败时由本方法自己收尾，避免中途失败把raw监听器留给下一个用例。</p>
     *
     * <p><b>为什么把「夹具/活动」与「边缘接通」拆开：</b>同一个测试方法里要覆盖多个相互独立的故障设备时，
     * 只有在一块已建立的项目/设备/活动上再接通边缘，才不会重复撞上生产信任包与类型基线的单次CAS围栏
     * （见 {@link #runningOnExistingFirmware}）。</p>
     *
     * @param fixture 本例真实项目图
     * @param provisioned 本例真实设备身份与一次性明文密钥
     * @param run 已READY且已启动的本例运行事实
     * @param faultPlan 注入下一次下载执行的故障计划（{@link OtaFaultPlan#NONE}表示不注入）
     * @param artifactSourceFactory 取字节端口工厂；每次下载执行取一个端口
     * @return 已建立的完整边缘场景（设备连接、运行时、raw监听器、平台申请事实）
     * @throws Exception 真实HTTP/MQTT/Kafka/数据库任一步失败时直接抛出
     */
    private EdgeScenario openConnectedEdge(Fixture fixture, ProvisionedDevice provisioned, Running run,
            OtaFaultPlan faultPlan, Supplier<OtaArtifactSource> artifactSourceFactory) throws Exception {
        assertAppListensOnDeclaredPort();
        awaitIngressReady(INGRESS_READY_BUDGET);
        assertThat(ingressReadiness.isReady())
                .as("设备连接之前生产ingress必须已完成内部Topic订阅，否则认证闸门会拒绝一切设备").isTrue();

        Device device = provisioned.device();

        // 1）真实租约准入：与4g完全相同的生产入口，把作业推进到DISPATCHED并生成派发意图。
        var pending = admission.claimOne().orElseThrow(() -> new AssertionError(
                "等待中的待准入作业必须可被真实数据库领取，当前作业状态=" + jobStatus(run.job())));
        assertThat(pending.jobId()).as("领取到的必须是本例刚启动的那条作业").isEqualTo(run.job());
        assertThat(admission.admit(pending.jobId(), pending.token()))
                .as("真实租约准入必须接受当前合格设备，失败原因=" + jobFailure(run.job())).isTrue();
        assertThat(jobStatus(run.job())).as("真实租约准入必须把作业推进到DISPATCHED")
                .isEqualTo("DISPATCHED");
        UUID event = owner().queryForObject("SELECT id FROM ota_job_dispatch_outbox WHERE job_id=?",
                UUID.class, run.job());
        assertThat(event).as("准入必须留下恰一条派发意图作为本次通知身份").isNotNull();
        Map<String, Object> dispatch = owner().queryForMap(
                "SELECT job_id,attempt_no,manifest_sha256,credential_version FROM ota_job_dispatch_outbox WHERE job_id=?",
                run.job());

        // 2）只有原始派发意图与通知投递：本前缀只发通知，设备因此只会上行下载申请。
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_notification_delivery WHERE event_id=?",
                Long.class, event)).as("派发意图必须同时留下恰一条通知投递事实").isEqualTo(1L);

        // 3）真实Kafka：先建raw与死信Topic，再按生产顺序手动启动raw监听器。
        var rawListener = startRawUplinkListener();
        String projectKey = projectKey(fixture);
        String uplinkTopic = "tc/v1/" + projectKey + "/" + device.deviceKey() + "/up/ota/download/request";

        PahoMqttDeviceClientFactory factory = new PahoMqttDeviceClientFactory();
        RecordingDeviceClient recording = null;
        try {
            // 4）真实设备会话：认证由生产HTTP回调决定，订阅与发布都走同一条已认证连接。
            MqttDeviceClient connected = factory.connect(mqttBrokerUri(), projectKey, device.deviceKey(),
                    provisioned.accessToken());
            assertThat(connected.isConnected()).as("真实Paho设备连接必须建立").isTrue();
            recording = new RecordingDeviceClient(connected);
            OtaSimulatorEvidence evidence = evidenceFromAcceptedReport(fixture, device, run);
            // assertHealth=true：本片要让运行时自己在真实重启边界轮换启动身份后发出HEALTH_CHECKING，
            // 因此显式声明健康事实（自检/看门狗是调用方声明的事实，不是模拟器发明的事实）。
            OtaDeviceRuntime runtime = new OtaDeviceRuntime(projectKey, device.deviceKey(), recording,
                    journalDirectory.resolve("ota").resolve(device.deviceKey()),
                    evidence, true, true, new SystemOtaClock(),
                    artifactSourceFactory, faultPlan);
            // start()内部等待每个SUBACK，返回即表示四个下行Topic都已订阅成功。
            runtime.start();
            assertThat(runtime.deviceDirectory()).as("真实设备目录必须落盘").isDirectory();

            // 身份来自生产认证回调的独立旁证：真实服务在凭据校验成功时会更新last_used_at。
            assertThat(owner().queryForObject("SELECT count(*) FROM dev_credential WHERE device_id=? "
                            + "AND deleted_at IS NULL AND last_used_at IS NOT NULL", Long.class, device.id()))
                    .as("生产认证回调必须验证过真实设备密钥并留下last_used_at，否则身份不可能来自生产代码")
                    .isEqualTo(1L);

            // 5）平台按生产通知worker的同一顺序发布真实通知：领取→预留→真实HTTP→观察。
            var claim = notifications.claimOne().orElseThrow(() -> new AssertionError(
                    "等待中的通知必须可被真实数据库交付租约领取，当前投递状态=" + deliveryStatus(event)));
            assertThat(claim.eventId()).as("领取到的通知必须是本例派发意图产生的那一条，事件=" + event)
                    .isEqualTo(event);
            var prepared = notifications.prepare(claim.eventId(), claim.leaseToken())
                    .orElseThrow(() -> new AssertionError("当前合格设备必须能完成通知预留，投递状态="
                            + deliveryStatus(event) + " 活动状态=" + campaignStatus(run.campaign())));
            assertThat(prepared.projectKey()).as("通知路由的项目短标识必须来自权威项目")
                    .isEqualTo(projectKey);
            assertThat(prepared.deviceKey()).as("通知路由的设备短标识必须来自权威设备")
                    .isEqualTo(device.deviceKey());
            var transport = prepared.transport();
            assertThat(transport.topic()).as("通知必须落在设备真实订阅的可用性Topic上")
                    .isEqualTo("tc/v1/" + projectKey + "/" + device.deviceKey() + "/down/ota/available");
            OtaNotificationPublisher.Result result = publisher.publish(prepared.route(),
                    transport.canonical(), Duration.ofSeconds(5));
            assertThat(result.outcome()).as("真实Broker必须接受本次MQTT发布，失败原因=" + result.reason()
                            + (result.outcome() == OtaNotificationPublisher.Outcome.BROKER_ACCEPTED
                                    ? "" : brokerFailureDiagnostics()))
                    .isEqualTo(OtaNotificationPublisher.Outcome.BROKER_ACCEPTED);
            assertThat(notifications.complete(transport.id(), transport.reservationToken(), result))
                    .as("真实Broker观察必须能推进当前投递").isTrue();

            // 6）设备必须真的收到通知并真的发出申请：字节由测试装饰器从真实客户端出口旁路复制。
            byte[] deviceUplink = recording.awaitUplink(uplinkTopic, Duration.ofSeconds(20));
            OtaDeviceDownloadRequestCodec.Decoded decoded = OtaDeviceDownloadRequestCodec.decode(deviceUplink);
            assertThat(decoded.value().jobId()).as("设备申请必须指向平台真实派发的作业").isEqualTo(run.job());
            assertThat(decoded.value().attemptNo()).as("设备申请必须使用平台唯一允许的尝试号").isEqualTo(1);
            assertThat(decoded.value().manifestSha256()).as("设备申请必须携带平台冻结的清单摘要")
                    .isEqualTo(dispatch.get("manifest_sha256"));

            // 7）整条边缘必须落成一条不可变平台事实。
            Map<String, Object> receipt = awaitDownloadRequest(device.id(), Duration.ofSeconds(30));
            assertThat(receipt.get("credential_version")).as(
                    "持久凭据代际必须等于凭据API返回的真实代际，这是身份来自生产认证回调而非测试信封的证据，"
                            + "实际=" + receipt.get("credential_version") + " 期望=" + device.credentialVersion())
                    .isEqualTo(device.credentialVersion());
            assertThat(receipt.get("canonical_sha256")).as("规范摘要必须等于设备真实发送字节的SHA-256")
                    .isEqualTo(sha(deviceUplink));
            assertThat((byte[]) receipt.get("canonical")).as("持久规范字节必须与设备真实发送字节逐字节相同")
                    .isEqualTo(deviceUplink);
            assertThat(receipt.get("broker_received_at")).as("原始Broker接收时间必须被真实记录").isNotNull();
            assertThat(receipt.get("job_id")).as("下载申请必须绑定平台真实作业").isEqualTo(run.job());
            assertThat(receipt.get("campaign_id")).as("下载申请必须绑定平台真实活动").isEqualTo(run.campaign());
            assertThat(receipt.get("device_id")).as("下载申请必须绑定认证设备").isEqualTo(device.id());
            assertThat(receipt.get("attempt_no")).as("下载申请必须使用派发尝试号").isEqualTo(1);
            assertThat(receipt.get("manifest_sha256")).as("下载申请必须绑定派发意图的清单摘要")
                    .isEqualTo(dispatch.get("manifest_sha256"));
            assertThat(receipt.get("request_id")).as("设备生成的requestId必须被平台如实持久")
                    .isEqualTo(decoded.value().requestId());

            return new EdgeScenario(fixture, provisioned, device, run, event, dispatch, projectKey, recording,
                    factory, runtime, rawListener, deviceUplink, decoded, receipt, evidence);
        } catch (Exception | Error failure) {
            if (recording != null) {
                recording.close();
            }
            factory.shutdown();
            stopRawUplinkListener(rawListener);
            throw failure;
        }
    }

    /** 仅在真实发布断言失败时保留本容器的末尾诊断，避免固定分类吞掉测试环境首因。 */
    private static String brokerFailureDiagnostics() {
        try {
            String logs = BROKER.getLogs();
            int start = Math.max(0, logs.length() - 6000);
            return "；Broker运行=" + BROKER.isRunning() + "，容器日志尾部=" + logs.substring(start);
        } catch (RuntimeException unavailable) {
            return "；Broker诊断不可用=" + unavailable.getClass().getSimpleName();
        }
    }

    /**
     * 有界等待出现一条可领取的下载授权。
     *
     * <p>下载授权由接纳事务的数据库触发器创建；本方法只做有界消抖，失败消息点名被等待的事实与
     * 观察到的作业状态，绝不把“还没创建”当成“不存在”。</p>
     *
     * @param budget 最长等待预算
     * @return 已领取的授权能力
     */
    private OtaDownloadAuthorizationRepository.Claim awaitDownloadClaim(Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            var found = authorizations.claimOne();
            if (found.isPresent()) {
                return found.orElseThrow();
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待可领取的下载授权超时，预算=" + budget
                + "，最后授权状态=" + latestAuthorizationStatus());
    }

    /**
     * 有界等待出现一条可领取的提交许可。
     *
     * <p>许可由健康窗口确认事务在同一事务里持久；本方法只做有界消抖，失败消息点名被等待的事实、
     * 观察到的作业状态与活动状态，绝不把“还没创建”当成“不存在”。</p>
     *
     * @param job 本例真实作业身份，只用于失败消息
     * @param budget 最长等待预算
     * @return 已领取的许可交付能力
     */
    private OtaCommitPermitDeliveryRepository.Claim awaitCommitPermitClaim(UUID job, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            var found = commitPermits.claimOne();
            if (found.isPresent()) {
                return found.orElseThrow();
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待可领取的提交许可超时，预算=" + budget + "，作业状态=" + jobStatus(job)
                + "，作业转移=" + transitionChain(job) + "，健康事实=" + healthChain(job));
    }

    /**
     * 有界等待出现一条可领取的安装前停止交付能力。
     *
     * <p>停止投递由生产短事务在取消请求后创建；本方法只做有界消抖，失败消息点名被等待的事实、
     * 观察到的活动/作业状态与停止控制事实，绝不把“还没创建”当成“不存在”。</p>
     *
     * @param job 本例真实作业身份，只用于失败消息
     * @param budget 最长等待预算
     * @return 已领取的停止交付能力
     */
    private OtaInstallStopDeliveryRepository.Claim awaitInstallStopClaim(UUID job, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            var found = stopDeliveries.claimOne();
            if (found.isPresent()) {
                return found.orElseThrow();
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待可领取的安装前停止交付超时，预算=" + budget + "，作业状态=" + jobStatus(job)
                + "，作业转移=" + transitionChain(job));
    }

    /**
     * 有界等待真实设备停止报告经整条边缘落成恰一条 {@code ota_install_stop_report}。
     *
     * @param operationId 本例停止操作身份
     * @param job 本例真实作业身份，只用于失败消息
     * @param budget 最长等待预算
     * @return 完整持久行
     */
    private Map<String, Object> awaitInstallStopReport(UUID operationId, UUID job, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        long count = 0;
        while (System.nanoTime() < deadline) {
            count = owner().queryForObject("SELECT count(*) FROM ota_install_stop_report WHERE operation_id=?",
                    Long.class, operationId);
            if (count == 1) {
                return owner().queryForMap("SELECT * FROM ota_install_stop_report WHERE operation_id=?", operationId);
            }
            if (count > 1) {
                break;
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待真实停止报告经边缘落成恰一条 ota_install_stop_report 超时，预算=" + budget
                + "，最后观察到行数=" + count + "，作业状态=" + jobStatus(job)
                + "，作业转移=" + transitionChain(job) + "，停止控制=" + stopControl(operationId));
    }

    /**
     * 有界等待设备耐久日志写下「安装前取消」裁决。
     *
     * <p>日志逐行fsync追加，读取可能撞上未写完的最后一行；那属于物理竞态，丢弃该次读取继续等待，
     * 而不是把它当成设备失败。超时消息给出最后阶段序列。</p>
     *
     * @param runtime 运行中的真实设备运行时
     * @param budget 最长等待预算
     * @return 完整耐久记录
     */
    private List<OtaJournalRecord> awaitStopDecision(OtaDeviceRuntime runtime, Duration budget) {
        FileOtaStateJournal journal = new FileOtaStateJournal(runtime.journalFile());
        long deadline = System.nanoTime() + budget.toNanos();
        List<OtaJournalRecord> records = List.of();
        while (System.nanoTime() < deadline) {
            records = readJournalQuietly(journal, records);
            if (records.stream().anyMatch(record ->
                    record.reasonCode() == OtaReasonCode.CANCELLED_BEFORE_SAFETY_STAGE)) {
                return records;
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待真实设备耐久日志写下停止先赢裁决超时，预算=" + budget
                + "，最后阶段序列=" + stages(records) + " 设备目录=" + runtime.deviceDirectory());
    }

    /** 停止控制事实诊断串，只用于失败消息。 */
    private String stopControl(UUID operationId) {
        return owner().queryForList("SELECT accepted_report_id,stopped_report_id,install_won_report_id,"
                + "conflicted_at FROM ota_install_stop_control WHERE operation_id=?", operationId).toString();
    }

    /**
     * 从已登记的父类型基线构造本片受控安装前停止扩展来源。
     *
     * <p>复制自 {@code OtaInstallStopIntegrationTests.stopSource} 的同一构造方式：扩展的
     * {@code typeBaselineSha256}必须等于运维配置里父基线的完整规范摘要，启动器范围只允许收窄，
     * 因此不能由测试自造数字。本片不新增父基线登记，只在这里声明扩展。</p>
     *
     * @return 可作为 {@code things-link.ota.install-stop.type-baselines-json} 的完整文本
     */
    private static String stopBaselineSource() {
        OtaCanonicalJson canonical = new OtaCanonicalJson();
        Map<String, Object> root = canonical.parseObject(baselineSource().getBytes(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> parentFields = (Map<String, Object>) ((List<?>) root.get("baselines")).getFirst();
        OtaTypeBaselineCodec.Decoded parent = new OtaTypeBaselineCodec().decode(canonical.writeObject(parentFields));
        OtaTypeBaselineCodec.Baseline value = parent.value();
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("contractVersion", "tc-ota-install-stop-baseline/v1");
        extension.put("tenantId", value.tenantId().toString());
        extension.put("projectId", value.projectId().toString());
        extension.put("deviceTypeId", value.deviceTypeId().toString());
        extension.put("productKey", value.productKey());
        extension.put("typeBaselineVersion", value.baselineVersion());
        extension.put("typeBaselineSha256", parent.sha256());
        extension.put("stopBaselineVersion", 1L);
        extension.put("atomicOperationProfile", "TC_OTA_INSTALL_STOP_JOURNAL_V1");
        extension.put("bootloader", parentFields.get("bootloader"));
        extension.put("evidenceReference", "s13-4d-2b-2b-5-edge-stop");
        return new String(canonical.writeObject(Map.of("baselines", List.of(extension))), StandardCharsets.UTF_8);
    }

    /** 本用例最近一条下载授权状态，只用于失败消息。 */
    private String latestAuthorizationStatus() {
        List<String> statuses = owner().queryForList(
                "SELECT status FROM ota_download_authorization ORDER BY created_at DESC LIMIT 1", String.class);
        return statuses.isEmpty() ? "<none>" : statuses.getFirst();
    }

    /** 下载授权状态，只用于失败消息。 */
    private String authorizationStatus(UUID authorizationId) {
        List<String> statuses = owner().queryForList("SELECT status FROM ota_download_authorization WHERE id=?",
                String.class, authorizationId);
        return statuses.isEmpty() ? "<none>" : statuses.getFirst();
    }

    /** 预签名地址只用于失败消息时去掉查询串（签名本身就是秘密）。 */
    private static String sanitized(URI url) {
        return url.getScheme() + "://" + url.getHost() + ":" + url.getPort() + url.getPath();
    }

    /**
     * 逐条复刻生产 {@code OtaDownloadAuthorizationProcessor.process} 的签址预算与取消检查。
     *
     * <p>为什么不直接调用处理器：处理器在独立线程池里按秒轮询并自行领取，测试无法把断言钉在
     * “这一次领取”上，也会与用例的顺序竞争。这里因此走同一组生产服务方法，并把处理器的预算规则
     * （TTL∈[1,54]秒、网络预算≤min(5秒, 剩余期限)、{@code VersionedStorageControl}的取消检查）
     * 原样复制；任何一条不成立都直接失败并点名观察值，绝不悄悄放宽。</p>
     *
     * @param signing 已预留的唯一签址能力
     * @return 真实MinIO为该固定版本签发的GET地址
     */
    private URI presignDownload(OtaDownloadAuthorizationService.Signing signing) {
        assertThat(storage.orderedStream().limit(2).count())
                .as("生产处理器只在恰有一个版本化私桶端口时签址").isEqualTo(1L);
        Instant expiresAt = signing.expiresAt();
        Instant leaseUntil = signing.leaseUntil();
        Instant limit = expiresAt.isBefore(leaseUntil) ? expiresAt : leaseUntil;
        Duration control = budget(Instant.now(), expiresAt, leaseUntil);
        assertThat(control).as("生产签址预算必须为正，响应期限=" + expiresAt + " 租约期限=" + leaseUntil)
                .isGreaterThanOrEqualTo(Duration.ofMillis(1));
        VersionedStorageControl cancellation = new VersionedStorageControl(control,
                () -> Thread.currentThread().isInterrupted() || !Instant.now().isBefore(limit));
        long seconds = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        assertThat(seconds).as("生产处理器只接受1..54秒的预签名TTL，响应期限=" + expiresAt + " 观测秒数=" + seconds)
                .isBetween(1L, 54L);
        assertThat(cancellation.cancelled()).as("签址前取消检查不得已经生效").isFalse();
        assertThat(cancellation.timedOut()).as("签址前预算不得已经耗尽").isFalse();
        return storage.getObject().presignGet(signing.version(), Duration.ofSeconds(seconds), cancellation);
    }

    /** 网络预算不越过任一固定期限，且独立上限五秒；与生产处理器逐字同规则。 */
    private static Duration budget(Instant now, Instant expiresAt, Instant leaseUntil) {
        Instant limit = expiresAt.isBefore(leaseUntil) ? expiresAt : leaseUntil;
        Duration remaining = Duration.between(now, limit);
        return remaining.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : remaining;
    }

    /**
     * 有界等待真实设备把耐久日志写到终态 {@link OtaStage#COMMITTED}。
     *
     * <p>日志是逐行fsync追加的，读取时可能撞上未写完的最后一行；那属于读取瞬间的物理竞态，
     * 本方法丢弃该次读取并继续等待，而不是把它当成设备失败。超时消息给出最后阶段序列与暂存区长度。</p>
     *
     * @param runtime 运行中的真实设备运行时
     * @param budget 最长等待预算
     * @return 完整耐久记录
     */
    private List<OtaJournalRecord> awaitDeviceConclusion(OtaDeviceRuntime runtime, Duration budget) {
        FileOtaStateJournal journal = new FileOtaStateJournal(runtime.journalFile());
        long deadline = System.nanoTime() + budget.toNanos();
        List<OtaJournalRecord> records = List.of();
        while (System.nanoTime() < deadline) {
            records = readJournalQuietly(journal, records);
            if (!records.isEmpty() && records.getLast().stage() == OtaStage.COMMITTED) {
                return records;
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待真实设备下载执行走到耐久COMMITTED超时，预算=" + budget
                + "，最后阶段序列=" + stages(records) + " 暂存区字节=" + stagingSize(runtime)
                + " 设备目录=" + runtime.deviceDirectory());
    }

    /** 读取设备日志；撞上未写完的最后一行时保留上一次成功读取的快照。 */
    private static List<OtaJournalRecord> readJournalQuietly(FileOtaStateJournal journal,
            List<OtaJournalRecord> previous) {
        try {
            return journal.read();
        } catch (RuntimeException transientRead) {
            return previous;
        }
    }

    /** 暂存区字节数，只用于失败消息。 */
    private static String stagingSize(OtaDeviceRuntime runtime) {
        try {
            return String.valueOf(Files.size(runtime.stagingFile()));
        } catch (IOException missing) {
            return "<unavailable:" + missing.getClass().getSimpleName() + ">";
        }
    }

    /** 设备日志阶段序列，只用于失败消息。 */
    private static String stages(List<OtaJournalRecord> records) {
        return records.stream().map(record -> record.stage().name()).toList().toString();
    }

    /**
     * 有界等待作业到达期望状态；出现 {@code RECOVERY_REQUIRED} 立即失败并打印平台给出的理由链。
     *
     * @param job 真实作业身份
     * @param expected 期望状态
     * @param budget 最长等待预算
     */
    private void awaitJobStatus(UUID job, String expected, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        String status = null;
        while (System.nanoTime() < deadline) {
            status = jobStatus(job);
            if (expected.equals(status)) {
                return;
            }
            if ("RECOVERY_REQUIRED".equals(status)) {
                break;
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待作业状态=" + expected + "超时，预算=" + budget + "，最后状态=" + status
                + "，进度事实=" + progressChain(job) + "，作业转移=" + transitionChain(job)
                + "，健康事实=" + healthChain(job));
    }

    /** 进度事实诊断串，只用于失败消息。 */
    private String progressChain(UUID job) {
        return owner().queryForList("SELECT progress_seq,stage,coalesce(adopted_status,'OBSERVED') AS adopted "
                + "FROM ota_job_progress WHERE job_id=? ORDER BY progress_seq", job).toString();
    }

    /** 作业转移诊断串，只用于失败消息。 */
    private String transitionChain(UUID job) {
        return owner().queryForList("SELECT to_status,reason FROM ota_job_transition WHERE job_id=? ORDER BY to_revision",
                job).toString();
    }

    /** 健康事实诊断串，只用于失败消息。 */
    private String healthChain(UUID job) {
        return owner().queryForList("SELECT health_seq,coalesce(adopted_status,'OBSERVED') AS adopted "
                + "FROM ota_health_receipt WHERE job_id=? ORDER BY health_seq", job).toString();
    }

    /**
     * 按25秒间隔发送4条真实健康心跳，使真实Broker时间跨过计划冻结的60秒健康窗口。
     *
     * <p><b>为什么心跳仍由调用方构造、只经运行时转发：</b>{@code OtaSimulatorEvidence}把自检/看门狗
     * 健康定义为“必须由掌握该事实的调用方显式构造”的输入，模拟器没有受保护计数器与真实自检，因此
     * 运行时绝不发明心跳。这里按真实观察构造 {@link OtaDeviceHealthCodec.Health}，并经运行时暴露的
     * 会话健康接口（{@link OtaDeviceRuntime#publishHealth}→{@code OtaDeviceSession#publishHealth}）
     * 在同一条真实已认证连接上发布；交付收据有界等待，避免把“已发起”误当成“Broker已确认”。</p>
     *
     * <p>相邻观察间隔必须≤30秒（{@code OtaConfirmationIngestionService}），且每条声明的
     * {@code uptimeMillis}/{@code healthyForMillis}增量必须相等；本方法让两者都等于自首条起的真实
     * 已过毫秒，因此平台看到的是连续且自洽的设备观察，而不是编造的时间。</p>
     */
    private void publishHealthWindow(EdgeScenario edge, UUID authorizationId, UUID rebootedBootId,
            List<OtaJournalRecord> records, String manifestSha256) {
        OtaJournalRecord health = lastStage(records, OtaStage.HEALTH_CHECKING);
        Instant firstSentAt = Instant.now();
        long firstUptimeMillis = 100_000L;
        for (long sequence = 1L; sequence <= 4L; sequence++) {
            if (sequence > 1L) {
                awaitSince(firstSentAt, Duration.ofSeconds(25L * (sequence - 1L)));
            }
            long elapsed = Duration.between(firstSentAt, Instant.now()).toMillis();
            OtaDeviceHealthCodec.Health message = new OtaDeviceHealthCodec.Health(
                    OtaDeviceHealthCodec.CONTRACT_VERSION, edge.run().job(), 1, authorizationId, manifestSha256,
                    sequence, rebootedBootId, new OtaDeviceHealthCodec.HealthEvidence(
                            edge.evidence().stageEvidence(health, health.artifactSha256(), health.artifactSize(), true),
                            firstUptimeMillis + elapsed, elapsed));
            awaitHealthDelivery(edge, message, sequence);
        }
    }

    /**
     * 经会话健康接口发布一条心跳，并有界等待真实Broker的交付收据。
     *
     * @param edge 完整边缘场景
     * @param message 调用方按真实观察构造的健康确认
     * @param healthSeq 健康序号，只用于失败消息
     */
    private static void awaitHealthDelivery(EdgeScenario edge, OtaDeviceHealthCodec.Health message, long healthSeq) {
        try {
            edge.runtime().publishHealth(message).get(20L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待健康心跳交付收据时线程被中断，healthSeq=" + healthSeq, interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new AssertionError("健康心跳未在20秒内获得真实Broker交付收据，healthSeq=" + healthSeq, failure);
        }
    }

    /** 有界等待自某时刻起至少经过给定间隔；用于形成真实的健康心跳节奏。 */
    private static void awaitSince(Instant origin, Duration interval) {
        long deadline = System.nanoTime() + interval.toNanos() + Duration.ofSeconds(20).toNanos();
        while (Duration.between(origin, Instant.now()).compareTo(interval) < 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("等待健康心跳间隔=" + interval + "超时，起点=" + origin);
            }
            sleepQuietly(100);
        }
    }

    /**
     * 有界等待四个设备阶段各留一条平台进度事实。
     *
     * <p><b>为什么不等待某个中间作业状态：</b>四条进度由同一个运行时在真实Broker上连续发出，平台采用
     * 速度远快于轮询，等待 {@code REBOOTING} 会与 {@code HEALTH_CHECKING} 的到达竞态。这里等待被断言
     * 的事实本身（每阶段一条 {@code ota_job_progress}），一旦出现 {@code RECOVERY_REQUIRED} 立即失败
     * 并打印平台给出的理由链，绝不把“还没到达”当成“已采用”。</p>
     *
     * @param job 真实作业身份
     * @param stages 期望按进度序号出现的阶段
     * @param budget 最长等待预算
     */
    private void awaitProgressStages(UUID job, List<String> stages, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        List<String> observed = List.of();
        while (System.nanoTime() < deadline) {
            observed = owner().queryForList("SELECT stage FROM ota_job_progress WHERE job_id=? ORDER BY progress_seq",
                    String.class, job);
            if (observed.equals(stages)) {
                return;
            }
            if ("RECOVERY_REQUIRED".equals(jobStatus(job))) {
                break;
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待设备进度阶段=" + stages + "超时，预算=" + budget + "，最后阶段=" + observed
                + "，进度事实=" + progressChain(job) + "，作业转移=" + transitionChain(job)
                + "，健康事实=" + healthChain(job));
    }

    /** 取某阶段的最后一条设备耐久记录；缺失时直接失败并点名实际阶段序列。 */
    private static OtaJournalRecord lastStage(List<OtaJournalRecord> records, OtaStage stage) {
        return records.stream().filter(record -> record.stage() == stage).reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("设备耐久日志缺少阶段=" + stage + "，实际阶段序列=" + stages(records)));
    }

    /**
     * 完整边缘场景：它的生命周期横跨「设备已连上真实Broker」到「平台已落成申请事实」。
     *
     * <p>关闭顺序与S13-4d-2b-2b-1主用例逐字相同：先关设备连接，再关客户端工厂，最后确实停掉
     * 生产raw监听器，使下一个用例仍能断言“测试profile没有自动启动它”。</p>
     *
     * @param fixture 本例真实项目图
     * @param provisioned 真实设备身份与一次性明文密钥
     * @param device 真实设备身份、短标识与凭据代际
     * @param run 已READY且已启动的运行事实
     * @param event 派发意图身份
     * @param dispatch 派发意图关键字段
     * @param projectKey 权威项目短标识
     * @param recording 旁路复制出站字节的真实设备客户端装饰器
     * @param factory 真实Paho设备客户端工厂
     * @param runtime 运行中的真实设备OTA运行时
     * @param rawListener 手动启动的生产raw监听器
     * @param deviceUplink 设备真实发出的下载申请字节
     * @param decoded 该申请的严格解码结果
     * @param receipt 平台落成的下载申请事实
     * @param evidence 与平台报告一致的设备证据（供重启后的进度/健康报文复用）
     */
    private record EdgeScenario(Fixture fixture, ProvisionedDevice provisioned, Device device, Running run,
            UUID event, Map<String, Object> dispatch, String projectKey, RecordingDeviceClient recording,
            PahoMqttDeviceClientFactory factory, OtaDeviceRuntime runtime,
            org.springframework.kafka.listener.MessageListenerContainer rawListener, byte[] deviceUplink,
            OtaDeviceDownloadRequestCodec.Decoded decoded, Map<String, Object> receipt,
            OtaSimulatorEvidence evidence) {

        /** 与主用例finally相同的收尾顺序。 */
        void close() throws InterruptedException {
            recording.close();
            factory.shutdown();
            stopRawUplinkListener(rawListener);
        }
    }

    /**
     * 失败关闭(a)：错误设备密钥必须被真实Broker在CONNECT阶段拒绝。
     *
     * <p>为什么先做一次正确凭据连接：只有先证明「同一账号、同一回调路径、同一时刻」的正确密钥能连上，
     * 「错误密钥被拒绝」才归因于凭据校验本身，而不是回调不可达或ingress未就绪。</p>
     *
     * @throws Exception 真实连接失败时抛出
     */
    @Test
    void wrongDeviceSecretIsRefusedByRealBroker() throws Exception {
        assertAppListensOnDeclaredPort();
        awaitIngressReady(INGRESS_READY_BUDGET);
        Fixture fixture = seed(ProjectRole.ADMIN);
        ProvisionedDevice provisioned = provisionDevice(fixture);
        Device device = provisioned.device();
        String projectKey = projectKey(fixture);

        PahoMqttDeviceClientFactory factory = new PahoMqttDeviceClientFactory();
        try {
            MqttDeviceClient correct = factory.connect(mqttBrokerUri(), projectKey, device.deviceKey(),
                    provisioned.accessToken());
            try {
                assertThat(correct.isConnected()).as("正确设备密钥必须能与真实Broker建立会话").isTrue();
            } finally {
                correct.close();
            }
            assertThatThrownBy(() -> factory.connect(mqttBrokerUri(), projectKey, device.deviceKey(),
                    "wrong-device-secret-" + UUID.randomUUID()))
                    .as("错误设备密钥必须被真实Broker拒绝，设备=" + device.deviceKey())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MQTT 设备连接失败")
                    .cause()
                    .isInstanceOf(MqttException.class)
                    .satisfies(cause -> {
                        // Paho把这两个拒绝码声明为short；直接isIn(常量)会装箱成Short并与Integer比较而永不相等，
                        // 因此先收敛成int再断言，避免把「供应商常量类型」误报成「Broker没有拒绝」。
                        int reasonCode = ((MqttException) cause).getReasonCode();
                        assertThat(reasonCode)
                                .as("真实Broker必须以MQTT3.1.1认证拒绝关闭CONNECT，4=口令错误 5=未授权，实际="
                                        + reasonCode)
                                .isIn((int) MqttException.REASON_CODE_FAILED_AUTHENTICATION,
                                        (int) MqttException.REASON_CODE_NOT_AUTHORIZED);
                    });
        } finally {
            factory.shutdown();
        }
    }

    /**
     * 失败关闭(b)：设备向不属于自己的projectKey/deviceKey上行，必须在边缘被永久拒绝。
     *
     * <p><b>这里观察到的是哪一道门：</b>直接观察到的是EMQX ACL（{@code no_match=deny}）：同一
     * (username, topic, publish)及真实认证签发属性及连接票据在ACL回调上返回{@code deny}，而同设备自己的上行Topic
     * 返回{@code allow}——正反两面都由生产{@code EmqxAclController}给出，排除了「回调整体失效」。</p>
     *
     * <p><b>第二道门为什么无法在本环境单独触发：</b>{@code RawUplinkIngestionService}还会核对
     * {@code MqttUplinkTopic.belongsTo(username)}。信封里的username只能来自MQTT CONNECT用户名，
     * 而ACL已经保证Topic里的project/device与该用户名一致；同时没有任何身份被允许向内部Topic
     * {@code tc/internal/v1/ingress/uplink}发布，因此无法在不绕过ACL的情况下构造「用户名与Topic
     * 不一致」的信封。第二道门是纵深防御，在本链路上被第一道严格覆盖，不能伪装成已被独立触发。</p>
     *
     * <p>载荷刻意用平台合同完全合法的五字段申请：唯一「错」的就是Topic归属，这样「无持久事实」
     * 才归因于归属而非载荷格式。</p>
     *
     * @throws Exception 真实MQTT/数据库失败时抛出
     */
    @Test
    void foreignTopicUplinkIsDeniedAtEdgeWithoutPlatformFact() throws Exception {
        assertAppListensOnDeclaredPort();
        awaitIngressReady(INGRESS_READY_BUDGET);
        Fixture fixture = seed(ProjectRole.ADMIN);
        ProvisionedDevice provisioned = provisionDevice(fixture);
        Device device = provisioned.device();
        String projectKey = projectKey(fixture);
        String deviceUser = projectKey + "/" + device.deviceKey();
        String ownTopic = "tc/v1/" + projectKey + "/" + device.deviceKey() + "/up/ota/download/request";
        String foreignTopic = "tc/v1/" + projectKey + "/device" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16) + "/up/ota/download/request";

        // 使用真实认证响应签发的身份及连接票据，不从业务载荷或测试猜测身份。
        var attributes = authenticateAttributes(deviceUser, provisioned.accessToken(), "ota-acl-" + device.id());
        assertThat(acl(deviceUser, ownTopic, "2")).as("缺服务器属性的旧三字段请求必须拒绝").isEqualTo("deny");
        assertThat(acl(deviceUser, ownTopic, "2", attributes)).as("生产ACL必须允许设备向自己的 up/ Topic发布")
                .isEqualTo("allow");
        // 待观察的门：同一身份、同一动作，只有Topic归属不同。
        assertThat(acl(deviceUser, foreignTopic, "2", attributes))
                .as("生产ACL必须拒绝设备向非本机 projectKey/deviceKey 的Topic发布，topic=" + foreignTopic)
                .isEqualTo("deny");

        PahoMqttDeviceClientFactory factory = new PahoMqttDeviceClientFactory();
        MqttDeviceClient connected = null;
        // raw监听器必须真的在运行：否则「没有申请行」只能说明没人消费，而不是边缘拒绝。
        var rawListener = startRawUplinkListener();
        try {
            connected = factory.connect(mqttBrokerUri(), projectKey, device.deviceKey(), provisioned.accessToken());
            byte[] wellFormedUplink = OtaDeviceDownloadRequestCodec.encode(new OtaDeviceDownloadRequestCodec.Request(
                    OtaDeviceDownloadRequestCodec.CONTRACT_VERSION, Uuid7.generate(), syntheticJobId(), 1,
                    "a".repeat(64)));
            // Broker的deny_action=ignore是「丢弃且不断开」，PUBACK与否属于供应商实现细节，因此不断言收据；
            // 断言的是两个独立可观察的结果：原始上行Topic没有该设备的记录，且平台没有申请事实。
            CompletableFuture<Void> delivery = connected.publish(foreignTopic, wellFormedUplink, 1, false);
            assertNoRawUplinkWithin(device.id(), Duration.ofSeconds(5));
            assertNoDownloadRequestWithin(device.id(), Duration.ofSeconds(5), delivery, foreignTopic);
        } finally {
            if (connected != null) {
                connected.close();
            }
            factory.shutdown();
            stopRawUplinkListener(rawListener);
        }
    }

    /**
     * 失败关闭(c)：ingress服务身份不得发布或订阅任何设备Topic。
     *
     * <p>本用例断言的是生产{@code EmqxAclController}的直接HTTP响应，而不是再开一条
     * {@code thingslink-uplink-ingress-v1}连接：同clientId再连一次会按MQTT接管语义把真实ingress
     * 踢下线，那既不是被观察的权限事实，也会破坏本类其他用例的前提。真实CONNECT侧另有两条独立
     * 断言：用户名与密码正确但clientId不是冻结所有者时必须被拒绝，用户名与clientId正确但密码错误
     * 时同样必须被拒绝。</p>
     *
     * @throws Exception 真实HTTP/连接失败时抛出
     */
    @Test
    void ingressServiceIdentityIsDeniedDeviceTopics() throws Exception {
        assertAppListensOnDeclaredPort();
        awaitIngressReady(INGRESS_READY_BUDGET);
        Fixture fixture = seed(ProjectRole.ADMIN);
        String projectKey = projectKey(fixture);
        String deviceKey = "device" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String upTopic = "tc/v1/" + projectKey + "/" + deviceKey + "/up/ota/download/request";
        String downTopic = "tc/v1/" + projectKey + "/" + deviceKey + "/down/ota/available";

        // 正面控制：它唯一被允许的动作是订阅内部交接Topic，证明这次拒绝不是「回调整体失效」。
        assertThat(acl(INGRESS_USERNAME, BrokerIngressProperties.INTERNAL_TOPIC, "1"))
                .as("ingress服务身份必须被允许订阅唯一内部Topic").isEqualTo("allow");
        assertThat(acl(INGRESS_USERNAME, BrokerIngressProperties.INTERNAL_TOPIC, "2"))
                .as("ingress服务身份不得向内部Topic发布").isEqualTo("deny");
        assertThat(acl(INGRESS_USERNAME, upTopic, "2"))
                .as("ingress服务身份不得向设备上行Topic发布，topic=" + upTopic).isEqualTo("deny");
        assertThat(acl(INGRESS_USERNAME, downTopic, "1"))
                .as("ingress服务身份不得订阅设备下行Topic，topic=" + downTopic).isEqualTo("deny");

        // 真实CONNECT侧：用户名与密码都正确，只有clientId不是冻结的唯一所有者，认证必须拒绝。
        assertThatThrownBy(() -> connectExplicitIdentity(INGRESS_USERNAME, "not-the-owner-" + UUID.randomUUID(),
                INGRESS_PASSWORD))
                .as("复用ingress用户名但clientId不是冻结所有者时，生产认证必须拒绝")
                .isInstanceOf(IllegalStateException.class);
        // 反向再钉一次：clientId正确但密码错误同样必须拒绝。两次都不会建立会话名，
        // 因此不会按MQTT接管语义把真实ingress踢下线。
        assertThatThrownBy(() -> connectExplicitIdentity(INGRESS_USERNAME,
                BrokerIngressProperties.CLIENT_ID, "wrong-" + UUID.randomUUID()))
                .as("ingress用户名与clientId正确但密码错误时，生产认证必须拒绝")
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 用真实Paho发起一次「用户名/密码/clientId由调用方显式给定」的MQTT 3.1.1 CONNECT。
     *
     * <p>为什么不复用设备工厂：设备工厂固定把用户名拼成 {@code projectKey/deviceKey}，
     * 无法表达生产ingress的单一服务身份。CONNECT被拒绝时本方法把供应商首因包成
     * {@link IllegalStateException}向上抛，绝不把「连不上」改写成「已授权」。</p>
     *
     * @param username MQTT CONNECT用户名
     * @param clientId MQTT CONNECT clientId
     * @param password MQTT CONNECT密码
     * @throws Exception CONNECT被拒绝时抛出
     */
    private static void connectExplicitIdentity(String username, String clientId, String password) throws Exception {
        var client = new org.eclipse.paho.client.mqttv3.MqttAsyncClient(mqttBrokerUri(), clientId,
                new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
        try {
            var options = new org.eclipse.paho.client.mqttv3.MqttConnectOptions();
            options.setMqttVersion(org.eclipse.paho.client.mqttv3.MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setCleanSession(true);
            options.setAutomaticReconnect(false);
            options.setConnectionTimeout(5);
            options.setUserName(username);
            options.setPassword(password.toCharArray());
            client.connect(options).waitForCompletion(8000);
        } catch (MqttException refusal) {
            throw new IllegalStateException("MQTT 显式身份连接被真实Broker拒绝", refusal);
        } finally {
            try {
                client.close();
            } catch (MqttException ignored) {
                // 未建立会话时关闭客户端没有更多状态需要收束。
            }
        }
    }

    /** 真实MQTT Broker地址，设备与ingress都用它。 */
    private static String mqttBrokerUri() {
        return "tcp://" + BROKER.getHost() + ":" + BROKER.getMappedPort(1883);
    }

    /**
     * 静态选定一个当前空闲的本地端口。
     *
     * <p>选端口与Spring真正绑定之间存在极小的竞争窗口；用例在每次断言前都会真实连一次该端口，
     * 因此「配置写了端口但没人监听」不会被静默放过。</p>
     *
     * @return 空闲端口号
     */
    private static int freePort() {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** 应用必须真的监听静态选定的端口，否则EMQX回调只会连接被拒。 */
    private static void assertAppListensOnDeclaredPort() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", APP_PORT), 3000);
            assertThat(probe.isConnected()).as("应用必须真的监听静态选定端口 " + APP_PORT).isTrue();
        } catch (IOException failure) {
            throw new AssertionError("应用没有监听静态选定端口 " + APP_PORT + "，观测到的错误=" + failure);
        }
    }

    /**
     * 有界等待生产ingress完成内部Topic订阅。
     *
     * <p>生产顺序要求设备在接管者出现之前不得被放行；本方法把这条顺序变成用例的前置断言，
     * 而不是靠「多半已经连上了」。等待期间观察到的失败是生产重连退避的正常表现：见
     * {@link #INGRESS_READY_BUDGET} 记录的实测现象，它不被解释为「设备可以提前放行」。</p>
     *
     * @param budget 最长等待预算
     */
    private void awaitIngressReady(Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        boolean ready;
        while (!(ready = ingressReadiness.isReady()) && System.nanoTime() < deadline) {
            sleepQuietly(100);
        }
        if (!ready) {
            throw new AssertionError("等待生产durable ingress完成内部Topic订阅(isReady)超时，预算=" + budget
                    + "，最后观察isReady=" + ingressReadiness.isReady());
        }
    }

    /**
     * 在观察窗口内有界轮询「平台没有出现该设备的下载申请」。
     *
     * <p>这是一个「零是期望值」的窗口：窗口内一旦出现非零立即失败，窗口结束后仍为零才算通过。
     * 窗口长度是有限的、被点名的，不是无界等待。</p>
     *
     * @param device 认证设备身份
     * @param window 观察窗口
     * @param delivery 被丢弃上行的发布收据，只用于失败消息里报告供应商行为
     * @param foreignTopic 被拒绝的Topic，只用于失败消息
     */
    private void assertNoDownloadRequestWithin(UUID device, Duration window,
            CompletableFuture<Void> delivery, String foreignTopic) {
        long deadline = System.nanoTime() + window.toNanos();
        long observed = 0;
        while (System.nanoTime() < deadline) {
            observed = owner().queryForObject("SELECT count(*) FROM ota_download_request WHERE device_id=?",
                    Long.class, device);
            if (observed != 0) {
                break;
            }
            sleepQuietly(100);
        }
        assertThat(observed).as("被边缘拒绝的外来Topic上行不得在观察窗口 " + window + " 内产生任何 "
                + "ota_download_request 行，设备=" + device + " topic=" + foreignTopic
                + " 发布收据=" + describe(delivery)).isZero();
    }

    /** 发布收据只用于失败消息，避免把超时误报成拒绝。 */
    private static String describe(CompletableFuture<Void> delivery) {
        if (!delivery.isDone()) {
            return "PENDING(未获PUBACK)";
        }
        try {
            delivery.get(0, TimeUnit.MILLISECONDS);
            return "COMPLETED(PUBACK)";
        } catch (Exception failure) {
            return "EXCEPTIONAL(" + failure.getClass().getSimpleName() + ")";
        }
    }

    /**
     * 有界等待平台出现该设备的唯一一条下载申请事实。
     *
     * @param device 认证设备身份
     * @param budget 最长等待预算
     * @return 完整持久行
     */
    private Map<String, Object> awaitDownloadRequest(UUID device, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        long count = 0;
        while (System.nanoTime() < deadline) {
            count = owner().queryForObject("SELECT count(*) FROM ota_download_request WHERE device_id=?",
                    Long.class, device);
            if (count == 1) {
                return owner().queryForMap("SELECT * FROM ota_download_request WHERE device_id=?", device);
            }
            if (count > 1) {
                break;
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待整条MQTT边缘产生恰一条 ota_download_request 超时，预算=" + budget
                + "，最后观察到行数=" + count + "，该设备=" + device
                + " 该设备最新Job状态=" + latestJobStatus(device)
                + " 通知投递状态=" + latestDeliveryStatus(device));
    }

    /**
     * 本设备当前作业状态，只用于失败消息。
     *
     * <p>设备上最多只有一条非CANCELLED作业（生产唯一索引 {@code ota_device_job_active_device_idx}），
     * 因此这里既不臆造 {@code ota_device_job} 并不存在的 createdAt 排序列，也不靠排序消歧。</p>
     *
     * @param device 认证设备
     * @return 状态文本；没有任何作业时返回 {@code <none>}
     */
    private String latestJobStatus(UUID device) {
        List<String> statuses = owner().queryForList(
                "SELECT status FROM ota_device_job WHERE device_id=? LIMIT 1", String.class, device);
        return statuses.isEmpty() ? "<none>" : statuses.getFirst();
    }

    /** 本设备最新通知投递状态，只用于失败消息。 */
    private String latestDeliveryStatus(UUID device) {
        List<String> statuses = owner().queryForList(
                "SELECT status FROM ota_notification_delivery WHERE device_id=? ORDER BY created_at DESC LIMIT 1",
                String.class, device);
        return statuses.isEmpty() ? "<none>" : statuses.getFirst();
    }

    /** 通知投递状态，只用于失败消息。 */
    private String deliveryStatus(UUID event) {
        return owner().queryForObject("SELECT status FROM ota_notification_delivery WHERE event_id=?",
                String.class, event);
    }

    /** 作业状态，只用于失败消息。 */
    private String jobStatus(UUID job) {
        return owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, job);
    }

    /** 作业失败归因，只用于失败消息。 */
    private String jobFailure(UUID job) {
        return owner().queryForObject("SELECT failure_code FROM ota_device_job WHERE id=?", String.class, job);
    }

    /** 活动状态，只用于失败消息。 */
    private String campaignStatus(UUID campaign) {
        return owner().queryForObject("SELECT status FROM ota_campaign WHERE id=?", String.class, campaign);
    }

    /**
     * 建立raw监听器可消费的前置：建Topic、按生产顺序手动启动、等待分区分配。
     *
     * <p>为什么必须手动启动：bootstrap测试profile把{@code spring.kafka.listener.auto-startup}
     * 钉为false，让不涉及消息的用例不依赖broker。因此这里显式启动生产容器，而不是绕过它手动调用
     * 消费者方法——后者会失去真实反序列化、分区分配与offset提交语义。</p>
     */
    private org.springframework.kafka.listener.MessageListenerContainer startRawUplinkListener() throws Exception {
        String topic = com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC;
        try (var admin = org.apache.kafka.clients.admin.Admin.create(
                Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var existing = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            var missing = new ArrayList<org.apache.kafka.clients.admin.NewTopic>();
            for (String name : List.of(topic,
                    com.things.link.ingestion.infrastructure.UplinkKafkaConfiguration.DEAD_LETTER_TOPIC)) {
                if (!existing.contains(name)) {
                    missing.add(new org.apache.kafka.clients.admin.NewTopic(name, 3, (short) 1));
                }
            }
            if (!missing.isEmpty()) {
                admin.createTopics(missing).all().get(10, TimeUnit.SECONDS);
            }
        }
        var container = listeners.getListenerContainers().stream()
                .filter(value -> "things-link-ingestion-raw".equals(value.getGroupId())).findFirst()
                .orElseThrow(() -> new AssertionError("必须存在groupId为 things-link-ingestion-raw 的生产raw监听容器"));
        assertThat(container.isRunning()).as("bootstrap测试profile不得自动启动raw监听器").isFalse();
        container.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty()) {
                return container;
            }
            sleepQuietly(100);
        }
        throw new AssertionError("等待生产raw监听器完成分区分配超时，最后分配="
                + container.getAssignedPartitions());
    }

    /** 每个用例都真正停止raw监听器，使下一个用例能再次断言「profile没有自动启动它」。 */
    private static void stopRawUplinkListener(org.springframework.kafka.listener.MessageListenerContainer container)
            throws InterruptedException {
        var stopped = new java.util.concurrent.CountDownLatch(1);
        container.stop(stopped::countDown);
        assertThat(stopped.await(20, TimeUnit.SECONDS)).as("生产raw监听器必须在预算内真正停止").isTrue();
    }

    /**
     * 在观察窗口内确认原始上行Topic没有出现该设备的任何记录。
     *
     * <p>为什么还要看Kafka：{@code ota_download_request} 只会在downstream接纳合同全部满足时出现，
     * 因此「没有申请行」本身无法区分「边缘拒绝了」与「边缘放行了但作业身份不成立」。原始上行Topic
     * 是边缘的出口边界：只要ACL或归属门放行，这里必然先出现一条以deviceId为key的记录。</p>
     *
     * @param device 认证设备身份，同时是原始上行的Kafka key
     * @param window 观察窗口
     */
    private void assertNoRawUplinkWithin(UUID device, Duration window) {
        Map<String, Object> properties = Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "durable-uplink-edge-negative-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "false",
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        String topic = com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC;
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, byte[]>(properties)) {
            consumer.subscribe(List.of(topic));
            long assignDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (consumer.assignment().isEmpty() && System.nanoTime() < assignDeadline) {
                consumer.poll(Duration.ofMillis(200));
            }
            assertThat(consumer.assignment()).as("负向观察用的真实Kafka消费者必须完成分区分配").isNotEmpty();
            long deadline = System.nanoTime() + window.toNanos();
            List<String> foreignKeys = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (device.toString().equals(record.key())) {
                        foreignKeys.add(record.key());
                    }
                }
            }
            assertThat(foreignKeys).as("被边缘拒绝的外来Topic上行不得在观察窗口 " + window
                    + " 内出现在原始上行Topic " + topic + " 上，设备=" + device).isEmpty();
        }
    }

    /**
     * 从平台已接纳的设备报告与活动冻结清单构造模拟器证据，而不是使用 {@code OtaSimulatorEvidence.DEFAULT}。
     *
     * <p>为什么必须来自平台事实：{@code DEFAULT}声明的信任域、根指纹与物模型版本都是模拟器自己的
     * 静态取值，用它等于让设备声称信任一个平台从未发布过的域；本片要求设备声明与本次发布一致的事实，
     * 因此逐字段读回{@code ota_device_report.canonical}（平台真实接纳过的设备声明）并交叉核对发布面。</p>
     *
     * <p><b>安全版本的口径（S13-4d-2b-2b-2修正）：</b>ADR0131要求进度证据回显的是
     * <b>本次安装的目标元组</b>：{@code securityVersion}必须等于活动冻结清单里的目标值（本例1），
     * 而{@code committedSecurityVersion}仍是原执行来源里已提交的旧值（本例0）。若这里继续沿用设备报告里
     * 的{@code currentSecurityVersion=0}，平台{@code OtaJobProgressEvidenceValidator}会以
     * {@code MANIFEST_TUPLE_MISMATCH}拒绝每一条进度——这是测试证据构造错误，不是平台放宽条件。
     * 因此本方法只把{@code securityVersion}换成清单目标值，其余字段仍逐条来自平台真实接纳的设备报告。</p>
     *
     * @param fixture 本例真实项目图
     * @param device 平台真实接纳过报告的设备
     * @param run 已READY且已启动的运行事实，用于读取活动冻结清单的目标安全版本
     * @return 与平台事实一致的设备证据
     */
    private OtaSimulatorEvidence evidenceFromAcceptedReport(Fixture fixture, Device device, Running run) {
        byte[] canonical = owner().queryForObject(
                "SELECT canonical FROM ota_device_report WHERE device_id=?", byte[].class, device.id());
        assertThat(canonical).as("平台必须已接纳该设备的真实报告，否则无法构造一致的设备证据").isNotNull();
        JsonNode report = JSON.readTree(canonical);
        String trustDomain = report.path("trustDomain").asText();
        String rootFingerprint = report.path("rootFingerprint").asText();
        UUID thingModelVersionId = UUID.fromString(report.path("thingModelVersionId").asText());
        String trustBundleSha256 = report.path("trustBundleSha256").asText();
        assertThat(trustDomain).as("设备声明的信任域必须等于本次发布的真实信任域").isEqualTo(DOMAIN);
        assertThat(rootFingerprint).as("设备声明的根指纹必须等于本地测试根的指纹")
                .isEqualTo(sha(ROOT.getPublic().getEncoded()));
        assertThat(thingModelVersionId).as("设备声明的物模型版本必须是本次发布绑定的真实版本")
                .isEqualTo(fixture.modelId());
        assertThat(trustBundleSha256).as("设备声明的信任包摘要必须等于平台真实保存的信任包摘要")
                .isEqualTo(owner().queryForObject("SELECT bundle_sha256 FROM ota_trust_bundle "
                        + "WHERE project_id=? AND trust_domain=?", String.class, fixture.projectId(), DOMAIN));
        byte[] manifestBytes = owner().queryForObject(
                "SELECT canonical_manifest FROM ota_campaign WHERE id=?", byte[].class, run.campaign());
        assertThat(manifestBytes).as("活动必须冻结完整清单，否则无法确定本次安装的目标安全版本").isNotNull();
        JsonNode manifest = JSON.readTree(manifestBytes);
        long targetSecurityVersion = manifest.path("securityVersion").asLong();
        assertThat(targetSecurityVersion).as("活动冻结清单必须声明非负的目标安全版本")
                .isGreaterThanOrEqualTo(0L);
        return new OtaSimulatorEvidence(
                report.path("hardware").path("model").asText(),
                report.path("hardware").path("boardRevision").asLong(),
                report.path("bootloaderVersion").asText(),
                thingModelVersionId,
                report.path("thingModelSchemaDigestAlgorithm").asText(),
                report.path("thingModelSchemaDigest").asText(),
                trustDomain,
                rootFingerprint,
                report.path("trustBundleVersion").asLong(),
                trustBundleSha256,
                targetSecurityVersion,
                report.path("committedSecurityVersion").asLong());
    }

    /**
     * 直接调用生产EMQX ACL回调，内部ingress不带设备属性。
     *
     * <p>为什么允许直接调用而不是全走EMQX：EMQX的HTTP authorizer本身就是生产组件，它把
     * (username, topic, access)及认证签发属性交给这个端点并只采纳其{@code result}。直接调用观察到的正是
     * 那道门的判决，并且可以在同一次用例里给出「允许」的正面控制，排除回调整体失效。</p>
     *
     * @param username MQTT CONNECT用户名
     * @param topic 被请求的完整Topic
     * @param access {@code 1}=subscribe，{@code 2}=publish
     * @return 生产回调给出的 {@code allow}/{@code deny}
     * @throws Exception 真实HTTP失败时抛出
     */
    private String acl(String username, String topic, String access) throws Exception {
        return acl(username, topic, access, Map.of());
    }

    /** 使用真实认证响应中的身份、连接票据与服务器覆盖后的clientid构造设备ACL正反对照。 */
    private String acl(String username, String topic, String access, Map<String, String> attributes) throws Exception {
        var fields = new java.util.LinkedHashMap<String, String>(attributes);
        fields.put("username", username); fields.put("topic", topic); fields.put("access", access);
        byte[] body = JSON.writeValueAsBytes(fields);
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + APP_PORT + "/api/v1/emqx/acl"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Broker-Callback-Token", CALLBACK_SECRET)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("生产ACL回调必须以200回答，body=" + response.body())
                    .isEqualTo(200);
            return JSON.readTree(response.body()).path("result").asText();
        }
    }

    /** 服务器实际校验凭据后签发属性，不能用测试自报身份替代。 */
    private Map<String, String> authenticateAttributes(String username, String secret, String clientId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + APP_PORT + "/api/v1/emqx/auth"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-Broker-Callback-Token", CALLBACK_SECRET)
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(
                        Map.of("username", username, "password", secret, "clientid", clientId)))).build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var body = JSON.readTree(response.body()); assertThat(body.path("result").asText()).isEqualTo("allow");
            var attributes = new java.util.LinkedHashMap<String, String>();
            for (String key : java.util.List.of("tc_auth_tenant_id", "tc_auth_project_id", "tc_auth_device_id",
                    "tc_auth_credential_version", "tc_auth_config_version", "tc_auth_connection_id")) {
                String value = body.path("client_attrs").path(key).asText();
                assertThat(value).as("认证响应必须提供属性 " + key).isNotBlank(); attributes.put(key, value);
            }
            String effectiveClientId = body.path("clientid_override").asText();
            assertThat(effectiveClientId).matches("tc-device-[0-9a-f]{64}");
            attributes.put("clientid", effectiveClientId);
            return Map.copyOf(attributes);
        }
    }

    /** 一个结构合法但不指向任何真实作业的jobId；本片只用它验证Topic归属这道门。 */
    private static UUID syntheticJobId() {
        return Uuid7.generate();
    }

    /** 可中断的有界退避，绝不吞掉中断状态。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待真实边缘事实时线程被中断", interrupted);
        }
    }

    /**
     * 透明装饰器：把应用交给真实Paho客户端的出去字节复制一份，用于断言「持久事实等于设备真实发送字节」。
     *
     * <p><b>为什么这不是替身：</b>每个方法都原样委托给真实 {@link MqttDeviceClient}；网络连接、认证、
     * 订阅、QoS 1投递与PUBACK仍然只由生产实现完成。这里只旁路复制出站载荷，既不改写Topic、QoS、
     * retained标志，也不伪造任何收据，因此不构成本片证据链上的模拟。</p>
     */
    private static final class RecordingDeviceClient implements MqttDeviceClient {

        /** 生产设备客户端。 */
        private final MqttDeviceClient delegate;

        /** 按Topic记录应用真实发出的载荷。 */
        private final Map<String, BlockingQueue<byte[]>> outbound = new ConcurrentHashMap<>();

        /**
         * @param delegate 真实已认证设备客户端
         */
        private RecordingDeviceClient(MqttDeviceClient delegate) {
            this.delegate = delegate;
        }

        /**
         * 有界等待指定Topic上的第一条真实出站载荷。
         *
         * @param topic 期望的完整Topic
         * @param budget 最长等待预算
         * @return 设备真实发出的规范字节副本
         */
        byte[] awaitUplink(String topic, Duration budget) {
            BlockingQueue<byte[]> queue = outbound.computeIfAbsent(topic,
                    ignored -> new LinkedBlockingQueue<>());
            try {
                byte[] payload = queue.poll(budget.toMillis(), TimeUnit.MILLISECONDS);
                if (payload == null) {
                    throw new AssertionError("等待设备在 " + topic + " 上发出真实上行超时，预算=" + budget
                            + "，本设备实际发出过的Topic=" + outbound.keySet());
                }
                return payload;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待设备真实上行时线程被中断", interrupted);
            }
        }

        /** 原样委托并在委托前复制出站字节，失败方向与生产实现完全一致。 */
        @Override
        public CompletableFuture<Void> publish(String topic, byte[] payload, int qos, boolean retained) {
            outbound.computeIfAbsent(topic, ignored -> new LinkedBlockingQueue<>())
                    .add(payload.clone());
            return delegate.publish(topic, payload, qos, retained);
        }

        /** 原样委托订阅。 */
        @Override
        public void subscribe(String topic, int qos, MqttDeviceMessageHandler handler) {
            delegate.subscribe(topic, qos, handler);
        }

        /** 原样委托故障断开。 */
        @Override
        public void forceConnectionLoss() {
            delegate.forceConnectionLoss();
        }

        /** 原样委托连接状态。 */
        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        /** 原样委托连接代次。 */
        @Override
        public long connectionGeneration() {
            return delegate.connectionGeneration();
        }

        /** 原样委托最后连接时刻。 */
        @Override
        public Instant lastConnectedAt() {
            return delegate.lastConnectedAt();
        }

        /** 原样委托关闭。 */
        @Override
        public void close() {
            delegate.close();
        }
    }
}
