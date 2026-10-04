package com.things.link.simulator.application.ota.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 设备侧健康确认合同编解码：{@code tc-ota-health/v1}（八字段 + 二十一字段证据）。
 *
 * <p>字段与平台 {@code OtaHealthCodec} 完全一致：证据在 JSON 内平铺——完整十九字段目标观察
 * 再加 {@code uptimeMillis} 与 {@code healthyForMillis} 两个安全整数，且
 * {@code healthyForMillis <= uptimeMillis}。</p>
 *
 * <p><b>为什么不自动填健康值：</b>「健康窗口是否满足」是设备对自身连续运行与自检的真实观察，
 * 单条健康报文并不能证明整个稳定窗口（ADR0132）。本类只编码调用方给出的观察；模拟器不会
 * 自行断言 {@code selfTestPassed=true} 或某个健康时长，避免把模拟结果当成硬件事实。</p>
 */
public final class OtaDeviceHealthCodec {

    /** 冻结合同版本。 */
    public static final String CONTRACT_VERSION = "tc-ota-health/v1";

    /** 平台对该合同冻结的原始字节上限。 */
    public static final int MAX_BYTES = 16_384;

    /** 精确顶层字段，不接受未来扩展。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "jobId", "attemptNo", "authorizationId",
            "manifestSha256", "healthSeq", "bootId", "evidence");

    /** 唯一规范 JSON 实现。 */
    private static final OtaCanonicalJson JSON = new OtaCanonicalJson();

    /** 工具类不允许实例化。 */
    private OtaDeviceHealthCodec() {
    }

    /**
     * 把类型化健康确认编码为平台可解码的规范字节。
     *
     * @param value 健康事实
     * @return 规范 UTF-8 字节
     * @throws IllegalArgumentException 字段缺失、健康时长越界或版本错误时
     */
    public static byte[] encode(Health value) {
        return decode(JSON.writeObject(toMap(value))).canonical();
    }

    /**
     * 严格解析健康确认。
     *
     * @param input 收到的原始 UTF-8 字节
     * @return 类型化健康确认、规范字节与规范摘要
     * @throws IllegalArgumentException 长度、闭集、版本、整数或规范形态不符时
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
            Map<String, Object> evidenceFields = new LinkedHashMap<>(OtaContractFields.object(fields.get("evidence")));
            long uptime = OtaContractFields.integer(evidenceFields, "uptimeMillis", 0,
                    OtaContractFields.MAX_SAFE_INTEGER);
            long healthyFor = OtaContractFields.integer(evidenceFields, "healthyForMillis", 0, uptime);
            evidenceFields.remove("uptimeMillis");
            evidenceFields.remove("healthyForMillis");
            // 复用进度证据的十九字段闭集，保证两份协议对同一目标元组只有一套词法。
            OtaDeviceJobProgressCodec.Evidence target = OtaDeviceJobProgressCodec.evidence(evidenceFields);
            Health health = new Health(CONTRACT_VERSION,
                    OtaContractFields.uuid(fields, "jobId"),
                    OtaContractFields.intValue(fields, "attemptNo", 1, Integer.MAX_VALUE),
                    OtaContractFields.uuid(fields, "authorizationId"),
                    OtaContractFields.hex(fields, "manifestSha256"),
                    OtaContractFields.integer(fields, "healthSeq", 1, OtaContractFields.MAX_SAFE_INTEGER),
                    OtaContractFields.uuid(fields, "bootId"),
                    new HealthEvidence(target, uptime, healthyFor));
            byte[] canonical = JSON.writeObject(fields);
            OtaContractFields.requireCanonical(input, canonical);
            return new Decoded(health, canonical, OtaContractFields.sha256Hex(canonical));
        } catch (RuntimeException exception) {
            throw OtaContractFields.invalid();
        }
    }

    /** 仅输出明确白名单字段；证据保持平铺。 */
    private static Map<String, Object> toMap(Health input) {
        if (input == null || input.evidence() == null) {
            throw OtaContractFields.invalid();
        }
        Map<String, Object> evidence = OtaDeviceJobProgressCodec.encodeEvidence(input.evidence().target());
        evidence.put("uptimeMillis", input.evidence().uptimeMillis());
        evidence.put("healthyForMillis", input.evidence().healthyForMillis());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("authorizationId", String.valueOf(input.authorizationId()));
        value.put("manifestSha256", input.manifestSha256());
        value.put("healthSeq", input.healthSeq());
        value.put("bootId", String.valueOf(input.bootId()));
        value.put("evidence", evidence);
        return value;
    }

    /** 不可变协议字段。
     *
     * @param contractVersion 协议版本
     * @param jobId 原作业
     * @param attemptNo 原尝试
     * @param authorizationId 原封存授权
     * @param manifestSha256 原发布清单摘要
     * @param healthSeq 单调健康序号
     * @param bootId 当前启动身份
     * @param evidence 完整健康证据
     */
    public record Health(String contractVersion, UUID jobId, int attemptNo, UUID authorizationId,
            String manifestSha256, long healthSeq, UUID bootId, HealthEvidence evidence) {
    }

    /** 健康证据在 JSON 内平铺，Java 组合保持目标证据语义独立。
     *
     * @param target 完整十九字段目标观察
     * @param uptimeMillis 当前启动持续毫秒
     * @param healthyForMillis 连续健康毫秒
     */
    public record HealthEvidence(OtaDeviceJobProgressCodec.Evidence target, long uptimeMillis,
            long healthyForMillis) {
    }

    /** 已解析的不可变合同投影。
     *
     * @param value 类型化健康确认
     * @param canonical 规范 JSON 字节
     * @param sha256 规范字节摘要
     */
    public record Decoded(Health value, byte[] canonical, String sha256) {

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
