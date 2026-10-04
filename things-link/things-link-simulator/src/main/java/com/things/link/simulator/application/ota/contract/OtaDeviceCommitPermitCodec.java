package com.things.link.simulator.application.ota.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 设备侧唯一提交许可合同编解码：{@code tc-ota-commit-permit/v1}（十一字段）。
 *
 * <p>字段、闭集与边界与平台 {@code OtaCommitPermitCodec} 完全一致：许可正文由平台在健康窗口确认时
 * 一次性编码并持久，之后只允许按原字节重传。<b>设备侧不重签、不改写、不补字段</b>：任何字段名、
 * 版本串、整数宽度或槽位枚举的漂移都会让设备读到一份平台并未签发的许可，
 * 因此这里对未知字段、缺失字段、重复字段、错误类型、越界整数与非规范字节全部失败关闭。</p>
 *
 * <p><b>为什么解码器不裁决设备安全事实：</b>本类只证明「设备能按平台的线格式读出许可」，
 * 不判断许可是否针对本机、是否过期、是否匹配已安装版本。那些裁决必须由持有设备耐久事实的
 * {@code OtaDeviceRuntime} 完成——把「解析成功」伪装成「已授权提交」正是本片要避免的伪造。</p>
 */
public final class OtaDeviceCommitPermitCodec {

    /** 冻结合同版本。 */
    public static final String CONTRACT_VERSION = "tc-ota-commit-permit/v1";

    /** 平台对该合同冻结的原始字节上限。 */
    public static final int MAX_BYTES = 16_384;

    /** 精确顶层字段，不接受未来扩展。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "permitId", "jobId", "attemptNo",
            "authorizationId", "manifestSha256", "bootId", "securityVersion", "artifactSha256", "targetSlot",
            "expiresAt");

    /**
     * 平台许可冻结的槽位闭集。
     *
     * <p>这里刻意与进度证据的 {@code A/B/SINGLE} 不同：许可必须声明真实的 A/B 切换目标，
     * {@code SINGLE} 在许可层不是合法取值。逐字对齐平台 {@code OtaCommitPermitCodec}。</p>
     */
    private static final Set<String> SLOTS = Set.of("A", "B");

    /** 唯一规范 JSON 实现。 */
    private static final OtaCanonicalJson JSON = new OtaCanonicalJson();

    /** 工具类不允许实例化。 */
    private OtaDeviceCommitPermitCodec() {
    }

    /**
     * 把类型化许可编码为平台可解码的规范字节。
     *
     * <p>主要用于跨模块线合同对照与受控测试；生产设备只解码、不产生许可。</p>
     *
     * @param value 许可事实
     * @return 规范 UTF-8 字节
     * @throws IllegalArgumentException 字段缺失、版本错误、槽位越界或整数越界时
     */
    public static byte[] encode(Permit value) {
        return decode(JSON.writeObject(toMap(value))).canonical();
    }

    /**
     * 严格解析提交许可。
     *
     * @param input 收到的原始 UTF-8 字节
     * @return 类型化许可、规范字节与规范摘要
     * @throws IllegalArgumentException 长度、闭集、版本、整数、槽位或规范形态不符时
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
            Permit permit = new Permit(CONTRACT_VERSION,
                    OtaContractFields.uuid(fields, "permitId"),
                    OtaContractFields.uuid(fields, "jobId"),
                    OtaContractFields.intValue(fields, "attemptNo", 1, Integer.MAX_VALUE),
                    OtaContractFields.uuid(fields, "authorizationId"),
                    OtaContractFields.hex(fields, "manifestSha256"),
                    OtaContractFields.uuid(fields, "bootId"),
                    OtaContractFields.integer(fields, "securityVersion", 0, OtaContractFields.MAX_SAFE_INTEGER),
                    OtaContractFields.hex(fields, "artifactSha256"),
                    OtaContractFields.literal(fields, "targetSlot", SLOTS),
                    OtaContractFields.integer(fields, "expiresAt", 1, 253_402_300_799L));
            byte[] canonical = JSON.writeObject(fields);
            OtaContractFields.requireCanonical(input, canonical);
            return new Decoded(permit, canonical, OtaContractFields.sha256Hex(canonical));
        } catch (RuntimeException exception) {
            throw OtaContractFields.invalid();
        }
    }

    /** 仅输出明确白名单字段。 */
    private static Map<String, Object> toMap(Permit input) {
        if (input == null) {
            throw OtaContractFields.invalid();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("permitId", String.valueOf(input.permitId()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("authorizationId", String.valueOf(input.authorizationId()));
        value.put("manifestSha256", input.manifestSha256());
        value.put("bootId", String.valueOf(input.bootId()));
        value.put("securityVersion", input.securityVersion());
        value.put("artifactSha256", input.artifactSha256());
        value.put("targetSlot", input.targetSlot());
        value.put("expiresAt", input.expiresAt());
        return value;
    }

    /** 不可变许可字段。
     *
     * @param contractVersion 固定合同
     * @param permitId 平台唯一许可身份
     * @param jobId 原作业
     * @param attemptNo 原尝试
     * @param authorizationId 原封存下载授权
     * @param manifestSha256 原发布清单摘要
     * @param bootId 平台确认的候选启动身份
     * @param securityVersion 目标安全版本
     * @param artifactSha256 目标 artifact 摘要
     * @param targetSlot 目标槽位
     * @param expiresAt 许可绝对期限（Unix 秒）
     */
    public record Permit(String contractVersion, UUID permitId, UUID jobId, int attemptNo, UUID authorizationId,
            String manifestSha256, UUID bootId, long securityVersion, String artifactSha256, String targetSlot,
            long expiresAt) {
    }

    /** 已解析的不可变合同投影。
     *
     * @param value 类型化许可
     * @param canonical 规范 JSON 字节
     * @param sha256 规范字节摘要
     */
    public record Decoded(Permit value, byte[] canonical, String sha256) {

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
