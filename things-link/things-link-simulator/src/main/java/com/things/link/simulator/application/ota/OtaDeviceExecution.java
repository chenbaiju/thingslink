package com.things.link.simulator.application.ota;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 设备侧 OTA 状态机：可确定性重放、以耐久日志为唯一恢复依据。
 *
 * <p><b>本片为什么是自包含组件而不是已接线的生产路径：</b>平台侧要真正派发 OTA，需要受控签名器签出
 * READY 固件，才会产生批次、作业、下载授权与提交许可。模拟器进程没有、也不应该持有签名私钥，
 * 因此本地无法产生真实授权链；本类只实现「拿到授权之后设备该怎么做」的一段，并通过
 * {@link OtaArtifactSource}/{@link OtaClock}/{@link OtaStateJournal} 三个端口与外界解耦。
 * 它不发布任何 MQTT 报文，也不假装平台已经派发。</p>
 *
 * <p><b>恢复模型（为什么安全阶段绝不自动重启刷写）：</b>重启时只读日志的最后一条：
 * <ul>
 *   <li>{@code COMMITTED}/{@code FAILED}/{@code CANCELLED}：幂等返回，不新增日志、不再取字节。</li>
 *   <li>最后阶段属于安全阶段（{@link OtaStage#isSafetyStage()}）：安装窗口已经打开，擦写/切槽是否发生
 *       无法由设备证明（ADR0131 要求保留 {@code RECOVERY_REQUIRED}，ADR0137 要求安装先赢返回
 *       {@code INSTALL_WON}）。此时只追加一条 {@link OtaReasonCode#INSTALL_OUTCOME_UNKNOWN} 观察并暂停，
 *       既不重新下载，也不重新刷写。</li>
 *   <li>其余情况：从日志里「已 fsync 的 offset + 已确认前缀摘要」续传。{@link OtaStage#VERIFYING}
 *       允许重做，因为它只读且幂等。</li>
 * </ul>
 * </p>
 *
 * <p><b>安装结果未知时设备可以上报什么：</b>只能上报「我重启了、日志显示我曾在某个安全阶段，
 * 但我无法证明擦写/切槽是否完成」。对应到平台语义就是保留责任并等待对账（ADR0131 的
 * {@code RECOVERY_REQUIRED}；ADR0137 的 {@code INSTALL_WON} 一侧），因此本类绝不返回
 * {@link OtaStage#COMMITTED}（没有提交证据），也绝不返回 {@link OtaStage#CANCELLED}（无法证明
 * 设备已经干净停止）。这正是「宁可暂停等对账，也不猜测」的落地方式。</p>
 *
 * <p><b>为什么完整摘要必须重新读暂存区：</b>SHA-256 的中间状态不能跨进程续用，只下载尾部无法得到完整
 * 摘要。因此设备把分片写进暂存区，重启后先重放前缀、核对日志里的前缀摘要（不一致即
 * {@link OtaReasonCode#STAGING_INCONSISTENT}），再继续下载，最后在 {@link OtaStage#VERIFYING}
 * 从头读一遍暂存区计算完整 SHA-256。只有完整摘要与预期一致才允许进入刷写。</p>
 *
 * <p><b>模拟器不能声称的硬件证据：</b>本类只产生阶段序列与摘要事实，{@link OtaStage#HEALTH_CHECKING}
 * 之后的真实自检、看门狗、受保护计数器、原子提交许可（ADR0132）都不在本片范围内，
 * 因此这里不会有「健康窗口已满」或「设备已原子提交」的断言；后续接线必须由平台许可驱动。</p>
 */
public final class OtaDeviceExecution {

    /** 可重试下载失败之间的固定退避；测试注入假时钟后不产生真实等待。 */
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);

    /** 计算/重放摘要时的读取缓冲。 */
    private static final int HASH_BUFFER_BYTES = 8192;

    /** 默认的下载失败预算：允许两次可重试抖动，第三次失败判 FAILED。 */
    public static final int DEFAULT_MAX_DOWNLOAD_FAILURES = 2;

    /** 默认的整体下载预算，覆盖全部重试；测试注入假时钟后可缩短。 */
    public static final Duration DEFAULT_DOWNLOAD_BUDGET = Duration.ofMinutes(5);

    /** 本次尝试的不可变身份。 */
    private final OtaDownloadAssignment assignment;

    /** 设备暂存区文件：分片先落这里，重启后用于重放前缀并计算完整摘要。 */
    private final Path stagingFile;

    /** 取字节端口。 */
    private final OtaArtifactSource source;

    /** 可注入时钟，用于期限与退避。 */
    private final OtaClock clock;

    /** 耐久追加日志端口。 */
    private final OtaStateJournal journal;

    /** 整体下载预算（含重试）。 */
    private final Duration downloadBudget;

    /** 可重试下载失败预算；由调用方按场景参数化，不下沉为平台全局策略。 */
    private final int maxDownloadFailures;

    /** 本次执行的故障注入计划。 */
    private final OtaFaultPlan faultPlan;

    /**
     * 创建带显式策略的设备执行。
     *
     * @param assignment 本次尝试的不可变身份
     * @param stagingFile 设备暂存区文件；目录会被自动创建
     * @param source 取字节端口
     * @param clock 可注入时钟
     * @param journal 耐久日志端口
     * @param downloadBudget 整体下载预算，必须为正
     * @param maxDownloadFailures 可重试下载失败预算，必须为非负
     * @param faultPlan 故障注入计划，不能为空（无故障用 {@link OtaFaultPlan#NONE}）
     */
    public OtaDeviceExecution(OtaDownloadAssignment assignment, Path stagingFile, OtaArtifactSource source,
                              OtaClock clock, OtaStateJournal journal, Duration downloadBudget,
                              int maxDownloadFailures, OtaFaultPlan faultPlan) {
        this.assignment = Objects.requireNonNull(assignment, "assignment");
        this.stagingFile = Objects.requireNonNull(stagingFile, "stagingFile");
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.downloadBudget = Objects.requireNonNull(downloadBudget, "downloadBudget");
        this.faultPlan = Objects.requireNonNull(faultPlan, "faultPlan");
        if (downloadBudget.isZero() || downloadBudget.isNegative()) {
            throw new IllegalArgumentException("downloadBudget 必须为正");
        }
        if (maxDownloadFailures < 0) {
            throw new IllegalArgumentException("maxDownloadFailures 不能为负数");
        }
        this.maxDownloadFailures = maxDownloadFailures;
    }

    /**
     * 使用默认下载失败预算、默认下载预算且不注入故障的便捷构造器。
     *
     * @param assignment 本次尝试的不可变身份
     * @param stagingFile 设备暂存区文件
     * @param source 取字节端口
     * @param clock 可注入时钟
     * @param journal 耐久日志端口
     */
    public OtaDeviceExecution(OtaDownloadAssignment assignment, Path stagingFile, OtaArtifactSource source,
                              OtaClock clock, OtaStateJournal journal) {
        this(assignment, stagingFile, source, clock, journal, DEFAULT_DOWNLOAD_BUDGET,
                DEFAULT_MAX_DOWNLOAD_FAILURES, OtaFaultPlan.NONE);
    }

    /**
     * 执行（或从日志恢复）本次 OTA 尝试，一直推进到终态或安全暂停。
     *
     * <p>方法可重复调用：日志已经给出终态时幂等返回；日志停在非安全阶段时从已确认偏移续传；
     * 日志停在安全阶段时只追加「安装结果未知」的诚实观察，绝不重新进入刷写。</p>
     *
     * @return 本次执行的结论与有序转换列表
     */
    public OtaExecutionResult run() {
        Instant startedAt = clock.now();
        Instant deadline = startedAt.plus(downloadBudget);
        RunState state = new RunState();
        List<OtaJournalRecord> existing = journal.read();

        if (!existing.isEmpty()) {
            OtaJournalRecord last = existing.getLast();
            OtaStage conclusion = last.conclusion();
            state.offset = last.downloadedBytes();
            state.digestHex = last.confirmedDigestSoFar();
            state.observedStage = last.stage();
            state.cancelAccepted = last.cancelAccepted();
            if (conclusion != null && conclusion != OtaStage.SAFETY_PAUSED) {
                // 已经收束：不新增日志、不再取字节，保证重启后的重复调用不会制造第二次副作用。
                return result(state, conclusion, last.reasonCode(), startedAt);
            }
            if (last.reasonCode().isSafetyStagePause(last.stage())) {
                // 安装窗口已经打开且结果未知：只记录观察，绝不重新下载或重新刷写。
                append(state, last.stage(), OtaReasonCode.INSTALL_OUTCOME_UNKNOWN, false);
                return result(state, OtaStage.SAFETY_PAUSED, OtaReasonCode.INSTALL_OUTCOME_UNKNOWN, startedAt);
            }
        }

        Path absoluteStaging = stagingFile.toAbsolutePath();
        createParentDirectory(absoluteStaging);
        try (FileChannel channel = FileChannel.open(absoluteStaging,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            if (existing.isEmpty()) {
                channel.truncate(0L);
            } else if (!alignStagingWithJournal(channel, state, startedAt)) {
                return result(state, OtaStage.FAILED, OtaReasonCode.STAGING_INCONSISTENT, startedAt);
            }
            MessageDigest digest = OtaDigests.sha256();
            hashRange(channel, 0L, state.offset, digest);
            String rebuiltPrefixDigest = OtaDigests.snapshotHex(digest);
            if (existing.isEmpty()) {
                // 尚无任何耐久事实：只登记派发，offset 必须保持 0。
                state.digestHex = OtaDigests.EMPTY_SHA256;
                append(state, OtaStage.DISPATCHED, OtaReasonCode.NONE, false);
            } else {
                state.digestHex = rebuiltPrefixDigest;
                if (!existing.getLast().matchesPrefixDigest(rebuiltPrefixDigest)) {
                    // 日志声称的前缀摘要与暂存区实际内容不符（截断/篡改/写坏）：绝不刷写。
                    append(state, existing.getLast().stage(), OtaReasonCode.STAGING_INCONSISTENT, false);
                    return result(state, OtaStage.FAILED, OtaReasonCode.STAGING_INCONSISTENT, startedAt);
                }
            }

            if (faultPlan.powerLossAt() == OtaStage.DISPATCHED) {
                append(state, OtaStage.DISPATCHED, OtaReasonCode.POWER_LOST, false);
                return result(state, OtaStage.SAFETY_PAUSED, OtaReasonCode.POWER_LOST, startedAt);
            }
            OtaExecutionResult cancelled = cancelAtBoundary(state, OtaStage.DOWNLOADING, startedAt);
            if (cancelled != null) {
                return cancelled;
            }
            if (faultPlan.rejectResume() && state.offset > 0L) {
                // 平台/传输明确拒绝续传：保持已确认偏移，绝不从零悄悄重来。
                append(state, OtaStage.DOWNLOADING, OtaReasonCode.RESUME_REJECTED, false);
                return result(state, OtaStage.SAFETY_PAUSED, OtaReasonCode.RESUME_REJECTED, startedAt);
            }

            while (state.offset < assignment.artifactSize()) {
                OtaArtifactSource.Chunk chunk;
                boolean injectedFirstChunkFailure = false;
                try {
                    state.fetchAttempts++;
                    if (faultPlan.failFirstChunk() && state.fetchAttempts == 1 && state.offset == 0L) {
                        injectedFirstChunkFailure = true;
                        throw new OtaArtifactException(OtaArtifactException.Kind.TRANSPORT, "注入首分片失败");
                    }
                    Duration remaining = Duration.between(clock.now(), deadline);
                    if (remaining.isZero() || remaining.isNegative()) {
                        throw new OtaArtifactException(
                                OtaArtifactException.Kind.DEADLINE, "整体下载预算已耗尽");
                    }
                    chunk = source.fetch(assignment.downloadUrl().toString(), state.offset,
                            assignment.artifactSize(), remaining);
                } catch (OtaArtifactException failure) {
                    OtaExecutionResult stopped =
                            onFetchFailure(state, failure, injectedFirstChunkFailure, startedAt);
                    if (stopped != null) {
                        return stopped;
                    }
                    continue;
                }
                OtaExecutionResult stopped = onChunkDurable(state, chunk, channel, digest, startedAt);
                if (stopped != null) {
                    return stopped;
                }
            }

            OtaExecutionResult verifying = verifyStagedArtifact(state, channel, absoluteStaging, startedAt);
            if (verifying != null) {
                return verifying;
            }
            for (OtaStage safetyStage : List.of(OtaStage.INSTALLING, OtaStage.REBOOTING,
                    OtaStage.HEALTH_CHECKING, OtaStage.CONFIRMING)) {
                OtaExecutionResult stopped = enterSafetyStage(state, safetyStage, startedAt);
                if (stopped != null) {
                    return stopped;
                }
            }
            append(state, OtaStage.COMMITTED, OtaReasonCode.NONE, false);
            return result(state, OtaStage.COMMITTED, OtaReasonCode.NONE, startedAt);
        } catch (IOException failure) {
            throw new UncheckedIOException("OTA 暂存区读写失败", failure);
        }
    }

    /**
     * 让暂存区与日志对齐：日志声明的偏移不能大于实际字节数，多出的尾部字节安全截断。
     *
     * @param channel 已打开的暂存区通道
     * @param state 运行状态（携带日志偏移）
     * @param startedAt 执行开始时刻
     * @return {@code true} 表示对齐成功；{@code false} 表示已判 {@link OtaReasonCode#STAGING_INCONSISTENT}
     */
    private boolean alignStagingWithJournal(FileChannel channel, RunState state, Instant startedAt)
            throws IOException {
        long size = channel.size();
        if (size < state.offset) {
            append(state, OtaStage.DOWNLOADING, OtaReasonCode.STAGING_INCONSISTENT, false);
            return false;
        }
        if (size > state.offset) {
            // 只可能来自上次「写完内容但没来得及写日志」的窗口，重写同一区间即可，先截断更干净。
            channel.truncate(state.offset);
        }
        return true;
    }

    /**
     * 核对暂存区完整摘要；只有完整摘要与预期一致才允许继续进入安全阶段。
     *
     * @param state 运行状态
     * @param channel 已打开的暂存区通道
     * @param stagingPath 暂存区绝对路径
     * @param startedAt 执行开始时刻
     * @return 需要提前结束时的结果；可以继续时为 {@code null}
     */
    private OtaExecutionResult verifyStagedArtifact(RunState state, FileChannel channel, Path stagingPath,
                                                    Instant startedAt) throws IOException {
        OtaExecutionResult cancelled = cancelAtBoundary(state, OtaStage.VERIFYING, startedAt);
        if (cancelled != null) {
            return cancelled;
        }
        append(state, OtaStage.VERIFYING, OtaReasonCode.NONE, false);
        channel.force(true);
        String fullDigest = hashFile(stagingPath);
        if (!fullDigest.equals(assignment.artifactSha256())) {
            // 摘要不符是判定性失败：绝不把未知镜像写进槽位。
            append(state, OtaStage.VERIFYING, OtaReasonCode.DIGEST_MISMATCH, false);
            return result(state, OtaStage.FAILED, OtaReasonCode.DIGEST_MISMATCH, startedAt);
        }
        if (!state.digestHex.equals(fullDigest)) {
            append(state, OtaStage.VERIFYING, OtaReasonCode.STAGING_INCONSISTENT, false);
            return result(state, OtaStage.FAILED, OtaReasonCode.STAGING_INCONSISTENT, startedAt);
        }
        if (faultPlan.powerLossAt() == OtaStage.VERIFYING) {
            append(state, OtaStage.VERIFYING, OtaReasonCode.POWER_LOST, false);
            return result(state, OtaStage.SAFETY_PAUSED, OtaReasonCode.POWER_LOST, startedAt);
        }
        return null;
    }

    /**
     * 进入一个安全阶段：先检查取消边界，再记录阶段并检查掉电注入。
     *
     * @param state 运行状态
     * @param stage 目标安全阶段
     * @param startedAt 执行开始时刻
     * @return 需要提前结束时的结果；可以继续时为 {@code null}
     */
    private OtaExecutionResult enterSafetyStage(RunState state, OtaStage stage, Instant startedAt) {
        OtaExecutionResult cancelled = cancelAtBoundary(state, stage, startedAt);
        if (cancelled != null) {
            return cancelled;
        }
        append(state, stage, OtaReasonCode.NONE, false);
        if (faultPlan.powerLossAt() == stage) {
            append(state, stage, OtaReasonCode.POWER_LOST, false);
            return result(state, OtaStage.SAFETY_PAUSED, OtaReasonCode.POWER_LOST, startedAt);
        }
        return null;
    }

    /**
     * 处理一次分片：先校验服务器观察结果、再写暂存区、再写日志，最后才把 offset 视为耐久。
     *
     * @param state 运行状态
     * @param chunk 取字节结果
     * @param channel 暂存区通道
     * @param digest 与已确认前缀同步的摘要器
     * @param startedAt 执行开始时刻
     * @return 需要提前结束时的结果；可以继续时为 {@code null}
     */
    private OtaExecutionResult onChunkDurable(RunState state, OtaArtifactSource.Chunk chunk, FileChannel channel,
                                              MessageDigest digest, Instant startedAt) throws IOException {
        if (state.offset > 0L && !chunk.contentRangeAccepted()) {
            append(state, OtaStage.DOWNLOADING, OtaReasonCode.RESUME_REJECTED, false);
            return result(state, OtaStage.SAFETY_PAUSED, OtaReasonCode.RESUME_REJECTED, startedAt);
        }
        if (chunk.contentRangeAccepted() && chunk.contentRangeStart() != state.offset) {
            append(state, OtaStage.DOWNLOADING, OtaReasonCode.RESUME_REJECTED, false);
            return result(state, OtaStage.SAFETY_PAUSED, OtaReasonCode.RESUME_REJECTED, startedAt);
        }
        if (chunk.totalLength() != assignment.artifactSize()) {
            append(state, OtaStage.DOWNLOADING, OtaReasonCode.ARTIFACT_SIZE_MISMATCH, false);
            return result(state, OtaStage.FAILED, OtaReasonCode.ARTIFACT_SIZE_MISMATCH, startedAt);
        }
        byte[] payload = chunk.payload();
        if (payload.length == 0) {
            // 服务器回了空分片：没有耐久进展，按可重试失败计。
            return retryDownloadFailure(state, startedAt);
        }
        long newOffset = state.offset + payload.length;
        if (newOffset > assignment.artifactSize()) {
            append(state, OtaStage.DOWNLOADING, OtaReasonCode.ARTIFACT_SIZE_MISMATCH, false);
            return result(state, OtaStage.FAILED, OtaReasonCode.ARTIFACT_SIZE_MISMATCH, startedAt);
        }
        writeAt(channel, state.offset, payload);
        channel.force(false);
        digest.update(payload);
        String newDigestHex = OtaDigests.snapshotHex(digest);
        // 日志先落盘：append 返回后该分片才算耐久，内存 offset 才能推进。
        append(state, OtaStage.DOWNLOADING, newOffset, newDigestHex, OtaReasonCode.NONE, false);
        state.offset = newOffset;
        state.digestHex = newDigestHex;
        state.downloadFailures = 0;
        if (faultPlan.powerLossAt() == OtaStage.DOWNLOADING) {
            append(state, OtaStage.DOWNLOADING, OtaReasonCode.POWER_LOST, false);
            return result(state, OtaStage.SAFETY_PAUSED, OtaReasonCode.POWER_LOST, startedAt);
        }
        return null;
    }

    /**
     * 判定一次取字节失败；只有确实可重试的类别才会消耗预算并退避。
     *
     * @param state 运行状态
     * @param failure 取字节失败
     * @param injectedFirstChunkFailure 是否为注入的首分片失败
     * @param startedAt 执行开始时刻
     * @return 需要提前结束时的结果；已退避可重试时为 {@code null}
     */
    private OtaExecutionResult onFetchFailure(RunState state, OtaArtifactException failure,
                                              boolean injectedFirstChunkFailure, Instant startedAt) {
        if (injectedFirstChunkFailure) {
            // 首错安全暂停：无法区分「抖动」与「授权/对象已被撤销」，不自动重试。
            append(state, OtaStage.DOWNLOADING, OtaReasonCode.FIRST_CHUNK_FAILED_SAFETY_PAUSE, false);
            return result(state, OtaStage.SAFETY_PAUSED,
                    OtaReasonCode.FIRST_CHUNK_FAILED_SAFETY_PAUSE, startedAt);
        }
        OtaReasonCode terminalReason = switch (failure.kind()) {
            case RESUME_REJECTED -> OtaReasonCode.RESUME_REJECTED;
            case ARTIFACT_OVERSIZED -> OtaReasonCode.ARTIFACT_OVERSIZED;
            case ARTIFACT_SIZE_MISMATCH -> OtaReasonCode.ARTIFACT_SIZE_MISMATCH;
            case DEADLINE -> OtaReasonCode.DOWNLOAD_DEADLINE_EXCEEDED;
            case HTTP_STATUS, TRANSPORT -> null;
        };
        if (terminalReason != null) {
            append(state, OtaStage.DOWNLOADING, terminalReason, false);
            return result(state, terminalReason.conclusion(), terminalReason, startedAt);
        }
        return retryDownloadFailure(state, startedAt);
    }

    /**
     * 记一次可重试下载失败；超出预算即判 {@link OtaStage#FAILED}。
     *
     * @param state 运行状态
     * @param startedAt 执行开始时刻
     * @return 预算耗尽时的失败结果；仍可重试时为 {@code null}
     */
    private OtaExecutionResult retryDownloadFailure(RunState state, Instant startedAt) {
        state.downloadFailures++;
        if (state.downloadFailures > maxDownloadFailures) {
            append(state, OtaStage.DOWNLOADING, OtaReasonCode.DOWNLOAD_FAILURE_BUDGET_EXHAUSTED, false);
            return result(state, OtaStage.FAILED,
                    OtaReasonCode.DOWNLOAD_FAILURE_BUDGET_EXHAUSTED, startedAt);
        }
        clock.sleep(RETRY_BACKOFF);
        return null;
    }

    /**
     * 在阶段边界投递一次取消请求：安装窗口打开前接受，打开后拒绝并如实上报真实阶段。
     *
     * @param state 运行状态
     * @param stage 即将进入的阶段
     * @param startedAt 执行开始时刻
     * @return 取消命中时的结果；未命中时为 {@code null}
     */
    private OtaExecutionResult cancelAtBoundary(RunState state, OtaStage stage, Instant startedAt) {
        if (faultPlan.cancelAtSafePoint() != stage) {
            return null;
        }
        if (stage.isSafetyStage()) {
            // 安装窗口已经打开：这里只能报告真实阶段，伪造 CANCELLED 会让平台误以为设备干净停止。
            append(state, stage, OtaReasonCode.CANCEL_REFUSED_AFTER_SAFETY_STAGE, false);
            return result(state, OtaStage.SAFETY_PAUSED,
                    OtaReasonCode.CANCEL_REFUSED_AFTER_SAFETY_STAGE, startedAt);
        }
        append(state, stage, OtaReasonCode.CANCELLED_BEFORE_SAFETY_STAGE, true);
        return result(state, OtaStage.CANCELLED, OtaReasonCode.CANCELLED_BEFORE_SAFETY_STAGE, startedAt);
    }

    /**
     * 追加一条耐久日志并计入本次转换列表。
     *
     * @param state 运行状态
     * @param stage 记录阶段（设备真实阶段）
     * @param reason 稳定原因码
     * @param cancelAccepted 是否接受干净取消
     */
    private void append(RunState state, OtaStage stage, OtaReasonCode reason, boolean cancelAccepted) {
        append(state, stage, state.offset, state.digestHex, reason, cancelAccepted);
    }

    /**
     * 追加一条耐久日志并计入本次转换列表，使用显式的偏移与摘要。
     *
     * @param state 运行状态
     * @param stage 记录阶段（设备真实阶段）
     * @param offset 记录时的已确认偏移
     * @param digestHex 记录时的已确认前缀摘要
     * @param reason 稳定原因码
     * @param cancelAccepted 是否接受干净取消
     */
    private void append(RunState state, OtaStage stage, long offset, String digestHex, OtaReasonCode reason,
                        boolean cancelAccepted) {
        OtaJournalRecord record = new OtaJournalRecord(stage, assignment.attemptNo(), offset, digestHex,
                assignment.artifactSha256(), assignment.artifactSize(), assignment.manifestSha256(),
                clock.now(), reason, cancelAccepted);
        journal.append(record);
        state.transitions.add(record);
        if (stage.isDeviceStage()) {
            state.observedStage = stage;
        }
        if (cancelAccepted) {
            state.cancelAccepted = true;
        }
    }

    /**
     * 冻结结果快照。
     *
     * @param state 运行状态
     * @param conclusion 结论阶段
     * @param reason 稳定原因码
     * @param startedAt 执行开始时刻
     * @return 不可变结果
     */
    private OtaExecutionResult result(RunState state, OtaStage conclusion, OtaReasonCode reason,
                                      Instant startedAt) {
        return new OtaExecutionResult(conclusion, state.observedStage, reason,
                assignment.authorizationId(), assignment.jobId(), assignment.attemptNo(),
                assignment.manifestSha256(), assignment.artifactSha256(), assignment.artifactSize(),
                state.offset, state.digestHex, state.fetchAttempts, state.downloadFailures,
                state.cancelAccepted, state.transitions, startedAt, clock.now());
    }

    /** 创建暂存区父目录；失败直接上抛为环境错误，不伪装成设备裁决。 */
    private static void createParentDirectory(Path stagingPath) {
        Path parent = stagingPath.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException failure) {
            throw new UncheckedIOException("无法创建 OTA 暂存区目录", failure);
        }
    }

    /** 在指定位置写入分片载荷，直到全部写满。 */
    private static void writeAt(FileChannel channel, long position, byte[] payload) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        long offset = position;
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer, offset);
            if (written < 0) {
                throw new IOException("OTA 暂存区写入返回 -1");
            }
            offset += written;
        }
    }

    /** 把暂存区 [start, start+length) 区间喂给摘要器。 */
    private static void hashRange(FileChannel channel, long start, long length, MessageDigest digest)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(HASH_BUFFER_BYTES);
        long position = start;
        long remaining = length;
        while (remaining > 0L) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("OTA 暂存区长度小于日志声明的前缀");
            }
            if (read == 0) {
                continue;
            }
            digest.update(buffer.array(), 0, read);
            position += read;
            remaining -= read;
        }
    }

    /** 从头读整个文件，计算完整 SHA-256。 */
    private static String hashFile(Path file) throws IOException {
        MessageDigest digest = OtaDigests.sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[HASH_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return OtaJournalRecord.hex(digest.digest());
    }

    /** 单次执行的易变状态；不跨调用复用，保证执行之间只通过日志通信。 */
    private static final class RunState {

        /** 本次执行新写入日志的转换。 */
        private final List<OtaJournalRecord> transitions = new ArrayList<>();

        /** 已耐久确认的偏移。 */
        private long offset;

        /** 已确认前缀摘要。 */
        private String digestHex = OtaDigests.EMPTY_SHA256;

        /** 本次执行的取字节次数。 */
        private int fetchAttempts;

        /** 本次执行消耗掉的可重试下载失败次数。 */
        private int downloadFailures;

        /** 本次执行是否接受过干净取消。 */
        private boolean cancelAccepted;

        /** 设备真实处在的阶段。 */
        private OtaStage observedStage = OtaStage.DISPATCHED;
    }
}
