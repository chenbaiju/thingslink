package com.things.link.simulator.application.ota.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 设备侧认证执行进度合同编解码：{@code tc-ota-job-progress/v1}（九字段 + 十九字段证据）。
 *
 * <p>字段、闭集与边界与平台 {@code OtaJobProgressCodec} 完全一致。这里只做词法合同：</p>
 * <ul>
 *   <li>平台只接受四个阶段 {@code VERIFYING}/{@code INSTALLING}/{@code REBOOTING}/{@code HEALTH_CHECKING}；
 *       {@code DOWNLOADING} 与终态不在这份合同里，因此模拟器不能把下载进度塞进本协议。</li>
 *   <li>证据里 {@code verification}/{@code bootVerified}/{@code selfTestPassed}/{@code watchdogHealthy}
 *       是设备对自身观察的声明。本类<b>不</b>替设备编造这些值：调用方给什么就编码什么，语义矛盾
 *       由平台安全裁决。</li>
 *   <li>摘要覆盖完整规范字节；本类不提供自引用摘要字段。</li>
 * </ul>
 */
public final class OtaDeviceJobProgressCodec {

    /** 冻结合同版本。 */
    public static final String CONTRACT_VERSION = "tc-ota-job-progress/v1";

    /** 平台对该合同冻结的原始字节上限。 */
    public static final int MAX_BYTES = 16_384;

    /** 顶层完整九字段。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "jobId", "attemptNo", "progressSeq",
            "authorizationId", "manifestSha256", "stage", "bootId", "evidence");

    /** 证据闭集，不接收设备指定的平台期限或状态修订。 */
    private static final Set<String> EVIDENCE = Set.of("artifactSha256", "artifactSize", "securityVersion",
            "committedSecurityVersion", "thingModelVersionId", "thingModelSchemaDigestAlgorithm",
            "thingModelSchemaDigest", "propertyProfile", "trustDomain", "rootFingerprint", "trustBundleVersion",
            "trustBundleSha256", "sourceSlot", "targetSlot", "activeSlot", "verification", "bootVerified",
            "selfTestPassed", "watchdogHealthy");

    /** 平台可识别的槽标识。 */
    private static final Set<String> SLOTS = Set.of("A", "B", "SINGLE");

    /** 平台唯一接受的阶段闭集。 */
    private static final Set<String> STAGES = Set.of("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING");

    /** 唯一规范 JSON 实现。 */
    private static final OtaCanonicalJson JSON = new OtaCanonicalJson();

    /** 工具类不允许实例化。 */
    private OtaDeviceJobProgressCodec() {
    }

    /**
     * 把类型化进度编码为平台可解码的规范字节。
     *
     * @param value 进度事实
     * @return 规范 UTF-8 字节
     * @throws IllegalArgumentException 字段缺失、阶段越界、证据不一致或版本错误时
     */
    public static byte[] encode(Progress value) {
        return decode(JSON.writeObject(toMap(value))).canonical();
    }

    /**
     * 严格解析进度报文。
     *
     * @param input 收到的原始 UTF-8 字节
     * @return 类型化进度、规范字节与规范摘要
     * @throws IllegalArgumentException 长度、闭集、版本、枚举、整数或规范形态不符时
     */
    public static Decoded decode(byte[] input) {
        try {
            if (input == null || input.length == 0 || input.length > MAX_BYTES) {
                throw OtaContractFields.invalid();
            }
            Map<String, Object> fields = JSON.parseObject(input);
            OtaContractFields.closed(fields, FIELDS);
            if (!CONTRACT_VERSION.equals(fields.get("contractVersion"))) {
                throw OtaContractFields.invalid();
            }
            Map<String, Object> evidenceFields = OtaContractFields.object(fields.get("evidence"));
            OtaContractFields.closed(evidenceFields, EVIDENCE);
            Progress progress = new Progress(CONTRACT_VERSION,
                    OtaContractFields.uuid(fields, "jobId"),
                    OtaContractFields.intValue(fields, "attemptNo", 1, Integer.MAX_VALUE),
                    OtaContractFields.integer(fields, "progressSeq", 1, OtaContractFields.MAX_SAFE_INTEGER),
                    OtaContractFields.uuid(fields, "authorizationId"),
                    OtaContractFields.hex(fields, "manifestSha256"),
                    OtaContractFields.literal(fields, "stage", STAGES),
                    OtaContractFields.uuid(fields, "bootId"),
                    evidence(evidenceFields));
            byte[] canonical = JSON.writeObject(fields);
            OtaContractFields.requireCanonical(input, canonical);
            return new Decoded(progress, canonical, OtaContractFields.sha256Hex(canonical));
        } catch (RuntimeException exception) {
            throw OtaContractFields.invalid();
        }
    }

    /** 解析十九字段证据闭集；健康合同复用同一词法，不另造一套字段规则。 */
    static Evidence evidence(Map<String, Object> fields) {
        return new Evidence(OtaContractFields.hex(fields, "artifactSha256"),
                OtaContractFields.integer(fields, "artifactSize", 1, 67_108_864),
                OtaContractFields.integer(fields, "securityVersion", 0, OtaContractFields.MAX_SAFE_INTEGER),
                OtaContractFields.integer(fields, "committedSecurityVersion", 0, OtaContractFields.MAX_SAFE_INTEGER),
                OtaContractFields.uuid(fields, "thingModelVersionId"),
                OtaContractFields.literal(fields, "thingModelSchemaDigestAlgorithm", Set.of("PG_JSONB_TEXT_V1_SHA256")),
                OtaContractFields.hex(fields, "thingModelSchemaDigest"),
                OtaContractFields.literal(fields, "propertyProfile", Set.of("TC_PROPERTY_COMPOSITE_V1")),
                OtaContractFields.text(fields, "trustDomain", "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"),
                OtaContractFields.hex(fields, "rootFingerprint"),
                OtaContractFields.integer(fields, "trustBundleVersion", 1, OtaContractFields.MAX_SAFE_INTEGER),
                OtaContractFields.hex(fields, "trustBundleSha256"),
                OtaContractFields.literal(fields, "sourceSlot", SLOTS),
                OtaContractFields.literal(fields, "targetSlot", SLOTS),
                OtaContractFields.literal(fields, "activeSlot", SLOTS),
                OtaContractFields.literal(fields, "verification", Set.of("NOT_STARTED", "PASSED")),
                OtaContractFields.flag(fields, "bootVerified"),
                OtaContractFields.flag(fields, "selfTestPassed"),
                OtaContractFields.flag(fields, "watchdogHealthy"));
    }

    /**
     * 只校验十九字段证据闭集，不解析取值。
     *
     * <p>提交回执与进度、健康共用同一份证据词法：回执单独复用它，避免「三份协议各自维护一份字段集合」
     * 导致某一份悄悄接受未知字段、而平台闭集拒绝。校验发生在取值之前，未知/缺失都失败关闭。</p>
     *
     * @param fields 证据字段映射
     * @throws IllegalArgumentException 字段集合不等于十九字段闭集时
     */
    static void requireEvidenceClosed(Map<String, Object> fields) {
        OtaContractFields.closed(fields, EVIDENCE);
    }

    /** 仅输出明确白名单字段。 */
    private static Map<String, Object> toMap(Progress input) {
        if (input == null) {
            throw OtaContractFields.invalid();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("progressSeq", input.progressSeq());
        value.put("authorizationId", String.valueOf(input.authorizationId()));
        value.put("manifestSha256", input.manifestSha256());
        value.put("stage", input.stage());
        value.put("bootId", String.valueOf(input.bootId()));
        value.put("evidence", encodeEvidence(input.evidence()));
        return value;
    }

    /**
     * 只序列化明确证据字段，不把对象实现细节带入规范合同。
     *
     * @param evidence 设备观察证据
     * @return 十九字段映射
     */
    static Map<String, Object> encodeEvidence(Evidence evidence) {
        if (evidence == null) {
            throw OtaContractFields.invalid();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("artifactSha256", evidence.artifactSha256());
        value.put("artifactSize", evidence.artifactSize());
        value.put("securityVersion", evidence.securityVersion());
        value.put("committedSecurityVersion", evidence.committedSecurityVersion());
        value.put("thingModelVersionId", String.valueOf(evidence.thingModelVersionId()));
        value.put("thingModelSchemaDigestAlgorithm", evidence.thingModelSchemaDigestAlgorithm());
        value.put("thingModelSchemaDigest", evidence.thingModelSchemaDigest());
        value.put("propertyProfile", evidence.propertyProfile());
        value.put("trustDomain", evidence.trustDomain());
        value.put("rootFingerprint", evidence.rootFingerprint());
        value.put("trustBundleVersion", evidence.trustBundleVersion());
        value.put("trustBundleSha256", evidence.trustBundleSha256());
        value.put("sourceSlot", evidence.sourceSlot());
        value.put("targetSlot", evidence.targetSlot());
        value.put("activeSlot", evidence.activeSlot());
        value.put("verification", evidence.verification());
        value.put("bootVerified", evidence.bootVerified());
        value.put("selfTestPassed", evidence.selfTestPassed());
        value.put("watchdogHealthy", evidence.watchdogHealthy());
        return value;
    }

    /** 完整设备进度，不包含自报设备身份。
     *
     * @param contractVersion 固定合同
     * @param jobId 原作业
     * @param attemptNo 原尝试
     * @param progressSeq 单调进度序号
     * @param authorizationId 原封存授权
     * @param manifestSha256 原发布清单摘要
     * @param stage 声明阶段
     * @param bootId 当前启动身份
     * @param evidence 完整观察证据
     */
    public record Progress(String contractVersion, UUID jobId, int attemptNo, long progressSeq, UUID authorizationId,
            String manifestSha256, String stage, UUID bootId, Evidence evidence) {
    }

    /** 证据词法投影，语义矛盾仍交给真实安全裁决。
     *
     * @param artifactSha256 设备观察的 artifact 摘要
     * @param artifactSize 设备观察的 artifact 尺寸
     * @param securityVersion 设备观察的安全版本
     * @param committedSecurityVersion 设备观察的已提交安全版本
     * @param thingModelVersionId 设备观察的物模型版本
     * @param thingModelSchemaDigestAlgorithm 固定 Schema 摘要算法
     * @param thingModelSchemaDigest Schema 摘要
     * @param propertyProfile 固定属性 Profile
     * @param trustDomain 信任域
     * @param rootFingerprint 根公钥指纹
     * @param trustBundleVersion 信任包版本
     * @param trustBundleSha256 信任包摘要
     * @param sourceSlot 来源槽
     * @param targetSlot 目标槽
     * @param activeSlot 当前活动槽
     * @param verification 验证声明
     * @param bootVerified 启动自检声明
     * @param selfTestPassed 自检声明
     * @param watchdogHealthy 看门狗声明
     */
    public record Evidence(String artifactSha256, long artifactSize, long securityVersion,
            long committedSecurityVersion, UUID thingModelVersionId, String thingModelSchemaDigestAlgorithm,
            String thingModelSchemaDigest, String propertyProfile, String trustDomain, String rootFingerprint,
            long trustBundleVersion, String trustBundleSha256, String sourceSlot, String targetSlot,
            String activeSlot, String verification, boolean bootVerified, boolean selfTestPassed,
            boolean watchdogHealthy) {
    }

    /** 已解析的不可变合同投影。
     *
     * @param value 类型化进度
     * @param canonical 规范 JSON 字节
     * @param sha256 规范字节摘要
     */
    public record Decoded(Progress value, byte[] canonical, String sha256) {

        /** 防止调用者修改内部规范字节。 */
        public Decoded {
            canonical = canonical.clone();
        }

        /**
         * @return 独立字节副本
         */
        @Override
        public byte[] canonical() {
            return canonical.clone();
        }
    }
}
