package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 只读回退预检规范合同，不提供设备执行权。 */
public final class OtaRollbackPreflightQueryCodec {
    /** 闭集顶层字段。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "queryId", "queryNonce", "jobId", "attemptNo", "recoveryRevision", "authorizationId", "manifestSha256", "rollbackBaselineVersion", "rollbackBaselineSha256", "sourceSlot", "targetSlot", "expiresAt", "permitIds");
    /** 独立16KiB合同解析，安全矛盾由资格服务决定。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-rollback-preflight-query/v1");
        var typed = new Query("tc-ota-rollback-preflight-query/v1",
                OtaTrustBundleCodec.uuid(value.get("queryId")),
                OtaTrustBundleCodec.uuid(value.get("queryNonce")),
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaConfirmationJson.integer(value, "recoveryRevision", 1, 9007199254740991L),
                OtaTrustBundleCodec.uuid(value.get("authorizationId")),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaConfirmationJson.integer(value, "rollbackBaselineVersion", 1, 9007199254740991L),
                OtaConfirmationJson.hex(value, "rollbackBaselineSha256"),
                OtaConfirmationJson.literal(value, "sourceSlot", Set.of("A", "B")),
                OtaConfirmationJson.literal(value, "targetSlot", Set.of("A", "B")),
                OtaConfirmationJson.integer(value, "expiresAt", 1, 253402300799L),
                OtaRollbackPreflightJson.ids(value.get("permitIds")));
        byte[] canonical = new OtaCanonicalJson().writeObject(toMap(typed));
        return new Decoded(typed, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 编码亦通过闭集校验。 */
    public byte[] encode(Query input) {
        if (input == null) throw OtaConfirmationJson.invalid();
        return decode(new OtaCanonicalJson().writeObject(toMap(input))).canonical();
    }
    /** 类型化字段映射，不序列化额外实现字段。 */
    private static Map<String, Object> toMap(Query input) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("queryId", String.valueOf(input.queryId()));
        value.put("queryNonce", String.valueOf(input.queryNonce()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("recoveryRevision", input.recoveryRevision());
        value.put("authorizationId", String.valueOf(input.authorizationId()));
        value.put("manifestSha256", input.manifestSha256());
        value.put("rollbackBaselineVersion", input.rollbackBaselineVersion());
        value.put("rollbackBaselineSha256", input.rollbackBaselineSha256());
        value.put("sourceSlot", input.sourceSlot());
        value.put("targetSlot", input.targetSlot());
        value.put("expiresAt", input.expiresAt());
        value.put("permitIds", input.permitIds().stream().map(UUID::toString).toList());
        return value;
    }
    /** 不可变只读协议。
     * @param contractVersion 协议contractVersion字段
     * @param queryId 协议queryId字段
     * @param queryNonce 协议queryNonce字段
     * @param jobId 协议jobId字段
     * @param attemptNo 协议attemptNo字段
     * @param recoveryRevision 协议recoveryRevision字段
     * @param authorizationId 协议authorizationId字段
     * @param manifestSha256 协议manifestSha256字段
     * @param rollbackBaselineVersion 协议rollbackBaselineVersion字段
     * @param rollbackBaselineSha256 协议rollbackBaselineSha256字段
     * @param sourceSlot 协议sourceSlot字段
     * @param targetSlot 协议targetSlot字段
     * @param expiresAt 协议expiresAt字段
     * @param permitIds 协议permitIds字段
     */
    public record Query(String contractVersion,
            UUID queryId,
            UUID queryNonce,
            UUID jobId,
            int attemptNo,
            long recoveryRevision,
            UUID authorizationId,
            String manifestSha256,
            long rollbackBaselineVersion,
            String rollbackBaselineSha256,
            String sourceSlot,
            String targetSlot,
            long expiresAt,
            List<UUID> permitIds) {
        /** 冻结集合，调用者不能替换原规范事实。 */
        public Query { permitIds = java.util.List.copyOf(permitIds); }
    }
    /** 规范字节及摘要。
     * @param value 协议value字段
     * @param canonical 协议canonical字段
     * @param sha256 协议sha256字段
     */
    public record Decoded(Query value,
            byte[] canonical,
            String sha256) {
        /** 输入防御复制。 */ public Decoded { canonical = canonical.clone(); }
        /** 输出独立副本。 */ @Override public byte[] canonical() { return canonical.clone(); }
    }
}
