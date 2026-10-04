package com.things.link.entitlement.application;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 已批准的自部署产品值；只解释签名内容，不签发授权或决定现场准入。 */
public final class ApprovedSelfHostedRevision {
    public static final String REVISION_ID = "self-hosted-product-revision-1";
    private static final String RESOURCE = "/self-hosted/revisions/self-hosted-product-revision-1.json";
    private static final String SHA256 = "fd311bd72a22909614f798ebd050f05860bf41c5ddb807ea7af679164ca21fe3";
    private static final int MAX_BYTES = 16_384;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> TIERS = Set.of("FREE", "STANDARD", "ENTERPRISE", "PROFESSIONAL");
    private static final Set<String> QUOTAS = Set.of(
            "PROJECTS_MAX", "DEVICES_MAX", "END_USERS_MAX", "DASHBOARDS_MAX",
            "EXTERNAL_COLLABORATOR_SEATS", "UPLINK_MESSAGE_DAILY", "DOWNLINK_MESSAGE_DAILY",
            "REST_API_WRITE_DAILY", "REST_API_RATE_PER_MINUTE", "WEBSOCKET_CONNECTION_CONCURRENT",
            "SCRIPT_RULE_CONCURRENCY", "SCRIPT_RULE_EXECUTION_DAILY", "SCRIPT_RULE_CPU_MILLIS_DAILY",
            "NOTIFICATION_DELIVERY_DAILY", "STORAGE_LIMIT_BYTES", "UPLINK_BYTES_DAILY",
            "TIME_SERIES_POINT_DAILY", "AUTOMATION_EXECUTION_DAILY");
    private static final Set<String> CAPABILITIES = Set.of(
            "REST_API_WRITE", "REST_API_RATE_LIMIT", "WEBSOCKET_CONNECTION",
            "SCRIPT_RULE_CONCURRENCY", "SCRIPT_RULE_EXECUTION", "SCRIPT_RULE_CPU",
            "NOTIFICATION_DELIVERY", "OBJECT_STORAGE", "OPEN_API", "APP_SUBSCRIPTION",
            "OTA", "SMS_CHANNEL", "WEBHOOK_DELIVERY", "MQTT_REALTIME",
            "AUTOMATION_EXECUTION", "DEVICE_DOWNLINK");

    private final byte[] sha256;
    private final Map<String, Tier> tiers;

    private ApprovedSelfHostedRevision(byte[] sha256, Map<String, Tier> tiers) {
        this.sha256 = sha256.clone();
        this.tiers = Map.copyOf(tiers);
    }

    /** 从发行物自身的只读资源加载，先检查固定原始字节摘要，再解析闭集。 */
    public static ApprovedSelfHostedRevision loadApproved() throws GeneralSecurityException {
        try (InputStream stream = ApprovedSelfHostedRevision.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw invalid();
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES || stream.read() != -1) throw invalid();
            return parseApproved(bytes);
        } catch (IOException unavailable) {
            throw new GeneralSecurityException("approved SHC revision unavailable", unavailable);
        }
    }

    static ApprovedSelfHostedRevision parseApproved(byte[] bytes) throws GeneralSecurityException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
        byte[] actual = digest(bytes);
        if (!MessageDigest.isEqual(actual, java.util.HexFormat.of().parseHex(SHA256))) throw invalid();
        try {
            JsonNode root = JSON.readTree(bytes);
            require(keys(root).equals(Set.of("schemaVersion", "revisionId", "scope", "tiers")));
            require(root.get("schemaVersion").isIntegralNumber()
                    && root.get("schemaVersion").canConvertToInt()
                    && root.get("schemaVersion").intValue() == 1);
            require(REVISION_ID.equals(text(root.get("revisionId"))));
            require("ONE_DEPLOYMENT_ONE_TENANT".equals(text(root.get("scope"))));
            JsonNode tierNode = root.get("tiers");
            require(keys(tierNode).equals(TIERS));
            Map<String, Tier> tiers = new HashMap<>();
            for (String name : TIERS) {
                JsonNode entry = tierNode.get(name);
                require(keys(entry).equals(Set.of("quotas", "capabilities")));
                JsonNode quotaNode = entry.get("quotas");
                String history = name.equals("FREE") ? "HISTORY_WINDOW_DAYS" : "HISTORY_WINDOW_MONTHS";
                Set<String> expectedQuotas = new HashSet<>(QUOTAS);
                expectedQuotas.add(history);
                require(keys(quotaNode).equals(expectedQuotas));
                Map<String, Long> quotas = new HashMap<>();
                for (String code : expectedQuotas) {
                    JsonNode value = quotaNode.get(code);
                    require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0);
                    quotas.put(code, value.longValue());
                }
                JsonNode capabilityNode = entry.get("capabilities");
                require(keys(capabilityNode).equals(CAPABILITIES));
                Set<String> enabled = new HashSet<>();
                for (String code : CAPABILITIES) {
                    JsonNode value = capabilityNode.get(code);
                    require(value.isBoolean());
                    if (value.booleanValue()) enabled.add(code);
                }
                tiers.put(name, new Tier(quotas, enabled));
            }
            require(tiers.get("FREE").quotas().get("EXTERNAL_COLLABORATOR_SEATS") == 0);
            return new ApprovedSelfHostedRevision(actual, tiers);
        } catch (RuntimeException malformed) {
            throw new GeneralSecurityException("invalid approved SHC revision", malformed);
        }
    }

    public String revisionId() { return REVISION_ID; }
    public byte[] sha256() { return sha256.clone(); }

    public Tier tier(String name) throws GeneralSecurityException {
        Tier tier = tiers.get(name);
        if (tier == null) throw invalid();
        return tier;
    }

    /** 调用方仍须检查可信公钥、权威主体、期限和持久最高序号。 */
    public void requireMatches(GrantV1Verification.VerifiedGrant grant) throws GeneralSecurityException {
        if (grant == null || !REVISION_ID.equals(grant.revisionId())
                || !MessageDigest.isEqual(sha256, grant.revisionSha256())) throw invalid();
        Tier expected = tier(grant.tier());
        if (!expected.quotas().equals(grant.quotas())
                || !expected.capabilities().equals(grant.capabilities())) throw invalid();
    }

    private static byte[] digest(byte[] bytes) throws GeneralSecurityException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new GeneralSecurityException("SHA-256 unavailable", unavailable);
        }
    }

    private static Set<String> keys(JsonNode node) throws GeneralSecurityException {
        require(node != null && node.isObject());
        Set<String> keys = new HashSet<>();
        node.properties().forEach(entry -> keys.add(entry.getKey()));
        return keys;
    }

    private static String text(JsonNode node) throws GeneralSecurityException {
        require(node != null && node.isTextual());
        return node.asText();
    }

    private static void require(boolean condition) throws GeneralSecurityException {
        if (!condition) throw invalid();
    }

    private static GeneralSecurityException invalid() {
        return new GeneralSecurityException("invalid approved SHC revision or grant binding");
    }

    public record Tier(Map<String, Long> quotas, Set<String> capabilities) {
        public Tier {
            quotas = Map.copyOf(quotas);
            capabilities = Set.copyOf(capabilities);
        }
    }
}
