package com.things.link.simulator.application.ota.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 设备侧安装前停止操作合同编解码：{@code tc-ota-install-stop-operation/v1}（下行命令）。
 *
 * <p>字段与平台 {@code OtaInstallStopOperationCodec} 完全一致。解析合法<b>不等于</b>停止已获
 * 设备耐久接纳：本类只搬运命令，不修改任何设备安全状态，也不刷新作业期限。</p>
 *
 * <p>{@code authorizationIds} 是零或一项的辅助事实：空数组表示当前没有可用下载授权，
 * 不表示围栏缩窄；{@code expiresAt} 是 Unix 整数秒，平台边界为 {@code 1..253402300799}。</p>
 */
public final class OtaDeviceInstallStopOperationCodec {

    /** 冻结合同版本。 */
    public static final String CONTRACT_VERSION = "tc-ota-install-stop-operation/v1";

    /** 平台对该合同冻结的原始字节上限。 */
    public static final int MAX_BYTES = 16_384;

    /** 严格顶层字段集。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "operationId", "campaignId", "jobId",
            "attemptNo", "manifestSha256", "authorizationIds", "stopBaselineSha256", "cancellationRevision",
            "expiresAt");

    /** Unix 秒上限：9999-12-31T23:59:59Z。 */
    private static final long MAX_UNIX_SECONDS = 253_402_300_799L;

    /** 唯一规范 JSON 实现。 */
    private static final OtaCanonicalJson JSON = new OtaCanonicalJson();

    /** 工具类不允许实例化。 */
    private OtaDeviceInstallStopOperationCodec() {
    }

    /**
     * 把类型化操作编码为平台可解码的规范字节。
     *
     * @param value 停止操作
     * @return 规范 UTF-8 字节
     * @throws IllegalArgumentException 字段缺失、授权列表超一项或版本错误时
     */
    public static byte[] encode(Operation value) {
        return decode(JSON.writeObject(toMap(value))).canonical();
    }

    /**
     * 严格解析停止操作。
     *
     * @param input 收到的原始 UTF-8 字节
     * @return 类型化操作、规范字节与规范摘要
     * @throws IllegalArgumentException 长度、闭集、版本、标识符或边界不符时
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
            List<UUID> authorizationIds = new ArrayList<>();
            for (Object item : OtaContractFields.items(fields.get("authorizationIds"))) {
                authorizationIds.add(OtaContractFields.uuid(item));
            }
            Operation operation = new Operation(CONTRACT_VERSION,
                    OtaContractFields.uuid(fields, "operationId"),
                    OtaContractFields.uuid(fields, "campaignId"),
                    OtaContractFields.uuid(fields, "jobId"),
                    OtaContractFields.intValue(fields, "attemptNo", 1, Integer.MAX_VALUE),
                    OtaContractFields.hex(fields, "manifestSha256"),
                    List.copyOf(authorizationIds),
                    OtaContractFields.hex(fields, "stopBaselineSha256"),
                    OtaContractFields.integer(fields, "cancellationRevision", 0, OtaContractFields.MAX_SAFE_INTEGER),
                    OtaContractFields.integer(fields, "expiresAt", 1, MAX_UNIX_SECONDS));
            byte[] canonical = JSON.writeObject(fields);
            OtaContractFields.requireCanonical(input, canonical);
            return new Decoded(operation, canonical, OtaContractFields.sha256Hex(canonical));
        } catch (RuntimeException exception) {
            throw OtaContractFields.invalid();
        }
    }

    /** 仅输出明确白名单字段。 */
    private static Map<String, Object> toMap(Operation input) {
        if (input == null || input.authorizationIds() == null) {
            throw OtaContractFields.invalid();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("operationId", String.valueOf(input.operationId()));
        value.put("campaignId", String.valueOf(input.campaignId()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("manifestSha256", input.manifestSha256());
        value.put("authorizationIds", input.authorizationIds().stream().map(String::valueOf).toList());
        value.put("stopBaselineSha256", input.stopBaselineSha256());
        value.put("cancellationRevision", input.cancellationRevision());
        value.put("expiresAt", input.expiresAt());
        return value;
    }

    /** 不可变协议字段。
     *
     * @param contractVersion 协议版本
     * @param operationId 停止操作身份
     * @param campaignId 活动身份
     * @param jobId 作业身份
     * @param attemptNo 尝试号
     * @param manifestSha256 清单摘要
     * @param authorizationIds 零或一项辅助授权
     * @param stopBaselineSha256 停止基线摘要
     * @param cancellationRevision 取消修订
     * @param expiresAt Unix 秒期限
     */
    public record Operation(String contractVersion, UUID operationId, UUID campaignId, UUID jobId, int attemptNo,
            String manifestSha256, List<UUID> authorizationIds, String stopBaselineSha256,
            long cancellationRevision, long expiresAt) {

        /** 冻结授权列表，防止解析后修改。 */
        public Operation {
            if (authorizationIds == null || authorizationIds.stream().anyMatch(Objects::isNull)) {
                throw OtaContractFields.invalid();
            }
            authorizationIds = List.copyOf(authorizationIds);
        }
    }

    /** 已解析的不可变合同投影。
     *
     * @param value 类型化操作
     * @param canonical 规范 JSON 字节
     * @param sha256 规范字节摘要
     */
    public record Decoded(Operation value, byte[] canonical, String sha256) {

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
