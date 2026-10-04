package com.things.link.simulator.application.ota.session;

import com.things.link.simulator.application.ota.OtaJournalRecord;
import com.things.link.simulator.application.ota.OtaStage;
import com.things.link.simulator.application.ota.contract.OtaDeviceInstallStopReportCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceJobProgressCodec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 模拟器声明的设备证据事实：只把调用方显式给出的取值填进平台合同字段。
 *
 * <p><b>为什么需要单独一层：</b>{@link OtaDeviceJobProgressCodec} 的十九字段证据里，
 * {@code verification}/{@code bootVerified}/{@code selfTestPassed}/{@code watchdogHealthy} 与
 * {@code securityVersion}/{@code committedSecurityVersion} 是设备对<b>自身硬件</b>的声明。
 * 模拟器没有受保护计数器、没有看门狗、也没有真实自检，因此它只能在被显式告知这些事实时
 * 才把它们写进报文。本类就是那个「显式告知」的载体：默认策略下不声明健康，不声称提交，
 * 从而让模拟器的进度报文停在它真的能证明的阶段。</p>
 *
 * <p><b>为什么槽位与信任域可以声明：</b>{@code sourceSlot}/{@code targetSlot} 描述这次尝试的
 * A/B 槽位切换意图，{@code trustDomain}/{@code rootFingerprint}/{@code trustBundleSha256} 描述
 * 设备被配置去信任哪个域——这些是模拟设备自己的配置事实，不是平台已发布清单的声明。
 * 模拟器不验签，所以它绝不声称签名已验证；它只声明「我按这份配置去校验」。</p>
 *
 * <p><b>默认值来源：</b>{@link #DEFAULT} 里的物模型版本、Schema 摘要、信任域与根指纹都是模拟器
 * 自身的静态声明，取值逐条写在类里以便审计，任何一个都不得被当作平台派发事实。</p>
 *
 * @param model 设备型号，必须匹配平台 {@code [A-Za-z0-9][A-Za-z0-9._-]{0,63}}
 * @param boardRevision 板修订号（非负安全整数）
 * @param bootloaderVersion 设备 bootloader 三轴语义版本
 * @param thingModelVersionId 设备声明的物模型版本标识
 * @param thingModelSchemaDigestAlgorithm 固定 Schema 摘要算法
 * @param thingModelSchemaDigest 设备声明的 Schema 摘要（小写十六进制）
 * @param trustDomain 设备被配置信任的信任域
 * @param rootFingerprint 根公钥指纹（小写十六进制）
 * @param trustBundleVersion 信任包版本，必须为正
 * @param trustBundleSha256 信任包摘要（小写十六进制）
 * @param securityVersion 设备当前安全版本；安装前不变，安装后由平台许可推进
 * @param committedSecurityVersion 已提交安全版本；安装前等于未提交哨兵 0
 */
public record OtaSimulatorEvidence(
        String model,
        long boardRevision,
        String bootloaderVersion,
        UUID thingModelVersionId,
        String thingModelSchemaDigestAlgorithm,
        String thingModelSchemaDigest,
        String trustDomain,
        String rootFingerprint,
        long trustBundleVersion,
        String trustBundleSha256,
        long securityVersion,
        long committedSecurityVersion) {

    /** 平台冻结的 Schema 摘要算法闭集里唯一取值。 */
    public static final String SCHEMA_DIGEST_ALGORITHM = "PG_JSONB_TEXT_V1_SHA256";

    /** 平台冻结的属性 Profile。 */
    public static final String PROPERTY_PROFILE = "TC_PROPERTY_COMPOSITE_V1";

    /** 摘要字段必须是完整的 64 位小写十六进制。 */
    private static final String SHA256_PATTERN = "[0-9a-f]{64}";

    /** 三轴语义版本文本。 */
    private static final String VERSION_PATTERN =
            "(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})";

    /**
     * 模拟器默认声明：TC-SIM-01 单板、bootloader 1.0.0、未提交安全版本 0。
     *
     * <p>物模型版本与 Schema 摘要是模拟器的静态声明（现场若要与真实物模型对齐，必须显式构造本记录），
     * 不是平台发布清单里的字段。</p>
     */
    public static final OtaSimulatorEvidence DEFAULT = new OtaSimulatorEvidence(
            "TC-SIM-01", 1L, "1.0.0", UUID.fromString("01920000-0000-7000-8000-000000000001"),
            SCHEMA_DIGEST_ALGORITHM, sha256Hex("thingslink-simulator-thing-model-schema"), "trust.simulator",
            sha256Hex("thingslink-simulator-root"), 1L, sha256Hex("thingslink-simulator-trust-bundle"), 1L, 0L);

    /**
     * 校验声明取值；非法取值必须在设备发出报文之前失败。
     */
    public OtaSimulatorEvidence {
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("证据硬件型号必须匹配平台字符集");
        }
        if (boardRevision < 0L) {
            throw new IllegalArgumentException("板修订号不能为负数");
        }
        if (bootloaderVersion == null || !bootloaderVersion.matches(VERSION_PATTERN)) {
            throw new IllegalArgumentException("bootloader 版本必须是三轴语义版本");
        }
        Objects.requireNonNull(thingModelVersionId, "thingModelVersionId");
        if (!SCHEMA_DIGEST_ALGORITHM.equals(thingModelSchemaDigestAlgorithm)) {
            throw new IllegalArgumentException("Schema 摘要算法必须是平台冻结取值");
        }
        requireHex(thingModelSchemaDigest, "Schema 摘要");
        if (trustDomain == null || !trustDomain.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("信任域必须匹配平台字符集");
        }
        requireHex(rootFingerprint, "根指纹");
        if (trustBundleVersion < 1L) {
            throw new IllegalArgumentException("信任包版本必须为正");
        }
        requireHex(trustBundleSha256, "信任包摘要");
        if (securityVersion < 0L || committedSecurityVersion < 0L
                || committedSecurityVersion > securityVersion) {
            throw new IllegalArgumentException("已提交安全版本必须位于 0..当前安全版本");
        }
    }

    /**
     * 为一次阶段转换生成进度证据。
     *
     * <p><b>取值规则（逐条对应平台 ADR0131 的证据语义）：</b>
     * {@code VERIFYING} 声明 {@code verification=NOT_STARTED}：完整摘要核对还在进行，设备不能提前宣称通过；
     * {@code INSTALLING}/{@code REBOOTING} 改声明 {@code verification=PASSED}（摘要核对确已通过）并只带安全版本，
     * 不声称自检或看门狗健康；{@code HEALTH_CHECKING} 是唯一需要 {@code selfTestPassed}/
     * {@code watchdogHealthy} 的取值，只有调用方通过 {@code assertHealth} 显式声明（真实硬件/受控
     * 场景）时才会生成——模拟器默认不会走到这一步，因此默认也不会伪造健康断言。</p>
     *
     * @param transition 本次执行新写入日志的阶段转换
     * @param artifactSha256 目标 artifact 摘要
     * @param artifactSize 目标 artifact 尺寸
     * @param assertHealth 是否允许声明自检与看门狗健康
     * @return 与该阶段相符的十九字段证据
     * @throws IllegalArgumentException 阶段落在平台闭集之外时
     */
    public OtaDeviceJobProgressCodec.Evidence stageEvidence(OtaJournalRecord transition, String artifactSha256,
                                                            long artifactSize, boolean assertHealth) {
        Objects.requireNonNull(transition, "transition");
        return evidenceForStage(transition.stage().name(), artifactSha256, artifactSize, assertHealth);
    }

    /**
     * 为一次<b>真实提交</b>生成回执证据：同一份已安装事实，但 {@code committedSecurityVersion} 必须等于
     * 目标 {@code securityVersion}。
     *
     * <p><b>为什么不能复制提交前的进度证据：</b>ADR0131 要求预提交进度回显的
     * {@code committedSecurityVersion} 是<b>原执行来源</b>已提交的旧值（安全下限），它证明的是「我还没有
     * 提交」；而平台 {@code OtaConfirmationIngestionService.acceptCommit} 对回执单独冻结
     * 「{@code committedSecurityVersion} 必须等于 {@code securityVersion}」这条不变量（矛盾即以
     * {@code COMMITTED_TARGET_MISMATCH} 拒绝）。若设备把预提交证据原样复制来回执，平台会看到一份
     * 自相矛盾的「已提交」声明。因此本方法只把已提交事实里的 {@code securityVersion}（本次安装目标）
     * 写进 {@code committedSecurityVersion}，其余字段仍逐条来自设备配置与本次真实安装结果。</p>
     *
     * <p><b>为什么必须是健康阶段形态：</b>提交回执是「安装后自检与看门狗都通过」之后的最终结论，
     * 平台按 {@code HEALTH_CHECKING} 形态校验 {@code verification=PASSED}、三个健康标志位与活动槽。
     * 因此这里复用健康阶段证据策略，并在未显式声明健康事实时失败关闭，绝不自行断言硬件健康。</p>
     *
     * @param artifactSha256 本次真实安装并核对通过的 artifact 摘要
     * @param artifactSize 本次真实安装的 artifact 尺寸
     * @param assertHealth 是否允许声明自检与看门狗健康（必须由掌握该事实的调用方显式声明）
     * @return 与已提交事实相符的十九字段证据
     * @throws IllegalArgumentException 未显式声明健康事实，或阶段证据本身不合法时
     */
    public OtaDeviceJobProgressCodec.Evidence commitEvidence(String artifactSha256, long artifactSize,
                                                              boolean assertHealth) {
        OtaDeviceJobProgressCodec.Evidence staged =
                evidenceForStage("HEALTH_CHECKING", artifactSha256, artifactSize, assertHealth);
        return new OtaDeviceJobProgressCodec.Evidence(staged.artifactSha256(), staged.artifactSize(),
                staged.securityVersion(), securityVersion, staged.thingModelVersionId(),
                staged.thingModelSchemaDigestAlgorithm(), staged.thingModelSchemaDigest(), staged.propertyProfile(),
                staged.trustDomain(), staged.rootFingerprint(), staged.trustBundleVersion(),
                staged.trustBundleSha256(), staged.sourceSlot(), staged.targetSlot(), staged.activeSlot(),
                staged.verification(), staged.bootVerified(), staged.selfTestPassed(), staged.watchdogHealthy());
    }

    /** 按阶段名生成证据；健康阶段的槽位与标志位规则集中在这里，避免提交证据复制一份漂移的副本。 */
    private OtaDeviceJobProgressCodec.Evidence evidenceForStage(String stage, String artifactSha256,
                                                                long artifactSize, boolean assertHealth) {
        boolean healthy = "HEALTH_CHECKING".equals(stage);
        if (healthy && !assertHealth) {
            throw new IllegalArgumentException("未显式声明健康事实时不得生成 HEALTH_CHECKING 证据");
        }
        boolean installStarted = "INSTALLING".equals(stage) || "REBOOTING".equals(stage) || healthy;
        String targetSlot = "B";
        String activeSlot = healthy ? targetSlot : "A";
        // 槽位是模拟设备自己声明的 A/B 切换意图：安装前活动槽是来源槽，健康检查通过后活动槽才切到目标槽。
        String sourceSlot = "A";
        return new OtaDeviceJobProgressCodec.Evidence(artifactSha256, artifactSize,
                securityVersion, committedSecurityVersion, thingModelVersionId, thingModelSchemaDigestAlgorithm,
                thingModelSchemaDigest, PROPERTY_PROFILE, trustDomain, rootFingerprint, trustBundleVersion,
                trustBundleSha256, sourceSlot, targetSlot, activeSlot,
                installStarted ? "PASSED" : "NOT_STARTED", healthy, healthy, healthy);
    }

    /**
     * 构造安装前停止报告的证据。
     *
     * <p>{@code stopBaselineSha256} 直接来自平台下发的停止操作：设备只如实回显「平台声明的停止基线」，
     * 不声称自己独立验证过基线资格（那需要受保护计数器和制造基线）。{@code writeState} 表示日志写入
     * 是否已经静止：停止先赢时设备已把取消记录 fsync 并停止取字节，因此是 {@code QUIESCENT}。</p>
     *
     * @param stopBaselineSha256 平台停止操作声明的停止基线摘要
     * @param hardware 设备硬件身份
     * @param journalRevision 报告时的设备日志修订号
     * @param writeState 写入状态，取自平台闭集 {@code QUIESCENT}/{@code WRITING}/{@code UNKNOWN}
     * @param stopOperations 已接纳的停止日志，零或一项
     * @param installOperations 已接纳的安装日志，零或一项
     * @return 完整的停止报告证据
     */
    public static OtaDeviceInstallStopReportCodec.Evidence reportEvidence(String stopBaselineSha256, Hardware hardware,
                                                                         long journalRevision, String writeState,
                                                                         List<StopOperation> stopOperations,
                                                                         List<InstallOperation> installOperations) {
        Objects.requireNonNull(hardware, "hardware");
        Objects.requireNonNull(stopOperations, "stopOperations");
        Objects.requireNonNull(installOperations, "installOperations");
        return new OtaDeviceInstallStopReportCodec.Evidence(stopBaselineSha256, hardware.bootloaderVersion(),
                new OtaDeviceInstallStopReportCodec.Hardware(hardware.model(), hardware.boardRevision()),
                OtaDeviceInstallStopReportCodec.ATOMIC_OPERATION_PROFILE, journalRevision, writeState,
                stopOperations.stream().map(stop -> new OtaDeviceInstallStopReportCodec.StopOperation(
                        stop.operationId(), stop.operationSha256(), stop.acceptedBootId(), stop.acceptedRevision(),
                        "STOPPED")).toList(),
                installOperations.stream().map(install -> new OtaDeviceInstallStopReportCodec.InstallOperation(
                        install.authorizationId(), install.acceptedBootId(), install.acceptedRevision(),
                        "INSTALL_ACCEPTED")).toList());
    }

    /**
     * 构造报告使用的硬件身份。
     *
     * @param model 设备型号
     * @param boardRevision 板修订号
     * @param bootloaderVersion bootloader 版本
     * @return 不可变硬件身份
     */
    public static Hardware hardware(String model, long boardRevision, String bootloaderVersion) {
        return new Hardware(model, boardRevision, bootloaderVersion);
    }

    /**
     * 构造一条已接纳的停止日志。
     *
     * @param operationId 停止操作身份
     * @param operationSha256 停止操作规范摘要
     * @param acceptedBootId 首次接受时的启动身份
     * @param acceptedRevision 首次接受时的日志修订
     * @return 不可变停止日志项
     */
    public static StopOperation acceptedStop(UUID operationId, String operationSha256, UUID acceptedBootId,
                                             long acceptedRevision) {
        return new StopOperation(operationId, operationSha256, acceptedBootId, acceptedRevision);
    }

    /**
     * 构造一条已接纳的安装日志；授权标识必须来自平台真实下发的下载响应，不能由设备自造。
     *
     * @param authorizationId 平台封存的下载授权标识
     * @param acceptedBootId 首次接受时的启动身份
     * @param acceptedRevision 首次接受时的日志修订
     * @return 不可变安装日志项
     */
    public static InstallOperation acceptedInstall(UUID authorizationId, UUID acceptedBootId, long acceptedRevision) {
        return new InstallOperation(authorizationId, acceptedBootId, acceptedRevision);
    }

    /**
     * 判断给定阶段的证据是否已经声明安装开始。
     *
     * @param stage 设备阶段
     * @return {@code true} 表示该阶段属于安全阶段
     */
    public static boolean installStarted(OtaStage stage) {
        return stage != null && stage.isSafetyStage();
    }

    /** 校验摘要文本。 */
    private static void requireHex(String value, String field) {
        if (value == null || !value.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException(field + " 必须是 64 位小写十六进制");
        }
    }

    /** 对声明文本取 SHA-256，保证默认证据可复现且不含随机值。 */
    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行时不提供 SHA-256", failure);
        }
    }

    /**
     * 报告证据使用的硬件身份。
     *
     * @param model 设备型号
     * @param boardRevision 板修订号
     * @param bootloaderVersion bootloader 版本
     */
    public record Hardware(String model, long boardRevision, String bootloaderVersion) {

        /** 冻结报告证据里的硬件取值。 */
        public Hardware {
            if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("硬件型号必须匹配平台字符集");
            }
            if (boardRevision < 0L) {
                throw new IllegalArgumentException("板修订号不能为负数");
            }
            if (bootloaderVersion == null || !bootloaderVersion.matches(VERSION_PATTERN)) {
                throw new IllegalArgumentException("bootloader 版本必须是三轴语义版本");
            }
        }
    }

    /**
     * 已接纳的停止日志（报告投影）。
     *
     * @param operationId 停止操作身份
     * @param operationSha256 停止操作规范摘要
     * @param acceptedBootId 首次接受时的启动身份
     * @param acceptedRevision 首次接受时的日志修订
     */
    public record StopOperation(UUID operationId, String operationSha256, UUID acceptedBootId,
                                long acceptedRevision) {

        /** 停止日志必须绑定真实操作与规范摘要。 */
        public StopOperation {
            Objects.requireNonNull(operationId, "operationId");
            requireHex(operationSha256, "停止操作摘要");
            Objects.requireNonNull(acceptedBootId, "acceptedBootId");
            if (acceptedRevision < 1L) {
                throw new IllegalArgumentException("停止日志接受修订必须为正");
            }
        }
    }

    /**
     * 已接纳的安装日志（报告投影）。
     *
     * @param authorizationId 平台封存的下载授权标识
     * @param acceptedBootId 首次接受时的启动身份
     * @param acceptedRevision 首次接受时的日志修订
     */
    public record InstallOperation(UUID authorizationId, UUID acceptedBootId, long acceptedRevision) {

        /** 安装日志必须绑定平台真实授权。 */
        public InstallOperation {
            Objects.requireNonNull(authorizationId, "authorizationId");
            Objects.requireNonNull(acceptedBootId, "acceptedBootId");
            if (acceptedRevision < 1L) {
                throw new IllegalArgumentException("安装日志接受修订必须为正");
            }
        }
    }

    /**
     * 返回<b>不</b>声明任何健康/自检事实的按阶段证据构建器。
     *
     * <p>存在的意义是给「模拟器只报告它能证明的阶段」这条策略一个显式名字，避免调用方误以为
     * 返回值里已经包含健康观察。</p>
     *
     * @param transition 阶段转换
     * @param artifactSha256 目标 artifact 摘要
     * @param artifactSize 目标 artifact 尺寸
     * @return 与该阶段相符、且不额外声明健康事实的证据
     */
    public OtaDeviceJobProgressCodec.Evidence stageEvidenceWithoutHealth(OtaJournalRecord transition,
                                                                        String artifactSha256, long artifactSize) {
        return stageEvidence(transition, artifactSha256, artifactSize, false);
    }
}
