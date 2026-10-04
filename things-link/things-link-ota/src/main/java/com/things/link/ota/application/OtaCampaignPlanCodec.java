package com.things.link.ota.application;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** DIRECT活动计划闭集、资源预算及稳定分批合同，不产生派发授权。 */
public final class OtaCampaignPlanCodec {
    /** 受限规范JSON。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 全部显式必填计划字段。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "firmwareId", "deviceIds", "batchSize",
            "notBefore", "executionPolicy");
    /** 执行预算闭集，不添加隐式默认值。 */
    private static final Set<String> POLICY_FIELDS = Set.of("maxConcurrentDownloads", "maxDownloadBytesPerSecond",
            "downloadRetryLimit", "retryBackoffSeconds", "healthWindowSeconds", "pauseMinEvaluated", "pauseFailureCount",
            "pauseFailureRateBps", "batchMinSuccessRateBps", "requireManualBatchApproval", "stageTimeoutSeconds");
    /** 状态预算闭集，未知恢复状态不按普通超时处理。 */
    private static final Set<String> STAGES = Set.of("DISPATCHED", "DOWNLOADING", "VERIFYING", "INSTALLING",
            "REBOOTING", "HEALTH_CHECKING", "CONFIRMING", "ROLLBACK_PENDING", "ROLLING_BACK");

    /** 严格解析计划并按UUID规范文本排序；时间相对now的资格由服务负责。 */
    public Decoded decode(byte[] input) {
        Map<String, Object> fields = json.parseObject(input);
        OtaTrustBundleCodec.closed(fields, FIELDS);
        if (!"tc-ota-campaign-plan/v1".equals(fields.get("contractVersion"))) throw invalid();
        UUID firmware = OtaTrustBundleCodec.uuid(fields.get("firmwareId"));
        List<UUID> devices = new ArrayList<>();
        for (Object item : OtaTrustBundleCodec.list(fields.get("deviceIds"), 1000)) {
            UUID id = OtaTrustBundleCodec.uuid(item);
            if (devices.contains(id)) throw invalid();
            devices.add(id);
        }
        devices.sort(Comparator.comparing(UUID::toString));
        int batchSize = integer(fields, "batchSize", 1, 1000);
        Instant notBefore = instant(fields.get("notBefore"));
        Map<String, Object> policy = OtaTrustBundleCodec.object(fields.get("executionPolicy"));
        OtaTrustBundleCodec.closed(policy, POLICY_FIELDS);
        Map<String, Object> timeouts = OtaTrustBundleCodec.object(policy.get("stageTimeoutSeconds"));
        OtaTrustBundleCodec.closed(timeouts, STAGES);
        StageTimeoutSeconds stages = new StageTimeoutSeconds(timeout(timeouts, "DISPATCHED"),
                timeout(timeouts, "DOWNLOADING"), timeout(timeouts, "VERIFYING"), timeout(timeouts, "INSTALLING"),
                timeout(timeouts, "REBOOTING"), timeout(timeouts, "HEALTH_CHECKING"), timeout(timeouts, "CONFIRMING"),
                timeout(timeouts, "ROLLBACK_PENDING"), timeout(timeouts, "ROLLING_BACK"));
        int healthWindow = integer(policy, "healthWindowSeconds", 1, 86400);
        if (stages.healthChecking() < healthWindow) throw invalid();
        if (!(policy.get("requireManualBatchApproval") instanceof Boolean manual)) throw invalid();
        ExecutionPolicy execution = new ExecutionPolicy(integer(policy, "maxConcurrentDownloads", 1, batchSize),
                OtaTrustBundleCodec.integer(policy.get("maxDownloadBytesPerSecond"), 1, 1_073_741_824L),
                integer(policy, "downloadRetryLimit", 0, 10), integer(policy, "retryBackoffSeconds", 1, 3600),
                healthWindow, integer(policy, "pauseMinEvaluated", 1, batchSize),
                integer(policy, "pauseFailureCount", 1, batchSize), integer(policy, "pauseFailureRateBps", 1, 10000),
                integer(policy, "batchMinSuccessRateBps", 1, 10000), manual, stages);
        Map<String, Object> normalized = new LinkedHashMap<>(fields);
        normalized.put("deviceIds", devices.stream().map(UUID::toString).toList());
        byte[] canonical = json.writeObject(normalized);
        Plan plan = new Plan("tc-ota-campaign-plan/v1", firmware, devices, batchSize, notBefore, execution);
        return new Decoded(plan, canonical, OtaTrustBundleCodec.sha256(canonical));
    }

    /** 各阶段使用相同显式秒预算上限。 */
    private static int timeout(Map<String, Object> fields, String name) {
        return integer(fields, name, 1, 86400);
    }

    /** 不接受字符串、浮点数或溢出整数。 */
    private static int integer(Map<String, Object> fields, String name, int minimum, int maximum) {
        return (int) OtaTrustBundleCodec.integer(fields.get(name), minimum, maximum);
    }

    /** 必须是四位年、秒精度UTC，回写相等拒绝闰秒归一化等宽松解析。 */
    private static Instant instant(Object value) {
        String text = OtaTrustBundleCodec.text(value, "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z");
        try {
            Instant parsed = Instant.parse(text);
            if (!parsed.toString().equals(text)) throw invalid();
            return parsed;
        } catch (DateTimeParseException failure) {
            throw invalid();
        }
    }

    /** 固定失败不携带用户计划。 */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("OTA活动计划合同不合法");
    }

    /** 规范计划身份。
     * @param value 强类型计划
     * @param canonical 规范字节
     * @param sha256 规范摘要
     */
    public record Decoded(Plan value, byte[] canonical, String sha256) {
        /** 冻结规范字节。 */
        public Decoded { canonical = canonical.clone(); }
        /** 返回独立副本。 */
        @Override public byte[] canonical() { return canonical.clone(); }
    }

    /** 不可变目标与执行策略。
     * @param contractVersion 固定计划版本
     * @param firmwareId 固件身份
     * @param deviceIds 规范文本排序目标
     * @param batchSize 每批上限
     * @param notBefore 原始排程时刻
     * @param executionPolicy 冻结执行预算
     */
    public record Plan(String contractVersion, UUID firmwareId, List<UUID> deviceIds, int batchSize,
                       Instant notBefore, ExecutionPolicy executionPolicy) {
        /** 冻结目标，分批不会受外部列表修改影响。 */
        public Plan {
            deviceIds = List.copyOf(deviceIds);
            if (batchSize < 1 || batchSize > 1000 || deviceIds.isEmpty() || deviceIds.size() > 1000) throw invalid();
        }
        /** 按排序快照连续分组，末批保留余数，外层与每批均不可变。 */
        public List<List<UUID>> batches() {
            List<List<UUID>> batches = new ArrayList<>();
            for (int start = 0; start < deviceIds.size(); start += batchSize) {
                batches.add(List.copyOf(deviceIds.subList(start, Math.min(start + batchSize, deviceIds.size()))));
            }
            return List.copyOf(batches);
        }
    }

    /** 完整执行预算，重试不改变原阶段总预算。
     * @param maxConcurrentDownloads 并发下载数
     * @param maxDownloadBytesPerSecond 下载字节预算，不证明真实传输限速
     * @param downloadRetryLimit 额外重试次数
     * @param retryBackoffSeconds 重试退避秒
     * @param healthWindowSeconds 健康窗口秒
     * @param pauseMinEvaluated 最小评估数
     * @param pauseFailureCount 失败数量门槛
     * @param pauseFailureRateBps 失败比例基点
     * @param batchMinSuccessRateBps 批成功门槛基点
     * @param requireManualBatchApproval 是否人工放行后批
     * @param stageTimeoutSeconds 全阶段秒预算
     */
    public record ExecutionPolicy(int maxConcurrentDownloads, long maxDownloadBytesPerSecond, int downloadRetryLimit,
            int retryBackoffSeconds, int healthWindowSeconds, int pauseMinEvaluated, int pauseFailureCount,
            int pauseFailureRateBps, int batchMinSuccessRateBps, boolean requireManualBatchApproval,
            StageTimeoutSeconds stageTimeoutSeconds) { }

    /** 九个状态独立预算，刷写后的超时语义仍按ADR0054。
     * @param dispatched 派发确认
     * @param downloading 下载
     * @param verifying 验签
     * @param installing 安装
     * @param rebooting 重启
     * @param healthChecking 健康检查
     * @param confirming 安全提交确认
     * @param rollbackPending 等待安全回退
     * @param rollingBack 执行回退
     */
    public record StageTimeoutSeconds(int dispatched, int downloading, int verifying, int installing, int rebooting,
            int healthChecking, int confirming, int rollbackPending, int rollingBack) { }
}
