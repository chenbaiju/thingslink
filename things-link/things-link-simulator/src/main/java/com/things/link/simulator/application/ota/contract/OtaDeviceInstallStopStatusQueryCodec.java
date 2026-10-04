package com.things.link.simulator.application.ota.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 设备侧安装前停止状态查询合同编解码：{@code tc-ota-install-stop-status-query/v1}（下行只读查询）。
 *
 * <p>字段与平台 {@code OtaInstallStopStatusQueryCodec} 完全一致。查询把一次只读观察绑定到
 * {@code queryId}/{@code queryNonce}；设备只按同一份耐久日志重放既有结论，不因查询推进任何状态或期限。</p>
 *
 * <p>{@code expiresAt} 是查询自身的期限（Unix 整数秒），不是原操作或作业期限的刷新：设备不得因为收到
 * 一次查询而延长任何窗口，否则「未知结果只读查询」就变成了隐性重试许可。</p>
 */
public final class OtaDeviceInstallStopStatusQueryCodec {

    /** 冻结合同版本。 */
    public static final String CONTRACT_VERSION = "tc-ota-install-stop-status-query/v1";

    /** 平台对该合同冻结的原始字节上限。 */
    public static final int MAX_BYTES = 16_384;

    /** 严格顶层字段集。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "queryId", "queryNonce", "operationId",
            "jobId", "attemptNo", "manifestSha256", "operationSha256", "expiresAt");

    /** Unix 秒上限：9999-12-31T23:59:59Z。 */
    private static final long MAX_UNIX_SECONDS = 253_402_300_799L;

    /** 唯一规范 JSON 实现。 */
    private static final OtaCanonicalJson JSON = new OtaCanonicalJson();

    /** 工具类不允许实例化。 */
    private OtaDeviceInstallStopStatusQueryCodec() {
    }

    /**
     * 把类型化查询编码为平台可解码的规范字节。
     *
     * @param value 状态查询
     * @return 规范 UTF-8 字节
     * @throws IllegalArgumentException 字段缺失、标识符或边界不符时
     */
    public static byte[] encode(Query value) {
        return decode(JSON.writeObject(toMap(value))).canonical();
    }

    /**
     * 严格解析状态查询。
     *
     * @param input 收到的原始 UTF-8 字节
     * @return 类型化查询、规范字节与规范摘要
     * @throws IllegalArgumentException 长度、闭集、版本、标识符、整数或规范形态不符时
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
            Query query = new Query(CONTRACT_VERSION,
                    OtaContractFields.uuid(fields, "queryId"),
                    OtaContractFields.uuid(fields, "queryNonce"),
                    OtaContractFields.uuid(fields, "operationId"),
                    OtaContractFields.uuid(fields, "jobId"),
                    OtaContractFields.intValue(fields, "attemptNo", 1, Integer.MAX_VALUE),
                    OtaContractFields.hex(fields, "manifestSha256"),
                    OtaContractFields.hex(fields, "operationSha256"),
                    OtaContractFields.integer(fields, "expiresAt", 1, MAX_UNIX_SECONDS));
            byte[] canonical = JSON.writeObject(fields);
            OtaContractFields.requireCanonical(input, canonical);
            return new Decoded(query, canonical, OtaContractFields.sha256Hex(canonical));
        } catch (RuntimeException exception) {
            throw OtaContractFields.invalid();
        }
    }

    /** 仅输出明确白名单字段。 */
    private static Map<String, Object> toMap(Query input) {
        if (input == null) {
            throw OtaContractFields.invalid();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("queryId", String.valueOf(input.queryId()));
        value.put("queryNonce", String.valueOf(input.queryNonce()));
        value.put("operationId", String.valueOf(input.operationId()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("manifestSha256", input.manifestSha256());
        value.put("operationSha256", input.operationSha256());
        value.put("expiresAt", input.expiresAt());
        return value;
    }

    /**
     * 不可变协议字段。
     *
     * @param contractVersion 协议版本
     * @param queryId 查询身份
     * @param queryNonce 查询随机数
     * @param operationId 被查询的原停止操作身份
     * @param jobId 原作业身份
     * @param attemptNo 原尝试号
     * @param manifestSha256 原清单摘要
     * @param operationSha256 原操作规范摘要
     * @param expiresAt 查询自身期限（Unix 秒）
     */
    public record Query(String contractVersion, UUID queryId, UUID queryNonce, UUID operationId, UUID jobId,
            int attemptNo, String manifestSha256, String operationSha256, long expiresAt) {
    }

    /**
     * 已解析的不可变合同投影。
     *
     * @param value 类型化查询
     * @param canonical 规范 JSON 字节
     * @param sha256 规范字节摘要
     */
    public record Decoded(Query value, byte[] canonical, String sha256) {

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
