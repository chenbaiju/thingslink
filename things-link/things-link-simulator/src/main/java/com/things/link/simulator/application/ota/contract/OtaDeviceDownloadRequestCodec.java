package com.things.link.simulator.application.ota.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 设备侧下载申请合同编解码：{@code tc-ota-download-request/v1}。
 *
 * <p>字段与平台 {@code OtaDownloadRequestCodec} 完全一致（五个字段：
 * {@code contractVersion}/{@code requestId}/{@code jobId}/{@code attemptNo}/{@code manifestSha256}），
 * 且不携带设备自报身份或临时下载地址：身份只能来自已认证 MQTT 连接，地址只能由后来的下载响应给出。</p>
 *
 * <p>{@code requestId} 由设备生成并在下载响应里被平台回显；本类只负责字节，幂等范围由持久服务决定。</p>
 */
public final class OtaDeviceDownloadRequestCodec {

    /** 冻结合同版本。 */
    public static final String CONTRACT_VERSION = "tc-ota-download-request/v1";

    /** 平台对该合同冻结的原始字节上限。 */
    public static final int MAX_BYTES = 2048;

    /** 唯一允许的五个字段。 */
    private static final Set<String> FIELDS = Set.of(
            "contractVersion", "requestId", "jobId", "attemptNo", "manifestSha256");

    /** 唯一的规范 JSON 实现。 */
    private static final OtaCanonicalJson JSON = new OtaCanonicalJson();

    /** 工具类不允许实例化。 */
    private OtaDeviceDownloadRequestCodec() {
    }

    /**
     * 把类型化申请编码为平台可解码的规范字节。
     *
     * @param value 申请身份
     * @return 规范 UTF-8 字节
     * @throws IllegalArgumentException 字段缺失、类型不符或版本错误时（失败关闭）
     */
    public static byte[] encode(Request value) {
        return decode(JSON.writeObject(toMap(value))).canonical();
    }

    /**
     * 严格解析下载申请。
     *
     * @param input 收到的原始 UTF-8 字节
     * @return 类型化申请、规范字节与规范摘要
     * @throws IllegalArgumentException 长度、闭集、版本、标识符或规范形态不符时
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
            Request request = new Request(CONTRACT_VERSION,
                    OtaContractFields.uuid(fields, "requestId"),
                    OtaContractFields.uuid(fields, "jobId"),
                    OtaContractFields.intValue(fields, "attemptNo", 1, Integer.MAX_VALUE),
                    OtaContractFields.hex(fields, "manifestSha256"));
            byte[] canonical = JSON.writeObject(fields);
            OtaContractFields.requireCanonical(input, canonical);
            return new Decoded(request, canonical, OtaContractFields.sha256Hex(canonical));
        } catch (RuntimeException exception) {
            throw OtaContractFields.invalid();
        }
    }

    /** 仅输出明确白名单字段。 */
    private static Map<String, Object> toMap(Request input) {
        if (input == null) {
            throw OtaContractFields.invalid();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("requestId", String.valueOf(input.requestId()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("manifestSha256", input.manifestSha256());
        return value;
    }

    /** 完整申请身份。
     *
     * @param contractVersion 固定协议版本
     * @param requestId 设备生成的请求标识
     * @param jobId 已派发作业
     * @param attemptNo 当前作业尝试号
     * @param manifestSha256 已签名清单摘要
     */
    public record Request(String contractVersion, UUID requestId, UUID jobId, int attemptNo, String manifestSha256) {
    }

    /** 已解析的不可变合同投影。
     *
     * @param value 类型化申请
     * @param canonical 规范 JSON 字节
     * @param sha256 规范字节摘要
     */
    public record Decoded(Request value, byte[] canonical, String sha256) {

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
