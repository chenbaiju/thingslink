package com.things.link.simulator.application.ota.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 设备侧真实提交回执合同编解码：{@code tc-ota-commit-receipt/v1}（九字段 + 十九字段证据）。
 *
 * <p>字段、闭集与边界与平台 {@code OtaCommitReceiptCodec} 完全一致，规范字节与平台权威解码器
 * 重新编码的结果逐字节可比。{@code evidence} 复用进度合同同一份十九字段证据记录：提交回执不是
 * 另一种证据语言，而是「设备已经提交」这一事实在同一目标元组上的投影，因此不能另造字段集合。</p>
 *
 * <p><b>本类刻意不做的事：</b>不判断 {@code committedSecurityVersion} 是否等于
 * {@code securityVersion}、不判断 {@code bootId} 是否与本次启动一致——那些是设备事实，必须由持有
 * 耐久日志与安装结论的 {@code OtaDeviceRuntime} 构造。本类只保证「设备发出的字节与平台合同一致」，
 * 绝不替运行时伪造一次提交。</p>
 *
 * <p><b>为什么证据闭集单独复核：</b>平台 {@code OtaConfirmationJson.evidence} 会拒绝未知字段，
 * 设备若宽松地忽略未知字段，就会产出一份自己解得开、平台拒收的报文。因此这里在取值之前先做
 * 十九字段闭集校验，失败方向与平台一致。</p>
 */
public final class OtaDeviceCommitReceiptCodec {

    /** 冻结合同版本。 */
    public static final String CONTRACT_VERSION = "tc-ota-commit-receipt/v1";

    /** 平台对该合同冻结的原始字节上限。 */
    public static final int MAX_BYTES = 16_384;

    /** 精确顶层字段，不接受未来扩展。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "jobId", "attemptNo", "authorizationId",
            "manifestSha256", "receiptId", "permitId", "bootId", "evidence");

    /** 唯一规范 JSON 实现。 */
    private static final OtaCanonicalJson JSON = new OtaCanonicalJson();

    /** 工具类不允许实例化。 */
    private OtaDeviceCommitReceiptCodec() {
    }

    /**
     * 把类型化提交回执编码为平台可解码的规范字节。
     *
     * @param value 提交事实
     * @return 规范 UTF-8 字节
     * @throws IllegalArgumentException 字段缺失、版本错误或证据字段不合法时
     */
    public static byte[] encode(Receipt value) {
        return decode(JSON.writeObject(toMap(value))).canonical();
    }

    /**
     * 严格解析提交回执。
     *
     * @param input 收到的原始 UTF-8 字节
     * @return 类型化回执、规范字节与规范摘要
     * @throws IllegalArgumentException 长度、闭集、版本、整数、证据闭集或规范形态不符时
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
            OtaDeviceJobProgressCodec.requireEvidenceClosed(evidenceFields);
            OtaDeviceJobProgressCodec.Evidence evidence = OtaDeviceJobProgressCodec.evidence(evidenceFields);
            Receipt receipt = new Receipt(CONTRACT_VERSION,
                    OtaContractFields.uuid(fields, "jobId"),
                    OtaContractFields.intValue(fields, "attemptNo", 1, Integer.MAX_VALUE),
                    OtaContractFields.uuid(fields, "authorizationId"),
                    OtaContractFields.hex(fields, "manifestSha256"),
                    OtaContractFields.uuid(fields, "receiptId"),
                    OtaContractFields.uuid(fields, "permitId"),
                    OtaContractFields.uuid(fields, "bootId"),
                    evidence);
            byte[] canonical = JSON.writeObject(fields);
            OtaContractFields.requireCanonical(input, canonical);
            return new Decoded(receipt, canonical, OtaContractFields.sha256Hex(canonical));
        } catch (RuntimeException exception) {
            throw OtaContractFields.invalid();
        }
    }

    /** 仅输出明确白名单字段；证据保持十九字段闭集。 */
    private static Map<String, Object> toMap(Receipt input) {
        if (input == null || input.evidence() == null) {
            throw OtaContractFields.invalid();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("authorizationId", String.valueOf(input.authorizationId()));
        value.put("manifestSha256", input.manifestSha256());
        value.put("receiptId", String.valueOf(input.receiptId()));
        value.put("permitId", String.valueOf(input.permitId()));
        value.put("bootId", String.valueOf(input.bootId()));
        value.put("evidence", OtaDeviceJobProgressCodec.encodeEvidence(input.evidence()));
        return value;
    }

    /** 不可变提交回执字段。
     *
     * @param contractVersion 固定合同
     * @param jobId 原作业
     * @param attemptNo 原尝试
     * @param authorizationId 原封存授权
     * @param manifestSha256 原发布清单摘要
     * @param receiptId 设备本次生成的回执身份
     * @param permitId 被消费的平台许可身份
     * @param bootId 提交时设备当前启动身份
     * @param evidence 完整十九字段已提交证据
     */
    public record Receipt(String contractVersion, UUID jobId, int attemptNo, UUID authorizationId,
            String manifestSha256, UUID receiptId, UUID permitId, UUID bootId,
            OtaDeviceJobProgressCodec.Evidence evidence) {
    }

    /** 已解析的不可变合同投影。
     *
     * @param value 类型化回执
     * @param canonical 规范 JSON 字节
     * @param sha256 规范字节摘要
     */
    public record Decoded(Receipt value, byte[] canonical, String sha256) {

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
