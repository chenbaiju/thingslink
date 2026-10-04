package com.things.link.simulator.application;

import com.things.link.simulator.api.dto.SimulationRequest;
import com.things.link.simulator.application.ota.OtaArtifactSource;
import com.things.link.simulator.application.ota.OtaClock;
import com.things.link.simulator.application.ota.OtaFaultPlan;
import com.things.link.simulator.application.ota.session.OtaDeviceRuntime;
import com.things.link.simulator.application.ota.session.OtaSimulatorEvidence;
import com.things.link.simulator.infrastructure.ota.HttpRangeArtifactSource;
import com.things.link.simulator.infrastructure.ota.SystemOtaClock;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DevicePropertyReport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * 通过真实 MQTT 连接驱动多台设备的模拟引擎。
 *
 * <p>架构文档 13.4 的模拟器用于联调接入链路，因此这里不能调用 telemetry 服务或查询业务数据库。
 * 每台设备建立独立连接，以其 Access Token 经过 EMQX 认证与 ACL，再用 QoS 1、非 retained 消息
 * 发布到固定 v1 Topic。Kafka 尚未接入时消息不会落库，这是正确的可观察失败，而不是绕开缺口。</p>
 */
@Service
public class DeviceSimulator {

    /** 设备协议基线要求 QoS 1，以应用层 messageId 承担幂等。 */
    private static final int QOS_AT_LEAST_ONCE = 1;

    /** 冻结 Topic 契约下，命令只允许有一个不可含斜杠的 ID 段。 */
    private static final String COMMAND_ID_PATTERN = "[A-Za-z0-9-]{1,64}";

    /**
     * G1-C3d nightly #8 冻结的每分片最小调度 worker 数。
     *
     * <p>两核 Runner 若只创建两个 worker，167 个零延迟周期任务会让同队列资源采样饿死；保留共享队列是为了继续
     * 暴露真实发生器饱和，因此这里只增加并行槽位，不能把采样器移到独立 executor。</p>
     */
    private static final int MINIMUM_SCHEDULER_WORKERS = 4;

    /** 模拟器日志不得包含 Access Token。 */
    private static final Logger log = LoggerFactory.getLogger(DeviceSimulator.class);

    /** MQTT 客户端工厂，抽象后可在单元测试中验证协议契约而无需假 Broker。 */
    private final MqttDeviceClientFactory clientFactory;

    /** 统一序列化器确保模拟器发出的报文与接入端冻结的 JSON 契约完全一致。 */
    private final ObjectMapper objectMapper;

    /** 定时任务只负责触发发布；worker 下限来自 G1-C3d #8，实际网络 IO 仍由 Paho 自己的线程处理。 */
    private final ScheduledExecutorService scheduler = newScheduler(Runtime.getRuntime().availableProcessors());

    /** 当前运行设备；以 deviceKey 为键同时阻止一次请求内的重复身份。 */
    private final Map<String, RunningDevice> devices = new ConcurrentHashMap<>();

    /** 分类型负载 manifest：按消息类型拆分「发起 / PUBACK 确认 / 失败」计数，并把确认 messageId 归档落盘。 */
    private final UplinkManifest manifest = new UplinkManifest();

    /** 非发布类失败（连接、订阅、关闭、解析）；发布成败在 manifest 里按类型统计，不混入这里。 */
    private final AtomicLong failedOperations = new AtomicLong();

    /** 故障演练累计强制断连数；每次启动新批次时清零。 */
    private final AtomicLong forcedDisconnects = new AtomicLong();

    /** 最近一次上报周期的间隔秒数，用于把调度偏差换算成相对周期的比值。 */
    private volatile long intervalSeconds = 1L;

    /**
     * 调度偏差直方图桶上界（毫秒，闭开区间），最后一桶为 {@code >= 最后一个上界} 的溢出桶。
     *
     * <p>桶越界只意味着「偏差大到一个阶梯」，P99 由桶上界近似；固定桶数使内存有界且覆盖整轮测量，
     * 不会像环形数组那样 24h 后丢弃前段样本，也不会把「样本已满」误当成「没有偏差」。</p>
     */
    private static final long[] DEVIATION_BUCKET_UPPER_MILLIS = {
            1L, 2L, 5L, 10L, 20L, 50L, 100L, 200L, 500L,
            1_000L, 2_000L, 5_000L, 10_000L, 30_000L
    };

    /** 调度偏差直方图；长度为桶上界数 + 1（末位为溢出桶），{@link AtomicLongArray} 保证并发记录无可见性竞态。 */
    private final AtomicLongArray deviationHistogram =
            new AtomicLongArray(DEVIATION_BUCKET_UPPER_MILLIS.length + 1);

    /** 已记录的调度偏差样本总数（覆盖整轮测量，不因桶数封顶）。 */
    private final AtomicLong deviationTotal = new AtomicLong();

    /** 当前批次正在执行周期上报任务的线程数；stop 时等待其归零，避免快照后仍有计数器落账。 */
    private final AtomicInteger activePeriodicTasks = new AtomicInteger();

    /** 当前批次正在处理的命令/配置入站任务数；它们同样可能发布回执并写 manifest。 */
    private final AtomicInteger activeInboundTasks = new AtomicInteger();

    /** 已交给 MQTT 客户端但 PUBACK/失败回调尚未完成的发布数；停止时必须归零后才能关连接。 */
    private final AtomicInteger activeDeliveries = new AtomicInteger();

    /** 温度随机游走使用密码学随机并非安全需要，而是避免多进程使用相同种子生成相同轨迹。 */
    private final SecureRandom random = new SecureRandom();

    /** manifest 归档目录；默认相对工作目录，可由运行方在启动前覆盖。 */
    private volatile Path manifestDir = Path.of("manifest");

    /** 当前运行实际使用的 run/shard 归档目录；不能只暴露基目录，否则聚合器可能读错轮次。 */
    private volatile Path activeManifestDir = Path.of("manifest", "not-started", "not-started");

    /** 当前资格运行标识；旧调用默认 local，正式资格运行由脚本显式注入。 */
    private volatile String runId = "not-started";

    /** 当前分片标识；同一 runId 内由编排器保证唯一。 */
    private volatile String shardId = "not-started";

    /** 当前报文属性数；A4-0 标准资格运行固定为 10，旧功能测试默认 1。 */
    private volatile int propertiesPerReport = 1;

    /** 当前运行预生成的属性键，避免每条报文重复格式化键名污染发生器 CPU 结论。 */
    private volatile List<String> propertyKeys = List.of("temperature");
    /** 模拟器声明的已绑定物模型版本，正式版本切换时由运行方显式覆盖。 */
    private final String modelVersion;

    /**
     * 是否启用设备侧 OTA 接线；默认关闭。
     *
     * <p>默认关闭是刻意的：OTA 需要平台先由受控签名器签出 READY 固件并派发真实授权，模拟器本地
     * 不可能产生真实授权链。关闭时既不订阅任何 OTA Topic，也不写任何 OTA 目录，既有旅程与资格
     * 运行的行为逐字节不变；只有运行方显式打开 {@code simulator.ota.enabled=true} 才会接线。</p>
     */
    private final boolean otaEnabled;

    /** 每设备 OTA 目录的基目录；设备目录为 {@code <otaDir>/<deviceKey>}。 */
    private final Path otaDirectory;

    /** 模拟器声明的设备证据事实；OTA 关闭时不会使用。 */
    private final OtaSimulatorEvidence otaEvidence;

    /** 是否允许 OTA 声明健康/自检事实；默认 false，避免伪造 HEALTH_CHECKING 硬件结论。 */
    private final boolean otaAssertHealth;

    /** OTA 取字节端口工厂；生产为真实 HTTP Range 实现。 */
    private final Supplier<OtaArtifactSource> otaArtifactSourceFactory;

    /** 下一次 OTA 下载执行注入的故障计划；生产固定为不注入。 */
    private final OtaFaultPlan otaFaultPlan;

    /** 是否允许 OTA 下载地址使用本机回环明文；默认 false。 */
    private final boolean otaAllowInsecureLoopback;

    /** OTA 执行时钟；生产为墙钟，测试注入推进式假时钟以避免真实等待。 */
    private final OtaClock otaClock;

    /** 本轮目标连接数。 */
    private final AtomicInteger targetConnections = new AtomicInteger();

    /** 本轮已尝试的初次连接数。 */
    private final AtomicInteger initialConnectionAttempts = new AtomicInteger();

    /** 本轮初次连接成功数（即使随后订阅失败回滚也保留事实）。 */
    private final AtomicInteger initialConnectionSuccesses = new AtomicInteger();

    /** 本轮初次连接失败数。 */
    private final AtomicInteger initialConnectionFailures = new AtomicInteger();

    /** 本轮连接爬坡起点。 */
    private volatile Instant connectionRampStartedAt;

    /** 全部目标连接成功时刻；未全连时为空。 */
    private volatile Instant allConnectedAt;

    /** 每秒资源采样任务；与发布调度共享 scheduler，发生器拥塞会体现为采样缺口而不是被掩盖。 */
    private volatile ScheduledFuture<?> resourceSamplerTask;

    /** 资源采样只使用自己的锁；绝不能和同步 start/stop 共用 this，否则连接爬坡期间会漏掉全部资源峰值。 */
    private final Object resourceSampleLock = new Object();

    /** 资源样本数。 */
    private final AtomicLong resourceSampleCount = new AtomicLong();

    /** 资源采样最大间隔；明显超过 1 秒意味着发生器调度已经饱和。 */
    private final AtomicLong maxResourceSampleGapMillis = new AtomicLong();

    /** 最近一次资源采样的单调时钟。 */
    private volatile long lastResourceSampleNanos;

    /** 最近一次资源采样的墙钟；仅用于给最大单调间隔补可审计起点，不能参与时长计算。 */
    private volatile Instant lastResourceSampleAt;

    /** 本轮最大资源采样缺口的墙钟起点；与 {@link #maxResourceSampleGapMillis} 同次更新。 */
    private volatile Instant maxResourceSampleGapStartedAt;

    /** 本轮最大资源采样缺口的墙钟终点；与 {@link #maxResourceSampleGapMillis} 同次更新。 */
    private volatile Instant maxResourceSampleGapEndedAt;

    /** 本轮进程 CPU 峰值（0~1；不可得为 -1）。 */
    private volatile double peakProcessCpuLoad = -1.0;

    /** CPU “持续”阈值采用预先冻结的 5 样本（约 5 秒）滚动平均，而不是被一次瞬时尖峰左右。 */
    private final double[] recentCpuSamples = new double[5];

    /** CPU 滚动窗口下一写入位置。 */
    private int recentCpuIndex;

    /** CPU 滚动窗口当前有效样本数。 */
    private int recentCpuCount;

    /** 本轮最高 5 秒 CPU 滚动平均（0~1；不可得为 -1）。 */
    private volatile double maxFiveSecondCpuAverage = -1.0;

    /** 本轮堆使用峰值字节。 */
    private final AtomicLong peakHeapUsedBytes = new AtomicLong();

    /** 本轮 RSS 峰值字节；不可得时为 -1。 */
    private volatile long peakRssBytes = -1L;

    /** 本轮活动线程峰值。 */
    private final AtomicLong peakThreadCount = new AtomicLong();

    /** 本轮打开 FD 峰值；不可得时为 -1。 */
    private volatile long peakOpenFileDescriptors = -1L;

    /** 本轮有多少样本缺失 CPU/RSS/FD 任一资格指标。 */
    private final AtomicLong incompleteResourceSamples = new AtomicLong();

    /**
     * 设备侧 OTA 接线配置。
     *
     * <p><b>为什么把开关收进一个值对象：</b>OTA 需要「是否接线 / 目录 / 证据事实 / 是否声明健康」多项
     * 一起生效；分散成多个构造器参数会让「目录被传入但开关为 false」这类半接线状态在编译期无法发现。
     * 各项默认值全部按「不产生任何平台事实」选取：</p>
     * <ul>
     *   <li>{@link #enabled()} 默认 {@code false}：不订阅任何 OTA Topic，也不写任何 OTA 目录；</li>
     *   <li>{@link #directory()} 默认 {@code ota}（相对工作目录，与 manifest 归档同一约定）；</li>
     *   <li>{@link #assertHealth()} 默认 {@code false}：自检/看门狗是硬件事实，模拟器不声明；</li>
     *   <li>{@link #artifactSourceFactory()} 默认真实 HTTP Range 实现，生产不暴露分片大小。</li>
     * </ul>
     */
    public static final class OtaRuntimeConfig {

        /** 是否启用 OTA 接线；默认关闭。 */
        private boolean enabled;

        /** 每设备 OTA 目录的基目录。 */
        private Path directory = Path.of("ota");

        /** 是否允许声明健康/自检事实。 */
        private boolean assertHealth;

        /** 设备证据事实。 */
        private OtaSimulatorEvidence evidence = OtaSimulatorEvidence.DEFAULT;

        /** 是否允许本机回环明文下载地址；默认 false，仅受控本机联调显式打开。 */
        private boolean allowInsecureLoopback;

        /** 取字节端口工厂；默认真实 HTTP Range 实现，测试可注入受控端口。 */
        private Supplier<OtaArtifactSource> artifactSourceFactory = HttpRangeArtifactSource::new;

        /** 下一次下载执行注入的故障计划；默认不注入。 */
        private OtaFaultPlan faultPlan = OtaFaultPlan.NONE;

        /**
         * @return 是否启用 OTA 接线；默认 {@code false}
         */
        public boolean enabled() {
            return enabled;
        }

        /**
         * 设置是否启用 OTA 接线。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * @return 每设备 OTA 目录的基目录
         */
        public Path directory() {
            return directory;
        }

        /**
         * 设置每设备 OTA 目录的基目录。
         *
         * @param directory 基目录
         */
        public void setDirectory(Path directory) {
            this.directory = directory;
        }

        /**
         * @return 是否允许声明健康/自检事实；默认 {@code false}
         */
        public boolean assertHealth() {
            return assertHealth;
        }

        /**
         * 设置是否允许声明健康/自检事实。
         *
         * @param assertHealth 是否允许
         */
        public void setAssertHealth(boolean assertHealth) {
            this.assertHealth = assertHealth;
        }

        /**
         * @return 模拟器声明的设备证据事实
         */
        public OtaSimulatorEvidence evidence() {
            return evidence;
        }

        /**
         * 设置模拟器声明的设备证据事实。
         *
         * @param evidence 证据事实
         */
        public void setEvidence(OtaSimulatorEvidence evidence) {
            this.evidence = evidence;
        }

        /**
         * @return 取字节端口工厂
         */
        public Supplier<OtaArtifactSource> artifactSourceFactory() {
            return artifactSourceFactory;
        }

        /**
         * 设置取字节端口工厂；每次 OTA 下载执行调用一次。
         *
         * @param artifactSourceFactory 端口工厂
         */
        public void setArtifactSourceFactory(Supplier<OtaArtifactSource> artifactSourceFactory) {
            this.artifactSourceFactory = artifactSourceFactory;
        }

        /**
         * @return 下一次下载执行注入的故障计划
         */
        public OtaFaultPlan faultPlan() {
            return faultPlan;
        }

        /**
         * 设置下一次下载执行注入的故障计划；故障是场景输入，不会持久化为设备状态。
         *
         * @param faultPlan 故障计划
         */
        public void setFaultPlan(OtaFaultPlan faultPlan) {
            this.faultPlan = faultPlan;
        }

        /**
         * @return 是否允许本机回环明文下载地址；默认 {@code false}
         */
        public boolean allowInsecureLoopback() {
            return allowInsecureLoopback;
        }

        /**
         * 设置是否允许本机回环明文下载地址。
         *
         * @param allowInsecureLoopback 是否允许
         */
        public void setAllowInsecureLoopback(boolean allowInsecureLoopback) {
            this.allowInsecureLoopback = allowInsecureLoopback;
        }
    }

    /**
     * 创建模拟引擎（唯一生产构造器，Spring 自动注入）。
     *
     * <p>manifest 归档目录由 {@code simulator.manifest.dir} 注入（默认相对工作目录）。设备侧 OTA 接线
     * <b>默认关闭</b>：只有 {@code simulator.ota.enabled=true} 时才订阅四个 OTA 下行 Topic，既有旅程与
     * 测试不受影响；打开后每台设备在 {@code simulator.ota.dir/<deviceKey>} 下持有自己的暂存区、耐久
     * 日志与启动身份。取字节分片上限取实现默认值，不由配置暴露（它不是资格参数）。</p>
     *
     * @param clientFactory MQTT 客户端工厂
     * @param objectMapper 统一 JSON 序列化器
     * @param manifestDir 分类型 messageId 归档目录
     * @param modelVersion 模拟设备声明的当前物模型版本
     * @param otaEnabled 是否启用设备侧 OTA 接线（默认 false）
     * @param otaDir 每设备 OTA 目录的基目录
     * @param otaAssertHealth 是否允许 OTA 声明健康/自检事实（默认 false）
     * @param otaAllowInsecureLoopback 是否允许 OTA 下载地址使用本机回环明文（默认 false，仅受控本机联调）
     */
    @Autowired
    public DeviceSimulator(MqttDeviceClientFactory clientFactory, ObjectMapper objectMapper,
                           @Value("${simulator.manifest.dir:manifest}") String manifestDir,
                           @Value("${simulator.model-version:1.0.0}") String modelVersion,
                           @Value("${simulator.ota.enabled:false}") boolean otaEnabled,
                           @Value("${simulator.ota.dir:ota}") String otaDir,
                           @Value("${simulator.ota.assert-health:false}") boolean otaAssertHealth,
                           @Value("${simulator.ota.allow-insecure-loopback:false}") boolean otaAllowInsecureLoopback) {
        this(clientFactory, objectMapper, Path.of(manifestDir), modelVersion, defaultOtaConfig(otaEnabled,
                Path.of(otaDir), otaAssertHealth, otaAllowInsecureLoopback), new SystemOtaClock());
    }

    /**
     * 测试与本地兼容构造器；初始绑定由 B-X1a 迁移固定为 1.0.0，OTA 接线保持关闭。
     *
     * @param clientFactory MQTT 客户端工厂
     * @param objectMapper JSON 序列化器
     * @param manifestDir manifest 目录
     */
    public DeviceSimulator(MqttDeviceClientFactory clientFactory, ObjectMapper objectMapper, String manifestDir) {
        this(clientFactory, objectMapper, Path.of(manifestDir), "1.0.0",
                defaultOtaConfig(false, Path.of("ota"), false, false), new SystemOtaClock());
    }

    /**
     * 测试与本地构造器：显式给出 OTA 配置与执行时钟。
     *
     * @param clientFactory MQTT 客户端工厂
     * @param objectMapper JSON 序列化器
     * @param manifestDir manifest 目录
     * @param modelVersion 模拟设备声明的物模型版本
     * @param otaConfig OTA 接线配置，含开关、目录、证据、健康声明与分片上限
     * @param otaClock OTA 执行时钟；生产为墙钟，测试为推进式假时钟
     */
    public DeviceSimulator(MqttDeviceClientFactory clientFactory, ObjectMapper objectMapper, Path manifestDir,
                           String modelVersion, OtaRuntimeConfig otaConfig, OtaClock otaClock) {
        this.clientFactory = clientFactory;
        this.objectMapper = objectMapper;
        this.manifestDir = manifestDir;
        if (modelVersion == null || !modelVersion.matches("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")) {
            throw new IllegalArgumentException("simulator.model-version 必须是严格语义版本");
        }
        this.modelVersion = modelVersion;
        OtaRuntimeConfig config = otaConfig == null
                ? defaultOtaConfig(false, Path.of("ota"), false, false) : otaConfig;
        this.otaEnabled = config.enabled();
        this.otaDirectory = config.directory();
        this.otaAssertHealth = config.assertHealth();
        this.otaEvidence = config.evidence();
        this.otaArtifactSourceFactory = config.artifactSourceFactory();
        this.otaFaultPlan = config.faultPlan();
        this.otaAllowInsecureLoopback = config.allowInsecureLoopback();
        this.otaClock = otaClock;
    }

    /** 构造默认 OTA 配置；所有开关按「不产生平台事实」的默认值。 */
    private static OtaRuntimeConfig defaultOtaConfig(boolean enabled, Path directory, boolean assertHealth,
                                                     boolean allowInsecureLoopback) {
        OtaRuntimeConfig config = new OtaRuntimeConfig();
        config.setEnabled(enabled);
        config.setDirectory(directory);
        config.setAssertHealth(assertHealth);
        config.setAllowInsecureLoopback(allowInsecureLoopback);
        return config;
    }

    /**
     * 按 G1-C3d #8 冻结规则计算共享调度池大小。
     *
     * @param availableProcessors JVM 可用处理器数
     * @return 不低于四且不低于可用处理器数的 worker 数
     */
    static int schedulerWorkerCount(int availableProcessors) {
        return Math.max(MINIMUM_SCHEDULER_WORKERS, availableProcessors);
    }

    /**
     * 把本批设备的固定周期相位均匀映射到完整上报周期。
     *
     * <p>G1-C3d nightly #13 证明一秒相位窗会把“每设备每分钟一报”的稳定模型放大成约
     * 六十倍瞬时突发，并在 durable republish 后触发 Broker 高 CPU、调度过载和下行连接失败。
     * L1 的冻结目标是固定频率稳定回归；集中上线/重连后的突发由 G3-P1 L2-R 单独验证，不能
     * 把发生器的人造齐发混入稳定基线，也不能用减少设备数或降低单设备频率追求通过。</p>
     *
     * @param deviceIndex 稳定请求次序中的设备下标
     * @param deviceCount 本批设备总数
     * @param intervalSeconds 固定上报周期秒数
     * @return 首次及后续固定周期使用的初始延迟毫秒数，范围为 {@code [0, intervalSeconds * 1000)}
     */
    static long initialPublishDelayMillis(int deviceIndex, int deviceCount, int intervalSeconds) {
        if (deviceCount <= 0 || deviceIndex < 0 || deviceIndex >= deviceCount || intervalSeconds <= 0) {
            throw new IllegalArgumentException("设备相位下标必须位于当前批次范围内且上报周期必须为正数");
        }
        long intervalMillis = TimeUnit.SECONDS.toMillis(intervalSeconds);
        return (long) deviceIndex * intervalMillis / deviceCount;
    }

    /**
     * 创建负载与资源采样共享的调度池；包级可见用于并发回归验证，生产代码只有字段初始化这一处调用。
     *
     * @param availableProcessors JVM 可用处理器数
     * @return 使用虚拟线程、且并行度符合 #8 冻结规则的调度池
     */
    static ScheduledExecutorService newScheduler(int availableProcessors) {
        return Executors.newScheduledThreadPool(
                schedulerWorkerCount(availableProcessors), Thread.ofVirtual().factory());
    }

    /**
     * 原子启动一批设备。
     *
     * <p>先完成全部连接，再发布任务；任一连接失败会关闭已经连接的客户端并保持停止状态，
     * 避免调用方以为 1000 台已经启动，实际只有不可知的半批设备在线。</p>
     *
     * @param request 已通过 Bean Validation 的启动请求
     */
    public synchronized void start(SimulationRequest request) {
        if (!devices.isEmpty()) {
            throw new IllegalStateException("模拟器已在运行，请先停止当前任务");
        }
        if (activePeriodicTasks.get() != 0 || activeInboundTasks.get() != 0 || activeDeliveries.get() != 0) {
            throw new IllegalStateException("上一批模拟任务尚未完全退出，暂不能启动新批次");
        }
        failedOperations.set(0);
        forcedDisconnects.set(0);
        runId = request.runId();
        shardId = request.shardId();
        propertiesPerReport = request.propertiesPerReport();
        propertyKeys = propertyKeys(propertiesPerReport);
        targetConnections.set(request.devices().size());
        initialConnectionAttempts.set(0);
        initialConnectionSuccesses.set(0);
        initialConnectionFailures.set(0);
        connectionRampStartedAt = Instant.now();
        allConnectedAt = null;
        deviationTotal.set(0);
        for (int i = 0; i < deviationHistogram.length(); i++) {
            deviationHistogram.set(i, 0L);
        }
        intervalSeconds = request.intervalSeconds();
        activeManifestDir = manifestDirectory(manifestDir, runId, shardId);
        manifest.open(activeManifestDir);
        startResourceSampling();
        List<RunningDevice> connected = new ArrayList<>();
        try {
            for (SimulationRequest.DeviceCredential credential : request.devices()) {
                if (devices.containsKey(credential.deviceKey())) {
                    throw new IllegalArgumentException("设备标识重复: " + credential.deviceKey());
                }
                initialConnectionAttempts.incrementAndGet();
                MqttDeviceClient client;
                try {
                    client = clientFactory.connect(
                            request.brokerUri(), request.projectKey(), credential.deviceKey(), credential.accessToken());
                    initialConnectionSuccesses.incrementAndGet();
                } catch (RuntimeException exception) {
                    initialConnectionFailures.incrementAndGet();
                    throw exception;
                }
                RunningDevice running = new RunningDevice(credential.deviceKey(), request.projectKey(), client,
                        20.0 + random.nextDouble() * 10.0, request.autoReplyCommands(), credential.gateway());
                // 订阅失败也必须回收刚建好的连接；否则批量启动回滚会遗漏当前设备。
                connected.add(running);
                if (otaEnabled) {
                    // OTA 接线默认关闭；打开后每台设备在独立目录里持有自己的暂存区与耐久日志。
                    OtaDeviceRuntime otaRuntime = new OtaDeviceRuntime(request.projectKey(), credential.deviceKey(),
                            client, otaDeviceDirectory(credential.deviceKey()), otaEvidence, otaAssertHealth,
                            otaAllowInsecureLoopback, otaClock, otaArtifactSourceFactory, otaFaultPlan);
                    running.otaRuntime = otaRuntime;
                    otaRuntime.start();
                }
                if (credential.gateway()) {
                    client.subscribe(configTopic(running), QOS_AT_LEAST_ONCE,
                            (topic, payload) -> runInboundTask(running,
                                    () -> handleConfig(running, topic, payload)));
                    // D-058 旅程 6 的子设备命令仍经网关连接下发；网关只订阅配置会让真实旅程永远停在 SENT。
                    client.subscribe(commandTopic(running), QOS_AT_LEAST_ONCE,
                            (topic, payload) -> runInboundTask(running,
                                    () -> handleCommand(running, topic, payload)));
                } else {
                    client.subscribe(commandTopic(running), QOS_AT_LEAST_ONCE,
                            (topic, payload) -> runInboundTask(running,
                                    () -> handleCommand(running, topic, payload)));
                }
                devices.put(credential.deviceKey(), running);
            }
            allConnectedAt = Instant.now();
            for (int index = 0; index < connected.size(); index++) {
                RunningDevice running = connected.get(index);
                running.task = scheduler.scheduleAtFixedRate(
                        () -> runPeriodicTask(running),
                        initialPublishDelayMillis(index, connected.size(), request.intervalSeconds()),
                        TimeUnit.SECONDS.toMillis(request.intervalSeconds()), TimeUnit.MILLISECONDS);
            }
            log.info("MQTT 模拟器已启动: {} 台设备, 上报间隔 {} 秒", devices.size(), request.intervalSeconds());
        } catch (RuntimeException exception) {
            failedOperations.incrementAndGet();
            connected.forEach(running -> running.acceptingWork = false);
            awaitActiveTasksDrained();
            closeDevices(connected);
            devices.clear();
            stopResourceSampling();
            // 本轮 open 已截断归档文件；启动失败也要关闭 writer，避免句柄泄漏且让下轮 start 能干净重开。
            manifest.close();
            throw exception;
        }
    }

    /**
     * 停止全部任务并主动断开 MQTT，触发 Broker 的正常离线生命周期。
     *
     * <p>顺序不可交换：先取消调度防止新发布，再等正在执行的周期任务退出（此时才不会有 publish 与
     * {@code close} 并发），随后关闭连接让在途 PUBACK 结算，最后关 manifest。否则正在执行的上报任务可能在
     * manifest 关闭之后才写入「发起/失败」计数，让最终快照与归档不一致。</p>
     */
    public synchronized void stop() {
        // 每台设备自己的闸门不会被下一轮 start 重新打开；已排队的旧入站任务即使稍后执行也只会直接返回。
        devices.values().forEach(running -> running.acceptingWork = false);
        devices.values().forEach(running -> {
            if (running.task != null) {
                running.task.cancel(false);
            }
        });
        awaitActiveTasksDrained();
        closeDevices(List.copyOf(devices.values()));
        devices.clear();
        stopResourceSampling();
        // 刷盘并关闭分类型 manifest；归档由操作者离线与平台入站集合对账。
        manifest.close();
        log.info("MQTT 模拟器已停止: 发起 {}, PUBACK 确认 {}, 失败操作 {}",
                totalInitiated(), totalConfirmed(), failedOperations.get());
    }

    /**
     * 同时强制当前批次全部设备异常断线，让生产 Paho 适配器按指数退避与等抖动恢复。
     *
     * <p>连接仍保留在运行批次中，周期上报在离线窗失败并计数；恢复后无需重新提交凭据或重启模拟任务。
     * 该行为与正常 {@link #stop()} 分离，避免联调人员误把故障注入当作资源释放。</p>
     */
    public synchronized void reconnectStorm() {
        if (devices.isEmpty()) {
            throw new IllegalStateException("模拟器未运行，无法注入重连风暴");
        }
        devices.values().forEach(running -> {
            // 每轮都记录下一连接代次；否则第二次风暴会把上一轮已恢复误算为本轮完成。
            running.expectedReconnectGeneration = running.client.connectionGeneration() + 1L;
            running.client.forceConnectionLoss();
            forcedDisconnects.incrementAndGet();
        });
        log.info("MQTT 模拟器已注入异常断连: {} 台设备", devices.size());
    }

    /**
     * 在不改变固定周期的前提下，为当前批次每台设备登记一次额外属性上报。
     *
     * <p>C4a-1c6 用它把一条带独立 messageId 的真实 QoS 1 报文放入指定故障窗口。资格入口只允许
     * 一台直连设备，并同步返回本次生成的 messageId；是否收到 PUBACK 仍必须另外观察 manifest 计数，
     * 不能把 HTTP 返回误作 Broker 确认。</p>
     *
     * @return 本次属性报文的业务 messageId
     */
    public synchronized UUID publishOnce() {
        if (devices.size() != 1) {
            throw new IllegalStateException("单次上报资格入口要求恰好一台运行设备");
        }
        RunningDevice running = devices.values().iterator().next();
        if (running.gateway || !running.acceptingWork) {
            throw new IllegalStateException("单次上报资格入口只允许正在运行的直连设备");
        }
        activePeriodicTasks.incrementAndGet();
        try {
            UUID messageId = publishTemperature(running);
            if (messageId == null) {
                throw new IllegalStateException("单次属性报文未能发起");
            }
            return messageId;
        } finally {
            activePeriodicTasks.decrementAndGet();
        }
    }

    /**
     * 以当前唯一直连设备发布指定平台命令的首份成功终态。
     *
     * <p>该方法只接受 UUIDv7 commandId，且不允许多设备批次产生含糊归属。相同 commandId 重复调用
     * 复用首次 payload/messageId，保持与真实 QoS 1 重投一致；PUBACK 仍由 manifest 独立确认。</p>
     *
     * @param commandId 已由平台生产 API 受理的命令标识
     * @return 冻结回复的业务 messageId
     */
    public synchronized UUID publishCommandReply(UUID commandId) {
        if (commandId == null || commandId.version() != 7) {
            throw new IllegalArgumentException("commandId 必须是 UUIDv7");
        }
        if (devices.size() != 1) {
            throw new IllegalStateException("命令回复资格入口要求恰好一台运行设备");
        }
        RunningDevice running = devices.values().iterator().next();
        if (running.gateway || !running.acceptingWork) {
            throw new IllegalStateException("命令回复资格入口只允许正在运行的直连设备");
        }
        CommandReply reply = running.completedReplies.computeIfAbsent(commandId.toString(), ignored -> createCommandReply());
        publishCommandReply(running, commandId.toString(), reply);
        return reply.messageId();
    }

    /**
     * 返回不含凭据的运行统计。
     *
     * @return 当前状态快照
     */
    public SimulationStats stats() {
        int connectedDevices = (int) devices.values().stream()
                .filter(running -> running.client.isConnected())
                .count();
        List<Instant> reconnectTimes = devices.values().stream()
                .filter(running -> running.expectedReconnectGeneration > 0
                        && running.client.connectionGeneration() >= running.expectedReconnectGeneration)
                .map(running -> running.client.lastConnectedAt())
                .sorted()
                .toList();
        long reconnectSpreadMillis = reconnectTimes.size() < 2 ? 0L
                : java.time.Duration.between(reconnectTimes.getFirst(), reconnectTimes.getLast()).toMillis();
        ResourceUsage resource = resourceUsage();
        ConnectionQualification connection = new ConnectionQualification(
                targetConnections.get(), initialConnectionAttempts.get(), initialConnectionSuccesses.get(),
                initialConnectionFailures.get(), connectionRampStartedAt, allConnectedAt,
                connectionRampStartedAt == null || allConnectedAt == null ? -1L
                        : java.time.Duration.between(connectionRampStartedAt, allConnectedAt).toMillis());
        GeneratorResourceQualification generatorResource = new GeneratorResourceQualification(
                resourceSampleCount.get(), maxResourceSampleGapMillis.get(),
                maxResourceSampleGapStartedAt, maxResourceSampleGapEndedAt, incompleteResourceSamples.get(),
                peakProcessCpuLoad, maxFiveSecondCpuAverage,
                peakHeapUsedBytes.get(), resource.heapMaxBytes(), peakRssBytes,
                peakThreadCount.get(), peakOpenFileDescriptors, resource.maxFileDescriptors());
        return new SimulationStats(!devices.isEmpty(), devices.size(), connectedDevices,
                totalInitiated(), totalConfirmed(), failedOperations.get(), forcedDisconnects.get(),
                reconnectTimes.size(), reconnectSpreadMillis,
                manifest.countersOf(UplinkManifest.Type.PROPERTY_REPORT),
                manifest.countersOf(UplinkManifest.Type.COMMAND_REPLY),
                manifest.countersOf(UplinkManifest.Type.CONFIG_REPLY),
                manifest.countersOf(UplinkManifest.Type.BATCH_REPORT),
                manifest.healthy(), manifest.failureReason(), activeManifestDir.toString(),
                schedulingDeviationP99Millis(), deviationTotal.get(),
                resource.processCpuLoad(), resource.heapUsedBytes(), resource.heapMaxBytes(),
                resource.rssBytes(), resource.threadCount(), resource.openFileDescriptors(),
                resource.maxFileDescriptors(), Instant.now(), runId, shardId, propertiesPerReport,
                connection, generatorResource);
    }

    /** 全部消息类型的发起数合计。 */
    private long totalInitiated() {
        long total = 0L;
        for (UplinkManifest.Type type : UplinkManifest.Type.values()) {
            total += manifest.countersOf(type).initiated();
        }
        return total;
    }

    /** 全部消息类型的 PUBACK 确认数合计。 */
    private long totalConfirmed() {
        long total = 0L;
        for (UplinkManifest.Type type : UplinkManifest.Type.values()) {
            total += manifest.countersOf(type).confirmed();
        }
        return total;
    }

    /**
     * 构造本设备的 OTA 目录：{@code <otaDir>/<deviceKey>}。
     *
     * <p>deviceKey 先按 OTA Topic 的冻结字符集校验；不合法时替换为下划线形态并只保留一层目录名，
     * 因此运行方即使把 {@code ..} 或含分隔符的标识传进来，也无法让暂存区/日志写到基目录之外。
     * 真实接入路径上 deviceKey 已由 Topic 拼接拒绝非法字符，这里的替换只是多一道防线。</p>
     *
     * @param deviceKey 设备短标识
     * @return 归一化后的设备 OTA 目录
     */
    private Path otaDeviceDirectory(String deviceKey) {
        String segment = deviceKey != null && deviceKey.matches("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
                ? deviceKey
                : sanitizeSegment(deviceKey);
        if (!segment.equals(deviceKey)) {
            log.warn("OTA 设备标识含非法字符，已归一化为安全目录名");
        }
        Path normalizedBase = otaDirectory.toAbsolutePath().normalize();
        Path target = normalizedBase.resolve(segment).normalize();
        if (!target.startsWith(normalizedBase)) {
            throw new IllegalArgumentException("OTA 设备目录越出配置基目录");
        }
        return target;
    }

    /** 把任意标识压缩成安全单层目录名；仍为空时使用稳定占位名，避免所有非法标识写进同一目录。 */
    private static String sanitizeSegment(String deviceKey) {
        String sanitized = deviceKey == null ? "" : deviceKey.replaceAll("[^A-Za-z0-9_-]", "_");
        if (sanitized.isEmpty() || !Character.isLetterOrDigit(sanitized.charAt(0))) {
            sanitized = "device" + sanitized;
        }
        return sanitized.length() > 64 ? sanitized.substring(0, 64) : sanitized;
    }

    /** 构造不可逃逸的 run/shard 目录；即使应用服务被绕过 Controller 直接调用也不能通过标识写出基目录。 */
    private static Path manifestDirectory(Path base, String runId, String shardId) {        if (runId == null || shardId == null
                || !runId.matches("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$")
                || !shardId.matches("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$")) {
            throw new IllegalArgumentException("runId/shardId 只能包含字母、数字、点、下划线和连字符");
        }
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path target = normalizedBase.resolve(runId).resolve(shardId).normalize();
        if (!target.startsWith(normalizedBase)) {
            throw new IllegalArgumentException("manifest 目录越出配置基目录");
        }
        return target;
    }

    /** 预生成本轮属性键；第一项保留真实示例键 temperature，其余使用稳定、低开销的资格键。 */
    private static List<String> propertyKeys(int count) {
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("propertiesPerReport 必须在 1..100 之间");
        }
        List<String> keys = new ArrayList<>(count);
        keys.add("temperature");
        for (int index = 2; index <= count; index++) {
            keys.add(String.format(Locale.ROOT, "metric_%02d", index));
        }
        return List.copyOf(keys);
    }

    /** 启动每秒资源采样并重置峰值；采样缺口本身也是发生器饱和证据。 */
    private void startResourceSampling() {
        resourceSampleCount.set(0L);
        incompleteResourceSamples.set(0L);
        lastResourceSampleNanos = 0L;
        lastResourceSampleAt = null;
        maxResourceSampleGapStartedAt = null;
        maxResourceSampleGapEndedAt = null;
        // 最大值最后清零，使并发 stats 读到 0 时不会同时看见上一轮的墙钟边界。
        maxResourceSampleGapMillis.set(0L);
        peakProcessCpuLoad = -1.0;
        recentCpuIndex = 0;
        recentCpuCount = 0;
        maxFiveSecondCpuAverage = -1.0;
        peakHeapUsedBytes.set(0L);
        peakRssBytes = -1L;
        peakThreadCount.set(0L);
        peakOpenFileDescriptors = -1L;
        sampleResourceUsage();
        resourceSamplerTask = scheduler.scheduleAtFixedRate(this::sampleResourceUsage,
                1L, 1L, TimeUnit.SECONDS);
    }

    /** 停止采样前再取一个末态样本；重复 stop 不会重复创建或泄漏任务。 */
    private void stopResourceSampling() {
        ScheduledFuture<?> task = resourceSamplerTask;
        resourceSamplerTask = null;
        if (task == null) {
            return;
        }
        task.cancel(false);
        sampleResourceUsage();
    }

    /** 记录一次发生器资源快照；单任务写峰值，volatile/Atomic 字段供并发 stats 安全读取。 */
    private void sampleResourceUsage() {
        synchronized (resourceSampleLock) {
            long now = System.nanoTime();
            Instant sampledAt = Instant.now();
            long previous = lastResourceSampleNanos;
            Instant previousAt = lastResourceSampleAt;
            lastResourceSampleNanos = now;
            lastResourceSampleAt = sampledAt;
            if (previous != 0L) {
                long gapMillis = TimeUnit.NANOSECONDS.toMillis(now - previous);
                if (gapMillis > maxResourceSampleGapMillis.get()) {
                    // 时长只取单调时钟；墙钟仅定位现场，先写边界再发布最大值，保证 stats 快照不读到无边界的新值。
                    maxResourceSampleGapStartedAt = previousAt;
                    maxResourceSampleGapEndedAt = sampledAt;
                    maxResourceSampleGapMillis.set(gapMillis);
                }
            }
            ResourceUsage sample = resourceUsage();
            resourceSampleCount.incrementAndGet();
            if (sample.processCpuLoad() >= 0.0) {
                peakProcessCpuLoad = Math.max(peakProcessCpuLoad, sample.processCpuLoad());
                recentCpuSamples[recentCpuIndex] = sample.processCpuLoad();
                recentCpuIndex = (recentCpuIndex + 1) % recentCpuSamples.length;
                recentCpuCount = Math.min(recentCpuCount + 1, recentCpuSamples.length);
                if (recentCpuCount == recentCpuSamples.length) {
                    double sum = 0.0;
                    for (double cpuSample : recentCpuSamples) {
                        sum += cpuSample;
                    }
                    maxFiveSecondCpuAverage = Math.max(
                            maxFiveSecondCpuAverage, sum / recentCpuSamples.length);
                }
            }
            peakHeapUsedBytes.accumulateAndGet(sample.heapUsedBytes(), Math::max);
            if (sample.rssBytes() >= 0L) {
                peakRssBytes = Math.max(peakRssBytes, sample.rssBytes());
            }
            peakThreadCount.accumulateAndGet(sample.threadCount(), Math::max);
            if (sample.openFileDescriptors() >= 0L) {
                peakOpenFileDescriptors = Math.max(peakOpenFileDescriptors, sample.openFileDescriptors());
            }
            if (sample.processCpuLoad() < 0.0 || sample.rssBytes() < 0L
                    || sample.openFileDescriptors() < 0L || sample.maxFileDescriptors() <= 0L) {
                incompleteResourceSamples.incrementAndGet();
            }
        }
    }

    /** 从 JVM 读取发生器自身资源占用，供 A4-0 判定「任一资源先饱和则本轮无效」。 */
    private static ResourceUsage resourceUsage() {
        long openFds = -1L;
        long maxFds = -1L;
        double cpuLoad = -1.0;
        try {
            java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory
                    .getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                cpuLoad = sunOs.getProcessCpuLoad();
            }
            // FD 计数只在 Unix 实现上存在（com.sun.management.UnixOperatingSystemMXBean），
            // 非 Unix 或未授权时保持哨兵 -1，判定方按「未知」处理而非误报合格。
            if (os instanceof com.sun.management.UnixOperatingSystemMXBean unixOs) {
                openFds = unixOs.getOpenFileDescriptorCount();
                maxFds = unixOs.getMaxFileDescriptorCount();
            }
        } catch (RuntimeException ignored) {
            // 指标读取失败不影响模拟，保持哨兵值。
        }
        long threadCount = java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();
        Runtime runtime = Runtime.getRuntime();
        return new ResourceUsage(cpuLoad, runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(),
                processRssBytes(), threadCount, openFds, maxFds);
    }

    /**
     * 读取进程 RSS；Linux 直接读 procfs，macOS 用受控的 {@code ps} 回退。
     *
     * <p>不能用 committed virtual memory 冒充 RSS：JVM 保留地址空间可能远大于实际驻留页，会让内存余量结论失真。
     * 资格运行若两个入口都不可用则返回 -1，并由聚合器把指标缺失判为失败。</p>
     */
    private static long processRssBytes() {
        Path procStatus = Path.of("/proc/self/status");
        if (Files.isReadable(procStatus)) {
            try {
                for (String line : Files.readAllLines(procStatus, StandardCharsets.UTF_8)) {
                    if (line.startsWith("VmRSS:")) {
                        String digits = line.substring("VmRSS:".length()).trim().split("\\s+")[0];
                        return Long.parseLong(digits) * 1024L;
                    }
                }
            } catch (IOException | NumberFormatException ignored) {
                return -1L;
            }
        }
        try {
            Process process = new ProcessBuilder("ps", "-o", "rss=", "-p",
                    Long.toString(ProcessHandle.current().pid())).start();
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return -1L;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return output.isEmpty() ? -1L : Long.parseLong(output) * 1024L;
        } catch (IOException | NumberFormatException exception) {
            return -1L;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return -1L;
        }
    }

    /**
     * 调度偏差 P99：对固定桶直方图从最低桶向上累计到第 99 分位，返回所在桶的上界。
     *
     * <p>用桶上界近似 P99 会略微高估，但直方图内存有界、并发安全、覆盖整轮测量，且高估方向对
     * 「偏差 ≤ 周期 5%」的资格判定是保守的（宁可判严不判松）。样本为空返回 0。</p>
     */
    private long schedulingDeviationP99Millis() {
        long total = deviationTotal.get();
        if (total == 0) {
            return 0L;
        }
        long threshold = (long) Math.ceil(total * 0.99);
        long cumulative = 0L;
        for (int i = 0; i < deviationHistogram.length(); i++) {
            cumulative += deviationHistogram.get(i);
            if (cumulative >= threshold) {
                // 溢出桶没有有限上界；返回 MAX_VALUE 强制资格判定失败，不能用 30s 冒充任意更大偏差。
                return i < DEVIATION_BUCKET_UPPER_MILLIS.length
                        ? DEVIATION_BUCKET_UPPER_MILLIS[i]
                        : Long.MAX_VALUE;
            }
        }
        return 30_000L;
    }

    /** 发生器进程资源快照；负值表示该指标在当前平台不可得。 */
    private record ResourceUsage(double processCpuLoad, long heapUsedBytes, long heapMaxBytes, long rssBytes,
                                 long threadCount, long openFileDescriptors, long maxFileDescriptors) {
    }

    /**
     * 发布单条温度属性报文；失败只影响当前设备本次上报，不停止其他设备。
     *
     * @param running 目标直连设备
     * @return 已交给 MQTT 客户端的业务 messageId；发布前失败时为空
     */
    private UUID publishTemperature(RunningDevice running) {
        // 任务入口即记录调度偏差：无论发布成败，本轮调度都已经发生，不能只统计成功轮次。
        recordSchedulingDeviation(running);
        try {
            double temperature = nextTemperature(running);
            UUID messageId = Uuid7.generate();
            Map<String, Object> payloadValues = new LinkedHashMap<>(propertiesPerReport);
            payloadValues.put(propertyKeys.getFirst(), temperature);
            for (int index = 1; index < propertyKeys.size(); index++) {
                // 资格模型关注报文编码/网络开销；附加属性使用有界数值，避免生成器自身维护额外设备状态。
                payloadValues.put(propertyKeys.get(index), Math.round(random.nextDouble() * 1000.0) / 10.0);
            }
            DevicePropertyReport report = new DevicePropertyReport(
                    messageId, Instant.now(), modelVersion, payloadValues);
            String payload = objectMapper.writeValueAsString(report);
            String topic = "tc/v1/" + running.projectKey + "/" + running.deviceKey + "/up/property/report";
            trackDelivery(UplinkManifest.Type.PROPERTY_REPORT,
                    running.client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), QOS_AT_LEAST_ONCE, false),
                    messageId);
            return messageId;
        } catch (RuntimeException exception) {
            // publish 端口保证同步发起失败也返回异常 Future；能在这里出现的异常发生在发布前
            // （序列化/载荷构造等），不能记成「已发起但未获 PUBACK」，否则 manifest 对账等式裂口。
            failedOperations.incrementAndGet();
            // 只记录设备标识和异常类型；异常消息可能由第三方库拼入连接参数，不能直接输出。
            log.warn("模拟设备上报失败 deviceKey={}, errorType={}",
                    running.deviceKey, exception.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 记录一次发布的 PUBACK 交付结果，并按消息类型计入 manifest。
     *
     * <p>发起发布（{@code client.publish} 返回）只代表交给客户端，PUBACK 才代表 Broker 确认；A4-0 冻结的
     * 「成功集合」以 PUBACK 为准。先在 manifest 登记「发起」，再注册可能同步执行的 whenComplete，
     * 避免快照瞬间出现「确认 > 发起」。批量上报一次 PUBACK 确认多个子消息 messageId，故按变参传入。</p>
     *
     * @param type 消息类型
     * @param delivery 发布交付收据
     * @param messageIds 本次发布携带的 messageId（单消息上报一个，批量上报多个）
     */
    private void trackDelivery(UplinkManifest.Type type, CompletableFuture<Void> delivery, UUID... messageIds) {
        manifest.recordInitiated(type);
        activeDeliveries.incrementAndGet();
        delivery.whenComplete((ignored, failure) -> {
            try {
                if (failure == null) {
                    manifest.recordConfirmed(type, messageIds);
                } else {
                    manifest.recordFailed(type);
                }
            } finally {
                activeDeliveries.decrementAndGet();
            }
        });
    }

    /**
     * 记录本次发布相对配置周期的调度偏差（毫秒），写入并发安全的固定桶直方图。
     *
     * <p>{@code scheduleAtFixedRate} 不暴露每轮计划触发时刻，这里以「相邻两次实际触发间隔 − 配置周期」的绝对值
     * 近似调度抖动；首次发布没有前序基准，跳过。直方图按桶累计，O(1) 记录、O(桶数) 取分位，
     * 覆盖整轮测量且无环形数组「满后丢弃前段样本」与「写样本前计数已可见」两类竞态。</p>
     */
    private void recordSchedulingDeviation(RunningDevice running) {
        long now = System.nanoTime();
        long last = running.lastPublishNanos;
        running.lastPublishNanos = now;
        if (last == 0L) {
            return;
        }
        long intervalNanos = TimeUnit.SECONDS.toNanos(intervalSeconds);
        long deviationNanos = Math.abs((now - last) - intervalNanos);
        long deviationMillis = TimeUnit.NANOSECONDS.toMillis(deviationNanos);
        deviationHistogram.incrementAndGet(deviationBucket(deviationMillis));
        deviationTotal.incrementAndGet();
    }

    /** 把偏差毫秒数映射到直方图桶下标；末位为「大于等于最大上界」的溢出桶。 */
    private static int deviationBucket(long deviationMillis) {
        for (int i = 0; i < DEVIATION_BUCKET_UPPER_MILLIS.length; i++) {
            if (deviationMillis < DEVIATION_BUCKET_UPPER_MILLIS[i]) {
                return i;
            }
        }
        return DEVIATION_BUCKET_UPPER_MILLIS.length;
    }

    /**
     * 返回已认证设备专属命令订阅 Topic。
     *
     * <p>通配符只位于最后一段，和架构文档 5.3 的 {@code down/command/{commandId}} 约定一致；
     * 若改成 {@code down/#} 会让模拟器在命令回复实现尚未覆盖的场景下错误消费其他下行协议。</p>
     *
     * @param running 已连接设备
     * @return MQTT 命令订阅 Topic
     */
    private String commandTopic(RunningDevice running) {
        return "tc/v1/" + running.projectKey + "/" + running.deviceKey + "/down/command/#";
    }

    /**
     * 处理平台下发命令，并在自动回复模式下发布同一命令的成功终态。
     *
     * <p>QoS 1 可重复投递；首次生成的回复按 commandId 缓存，重投仅重发相同终态 payload，
     * 因而不会表现为模拟设备再次执行相同 RPC。关闭自动回复时故意完全静默，以演练平台超时扫描。</p>
     *
     * @param running 收到消息的设备
     * @param topic Broker 投递的完整 Topic
     * @param payload 下行 JSON payload
     */
    private void handleCommand(RunningDevice running, String topic, byte[] payload) {
        if (!running.autoReplyCommands) {
            return;
        }
        String commandId = commandIdFromTopic(running, topic);
        if (commandId == null) {
            failedOperations.incrementAndGet();
            log.warn("模拟设备收到非法命令 Topic deviceKey={}", running.deviceKey);
            return;
        }
        try {
            CommandReply reply = running.completedReplies.computeIfAbsent(commandId,
                    ignored -> createCommandReply());
            publishCommandReply(running, commandId, reply);
        } catch (RuntimeException exception) {
            // 此处只承接发布前的解析/序列化异常；发布交付失败由 trackDelivery 统一记入 manifest。
            failedOperations.incrementAndGet();
            log.warn("模拟设备命令回复失败 deviceKey={}, errorType={}",
                    running.deviceKey, exception.getClass().getSimpleName());
        }
    }

    /** 统一命令回复发布路径，保证自动回复与 C4a 故障注入使用同一 Topic、序列化和 PUBACK 记账。 */
    private void publishCommandReply(RunningDevice running, String commandId, CommandReply reply) {
        String replyTopic = "tc/v1/" + running.projectKey + "/" + running.deviceKey
                + "/up/command/" + commandId + "/reply";
        byte[] replyPayload = objectMapper.writeValueAsString(reply).getBytes(StandardCharsets.UTF_8);
        trackDelivery(UplinkManifest.Type.COMMAND_REPLY,
                running.client.publish(replyTopic, replyPayload, QOS_AT_LEAST_ONCE, false),
                reply.messageId());
    }

    /**
     * 从严格匹配当前设备身份的 Topic 取得 commandId。
     *
     * <p>回调接口即使被错误适配也不能让其他项目或设备的 Topic 驱动本连接回复；这项复核与
     * Broker ACL 互为防线，避免测试替身掩盖 Topic 拼接错误。</p>
     *
     * @param running 当前连接设备
     * @param topic 收到 Topic
     * @return 命令标识；不符合冻结层级时为 {@code null}
     */
    private String commandIdFromTopic(RunningDevice running, String topic) {
        String prefix = "tc/v1/" + running.projectKey + "/" + running.deviceKey + "/down/command/";
        if (topic == null || !topic.startsWith(prefix)) {
            return null;
        }
        String commandId = topic.substring(prefix.length());
        return commandId.matches(COMMAND_ID_PATTERN) ? commandId : null;
    }

    /**
     * 生成 S4-3 冻结的命令成功回复。
     *
     * <p>自动模拟器不持有物模型输出 Schema，不能伪造具体设备的业务结果，所以按 S4-3 协议将
     * {@code output} 固定为空对象。真实设备必须按命令定义输出 Schema 返回，不能把此默认行为
     * 复制到固件。</p>
     *
     * @return 命令成功回复（messageId 固定用于 QoS1 重投去重）
     */
    private CommandReply createCommandReply() {
        return new CommandReply(Uuid7.generate(), Instant.now(), "SUCCESS", Map.of(), null, null);
    }

    /** @return 网关配置下发订阅 Topic */
    private String configTopic(RunningDevice running) {
        return "tc/v1/" + running.projectKey + "/" + running.deviceKey + "/down/config";
    }

    /** 处理配置下发：解析全量点位集、记录已应用版本并回执 APPLIED。 */
    private void handleConfig(RunningDevice running, String topic, byte[] payload) {
        try {
            JsonNode config = objectMapper.readTree(payload);
            int version = config.path("version").asInt(0);
            List<ConfigPoint> points = new ArrayList<>();
            for (JsonNode point : config.path("points")) {
                points.add(new ConfigPoint(point.path("subDeviceKey").asString(),
                        point.path("propertyKey").asString(), point.path("dataType").asString()));
            }
            running.appliedVersion = version;
            running.configPoints = points;
            ackConfig(running, version, "APPLIED", null, null);
            // 子设备在线态不能从网关连接推导（架构文档 5.2）；配置里出现的每个子设备须显式 login。
            points.stream().map(ConfigPoint::subDeviceKey).distinct()
                    .forEach(subDeviceKey -> publishSubDeviceLogin(running, subDeviceKey));
        } catch (RuntimeException exception) {
            failedOperations.incrementAndGet();
            log.warn("网关配置解析失败 deviceKey={}, errorType={}",
                    running.deviceKey, exception.getClass().getSimpleName());
        }
    }

    /**
     * 以已认证网关身份显式上报子设备在线，供网关/子设备真实旅程验证权威在线态与离线级联。
     *
     * <p>消息不携带 gatewayId；接入层只采用 MQTT 连接的已认证身份。每次配置重发生成新的 UUIDv7，
     * 让平台 CAS 以最新可信接收时间推进，而不是把配置重发误判为旧消息重放。</p>
     *
     * @param running 已认证网关
     * @param subDeviceKey 已绑定且出现在发布配置中的子设备标识
     */
    private void publishSubDeviceLogin(RunningDevice running, String subDeviceKey) {
        try {
            UUID messageId = Uuid7.generate();
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("messageId", messageId.toString());
            payload.put("subDeviceKey", subDeviceKey);
            String topic = "tc/v1/" + running.projectKey + "/" + running.deviceKey + "/up/sub/login";
            // A4 manifest 只核算四类负载报文；拓扑在线信号不混入其等式，但异步交付失败仍进入总失败计数。
            running.client.publish(topic,
                            objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8),
                            QOS_AT_LEAST_ONCE, false)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            failedOperations.incrementAndGet();
                            log.warn("网关子设备上线交付失败 gatewayKey={}", running.deviceKey);
                        }
                    });
        } catch (RuntimeException exception) {
            failedOperations.incrementAndGet();
            log.warn("网关子设备上线发布失败 gatewayKey={}, errorType={}",
                    running.deviceKey, exception.getClass().getSimpleName());
        }
    }

    /** 回执已应用配置版本。 */
    private void ackConfig(RunningDevice running, int version, String status, String errorCode, String message) {
        try {
            UUID messageId = Uuid7.generate();
            ObjectNode reply = objectMapper.createObjectNode();
            reply.put("messageId", messageId.toString());
            reply.put("configType", "MODBUS_POINT_MAPPING");
            reply.put("version", version);
            reply.put("status", status);
            if (errorCode != null) reply.put("errorCode", errorCode);
            if (message != null) reply.put("message", message);
            reply.put("occurredAt", Instant.now().toString());
            String replyTopic = "tc/v1/" + running.projectKey + "/" + running.deviceKey + "/up/config/reply";
            trackDelivery(UplinkManifest.Type.CONFIG_REPLY,
                    running.client.publish(replyTopic, objectMapper.writeValueAsString(reply).getBytes(StandardCharsets.UTF_8),
                            QOS_AT_LEAST_ONCE, false),
                    messageId);
        } catch (RuntimeException exception) {
            // 此处只承接发布前的解析/序列化异常；发布交付失败由 trackDelivery 统一记入 manifest。
            failedOperations.incrementAndGet();
            log.warn("网关配置回执失败 deviceKey={}, errorType={}",
                    running.deviceKey, exception.getClass().getSimpleName());
        }
    }

    /** 按点位生成子设备属性并按子设备分组，经 {@code up/batch/report} 批量上报。 */
    private void publishBatchReport(RunningDevice running) {
        if (running.configPoints.isEmpty()) {
            return; // 尚未收到配置，不产生上报
        }
        try {
            Map<String, ObjectNode> byDevice = new java.util.LinkedHashMap<>();
            for (ConfigPoint point : running.configPoints) {
                ObjectNode payload = byDevice.computeIfAbsent(point.subDeviceKey, ignored -> objectMapper.createObjectNode());
                if ("BIT".equals(point.dataType)) {
                    payload.put(point.propertyKey, random.nextBoolean());
                } else {
                    payload.put(point.propertyKey, Math.round((20.0 + random.nextDouble() * 10.0) * 10.0) / 10.0);
                }
            }
            ObjectNode report = objectMapper.createObjectNode();
            var devicesArray = report.putArray("devices");
            List<UUID> subMessageIds = new ArrayList<>(byDevice.size());
            for (Map.Entry<String, ObjectNode> entry : byDevice.entrySet()) {
                ObjectNode device = objectMapper.createObjectNode();
                UUID subMessageId = Uuid7.generate();
                subMessageIds.add(subMessageId);
                device.put("messageId", subMessageId.toString());
                device.put("deviceKey", entry.getKey());
                device.put("occurredAt", Instant.now().toString());
                device.set("payload", entry.getValue());
                devicesArray.add(device);
            }
            String topic = "tc/v1/" + running.projectKey + "/" + running.deviceKey + "/up/batch/report";
            // 一次批量上报 PUBACK 确认全部子消息 messageId，按变参一并归档，避免丢失中间子消息。
            trackDelivery(UplinkManifest.Type.BATCH_REPORT,
                    running.client.publish(topic, objectMapper.writeValueAsString(report).getBytes(StandardCharsets.UTF_8),
                            QOS_AT_LEAST_ONCE, false),
                    subMessageIds.toArray(UUID[]::new));
        } catch (RuntimeException exception) {
            // 此处只承接发布前的载荷构造/序列化异常；发布交付失败由 trackDelivery 统一记入 manifest。
            failedOperations.incrementAndGet();
            log.warn("网关批量上报失败 deviceKey={}, errorType={}",
                    running.deviceKey, exception.getClass().getSimpleName());
        }
    }

    /** 温度按 0.1 精度随机游走，并限制在示例物模型的 -40～125 范围。 */
    private double nextTemperature(RunningDevice running) {
        double next = Math.max(-40.0, Math.min(125.0,
                running.temperature + (random.nextDouble() - 0.5) * 2.0));
        running.temperature = Math.round(next * 10.0) / 10.0;
        return running.temperature;
    }

    /** 关闭异常不妨碍其他设备回收，但必须进入失败计数。 */
    private void closeQuietly(RunningDevice running) {
        try {
            running.client.close();
        } catch (RuntimeException exception) {
            failedOperations.incrementAndGet();
            Throwable cause = exception.getCause();
            log.warn("模拟设备断开失败 deviceKey={}, errorType={}, causeType={}",
                    running.deviceKey, exception.getClass().getSimpleName(),
                    cause == null ? "none" : cause.getClass().getSimpleName());
        }
    }

    /**
     * 有界并发关闭一个分片的设备连接，使单客户端最多 2 秒的 DISCONNECT 等待不会线性放大成 N×2 秒。
     *
     * <p>每个 close 仍走 Paho 正常下线语义。固定 16 个平台线程避免为一个分片同时发出数百个 DISCONNECT，后者在真实跑数中
     * 造成 Paho 关闭异常；又不会退化为最坏 N×2 秒的串行等待。15 秒后仍未收敛说明发生器收尾失控，必须把本轮 manifest
     * 标记无效，而不是让资格进程无限挂住或强行宣称成功。</p>
     *
     * @param runningDevices 待关闭设备快照
     */
    private void closeDevices(List<RunningDevice> runningDevices) {
        if (runningDevices.isEmpty()) {
            return;
        }
        int workers = Math.min(16, runningDevices.size());
        try (ExecutorService closer = Executors.newFixedThreadPool(workers,
                Thread.ofPlatform().name("tc-simulator-close-", 0).factory())) {
            runningDevices.forEach(running -> closer.submit(() -> closeQuietly(running)));
            closer.shutdown();
            if (!closer.awaitTermination(15L, TimeUnit.SECONDS)) {
                manifest.invalidate("设备连接未在 15 秒内全部关闭: " + runningDevices.size());
                closer.shutdownNow();
                log.warn("{} 台模拟设备未在 15 秒内全部关闭，本轮 manifest 已标记无效", runningDevices.size());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            manifest.invalidate("等待设备连接关闭时被中断");
            log.warn("等待模拟设备关闭时被中断，本轮 manifest 已标记无效");
        }
    }

    /**
     * 周期任务的统一入口：包裹业务发布以追踪「正在执行」线程数。
     *
     * <p>{@code ScheduledFuture.cancel(false)} 只阻止后续触发，不打断正在执行的这一轮；stop 必须等这轮
     * 真正退出才能关 manifest，否则它的计数会在快照之后才落账。计数用 finally 递减，保证发布抛异常也不泄漏。</p>
     *
     * @param running 目标设备
     */
    private void runPeriodicTask(RunningDevice running) {
        activePeriodicTasks.incrementAndGet();
        try {
            if (!running.acceptingWork) {
                return;
            }
            if (running.gateway) {
                publishBatchReport(running);
            } else {
                publishTemperature(running);
            }
        } finally {
            activePeriodicTasks.decrementAndGet();
        }
    }

    /**
     * 入站命令/配置的统一入口：与周期任务使用相同的停止闸门，并单独统计正在执行数量。
     *
     * <p>Paho 的串行执行器可能已经排队旧消息；闸门属于 {@link RunningDevice} 而非全局批次，所以下一轮 start
     * 不会误把上一轮迟到任务重新放行。计数先增加再检查闸门：若 stop 看到 0 后任务才开始，它只会读到关闭闸门并立即退出；
     * 若任务已越过闸门，stop 必然看到活动计数并等待其收敛。</p>
     *
     * @param running 消息所属设备
     * @param action 命令或配置处理逻辑
     */
    private void runInboundTask(RunningDevice running, Runnable action) {
        activeInboundTasks.incrementAndGet();
        try {
            if (!running.acceptingWork) {
                return;
            }
            action.run();
        } finally {
            activeInboundTasks.decrementAndGet();
        }
    }

    /**
     * 有界等待正在执行的周期/入站任务和 PUBACK 交付全部退出，确保 stop 关闭 manifest 前所有计数都已落账。
     *
     * <p>正常应在毫秒级返回；5 秒只是防御性上界。超时后仍继续释放连接，但会显式把 manifest 标记为无效，
     * 不能让资格脚本把缺口当成合格证据，也不能让 stop 永久挂起。</p>
     */
    private void awaitActiveTasksDrained() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((activePeriodicTasks.get() > 0 || activeInboundTasks.get() > 0 || activeDeliveries.get() > 0)
                && System.nanoTime() < deadline) {
            // 停止路径不应为等待异步回调而满核自旋；1ms 轮询足以保持收尾响应性。
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        int periodic = activePeriodicTasks.get();
        int inbound = activeInboundTasks.get();
        int deliveries = activeDeliveries.get();
        if (periodic > 0 || inbound > 0 || deliveries > 0) {
            manifest.invalidate("停止超时：仍有周期任务 " + periodic + "、入站任务 " + inbound
                    + "、在途交付 " + deliveries + " 未退出");
            log.warn("停止时仍有周期任务 {} 个、入站任务 {} 个、在途交付 {} 个未退出，本轮 manifest 已标记无效",
                    periodic, inbound, deliveries);
        }
    }

    /** 应用退出时释放连接与调度线程，防止本地反复启动留下幽灵连接。 */
    @PreDestroy
    void shutdown() {
        stop();
        scheduler.shutdown();
    }

    /**
     * 对外统计契约。
     *
     * @param running 是否有设备正在运行
     * @param deviceCount 当前连接设备数
     * @param connectedDevices 当前已恢复 MQTT 连接的设备数
     * @param publishedReports 全部消息类型的发起发布合计
     * @param deliveredReports 全部消息类型的 PUBACK 确认合计
     * @param failedOperations 非发布类失败（连接、订阅、关闭、解析）；发布成败按类型计入下方 Counters
     * @param forcedDisconnects 本批次累计强制异常断连数
     * @param reconnectedDevices 至少完成过一次故障恢复的设备数
     * @param reconnectSpreadMillis 最近一轮设备恢复完成时刻的最大跨度毫秒数
     * @param propertyReports property-report 的发起/确认/失败计数
     * @param commandReplies command-reply 的发起/确认/失败计数
     * @param configReplies config-reply 的发起/确认/失败计数
     * @param batchReports batch-report 的发起/确认/失败计数
     * @param manifestHealthy manifest 本轮是否健康（任何打开/写入/关闭失败都会置 false，使整轮资格失效）
     * @param manifestFailureReason manifest 首次置为不健康的原因；健康时为空
     * @param manifestDir 分类型 messageId 归档目录
     * @param schedulingDeviationP99Millis 上报调度偏差 P99（与配置周期的差值毫秒，按桶上界近似）
     * @param schedulingDeviationSampleCount 已记录的调度偏差样本总数（覆盖整轮测量）
     * @param processCpuLoad 发生器进程近期 CPU 占用（0~1，不可得为 -1）
     * @param heapUsedBytes 已用堆字节
     * @param heapMaxBytes 最大堆字节
     * @param rssBytes 进程驻留集字节（不可得为 -1）
     * @param threadCount 活动线程数
     * @param openFileDescriptors 已打开文件描述符数（不可得为 -1）
     * @param maxFileDescriptors 文件描述符上限（不可得为 -1）
     * @param clockNow 发生器本地时钟快照，用于与 SUT 时钟对偏
     * @param runId 本轮资格运行标识
     * @param shardId 当前分片标识
     * @param propertiesPerReport 每条属性报文的属性数
     * @param connection 初次连接爬坡事实
     * @param generatorResources 本轮持续资源峰值与采样完整性
     */
    public record SimulationStats(boolean running, int deviceCount, int connectedDevices,
                                  long publishedReports, long deliveredReports, long failedOperations,
                                  long forcedDisconnects, int reconnectedDevices, long reconnectSpreadMillis,
                                  UplinkManifest.Counters propertyReports, UplinkManifest.Counters commandReplies,
                                  UplinkManifest.Counters configReplies, UplinkManifest.Counters batchReports,
                                  boolean manifestHealthy, String manifestFailureReason, String manifestDir,
                                  long schedulingDeviationP99Millis, long schedulingDeviationSampleCount,
                                  double processCpuLoad, long heapUsedBytes, long heapMaxBytes,
                                  long rssBytes, long threadCount, long openFileDescriptors,
                                  long maxFileDescriptors, Instant clockNow,
                                  String runId, String shardId, int propertiesPerReport,
                                  ConnectionQualification connection,
                                  GeneratorResourceQualification generatorResources) {
    }

    /**
     * 初次连接资格事实；启动失败回滚后仍保留，不能因 devices 清空而丢失失败分母。
     *
     * @param target 目标设备数
     * @param attempted 已尝试连接数
     * @param succeeded 初次连接成功数
     * @param failed 初次连接失败数
     * @param rampStartedAt 连接爬坡起点
     * @param allConnectedAt 全部目标连接成功时刻；未全连为空
     * @param rampDurationMillis 全连耗时；未全连为 -1
     */
    public record ConnectionQualification(int target, int attempted, int succeeded, int failed,
                                          Instant rampStartedAt, Instant allConnectedAt,
                                          long rampDurationMillis) {
    }

    /**
     * 发生器持续资源资格事实；上限与比例由运行脚本根据冻结阈值判定，不在代码里事后调整。
     *
     * @param sampleCount 资源样本数
     * @param maxSampleGapMillis 相邻样本最大间隔
     * @param maxSampleGapStartedAt 最大样本间隔的墙钟起点；仅一个样本时为空
     * @param maxSampleGapEndedAt 最大样本间隔的墙钟终点；仅一个样本时为空
     * @param incompleteSamples 缺失 CPU/RSS/FD 任一指标的样本数
     * @param peakProcessCpuLoad 进程 CPU 峰值（0~1）
     * @param maxFiveSecondCpuAverage 最高约 5 秒 CPU 滚动平均（0~1），用于“持续 CPU≤70%”判定
     * @param peakHeapUsedBytes 堆使用峰值
     * @param heapMaxBytes 最大堆
     * @param peakRssBytes RSS 峰值
     * @param peakThreadCount 活动线程峰值
     * @param peakOpenFileDescriptors 打开 FD 峰值
     * @param maxFileDescriptors FD 上限
     */
    public record GeneratorResourceQualification(
            long sampleCount, long maxSampleGapMillis,
            Instant maxSampleGapStartedAt, Instant maxSampleGapEndedAt, long incompleteSamples,
            double peakProcessCpuLoad, double maxFiveSecondCpuAverage,
            long peakHeapUsedBytes, long heapMaxBytes,
            long peakRssBytes, long peakThreadCount, long peakOpenFileDescriptors,
            long maxFileDescriptors) {
    }

    /**
     * MQTT {@code up/command/{commandId}/reply} 的模拟成功终态载荷。
     *
     * @param messageId 设备生成的 UUIDv7；同一 commandId 重投时保持不变
     * @param occurredAt 首次模拟执行完成时刻
     * @param status 命令状态，自动回复固定为 {@code SUCCESS}
     * @param output 命令输出 JSON，自动模式固定为空对象
     * @param errorCode 失败错误码；成功时为空
     * @param message 失败说明；成功时为空
     */
    private record CommandReply(UUID messageId, Instant occurredAt, String status, Map<String, Object> output,
                                String errorCode, String message) {
    }

    /** 单台运行设备的内部状态；凭据在完成连接后即不再保留。 */
    private static final class RunningDevice {

        /** 设备 Topic 段。 */
        private final String deviceKey;

        /** 项目 Topic 段。 */
        private final String projectKey;

        /** 已认证的 MQTT 连接。 */
        private final MqttDeviceClient client;

        /** 当前随机游走温度。 */
        private volatile double temperature;

        /** 是否对该运行批次收到的命令自动发布成功终态。 */
        private final boolean autoReplyCommands;

        /** 是否按网关模拟：订阅 down/config 并批量上报子设备属性。 */
        private final boolean gateway;

        /** 已应用配置版本；网关本地轮询场景使用。 */
        private volatile int appliedVersion;

        /** 已应用的配置点位；网关本地轮询场景使用。 */
        private volatile List<ConfigPoint> configPoints = List.of();

        /** 已执行命令的首份终态回复；QoS 1 重投只能重发，不能重复模拟执行，messageId 保持稳定用于去重。 */
        private final Map<String, CommandReply> completedReplies = new ConcurrentHashMap<>();

        /** 设备侧 OTA 运行时；仅在 {@code simulator.ota.enabled=true} 时非空。 */
        private volatile OtaDeviceRuntime otaRuntime;

        /** 周期发布任务。 */
        private volatile ScheduledFuture<?> task;

        /** 当前一轮风暴完成所需连接代次；零表示尚未注入故障。 */
        private volatile long expectedReconnectGeneration;

        /** 上一次发布触发时刻（纳秒）；用于计算相邻两次发布的调度偏差，首次发布前为 0。 */
        private volatile long lastPublishNanos;

        /** stop 或启动回滚后关闭；已排队的周期/入站任务不得再发布或修改本轮 manifest。 */
        private volatile boolean acceptingWork = true;

        /** 创建已连接设备状态。 */
        private RunningDevice(String deviceKey, String projectKey, MqttDeviceClient client, double temperature,
                              boolean autoReplyCommands, boolean gateway) {
            this.deviceKey = deviceKey;
            this.projectKey = projectKey;
            this.client = client;
            this.temperature = temperature;
            this.autoReplyCommands = autoReplyCommands;
            this.gateway = gateway;
        }
    }

    /** 网关配置中的一个点位，用于本地轮询生成子设备属性。 */
    private record ConfigPoint(String subDeviceKey, String propertyKey, String dataType) {
    }
}
