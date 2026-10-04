package com.things.link.simulator.application.ota.session;

import com.things.link.shared.id.Uuid7;
import com.things.link.simulator.application.ota.OtaAcceptedDownloadResponse;
import com.things.link.simulator.application.ota.OtaAcceptedDownloadResponseStore;
import com.things.link.simulator.application.ota.OtaArtifactSource;
import com.things.link.simulator.application.ota.OtaClock;
import com.things.link.simulator.application.ota.OtaDeviceExecution;
import com.things.link.simulator.application.ota.OtaDownloadAssignment;
import com.things.link.simulator.application.ota.OtaExecutionResult;
import com.things.link.simulator.application.ota.OtaFaultPlan;
import com.things.link.simulator.application.ota.OtaJournalRecord;
import com.things.link.simulator.application.ota.OtaReasonCode;
import com.things.link.simulator.application.ota.OtaStage;
import com.things.link.simulator.application.ota.OtaStateJournal;
import com.things.link.simulator.application.ota.contract.OtaDeviceAvailableNotificationCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceCommitReceiptCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceDownloadRequestCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceDownloadResponseCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceHealthCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceInstallStopReportCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceInstallStopStatusReportCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceJobProgressCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceTopics;
import com.things.link.simulator.application.ota.contract.OtaDeviceTransport;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 设备侧 OTA 会话语义：把平台下行报文路由到状态机，并把设备上行报文放回传输端口。
 *
 * <p><b>路由链路（真实顺序，不是臆造）：</b>
 * <ol>
 *   <li>平台下发 {@code down/ota/available}（ADR0128 的八字段通知，无地址无私钥）；</li>
 *   <li>设备生成新的 {@code requestId} 并发出 {@code up/ota/download/request}；</li>
 *   <li>平台回 {@code down/ota/download/response}（授权 + 清单 + 签名 + 临时地址）；</li>
 *   <li>设备校验该响应确实对应自己刚发出的请求，构造 {@link OtaDownloadAssignment}，
 *       运行 {@link OtaDeviceExecution}；</li>
 *   <li>设备把每个阶段转换交给 {@link ProgressReporter}，只有平台闭集允许的阶段才会上行
 *       {@code up/ota/progress}。</li>
 * </ol>
 * </p>
 *
 * <p><b>本类刻意不做的事：</b>不验签（没有信任锚）、不发明证据（{@link ProgressReporter} 提供）、
 * 不自动发布健康/停止报告（它们携带设备硬件与日志事实，必须由掌握这些事实的调用方显式构造）。
 * 真正的 OTA 派发仍需要受控签名器，本类不会让模拟器看起来像已经被派发。</p>
 *
 * <p><b>下载响应的重投递与跨重启识别（债务 D-159 设备半边）：</b>下载响应是 QoS 1 报文，Broker 可能重复
 * 投递；内存里的待处理申请在进程重启后也会全部丢失。因此首次接纳时把响应身份耐久写入
 * {@link OtaAcceptedDownloadResponseStore}（先落盘、后执行），重投递时凭该记录与 {@link OtaStateJournal}
 * 裁决：身份一致且尝试未收束就从耐久偏移续传，已收束则幂等空操作，无法证明则失败关闭。见
 * {@link #onDownloadResponse(byte[])} 的完整规则。</p>
 *
 * <p><b>停止协议的接线位置：</b>{@code down/ota/install-stop/operation} 与
 * {@code down/ota/install-stop/status/query} 的编解码器已经就绪，但「停止先赢 / 安装先赢」必须依据
 * 设备耐久日志裁决，而本类刻意不持有该裁决所需的证据策略与报告序号。接线由
 * {@code OtaDeviceRuntime} 完成：它读同一份 {@link OtaStateJournal}，调用
 * {@link #publishInstallStopReport} / {@link #publishInstallStopStatusReport} 发布真实报告，
 * 并由运行时先写耐久取消记录、再让状态机在恢复时幂等返回 {@code CANCELLED}。本类只保证
 * 「设备发送的字节与平台合同一致」，绝不替运行时捏造停止已经过耐久接纳。</p>
 */
public final class OtaDeviceSession {

    /** 项目短标识。 */
    private final String projectKey;

    /** 设备短标识。 */
    private final String deviceKey;

    /** 只搬运字节的传输端口。 */
    private final OtaDeviceTransport transport;

    /** 可注入时钟。 */
    private final OtaClock clock;

    /** 设备暂存区文件。 */
    private final Path stagingFile;

    /** 取 artifact 字节端口。 */
    private final OtaArtifactSource artifactSource;

    /** 耐久日志端口。 */
    private final OtaStateJournal journal;

    /** 已接纳下载响应身份的耐久单槽存储；用于识别同会话/跨重启的重投递。 */
    private final OtaAcceptedDownloadResponseStore acceptedResponses;

    /** 设备侧进度证据策略；本类不替它编造任何安全声明。 */
    private final ProgressReporter progressReporter;

    /** 下载响应解码器；回环明文例外只作用于本机受控联调。 */
    private final OtaDeviceDownloadResponseCodec responses;

    /**
     * 下一次执行使用的故障计划；默认 {@link OtaFaultPlan#NONE}。
     *
     * <p>故障是<b>场景输入</b>而不是设备状态（见 {@link OtaFaultPlan}）：掉电记录一旦写进日志，恢复逻辑就
     * 必须按「安装结果未知」处理，因此计划只对调用方声明的那一次执行生效，绝不跨执行保留。字段为
     * volatile，使注入线程与 MQTT 回调线程之间的可见性成立。</p>
     */
    private volatile OtaFaultPlan faultPlan = OtaFaultPlan.NONE;

    /** 已发出但尚未收到响应的下载申请：jobId → 申请身份与期望清单。 */
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    /** 单调进度序号；只在真正发布时递增，避免出现空洞序号。 */
    private final AtomicLong progressSeq = new AtomicLong();

    /**
     * 创建默认拒绝明文下载地址的会话。
     *
     * @param projectKey 项目短标识
     * @param deviceKey 设备短标识
     * @param transport 传输端口
     * @param clock 可注入时钟
     * @param stagingFile 设备暂存区文件
     * @param artifactSource 取字节端口
     * @param journal 耐久日志端口
     * @param acceptedResponses 已接纳下载响应身份的耐久存储
     * @param progressReporter 设备侧进度证据策略
     */
    public OtaDeviceSession(String projectKey, String deviceKey, OtaDeviceTransport transport, OtaClock clock,
                            Path stagingFile, OtaArtifactSource artifactSource, OtaStateJournal journal,
                            OtaAcceptedDownloadResponseStore acceptedResponses, ProgressReporter progressReporter) {
        this(projectKey, deviceKey, transport, clock, stagingFile, artifactSource, journal, acceptedResponses,
                progressReporter, false);
    }

    /**
     * @param projectKey 项目短标识
     * @param deviceKey 设备短标识
     * @param transport 传输端口
     * @param clock 可注入时钟
     * @param stagingFile 设备暂存区文件
     * @param artifactSource 取字节端口
     * @param journal 耐久日志端口
     * @param acceptedResponses 已接纳下载响应身份的耐久存储
     * @param progressReporter 设备侧进度证据策略
     * @param allowInsecureLoopback 是否允许本机回环明文下载地址
     */
    public OtaDeviceSession(String projectKey, String deviceKey, OtaDeviceTransport transport, OtaClock clock,
                            Path stagingFile, OtaArtifactSource artifactSource, OtaStateJournal journal,
                            OtaAcceptedDownloadResponseStore acceptedResponses, ProgressReporter progressReporter,
                            boolean allowInsecureLoopback) {
        this.projectKey = Objects.requireNonNull(projectKey, "projectKey");
        this.deviceKey = Objects.requireNonNull(deviceKey, "deviceKey");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stagingFile = Objects.requireNonNull(stagingFile, "stagingFile");
        this.artifactSource = Objects.requireNonNull(artifactSource, "artifactSource");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.acceptedResponses = Objects.requireNonNull(acceptedResponses, "acceptedResponses");
        this.progressReporter = Objects.requireNonNull(progressReporter, "progressReporter");
        this.responses = new OtaDeviceDownloadResponseCodec(allowInsecureLoopback);
    }

    /**
     * 订阅下载链路的两个平台下行 Topic。
     *
     * <p>订阅 Topic 由 {@link OtaDeviceTopics#forDevice} 从冻结模板与设备身份拼出，并在回调里
     * 再次核对完整 Topic：Broker ACL 之外再加一道「回复只对应本设备收到的命令」的检查。</p>
     */
    public void start() {
        transport.subscribe(OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, projectKey, deviceKey),
                (topic, payload) -> requireTopic(OtaDeviceTopics.AVAILABLE, topic, payload, this::onAvailable));
        transport.subscribe(OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, projectKey, deviceKey),
                (topic, payload) -> requireTopic(OtaDeviceTopics.DOWNLOAD_RESPONSE, topic, payload,
                        this::onDownloadResponse));
    }

    /**
     * 处理平台可升级通知：生成新 requestId 并发出下载申请。
     *
     * @param payload 通知的原始字节
     * @return 本次生成的请求标识
     * @throws IllegalArgumentException 通知不合法时
     */
    public UUID onAvailable(byte[] payload) {
        return requestDownload(OtaDeviceAvailableNotificationCodec.decode(payload).value());
    }

    /**
     * 为给定通知发出下载申请。
     *
     * <p>本方法不等待 PUBACK：与 {@code DeviceSimulator} 的其他上行一样，交付收据必须计入
     * manifest 才能区分「已交给客户端」与「Broker 已确认」。该记账属于后续接线，本片只保证
     * 申请字节与平台合同一致。</p>
     *
     * @param notification 已解析的可升级通知
     * @return 本次生成的请求标识
     */
    public UUID requestDownload(OtaDeviceAvailableNotificationCodec.Notification notification) {
        Objects.requireNonNull(notification, "notification");
        UUID requestId = Uuid7.generate();
        byte[] request = OtaDeviceDownloadRequestCodec.encode(new OtaDeviceDownloadRequestCodec.Request(
                OtaDeviceDownloadRequestCodec.CONTRACT_VERSION, requestId, notification.jobId(),
                notification.attemptNo(), notification.manifestSha256()));
        pendingRequests.put(notification.jobId().toString(),
                new PendingRequest(requestId, notification.manifestSha256()));
        transport.publish(OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, projectKey, deviceKey), request);
        return requestId;
    }

    /**
     * 设置下一次下载执行的故障计划；不设置时执行不注入任何故障。
     *
     * @param plan 下一次执行的故障计划，不能为空
     */
    public void setFaultPlan(OtaFaultPlan plan) {
        this.faultPlan = Objects.requireNonNull(plan, "plan");
    }

    /**
     * ADR0212：启动只观察已经打开的安装窗口，不为未知安装结果重新申请下载能力。
     * 身份缺失/不符不推测结论，持久化失败向启动方传播；重复启动不追加第二条观察。
     * 本方法必须在订阅任何下行之前调用，不发送平台进度或修改启动身份。
     */
    public void observeInterruptedInstallation() {
        OtaAcceptedDownloadResponse accepted = acceptedResponses.read().orElse(null);
        if (accepted == null) return;
        List<OtaJournalRecord> records = journal.read();
        if (records.isEmpty()) return;
        OtaJournalRecord last = records.getLast();
        if (!belongsToAttempt(last, accepted) || !last.stage().isSafetyStage() || !isUnfinished(last)
                || last.reasonCode() == OtaReasonCode.INSTALL_OUTCOME_UNKNOWN) return;
        journal.append(new OtaJournalRecord(last.stage(), last.attemptNo(), last.downloadedBytes(),
                last.confirmedDigestSoFar(), last.artifactSha256(), last.artifactSize(), last.manifestSha256(),
                clock.now(), OtaReasonCode.INSTALL_OUTCOME_UNKNOWN, false));
    }

    /**
     * D-161 重连/重启后的重投触发：只为「已经耐久受理过、且耐久日志显示尚未收束」的同一尝试再申请一次下载。
     *
     * <p><b>为什么必须由设备触发：</b>授权已经终态、内嵌地址必然过期，平台不会也无法主动重发；只有设备在真实
     * 重连边界上声明「我仍在同一次尝试上」并重新申请，平台才有合法的重投入口。申请正文与首次申请完全同构
     * （新的 {@code requestId}，相同 {@code jobId}/{@code attemptNo}/{@code manifestSha256}），平台据此做幂等
     * 重投登记，回包仍回显设备耐久受理记录里的原 {@code requestId}/授权身份，因此
     * {@link #onDownloadResponse(byte[])} 会走既有续传裁决。</p>
     *
     * <p><b>fail-closed：</b>没有耐久受理记录、最后一条日志不属于该受理尝试、或该尝试已经收束
     * （{@code COMMITTED}/{@code FAILED}/{@code CANCELLED}）时，本方法<b>不发送任何报文</b>并返回空；
     * 设备绝不因为「重启过」就凭空申请一次自己证明不了的升级。</p>
     *
     * @return 本次生成的请求标识；不满足重投条件时为空
     */
    public Optional<UUID> requestAcceptedResume() {
        OtaAcceptedDownloadResponse accepted = acceptedResponses.read().orElse(null);
        if (accepted == null) {
            return Optional.empty();
        }
        List<OtaJournalRecord> records = journal.read();
        OtaJournalRecord last = records.isEmpty() ? null : records.getLast();
        if (last != null && !belongsToAttempt(last, accepted)) {
            return Optional.empty();
        }
        if (last != null && (!isUnfinished(last) || last.stage().isSafetyStage())) {
            return Optional.empty();
        }
        UUID requestId = Uuid7.generate();
        byte[] request = OtaDeviceDownloadRequestCodec.encode(new OtaDeviceDownloadRequestCodec.Request(
                OtaDeviceDownloadRequestCodec.CONTRACT_VERSION, requestId, accepted.jobId(),
                accepted.attemptNo(), accepted.manifestSha256()));
        // 刻意不登记 pendingRequests：回包回显的是平台既有申请的原requestId，重投不是一次新的受理，
        // 必须交给 resumeAcceptedDelivery 的耐久身份裁决，而不是走首次接纳分支。
        transport.publish(OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, projectKey, deviceKey), request);
        return Optional.of(requestId);
    }

    /**
     * 处理平台下载响应：核对申请或耐久接纳记录、执行状态机、按平台闭集发布进度。
     *
     * <p><b>首次接纳（内存里还有对应待处理申请）：</b>核对 {@code jobId}/{@code requestId}/
     * {@code manifestSha256} 后，先把这份响应的身份<b>耐久落盘</b>，再运行执行。顺序不可颠倒：
     * 设备一旦开始执行就可能取字节、写暂存区甚至打开安装窗口，先落盘才能保证崩在执行中途时仍能识别
     * 同一份响应的重投递；反过来（先执行、后落盘）则会在崩溃后丢失「我曾接纳过它」的唯一依据。
     * 另一端「身份已落盘但执行从未开始」是安全窗口——重投递会读到这份记录并在空日志上正常开始，
     * 绝不产生第二次副作用。</p>
     *
     * <p><b>重投递（内存里已没有待处理申请，通常是 QoS 1 重复投递或进程重启后再次收到）：</b>只有当
     * 收到的响应与耐久记录逐字段一致（{@code jobId}/{@code requestId}/{@code authorizationId}/
     * {@code attemptNo}/{@code manifestSha256}/{@code artifactSha256}/{@code artifactSize}），
     * <b>并且</b>耐久日志显示该尝试尚未收束时，才再次运行执行；恢复偏移由 {@link OtaDeviceExecution}
     * 从日志中已确认的 offset 读出，绝不从零悄悄重来。日志为空（记录已落盘、执行从未开始）同样按
     * 「可以开始」处理。</p>
     *
     * <p><b>终态重投递是幂等空操作：</b>若日志最后一条记录属于同一尝试且已给出终态结论
     * （{@code COMMITTED}/{@code FAILED}/{@code CANCELLED}），本方法不构造执行、不写日志、不再取字节，
     * 只按日志事实重放同一结论返回。这样重复投递一份已经完成的下载响应既不会失败关闭（它不是未申请
     * 响应），也不会制造第二次副作用。</p>
     *
     * <p><b>其余情况失败关闭：</b>没有任何耐久接纳记录、身份与记录不一致，或日志最后一条记录不属于
     * 本次尝试时，一律抛 {@link IllegalStateException}：设备不能因为收到一份结构合法的授权就执行一次
     * 自己没有申请过的升级，也不能把一份记录错配到别的尝试上。</p>
     *
     * @param payload 下载响应原始字节
     * @return 状态机结论
     * @throws IllegalArgumentException 响应字节不合法时
     * @throws IllegalStateException 响应不对应本设备待处理申请、也不对应耐久接纳记录时
     */
    public OtaExecutionResult onDownloadResponse(byte[] payload) {
        OtaDeviceDownloadResponseCodec.Response response = responses.decode(payload).value();
        OtaDownloadAssignment assignment = new OtaDownloadAssignment(response.authorizationId(), response.jobId(),
                response.attemptNo(), response.manifestSha256(), response.manifestArtifactSha256(),
                response.manifestArtifactSize(), response.downloadUrl());
        PendingRequest pending = pendingRequests.remove(response.jobId().toString());
        if (pending != null && pending.requestId().equals(response.requestId())
                && pending.manifestSha256().equals(response.manifestSha256())) {
            // 首次接纳：身份先耐久落盘，再运行执行（崩在两者之间也能凭记录识别重投递）。
            acceptedResponses.write(OtaAcceptedDownloadResponse.from(assignment, response.requestId(), clock.now()));
            return runExecution(assignment);
        }
        return resumeAcceptedDelivery(assignment, response.requestId());
    }

    /**
     * 处理内存中已无待处理申请的重投递：凭耐久接纳身份与耐久日志裁决「续传」还是「空操作」。
     *
     * @param assignment 本次响应对应的下载赋值
     * @param requestId 本次响应携带的请求标识
     * @return 续传执行的结论，或终态重投递的幂等重放结论
     * @throws IllegalStateException 没有匹配的耐久接纳记录，或日志最后一条记录不属于本次尝试时
     */
    private OtaExecutionResult resumeAcceptedDelivery(OtaDownloadAssignment assignment, UUID requestId) {
        OtaAcceptedDownloadResponse accepted = acceptedResponses.read().orElse(null);
        if (accepted == null || !accepted.matches(assignment, requestId)) {
            throw new IllegalStateException("收到未申请或与申请不一致的 OTA 下载响应");
        }
        List<OtaJournalRecord> records = journal.read();
        OtaJournalRecord last = records.isEmpty() ? null : records.getLast();
        if (last != null && !belongsToAttempt(last, accepted)) {
            // 日志的最后事实指向另一次尝试：无法证明本次接纳仍处于未完成状态，失败关闭。
            throw new IllegalStateException("收到未申请或与申请不一致的 OTA 下载响应");
        }
        if (last != null && (!isUnfinished(last)
                || last.reasonCode() == OtaReasonCode.INSTALL_OUTCOME_UNKNOWN)) {
            // 终态或已观察未知的重投递：不构造执行、不写日志，按耐久日志事实重放同一结论。
            return replayTerminal(accepted, last);
        }
        return runExecution(assignment);
    }

    /**
     * 判断日志的最后一条记录是否允许再次运行执行。
     *
     * <p>仍在推进（没有结论）或安全暂停（例如 {@link OtaReasonCode#POWER_LOST}）都允许再次运行；
     * 具体是从耐久偏移续传、还是拒绝重新进入安全阶段，由 {@link OtaDeviceExecution} 的既有裁决决定，
     * 本类不越过状态机自行猜测。终态 {@code COMMITTED}/{@code FAILED}/{@code CANCELLED} 不允许再运行。</p>
     *
     * @param last 耐久日志的最后一条记录
     * @return {@code true} 表示可以再次运行执行
     */
    private static boolean isUnfinished(OtaJournalRecord last) {
        OtaStage conclusion = last.conclusion();
        return conclusion == null || conclusion == OtaStage.SAFETY_PAUSED;
    }

    /**
     * 判断日志记录是否属于耐久接纳的那一次尝试。
     *
     * <p>日志不含 {@code jobId}/{@code requestId}/{@code authorizationId}，因此用
     * {@code attemptNo}+{@code manifestSha256}+{@code artifactSha256}+{@code artifactSize} 四元组绑定尝试；
     * 这四项全部相等才认为最后一条日志事实描述的就是本次接纳。</p>
     *
     * @param last 耐久日志的最后一条记录
     * @param accepted 耐久接纳身份
     * @return {@code true} 表示该记录属于本次尝试
     */
    private static boolean belongsToAttempt(OtaJournalRecord last, OtaAcceptedDownloadResponse accepted) {
        return last.attemptNo() == accepted.attemptNo()
                && last.manifestSha256().equals(accepted.manifestSha256())
                && last.artifactSha256().equals(accepted.artifactSha256())
                && last.artifactSize() == accepted.artifactSize();
    }

    /**
     * 按耐久日志事实重放终态结论，不产生任何新的设备动作。
     *
     * @param accepted 耐久接纳身份
     * @param last 终态日志记录
     * @return 与日志一致的结论投影；转换列表为空
     */
    private OtaExecutionResult replayTerminal(OtaAcceptedDownloadResponse accepted, OtaJournalRecord last) {
        Instant now = clock.now();
        return new OtaExecutionResult(last.conclusion(), last.stage(), last.reasonCode(),
                accepted.authorizationId(), accepted.jobId(), accepted.attemptNo(), accepted.manifestSha256(),
                accepted.artifactSha256(), accepted.artifactSize(), last.downloadedBytes(),
                last.confirmedDigestSoFar(), 0, 0, last.cancelAccepted(), List.of(), now, now);
    }

    /**
     * 构造并运行一次下载执行，随后按设备侧证据策略发布进度。
     *
     * @param assignment 本次尝试的不可变身份
     * @return 状态机结论
     */
    private OtaExecutionResult runExecution(OtaDownloadAssignment assignment) {
        // 先读取当次计划再执行：注入线程可能在任意时刻覆盖字段，但本次执行必须只使用一个稳定值。
        OtaExecutionResult result = new OtaDeviceExecution(assignment, stagingFile, artifactSource, clock, journal,
                OtaDeviceExecution.DEFAULT_DOWNLOAD_BUDGET, OtaDeviceExecution.DEFAULT_MAX_DOWNLOAD_FAILURES,
                faultPlan).run();
        publishProgress(result);
        return result;
    }

    /**
     * 显式发布一份健康确认。
     *
     * <p>本方法不构造证据：健康窗口、连续序号与自检结论都属于设备真实事实，必须由调用方提供。</p>
     *
     * @param health 平台合同形态的健康确认
     * @return 传输交付收据
     */
    public CompletableFuture<Void> publishHealth(OtaDeviceHealthCodec.Health health) {
        return transport.publish(OtaDeviceTopics.forDevice(OtaDeviceTopics.HEALTH, projectKey, deviceKey),
                OtaDeviceHealthCodec.encode(health));
    }

    /**
     * 显式发布一份安装前停止操作报告。
     *
     * @param report 平台合同形态的停止报告
     * @return 传输交付收据
     */
    public CompletableFuture<Void> publishInstallStopReport(OtaDeviceInstallStopReportCodec.Report report) {
        return transport.publish(
                OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_OPERATION_REPORT, projectKey, deviceKey),
                OtaDeviceInstallStopReportCodec.encode(report));
    }

    /**
     * 显式发布一份安装前停止状态报告。
     *
     * @param report 平台合同形态的状态报告
     * @return 传输交付收据
     */
    public CompletableFuture<Void> publishInstallStopStatusReport(
            OtaDeviceInstallStopStatusReportCodec.Report report) {
        return transport.publish(
                OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_STATUS_REPORT, projectKey, deviceKey),
                OtaDeviceInstallStopStatusReportCodec.encode(report));
    }

    /**
     * 显式发布一份真实提交回执。
     *
     * <p><b>为什么本方法不构造回执：</b>回执是「设备已在本次启动里提交并绑定目标版本」的最终事实，
     * 它必须由持有耐久日志、已安装 artifact 与真实启动身份的 {@link OtaDeviceRuntime} 在消费到
     * 平台许可后构造。本方法只负责把调用方给出的事实放到设备真实订阅/发布的同一条已认证连接上，
     * 绝不自行生成 {@code receiptId}、{@code permitId} 或证据。</p>
     *
     * @param receipt 平台合同形态的提交回执，由运行时按许可与真实安装事实构造
     * @return 传输交付收据
     */
    public CompletableFuture<Void> publishCommitReceipt(OtaDeviceCommitReceiptCodec.Receipt receipt) {
        return transport.publish(OtaDeviceTopics.forDevice(OtaDeviceTopics.COMMIT_RECEIPT, projectKey, deviceKey),
                OtaDeviceCommitReceiptCodec.encode(receipt));
    }

    /** 只发布 {@link ProgressReporter} 明确给出的阶段观察。 */
    private void publishProgress(OtaExecutionResult result) {
        for (OtaJournalRecord transition : result.transitions()) {
            OtaDeviceJobProgressCodec.Progress progress =
                    progressReporter.report(transition, result, progressSeq.get() + 1L);
            if (progress == null) {
                continue;
            }
            // 先编码再递增序号：编码失败说明策略给出的阶段/证据不合法，序号不能留下空洞。
            byte[] encoded = OtaDeviceJobProgressCodec.encode(progress);
            progressSeq.incrementAndGet();
            transport.publish(OtaDeviceTopics.forDevice(OtaDeviceTopics.PROGRESS, projectKey, deviceKey), encoded);
        }
    }

    /** 复核回调 Topic 精确等于本设备身份对应的模板，否则拒绝处理。 */
    private void requireTopic(String template, String topic, byte[] payload, Consumer<byte[]> handler) {
        if (!OtaDeviceTopics.matches(template, projectKey, deviceKey, topic)) {
            throw new IllegalStateException("OTA 下行 Topic 与本设备身份不匹配: " + topic);
        }
        handler.accept(payload);
    }

    /** 已发出但尚未收到响应的申请。 */
    private record PendingRequest(UUID requestId, String manifestSha256) {
    }

    /**
     * 设备侧进度证据策略：决定某个已发生阶段是否上报、以及上报哪些设备观察。
     *
     * <p>把这一步留给调用方，是因为「验证通过、自检通过、看门狗健康」是硬件事实。模拟器只有
     * 在调用方明确声明这些事实时才会上报，绝不自行断言。</p>
     */
    @FunctionalInterface
    public interface ProgressReporter {

        /**
         * 为一次设备阶段转换生成进度报文。
         *
         * @param transition 本次执行新写入日志的阶段转换
         * @param result 本次执行结论
         * @param progressSeq 若上报将使用的单调序号
         * @return 进度报文；返回 {@code null} 表示当前策略不上报该阶段
         */
        OtaDeviceJobProgressCodec.Progress report(OtaJournalRecord transition, OtaExecutionResult result,
                                                  long progressSeq);
    }
}
