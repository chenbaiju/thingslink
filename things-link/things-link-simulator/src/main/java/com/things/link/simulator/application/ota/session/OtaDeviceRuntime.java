package com.things.link.simulator.application.ota.session;

import com.things.link.shared.id.Uuid7;
import com.things.link.simulator.application.MqttDeviceClient;
import com.things.link.simulator.application.ota.OtaArtifactSource;
import com.things.link.simulator.application.ota.OtaClock;
import com.things.link.simulator.application.ota.OtaDigests;
import com.things.link.simulator.application.ota.OtaExecutionResult;
import com.things.link.simulator.application.ota.OtaFaultPlan;
import com.things.link.simulator.application.ota.OtaJournalRecord;
import com.things.link.simulator.application.ota.OtaReasonCode;
import com.things.link.simulator.application.ota.OtaStage;
import com.things.link.simulator.application.ota.contract.OtaDeviceCommitPermitCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceCommitReceiptCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceHealthCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceInstallStopOperationCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceInstallStopReportCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceInstallStopStatusQueryCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceInstallStopStatusReportCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceJobProgressCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceTopics;
import com.things.link.simulator.application.ota.contract.OtaDeviceTransport;
import com.things.link.simulator.infrastructure.ota.FileOtaAcceptedDownloadResponseStore;
import com.things.link.simulator.infrastructure.ota.FileOtaStateJournal;
import com.things.link.simulator.infrastructure.ota.HttpRangeArtifactSource;
import com.things.link.simulator.infrastructure.ota.MqttOtaDeviceTransport;
import com.things.link.simulator.infrastructure.ota.SystemOtaClock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 单台设备的 OTA 运行时：把已交付的设备状态机接到真实连接生命周期上，并裁决「停止先赢 / 安装先赢」。
 *
 * <p><b>每台设备一个目录：</b>本类在 {@code <otaDir>/<deviceKey>/} 下持有三个真实文件：
 * {@code staging.bin}（分片暂存区）、{@code ota.log}（fsync 的耐久日志）与
 * {@code accepted-download}（已接纳下载响应身份的耐久单槽记录），另加一个 {@code boot-id}
 * 记录设备启动身份。目录名与 Topic 段使用同一套字符集校验，运行方即使直接传入 deviceKey 也无法借
 * {@code ..} 写出基目录。</p>
 *
 * <p><b>启动会话为什么必须在真实重启边界轮换（ADR0131，登记债 D-156）：</b>ADR0131 第57行冻结
 * 「首条 {@code VERIFYING} 冻结本次 bootId；{@code INSTALLING}/{@code REBOOTING} 保持该 bootId，
 * {@code HEALTH_CHECKING} 必须为新的 bootId」，因为健康观察只有在设备真的重启后才可信。修复前本类
 * 只在构造时生成一次 {@code boot-id} 并跨重启复用，于是运行时自己发出的 {@code HEALTH_CHECKING}
 * 会被平台以 {@code BOOT_SESSION_MISMATCH} 拒绝并转入 {@code RECOVERY_REQUIRED}。现在本类在
 * {@code REBOOTING} 帧发出之后、{@code HEALTH_CHECKING} 证据构造之前，生成并耐久落盘一个新的启动身份：
 * 前三阶段仍带 {@code VERIFYING} 冻结的原身份，健康阶段带重启后的新身份；后续只读查询与停止报告
 * 读到的也是这个最新身份。进程重启本身不会轮换身份——只有真正越过一次安装后重启边界才会，
 * 因此「重启但未发生新安装」仍然复用持久化的最新取值，与平台对账语义一致。</p>
 *
 * <p><b>裁决只依据耐久日志，不依据内存：</b>平台下发安装前停止命令（或只读状态查询）时，本类读取
 * 同一份 {@link FileOtaStateJournal} 并只看已 fsync 的事实：</p>
 * <ul>
 *   <li><b>还没有进入安全阶段</b>（日志里没有 {@code INSTALLING}/{@code REBOOTING}/
 *       {@code HEALTH_CHECKING}/{@code CONFIRMING}，也没有 {@link OtaReasonCode#INSTALL_OUTCOME_UNKNOWN}）：
 *       停止先赢。设备追加一条带 {@link OtaReasonCode#CANCELLED_BEFORE_SAFETY_STAGE} 的耐久记录，
 *       发布 {@code STOPPED} 操作报告，并从此不再发布任何进度。</li>
 *   <li><b>已经进入安全阶段，或安装结果未知</b>：安装先赢。设备<b>不</b>声称干净取消，只追加一条
 *       {@link OtaReasonCode#CANCEL_REFUSED_AFTER_SAFETY_STAGE} 观察，并发布 {@code INSTALL_WON}
 *       报告；安装日志里的授权标识只可能是平台真实下发过的 {@code authorizationId}，
 *       没有真实授权时数组保持为空，绝不伪造授权。</li>
 * </ul>
 *
 * <p><b>本类不做什么：</b>不验签（没有信任锚）、不伪造健康/自检证据、不产生任何「平台已派发」的事实。
 * 安装窗口打开后的擦写、切槽、看门狗与原子提交属于 G3 硬件验收；模拟器只报告它能证明的阶段，
 * 默认连 {@code HEALTH_CHECKING} 都不上报（见 {@link OtaSimulatorEvidence}）。</p>
 *
 * <p><b>并发边界：</b>停止命令与下载响应可能来自不同线程。落在下载执行<b>期间</b>到达的停止命令不会
 * 打断正在取字节的那一次执行——设备只能在分片边界停下来；越过安全阶段边界后到达的停止按真实阶段裁决，
 * 恢复责任留给平台对账（ADR0137）。</p>
 */
public final class OtaDeviceRuntime {

    /** 暂存区文件名；同一设备目录跨重启复用，是断点续传的前提。 */
    private static final String STAGING_FILE_NAME = "staging.bin";

    /** 耐久日志文件名。 */
    private static final String JOURNAL_FILE_NAME = "ota.log";

    /** 启动身份文件名；只在真实重启边界轮换，跨进程重启复用最后一次持久化取值。 */
    private static final String BOOT_ID_FILE_NAME = "boot-id";

    /** 启动身份的原子替换临时文件名；避免停止报告线程读到半写内容。 */
    private static final String BOOT_ID_TEMP_FILE_NAME = "boot-id.tmp";

    /** 已接纳下载响应身份的单槽文件名；用于跨重启识别 QoS 1 重投递（债务 D-159）。 */
    private static final String ACCEPTED_RESPONSE_FILE_NAME = "accepted-download";

    /** 平台闭集允许上报的进度阶段。 */
    private static final Set<String> PROGRESS_STAGES =
            Set.of("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING");

    /** 停止先赢后的写入状态：设备已 fsync 取消记录并停止取字节。 */
    private static final String WRITE_STATE_QUIESCENT = "QUIESCENT";

    /** 安装先赢后的写入状态：设备无法证明擦写/切槽已经静止。 */
    private static final String WRITE_STATE_WRITING = "WRITING";

    /** 平台报告状态：停止先赢。 */
    private static final String STATUS_STOPPED = "STOPPED";

    /** 平台报告状态：安装先赢。 */
    private static final String STATUS_INSTALL_WON = "INSTALL_WON";

    /** 项目短标识。 */
    private final String projectKey;

    /** 设备短标识。 */
    private final String deviceKey;

    /** 设备目录：暂存区、日志与启动身份都落在这里。 */
    private final Path deviceDirectory;

    /** 会话：负责下载链路与上行编码。 */
    private final OtaDeviceSession session;

    /** 真实文件日志；停止裁决在同一份日志上读取与追加。 */
    private final FileOtaStateJournal journal;

    /** 已接纳下载响应身份的耐久单槽存储；会话据此识别重投递与跨重启重投递。 */
    private final FileOtaAcceptedDownloadResponseStore acceptedResponses;

    /** 已认证连接的传输端口；订阅与发布都经它，保证 Topic/QoS 语义单一来源。 */
    private final OtaDeviceTransport transport;

    /** 模拟器声明的设备证据事实。 */
    private final OtaSimulatorEvidence evidence;

    /** 默认是否允许声明健康/自检事实；false 时设备停在 REBOOTING，不伪造 HEALTH_CHECKING。 */
    private final boolean assertHealth;

    /** 注入时钟；生产为墙钟，测试为推进式假时钟。 */
    private final OtaClock clock;

    /** 下一次下载执行注入的故障计划；默认不注入。 */
    private final OtaFaultPlan faultPlan;

    /** 已收到但尚未被只读查询引用的停止操作：operationId → 已解析操作。 */
    private final Map<UUID, OtaDeviceInstallStopOperationCodec.Operation> knownStopOperations =
            new ConcurrentHashMap<>();

    /** D-161：本运行时实例是否已经真实触发过一次重投申请（每个真实重启实例至多一次）。 */
    private final java.util.concurrent.atomic.AtomicBoolean resumeRequested =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 平台真实下发过的下载授权：尝试身份 → authorizationId；用于安装先赢时的安装日志。 */
    private final Map<AttemptKey, UUID> knownAuthorizations = new ConcurrentHashMap<>();

    /**
     * 已耐久提交且完整摘要核对通过的尝试：尝试身份 → 已安装事实。
     *
     * <p><b>为什么必须单独记住「已提交」而不是复用 {@link #knownAuthorizations}：</b>收到下载响应只证明
     * 平台授权过一次下载，不证明设备真的装上了镜像。提交回执是「我已提交」的最终声明，只有设备自己
     * 越过安装后重启边界并核对完整摘要后才允许产生（见 {@link #onCommitPermit}）。</p>
     */
    private final Map<AttemptKey, InstalledAttempt> committedAttempts = new ConcurrentHashMap<>();

    /**
     * 创建运行时并准备好设备专属目录；目录与启动身份在构造时落盘，日志与暂存区在首次写入时创建。
     *
     * @param projectKey 项目短标识
     * @param deviceKey 设备短标识
     * @param client 已认证的单设备 MQTT 连接
     * @param otaDirectory 本设备的 OTA 目录
     * @param evidence 模拟器声明的设备证据事实
     * @param assertHealth 是否允许声明健康/自检事实（真实硬件或受控场景才应为 true）
     * @param allowInsecureLoopback 是否允许本机回环明文下载地址（仅受控测试）
     */
    public OtaDeviceRuntime(String projectKey, String deviceKey, MqttDeviceClient client, Path otaDirectory,
                            OtaSimulatorEvidence evidence, boolean assertHealth, boolean allowInsecureLoopback) {
        this(projectKey, deviceKey, client, otaDirectory, evidence, assertHealth, allowInsecureLoopback,
                new SystemOtaClock(), HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
    }

    /**
     * 创建运行时并注入时钟与取字节端口工厂。
     *
     * <p><b>为什么是工厂而不是单个实例：</b>每次下载执行都要拿到一个端口，而端口自身可能持有连接池或
     * 分片上限。工厂让调用方在「真实 HTTP Range」与「受控测试端口」之间切换，而不必让运行时知道分片大小
     * 这类传输细节。生产传 {@code bytes -> new HttpRangeArtifactSource()}（实现默认分片）。</p>
     *
     * @param projectKey 项目短标识
     * @param deviceKey 设备短标识
     * @param client 已认证的单设备 MQTT 连接
     * @param otaDirectory 本设备的 OTA 目录
     * @param evidence 模拟器声明的设备证据事实
     * @param assertHealth 是否允许声明健康/自检事实（真实硬件或受控场景才应为 true）
     * @param allowInsecureLoopback 是否允许本机回环明文下载地址（仅受控测试）
     * @param clock 执行时钟；生产为 {@link SystemOtaClock}
     * @param artifactSourceFactory 取字节端口工厂；每次下载执行调用一次
     * @param faultPlan 下一次下载执行注入的故障计划；生产为 {@link OtaFaultPlan#NONE}
     */
    public OtaDeviceRuntime(String projectKey, String deviceKey, MqttDeviceClient client, Path otaDirectory,
                            OtaSimulatorEvidence evidence, boolean assertHealth, boolean allowInsecureLoopback,
                            OtaClock clock, Supplier<OtaArtifactSource> artifactSourceFactory,
                            OtaFaultPlan faultPlan) {
        this.projectKey = requireSegment(projectKey, "projectKey");
        this.deviceKey = requireSegment(deviceKey, "deviceKey");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.assertHealth = assertHealth;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.faultPlan = Objects.requireNonNull(faultPlan, "faultPlan");
        this.deviceDirectory = Objects.requireNonNull(otaDirectory, "otaDirectory").toAbsolutePath().normalize();
        createDeviceDirectory(this.deviceDirectory);
        this.transport = new MqttOtaDeviceTransport(Objects.requireNonNull(client, "client"));
        this.journal = new FileOtaStateJournal(this.deviceDirectory.resolve(JOURNAL_FILE_NAME));
        this.acceptedResponses = new FileOtaAcceptedDownloadResponseStore(
                this.deviceDirectory.resolve(ACCEPTED_RESPONSE_FILE_NAME));
        Path stagingFile = this.deviceDirectory.resolve(STAGING_FILE_NAME);
        this.session = new OtaDeviceSession(this.projectKey, this.deviceKey, transport, clock, stagingFile,
                Objects.requireNonNull(artifactSourceFactory, "artifactSourceFactory").get(), journal,
                acceptedResponses, this::progressFor, allowInsecureLoopback);
        // 故障是场景输入：在订阅任何下行之前就交给会话，使第一次执行即可复现目标状态。
        this.session.setFaultPlan(faultPlan);
        // 启动身份必须在受理任何下行之前就耐久落盘：报告里的 acceptedBootId 必须跨重启可对账，
        // 若等到发布时才生成，掉电中断后的重启就会得到一个新身份，平台无法判断是否同一次停止。
        bootId();
    }

    /**
     * 订阅四个 OTA 下行 Topic 并绑定到本设备运行时。
     *
     * <p>订阅与命令/配置订阅共用同一条已认证连接，EMQX ACL 无法被绕过；每条回调都重新核对完整 Topic，
     * Broker 若把别的设备报文投进来，回调直接失败关闭而不是执行。</p>
     *
     * <p><b>D-161 重投触发：</b>真实进程重启（或重连）后，如果本目录耐久受理记录存在且耐久日志显示该尝试
     * 尚未收束，运行时会在<b>不等任何新 available 通知</b>的情况下自己重投一次同一尝试的下载申请；
     * 没有记录、记录与日志不符或尝试已收束时绝不发报文。每个运行时实例至多触发一次，重复调用
     * {@code start()} 不会放大成第二次申请（平台侧登记本身也是幂等的）。</p>
     */
    public void start() {
        if (!resumeRequested.compareAndSet(false, true)) return;
        session.observeInterruptedInstallation();
        subscribe(OtaDeviceTopics.AVAILABLE, session::onAvailable);
        subscribe(OtaDeviceTopics.DOWNLOAD_RESPONSE, this::onDownloadResponse);
        subscribe(OtaDeviceTopics.COMMIT_PERMIT, this::onCommitPermit);
        subscribe(OtaDeviceTopics.INSTALL_STOP_OPERATION, this::onInstallStopOperation);
        subscribe(OtaDeviceTopics.INSTALL_STOP_STATUS_QUERY, this::onInstallStopStatusQuery);
        session.requestAcceptedResume();
    }

    /**
     * 处理平台下载响应，记忆平台真实下发的授权并记录本次执行结论。
     *
     * <p>重投递与跨重启识别由 {@link OtaDeviceSession#onDownloadResponse(byte[])} 依据本设备目录里的
     * {@code accepted-download} 记录与耐久日志裁决：身份一致且尝试未收束时从耐久偏移续传，已收束时
     * 幂等空操作（本方法只重放结论、不产生第二次副作用），无法证明时失败关闭。</p>
     *
     * @param payload 下载响应原始字节
     * @return 本次执行结论
     */
    public OtaExecutionResult onDownloadResponse(byte[] payload) {
        OtaExecutionResult result = session.onDownloadResponse(payload);
        // 故障是场景输入而不是设备状态：本次执行已经消费它，后续执行必须回到「不注入」，
        // 否则「上次掉过电」会被当成持久设备状态重放，恢复路径永远无法前进。
        session.setFaultPlan(OtaFaultPlan.NONE);
        knownAuthorizations.put(new AttemptKey(result.attemptNo(), result.manifestSha256()),
                result.authorizationId());
        // 只有「结论为COMMITTED且已确认摘要等于完整artifact摘要」才记入已提交尝试；这是后续
        // 提交回执唯一允许使用的设备事实来源，避免把「下载成功」冒充成「已经提交」。
        if (result.claimsArtifactVerified()) {
            committedAttempts.put(new AttemptKey(result.attemptNo(), result.manifestSha256()),
                    new InstalledAttempt(result.jobId(), result.attemptNo(), result.authorizationId(),
                            result.manifestSha256(), result.artifactSha256(), result.artifactSize()));
        }
        return result;
    }

    /**
     * 处理平台安装前停止命令，并按耐久日志裁决停止先赢还是安装先赢。
     *
     * @param payload 停止操作原始字节
     * @return 本次发布的报告状态（{@code STOPPED} 或 {@code INSTALL_WON}）
     * @throws IllegalArgumentException 操作字节不是合法规范合同字节时
     */
    public String onInstallStopOperation(byte[] payload) {
        OtaDeviceInstallStopOperationCodec.Decoded decoded = OtaDeviceInstallStopOperationCodec.decode(payload);
        return acceptStopOperation(decoded.value(), decoded.sha256());
    }

    /**
     * 处理平台只读停止状态查询，并按同一份耐久日志重放当前真实结论。
     *
     * <p>查询不刷新操作或作业期限，本方法也不推进任何设备状态：它只把同一个耐久裁决重新编码一次，
     * 报告序号取追加停止记录后的日志修订，从而满足平台「同一次查询只接受首份报告及其精确重放」的约束。</p>
     *
     * @param payload 状态查询原始字节
     * @return 本次发布的状态报告状态（{@code STOPPED} 或 {@code INSTALL_WON}）
     * @throws IllegalArgumentException 查询字节不是合法规范合同字节时
     * @throws IllegalStateException 查询引用的停止操作尚未在本设备内存中时
     */
    public String onInstallStopStatusQuery(byte[] payload) {
        OtaDeviceInstallStopStatusQueryCodec.Query query =
                OtaDeviceInstallStopStatusQueryCodec.decode(payload).value();
        OtaDeviceInstallStopOperationCodec.Operation operation = knownStopOperations.get(query.operationId());
        if (operation == null) {
            // 没有原操作字节就无法构造合法的停止基线证据（停止基线摘要只存在于操作报文里）：
            // 静默不回复比自造一份报告诚实，平台会按查询冷却与次数上限继续观察。
            throw new IllegalStateException("状态查询引用的停止操作尚未在本设备内存中");
        }
        if (!operation.jobId().equals(query.jobId()) || operation.attemptNo() != query.attemptNo()
                || !operation.manifestSha256().equals(query.manifestSha256())) {
            throw new IllegalStateException("状态查询与原停止操作身份不一致");
        }
        StopDecision fenced = appendStopDecision(operation, journalDecision());
        OtaDeviceInstallStopStatusReportCodec.Report report = new OtaDeviceInstallStopStatusReportCodec.Report(
                OtaDeviceInstallStopStatusReportCodec.CONTRACT_VERSION, operation.operationId(), query.jobId(),
                query.attemptNo(), query.manifestSha256(), query.operationSha256(), Uuid7.generate(),
                journalRevision(), bootId(), fenced.status(),
                reportEvidence(operation, fenced, query.operationSha256()), query.queryId(), query.queryNonce());
        session.publishInstallStopStatusReport(report);
        return report.status();
    }

    /**
     * 消费平台唯一提交许可，并在全部设备事实自洽时发布真实提交回执（ADR0132，关闭 D-155）。
     *
     * <p><b>为什么回执必须由运行时而不是状态机产生：</b>许可只说明「平台允许你提交」，它不能替代
     * 设备自己的安装事实。运行时持有耐久日志、已核对摘要的 artifact 尺寸与<b>当前</b>启动身份，
     * 因此只有它能判断这次许可是不是指向本机真的已经提交的那一次尝试。状态机只模拟
     * 下载/验签/安装/重启/健康这条本地阶段序列；<b>平台可见的权威提交</b>是本方法在收到合法许可后
     * 发出的回执。真实设备同样不得在许可到达之前提交，本类的本地 {@code COMMITTED} 阶段只是让模拟器
     * 走到「已经安装完成、等待许可」这一步。</p>
     *
     * <p><b>许可匹配规则（全部必须成立才发布回执）：</b></p>
     * <ol>
     *   <li><b>尝试身份必须已在耐久事实里提交。</b>回执使用的授权/清单来自设备真实收到的下载响应并
     *       且执行结论是 {@code COMMITTED}。没有这条记忆时忽略许可——设备从未下载或从未通过完整摘要
     *       核对时声称提交就是伪造。</li>
     *   <li><b>jobId 必须一致。</b>同一尝试号与清单摘要可以被平台复用到别的作业；作业身份不符意味着
     *       许可指向另一次派发，忽略。</li>
     *   <li><b>authorizationId 必须一致。</b>授权是平台真实封存过的下载权限；授权不符意味着许可
     *       对应另一次授权，忽略。</li>
     *   <li><b>bootId 必须是设备当前启动身份。</b>许可绑定平台确认过的候选启动会话；设备若已经再次
     *       重启（或还没有重启）就与许可的会话不符，此时提交会破坏 ADR0131 的启动会话连续性，忽略。</li>
     *   <li><b>许可必须未过期。</b>{@code expiresAt} 已过（以本机时钟判断）时不得再提交，忽略；
     *       平台也不会接受过期窗口内的成功。</li>
     *   <li><b>许可声明的目标必须与本机已安装事实一致。</b>{@code securityVersion}/{@code artifactSha256}/
     *       {@code targetSlot} 任一不符都说明许可指向别的镜像，忽略。</li>
     *   <li><b>设备必须能证明安装后自检与看门狗健康。</b>提交回执是 {@code HEALTH_CHECKING} 形态的最终
     *       结论；未显式声明健康事实时不构造证据，忽略。</li>
     * </ol>
     *
     * <p>以上任何一条不成立都<b>不发布任何报文</b>：平台因此永远不会看到一次伪造的提交，只能看到
     * 真实许可驱动的真实回执。</p>
     *
     * @param payload 平台提交许可原始字节
     * @return 已发布回执；许可不匹配本机事实时为 {@code null}（静默忽略，不伪造）
     * @throws IllegalArgumentException 许可字节不是合法规范合同时
     */
    public OtaDeviceCommitReceiptCodec.Receipt onCommitPermit(byte[] payload) {
        OtaDeviceCommitPermitCodec.Permit permit = OtaDeviceCommitPermitCodec.decode(payload).value();
        InstalledAttempt installed = committedAttempts.get(new AttemptKey(permit.attemptNo(), permit.manifestSha256()));
        if (installed == null) {
            return null;
        }
        if (!installed.jobId().equals(permit.jobId())) {
            return null;
        }
        if (!installed.authorizationId().equals(permit.authorizationId())) {
            return null;
        }
        if (!assertHealth) {
            // 自检与看门狗是硬件事实：没有显式声明时设备不能声称安装后健康，也就不能产生提交回执。
            return null;
        }
        UUID currentBootId = bootId();
        if (!permit.bootId().equals(currentBootId)) {
            return null;
        }
        if (permit.expiresAt() <= clock.now().getEpochSecond()) {
            return null;
        }
        OtaDeviceJobProgressCodec.Evidence evidence = this.evidence.commitEvidence(installed.artifactSha256(),
                installed.artifactSize(), true);
        if (permit.securityVersion() != evidence.securityVersion()
                || !permit.artifactSha256().equals(evidence.artifactSha256())
                || !permit.targetSlot().equals(evidence.targetSlot())) {
            return null;
        }
        OtaDeviceCommitReceiptCodec.Receipt receipt = new OtaDeviceCommitReceiptCodec.Receipt(
                OtaDeviceCommitReceiptCodec.CONTRACT_VERSION, permit.jobId(), permit.attemptNo(),
                permit.authorizationId(), permit.manifestSha256(), Uuid7.generate(), permit.permitId(),
                currentBootId, evidence);
        session.publishCommitReceipt(receipt);
        return receipt;
    }

    /**
     * @return 本设备耐久日志绝对路径
     */
    public Path journalFile() {
        return journal.file();
    }

    /**
     * @return 本设备「已接纳下载响应身份」记录文件绝对路径
     */
    public Path acceptedResponseFile() {
        return acceptedResponses.file();
    }

    /**
     * @return 本设备暂存区绝对路径
     */
    public Path stagingFile() {
        return deviceDirectory.resolve(STAGING_FILE_NAME).toAbsolutePath();
    }

    /**
     * @return 本次运行的设备目录绝对路径
     */
    public Path deviceDirectory() {
        return deviceDirectory;
    }

    /**
     * 读取耐久启动身份；首次调用时生成并落盘，之后每次读到的是最后一次持久化取值。
     *
     * <p>因此同一设备目录跨进程重启得到同一身份；只有在真实重启边界（见
     * {@link #progressFor}）才会被 {@link #rotateBootId()} 替换为新身份。</p>
     *
     * @return 当前启动身份
     */
    public UUID bootId() {
        Path file = deviceDirectory.resolve(BOOT_ID_FILE_NAME);
        if (Files.exists(file)) {
            try {
                return UUID.fromString(Files.readString(file, StandardCharsets.UTF_8).trim());
            } catch (IOException failure) {
                throw new UncheckedIOException("设备启动身份读取失败", failure);
            }
        }
        UUID bootId = Uuid7.generate();
        persistBootId(bootId);
        return bootId;
    }

    /**
     * 生成并耐久落盘一个新的启动身份，返回新身份。
     *
     * <p><b>为什么在真实重启边界轮换（ADR0131，登记债 D-156）：</b>设备完成安装并重启后，健康观察
     * 属于新一次启动；若继续沿用安装前冻结的 bootId，平台会以 {@code BOOT_SESSION_MISMATCH} 拒绝
     * 健康进度。新身份先落盘再用于报文，使「设备自己声明的重启事实」与「平台可对账的持久身份」
     * 是同一个值；后续只读查询与停止报告也自然读到最新身份。</p>
     *
     * @return 新的启动身份
     */
    private UUID rotateBootId() {
        UUID bootId = Uuid7.generate();
        persistBootId(bootId);
        return bootId;
    }

    /**
     * 原子写入启动身份。
     *
     * <p>停止命令与下载执行可能来自不同线程（见类注释的并发边界），若直接原地截断重写，并发的
     * {@link #bootId()} 可能读到空文件或半写内容。先写同目录临时文件再原子替换，使读者只会看到
     * 替换前或替换后的完整身份。</p>
     *
     * @param bootId 要持久化的启动身份
     */
    private void persistBootId(UUID bootId) {
        Path file = deviceDirectory.resolve(BOOT_ID_FILE_NAME);
        Path temporary = deviceDirectory.resolve(BOOT_ID_TEMP_FILE_NAME);
        try {
            Files.writeString(temporary, bootId.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("设备启动身份写入失败", failure);
        }
    }

    /**
     * 显式发布一份健康确认（心跳）。
     *
     * <p><b>为什么运行时只转发、不构造：</b>健康窗口、连续序号与自检结论都是设备真实事实；模拟器
     * 没有受保护计数器与真实自检，{@link OtaSimulatorEvidence} 因此把自检/健康定义为「必须由掌握该
     * 事实的调用方显式构造」的输入。本方法只是把 {@link OtaDeviceSession#publishHealth} 暴露给持有
     * 设备连接的调用方，运行时绝不自行产生或补造健康心跳。</p>
     *
     * @param health 平台合同形态的健康确认，由调用方按计划窗口与真实观察构造
     * @return 同一 MQTT 交付收据；未获 PUBACK 时异常完成
     */
    public CompletableFuture<Void> publishHealth(OtaDeviceHealthCodec.Health health) {
        return session.publishHealth(Objects.requireNonNull(health, "health"));
    }

    /**
     * 只发布本策略明确允许的阶段；默认不声明健康事实。
     *
     * <p><b>为什么轮换点恰好在这里（ADR0131，登记债 D-156）：</b>{@link OtaDeviceSession}
     * 按结果里的转换顺序逐条调用本方法，因此进入 {@code HEALTH_CHECKING} 时
     * {@code INSTALLING}/{@code REBOOTING} 两帧已经带着 {@code VERIFYING} 冻结的原身份发出去了。
     * 此刻生成新身份，既不违反「REBOOTING 必须保持原 bootId」，又保证紧随其后的
     * {@code HEALTH_CHECKING} 进度与健康证据都带新身份。轮换不依赖 {@code assertHealth}：
     * 设备是否声明健康是事实声明策略，重启本身是真实设备事件；即使不报健康，持久身份也必须已换，
     * 后续停止报告与只读查询才能对上重启后的设备。反过来，若执行停在 {@code REBOOTING}
     * （例如该边界掉电），本方法不会再被 {@code HEALTH_CHECKING} 调用，身份保持不变——设备无法证明
     * 重启已完成时不得声称新会话，这与「安装结果未知」的保守对账方向一致。</p>
     */
    private OtaDeviceJobProgressCodec.Progress progressFor(OtaJournalRecord transition, OtaExecutionResult result,
                                                          long progressSeq) {
        String stage = transition.stage().name();
        if (!PROGRESS_STAGES.contains(stage)) {
            return null;
        }
        boolean healthChecking = "HEALTH_CHECKING".equals(stage);
        if (healthChecking) {
            // 真实重启边界：REBOOTING 帧已按原身份发出，此处换上新会话再做健康证据。
            rotateBootId();
        }
        if (!assertHealth && healthChecking) {
            // 自检与看门狗是硬件事实：没有被显式告知时模拟器绝不上报该阶段。
            return null;
        }
        return new OtaDeviceJobProgressCodec.Progress(OtaDeviceJobProgressCodec.CONTRACT_VERSION, result.jobId(),
                result.attemptNo(), progressSeq, result.authorizationId(), result.manifestSha256(), stage, bootId(),
                evidence.stageEvidence(transition, result.artifactSha256(), result.artifactSize(), assertHealth));
    }

    /** 接纳一次停止操作：追加耐久裁决记录并发布操作报告。 */
    private String acceptStopOperation(OtaDeviceInstallStopOperationCodec.Operation operation, String operationSha256) {
        knownStopOperations.put(operation.operationId(), operation);
        StopDecision fenced = appendStopDecision(operation, journalDecision());
        OtaDeviceInstallStopReportCodec.Report report = new OtaDeviceInstallStopReportCodec.Report(
                OtaDeviceInstallStopReportCodec.CONTRACT_VERSION, operation.operationId(), operation.jobId(),
                operation.attemptNo(), operation.manifestSha256(), operationSha256, Uuid7.generate(),
                journalRevision(), bootId(), fenced.status(),
                reportEvidence(operation, fenced, operationSha256));
        session.publishInstallStopReport(report);
        return report.status();
    }

    /**
     * 按耐久日志裁决停止与安装谁先赢。
     *
     * <p>只看已 fsync 的记录：任何安全阶段出现都代表安装窗口已经打开；{@code INSTALL_OUTCOME_UNKNOWN}
     * 代表设备曾在安全阶段掉电、擦写/切槽结果不可证明。两种情况都不得声称干净取消。</p>
     *
     * @return 不可变裁决；日志为空时按「尚未进入安全阶段」处理
     */
    private StopDecision journalDecision() {
        List<OtaJournalRecord> records = journal.read();
        boolean safetyStageEntered = false;
        boolean outcomeUnknown = false;
        OtaJournalRecord latest = null;
        for (OtaJournalRecord record : records) {
            safetyStageEntered |= record.stage().isSafetyStage();
            outcomeUnknown |= record.reasonCode() == OtaReasonCode.INSTALL_OUTCOME_UNKNOWN;
            latest = record;
        }
        if (latest == null) {
            // 尚无任何耐久事实：设备从未开始该尝试，属于「没有进入安全阶段」。
            return new StopDecision(false, false, 0L, OtaDigests.EMPTY_SHA256, 1L, OtaDigests.EMPTY_SHA256, null);
        }
        return new StopDecision(safetyStageEntered, outcomeUnknown, latest.downloadedBytes(),
                latest.confirmedDigestSoFar(), latest.artifactSize(), latest.artifactSha256(),
                latest.stage().isSafetyStage() ? latest.stage() : null);
    }

    /**
     * 把裁决结果追加为一条耐久日志，并返回携带修订号的裁决快照。
     *
     * <p>停止先赢写 {@link OtaReasonCode#CANCELLED_BEFORE_SAFETY_STAGE}（终态 {@code CANCELLED}），
     * 安装先赢写 {@link OtaReasonCode#CANCEL_REFUSED_AFTER_SAFETY_STAGE}（终态 {@code SAFETY_PAUSED}）。
     * 停止记录使用「尚未取得字节」的哨兵身份，因为此时设备无法证明任何 artifact 进度；这正是
     * 「不伪造平台事实」的方向。</p>
     *
     * @param operation 平台下发的停止操作（提供尝试号与清单摘要）
     * @param decision 追加前的耐久裁决
     * @return 携带日志修订与授权事实的裁决快照
     */
    private StopDecision appendStopDecision(OtaDeviceInstallStopOperationCodec.Operation operation,
                                            StopDecision decision) {
        boolean installWon = decision.installWon();
        OtaStage stage = installWon
                ? (decision.safetyStage() == null ? OtaStage.INSTALLING : decision.safetyStage())
                : OtaStage.DOWNLOADING;
        OtaReasonCode reason = installWon
                ? OtaReasonCode.CANCEL_REFUSED_AFTER_SAFETY_STAGE
                : OtaReasonCode.CANCELLED_BEFORE_SAFETY_STAGE;
        OtaJournalRecord record = new OtaJournalRecord(stage, operation.attemptNo(), decision.downloadedBytes(),
                decision.confirmedDigest(), decision.artifactSha256(), decision.artifactSize(),
                operation.manifestSha256(), clock.now(), reason, !installWon);
        journal.append(record);
        return decision.withRevision(journalRevision(), installWon && decision.safetyStageEntered
                ? knownAuthorizations.get(new AttemptKey(operation.attemptNo(), operation.manifestSha256()))
                : null, installWon);
    }

    /**
     * 构造停止报告证据：停止先赢时只有停止日志，安装先赢时只有真实平台授权对应的安装日志。
     *
     * @param operation 平台停止操作（提供停止基线摘要）
     * @param fenced 已完成耐久追加的裁决快照
     * @param operationSha256 操作规范字节摘要
     * @return 平台合同形态的证据
     */
    private OtaDeviceInstallStopReportCodec.Evidence reportEvidence(
            OtaDeviceInstallStopOperationCodec.Operation operation, StopDecision fenced, String operationSha256) {
        OtaSimulatorEvidence.Hardware hardware = OtaSimulatorEvidence.hardware(evidence.model(),
                evidence.boardRevision(), evidence.bootloaderVersion());
        List<OtaSimulatorEvidence.StopOperation> stops = List.of();
        List<OtaSimulatorEvidence.InstallOperation> installs = List.of();
        if (fenced.installWon()) {
            if (fenced.installAuthorizationId() != null) {
                installs = List.of(OtaSimulatorEvidence.acceptedInstall(fenced.installAuthorizationId(), bootId(),
                        safetyRevision(fenced.journalRevision())));
            }
        } else {
            // 停止日志的接受修订就是刚追加的这条停止记录所在修订；它必然不超过 journalRevision。
            stops = List.of(OtaSimulatorEvidence.acceptedStop(operation.operationId(), operationSha256, bootId(),
                    fenced.journalRevision()));
        }
        return OtaSimulatorEvidence.reportEvidence(operation.stopBaselineSha256(), hardware, fenced.journalRevision(),
                fenced.installWon() ? WRITE_STATE_WRITING : WRITE_STATE_QUIESCENT, stops, installs);
    }

    /** 安装日志的接受修订：至少 1，且不超过报告时的日志修订。 */
    private long safetyRevision(long journalRevision) {
        long safetyRecords = journal.read().stream().filter(record -> record.stage().isSafetyStage()).count();
        return Math.max(1L, Math.min(journalRevision, Math.max(1L, safetyRecords)));
    }

    /** @return 当前耐久日志修订号（记录条数；空日志为 0） */
    private long journalRevision() {
        return journal.read().size();
    }

    /** 订阅一个下行模板并复核回调 Topic 与本设备身份精确匹配。 */
    private void subscribe(String template, Consumer<byte[]> handler) {
        String topic = OtaDeviceTopics.forDevice(template, projectKey, deviceKey);
        transport.subscribe(topic, (receivedTopic, payload) -> {
            if (!OtaDeviceTopics.matches(template, projectKey, deviceKey, receivedTopic)) {
                throw new IllegalStateException("OTA 下行 Topic 与本设备身份不匹配: " + receivedTopic);
            }
            handler.accept(payload);
        });
    }

    /** 创建设备目录；不存在的祖先目录一并创建，环境错误直接上抛而不伪装成设备裁决。 */
    private static void createDeviceDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new UncheckedIOException("无法创建 OTA 设备目录", failure);
        }
    }

    /** 校验 Topic 段字符集；与 {@link OtaDeviceTopics#forDevice} 使用同一套冻结规则。 */
    private static String requireSegment(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException(field + " 必须匹配 OTA Topic 标识符字符集");
        }
        return value;
    }

    /** 一次停止裁决的不可变投影；{@code journalRevision} 只在耐久追加后由 {@link #withRevision} 填入。 */
    private record StopDecision(boolean safetyStageEntered, boolean outcomeUnknown, long downloadedBytes,
                                String confirmedDigest, long artifactSize, String artifactSha256,
                                OtaStage safetyStage, long journalRevision, UUID installAuthorizationId,
                                boolean installWonAfterAppend) {

        /** 追加前的裁决：尚无日志修订与授权事实。 */
        private StopDecision(boolean safetyStageEntered, boolean outcomeUnknown, long downloadedBytes,
                             String confirmedDigest, long artifactSize, String artifactSha256, OtaStage safetyStage) {
            this(safetyStageEntered, outcomeUnknown, downloadedBytes, confirmedDigest, artifactSize, artifactSha256,
                    safetyStage, 0L, null, safetyStageEntered || outcomeUnknown);
        }

        /**
         * 安装先赢的条件：安全阶段已经出现，或安装结果不可证明。
         *
         * @return {@code true} 表示不得声称干净取消
         */
        private boolean installWon() {
            return safetyStageEntered || outcomeUnknown;
        }

        /**
         * @return 平台报告状态闭集内的取值
         */
        private String status() {
            return installWonAfterAppend ? STATUS_INSTALL_WON : STATUS_STOPPED;
        }

        /** 追加耐久记录后补全日志修订与平台真实授权。 */
        private StopDecision withRevision(long revision, UUID authorizationId, boolean installWon) {
            return new StopDecision(safetyStageEntered, outcomeUnknown, downloadedBytes, confirmedDigest, artifactSize,
                    artifactSha256, safetyStage, revision, authorizationId, installWon);
        }
    }

    /** 下载授权对应的尝试身份：尝试号与已签名清单摘要。 */
    private record AttemptKey(int attemptNo, String manifestSha256) {

        /** 冻结键字段，避免 null 键进入并发映射。 */
        private AttemptKey {
            Objects.requireNonNull(manifestSha256, "manifestSha256");
        }
    }

    /**
     * 一次已耐久提交尝试的不可变设备事实：提交回执只允许从这里构造。
     *
     * @param jobId 平台真实作业身份
     * @param attemptNo 平台真实尝试号
     * @param authorizationId 平台真实封存的下载授权
     * @param manifestSha256 平台冻结的已签名清单摘要
     * @param artifactSha256 本次真实核对通过的 artifact 摘要
     * @param artifactSize 本次真实安装的 artifact 字节数
     */
    private record InstalledAttempt(UUID jobId, int attemptNo, UUID authorizationId, String manifestSha256,
                                    String artifactSha256, long artifactSize) {
    }
}
