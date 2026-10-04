package com.things.link.ota.application;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 八字段无URL通知合同，事件身份不等价于设备下载资格。 */
public final class OtaNotificationCodec {
    /** 固定字段集合。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "eventId", "campaignId", "jobId", "firmwareId",
            "attemptNo", "manifestSha256", "deadlineAt");
    /** 唯一受限规范JSON。 */ private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 生成规范字节并通过同一闭集校验。 */
    public byte[] encode(Notification value) {
        if (value == null) throw invalid();
        try {
            byte[] bytes = json.writeObject(Map.of("contractVersion", value.contractVersion(), "eventId", value.eventId().toString(),
                    "campaignId", value.campaignId().toString(), "jobId", value.jobId().toString(),
                    "firmwareId", value.firmwareId().toString(), "attemptNo", (long) value.attemptNo(),
                    "manifestSha256", value.manifestSha256(), "deadlineAt", value.deadlineAt().toString()));
            decode(bytes);
            return bytes;
        } catch (NullPointerException failure) { throw invalid(); }
    }
    /** 解析严格身份；ADR0138尝试为正整数，实际重试上限由冻结策略与持久状态约束。 */
    public Notification decode(byte[] bytes) {
        var v = json.parseObject(bytes);
        OtaTrustBundleCodec.closed(v, FIELDS);
        if (!"tc-ota-available/v1".equals(v.get("contractVersion"))) throw invalid();
        String text = OtaTrustBundleCodec.text(v.get("deadlineAt"),
                "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z");
        Instant deadline;
        try {
            deadline = Instant.parse(text);
            if (!deadline.toString().equals(text)) throw invalid();
        } catch (DateTimeParseException failure) { throw invalid(); }
        return new Notification("tc-ota-available/v1", OtaTrustBundleCodec.uuid(v.get("eventId")),
                OtaTrustBundleCodec.uuid(v.get("campaignId")), OtaTrustBundleCodec.uuid(v.get("jobId")),
                OtaTrustBundleCodec.uuid(v.get("firmwareId")), (int) OtaTrustBundleCodec.integer(v.get("attemptNo"), 1, Integer.MAX_VALUE),
                OtaTrustBundleCodec.text(v.get("manifestSha256"), "[0-9a-f]{64}"), deadline);
    }
    /** 路由只能来自权威短标识，不接受用户通配符或多层Topic。 */
    public static String topic(String projectKey, String deviceKey) {
        if (!segment(projectKey) || !segment(deviceKey)) throw invalid();
        return "tc/v1/" + projectKey + "/" + deviceKey + "/down/ota/available";
    }
    /** 精确已有设备短标识语法。 */
    private static boolean segment(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }
    /** 固定错误不泄露报文。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA通知合同不合法"); }
    /** 完整通知身份，无私钥、对象地址或安装指令。
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
                               int attemptNo, String manifestSha256, Instant deadlineAt) { }
}
