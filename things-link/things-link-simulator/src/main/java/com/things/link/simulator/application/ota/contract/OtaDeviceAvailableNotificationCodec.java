package com.things.link.simulator.application.ota.contract;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 设备侧可升级通知合同编解码：{@code tc-ota-available/v1}（八字段，无下载地址）。
 *
 * <p>字段与平台 {@code OtaNotificationCodec} 完全一致。设备收到本通知只获得「可以去申请下载」的
 * 授权信号：报文里没有 URL、没有签名、也没有安装指令。</p>
 *
 * <p>{@code attemptNo} 按ADR0138为持久正整数，实际次数由平台冻结重试预算约束；{@code deadlineAt} 只接受不超过 9 位
 * 小数的 UTC 时刻，且必须与 {@link Instant#toString()} 逐字符一致。</p>
 */
public final class OtaDeviceAvailableNotificationCodec {

    /** 冻结合同版本。 */
    public static final String CONTRACT_VERSION = "tc-ota-available/v1";

    /** 固定字段集合。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "eventId", "campaignId", "jobId", "firmwareId",
            "attemptNo", "manifestSha256", "deadlineAt");

    /** 平台接受的 UTC 时刻文本形态。 */
    private static final String DEADLINE_PATTERN =
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z";

    /** 唯一规范 JSON 实现。 */
    private static final OtaCanonicalJson JSON = new OtaCanonicalJson();

    /** 工具类不允许实例化。 */
    private OtaDeviceAvailableNotificationCodec() {
    }

    /**
     * 把类型化通知编码为平台可解码的规范字节。
     *
     * @param value 通知身份
     * @return 规范 UTF-8 字节
     * @throws IllegalArgumentException 字段缺失、时刻非规范或版本错误时
     */
    public static byte[] encode(Notification value) {
        return decode(JSON.writeObject(toMap(value))).canonical();
    }

    /**
     * 严格解析可升级通知。
     *
     * @param input 收到的原始 UTF-8 字节
     * @return 类型化通知、规范字节与规范摘要
     * @throws IllegalArgumentException 闭集、版本、标识符、时刻或规范形态不符时
     */
    public static Decoded decode(byte[] input) {
        try {
            if (input == null || input.length == 0) {
                throw OtaContractFields.invalid();
            }
            Map<String, Object> fields = JSON.parseObject(input);
            OtaContractFields.closed(fields, FIELDS);
            if (!CONTRACT_VERSION.equals(fields.get("contractVersion"))) {
                throw OtaContractFields.invalid();
            }
            String deadlineText = OtaContractFields.text(fields, "deadlineAt", DEADLINE_PATTERN);
            Instant deadline;
            try {
                deadline = Instant.parse(deadlineText);
                if (!deadline.toString().equals(deadlineText)) {
                    throw OtaContractFields.invalid();
                }
            } catch (DateTimeParseException exception) {
                throw OtaContractFields.invalid();
            }
            Notification notification = new Notification(CONTRACT_VERSION,
                    OtaContractFields.uuid(fields, "eventId"),
                    OtaContractFields.uuid(fields, "campaignId"),
                    OtaContractFields.uuid(fields, "jobId"),
                    OtaContractFields.uuid(fields, "firmwareId"),
                    OtaContractFields.intValue(fields, "attemptNo", 1, Integer.MAX_VALUE),
                    OtaContractFields.hex(fields, "manifestSha256"),
                    deadline);
            byte[] canonical = JSON.writeObject(fields);
            OtaContractFields.requireCanonical(input, canonical);
            return new Decoded(notification, canonical, OtaContractFields.sha256Hex(canonical));
        } catch (RuntimeException exception) {
            throw OtaContractFields.invalid();
        }
    }

    /** 仅输出明确白名单字段。 */
    private static Map<String, Object> toMap(Notification input) {
        if (input == null) {
            throw OtaContractFields.invalid();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("eventId", String.valueOf(input.eventId()));
        value.put("campaignId", String.valueOf(input.campaignId()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("firmwareId", String.valueOf(input.firmwareId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("manifestSha256", input.manifestSha256());
        value.put("deadlineAt", String.valueOf(input.deadlineAt()));
        return value;
    }

    /** 完整通知身份，无私钥、对象地址或安装指令。
     *
     * @param contractVersion 固定合同
     * @param eventId 不可变事件
     * @param campaignId 活动
     * @param jobId 作业
     * @param firmwareId 固件
     * @param attemptNo 作业尝试号
     * @param manifestSha256 清单摘要
     * @param deadlineAt 原始阶段期限
     */
    public record Notification(String contractVersion, UUID eventId, UUID campaignId, UUID jobId, UUID firmwareId,
            int attemptNo, String manifestSha256, Instant deadlineAt) {
    }

    /** 已解析的不可变合同投影。
     *
     * @param value 类型化通知
     * @param canonical 规范 JSON 字节
     * @param sha256 规范字节摘要
     */
    public record Decoded(Notification value, byte[] canonical, String sha256) {

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
