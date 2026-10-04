package com.things.link.ota.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.things.link.ota.application.OtaRollbackPreflightReportCodec.CommitOperation;
import com.things.link.ota.application.OtaRollbackPreflightReportCodec.Evidence;
import com.things.link.ota.application.OtaRollbackPreflightReportCodec.Hardware;
import com.things.link.ota.application.OtaRollbackPreflightReportCodec.Journal;
import com.things.link.ota.application.OtaRollbackPreflightReportCodec.Slot;

/** 预检独立嵌套闭集解析，不修改原报告或提交协议。 */
final class OtaRollbackPreflightJson {
    /** 不创建工具对象。 */ private OtaRollbackPreflightJson() { }
    /** 平台受控原子操作协议名，声明本身不是实机证明。 */
    private static final String PROFILE = "TC_OTA_AB_COMMIT_ROLLBACK_JOURNAL_V1";
    /** 最多一个规范UUID身份，集合不可变。 */
    static List<UUID> ids(Object value) {
        var values = optionalOperationList(value);
        return values.stream().map(OtaTrustBundleCodec::uuid).toList();
    }
    /** 操作数组允许尚无操作，但拒绝非数组及超过一个的身份。 */
    private static List<?> optionalOperationList(Object value) {
        if (!(value instanceof List<?> values) || values.size() > 1) {
            throw OtaConfirmationJson.invalid();
        }
        return values;
    }
    /** bootloader保持已有三轴int边界语义。 */
    static String version(Object value) {
        String text = OtaTrustBundleCodec.text(value, "(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})");
        try { for (String axis : text.split("\\.")) Integer.parseInt(axis); }
        catch (NumberFormatException failure) { throw OtaConfirmationJson.invalid(); }
        return text;
    }
    /** 完整双槽及原子日志；仅检查词法，未知状态仍可形成拒绝观察。 */
    static Evidence evidence(Object value) {
        var e = OtaTrustBundleCodec.object(value);
        OtaTrustBundleCodec.closed(e, Set.of("hardware", "slots", "journal", "writeState"));
        var h = OtaTrustBundleCodec.object(e.get("hardware"));
        OtaTrustBundleCodec.closed(h, Set.of("model", "boardRevision"));
        var hardware = new Hardware(OtaTrustBundleCodec.text(h.get("model"), "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"),
                OtaConfirmationJson.integer(h, "boardRevision", 0, 9007199254740991L));
        var slots = new ArrayList<Slot>();
        for (Object item : OtaTrustBundleCodec.list(e.get("slots"), 2)) slots.add(slot(item));
        if (slots.size() != 2 || slots.get(0).slot().equals(slots.get(1).slot())) throw OtaConfirmationJson.invalid();
        slots.sort(Comparator.comparing(Slot::slot));
        var j = OtaTrustBundleCodec.object(e.get("journal"));
        OtaTrustBundleCodec.closed(j, Set.of("atomicOperationProfile", "operationRevision", "state", "commitOperations", "rollbackOperationIds"));
        var operations = new ArrayList<CommitOperation>();
        for (Object item : optionalOperationList(j.get("commitOperations"))) {
            var op = OtaTrustBundleCodec.object(item);
            OtaTrustBundleCodec.closed(op, Set.of("permitId", "state"));
            operations.add(new CommitOperation(OtaTrustBundleCodec.uuid(op.get("permitId")),
                    OtaConfirmationJson.literal(op, "state", Set.of("NOT_ACCEPTED", "PENDING", "COMMITTED", "FENCED", "UNKNOWN"))));
        }
        var journal = new Journal(OtaConfirmationJson.literal(j, "atomicOperationProfile", Set.of(PROFILE)),
                OtaConfirmationJson.integer(j, "operationRevision", 0, 9007199254740991L),
                OtaConfirmationJson.literal(j, "state", Set.of("IDLE", "COMMIT_PENDING", "COMMITTED", "ROLLBACK_PENDING", "UNKNOWN")),
                operations, ids(j.get("rollbackOperationIds")));
        return new Evidence(hardware, slots, journal,
                OtaConfirmationJson.literal(e, "writeState", Set.of("QUIESCENT", "WRITING", "UNKNOWN")));
    }
    /** 两槽原身份必须完整，未知完整性不能用缺字段表示。 */
    private static Slot slot(Object value) {
        var v = OtaTrustBundleCodec.object(value);
        OtaTrustBundleCodec.closed(v, Set.of("slot", "artifactSha256", "securityVersion", "thingModelVersionId",
                "thingModelSchemaDigestAlgorithm", "thingModelSchemaDigest", "propertyProfile", "integrity", "bootable", "bootloaderVerified", "health"));
        return new Slot(OtaConfirmationJson.literal(v, "slot", Set.of("A", "B")),
                OtaConfirmationJson.hex(v, "artifactSha256"), OtaConfirmationJson.integer(v, "securityVersion", 0, 9007199254740991L),
                OtaTrustBundleCodec.uuid(v.get("thingModelVersionId")),
                OtaConfirmationJson.literal(v, "thingModelSchemaDigestAlgorithm", Set.of("PG_JSONB_TEXT_V1_SHA256")),
                OtaConfirmationJson.hex(v, "thingModelSchemaDigest"),
                OtaConfirmationJson.literal(v, "propertyProfile", Set.of("TC_PROPERTY_COMPOSITE_V1")),
                OtaConfirmationJson.literal(v, "integrity", Set.of("VERIFIED", "INVALID", "UNKNOWN")),
                OtaConfirmationJson.flag(v, "bootable"), OtaConfirmationJson.flag(v, "bootloaderVerified"),
                OtaConfirmationJson.literal(v, "health", Set.of("HEALTHY", "UNHEALTHY", "UNKNOWN")));
    }
    /** 仅白名单编码嵌套数据，避免泄露实现字段。 */
    static Map<String, Object> encode(Evidence e) {
        if (e == null || e.hardware() == null || e.journal() == null) throw OtaConfirmationJson.invalid();
        Map<String, Object> value = new LinkedHashMap<>();
        Map<String, Object> hardware = new LinkedHashMap<>();
        hardware.put("model", e.hardware().model()); hardware.put("boardRevision", e.hardware().boardRevision());
        value.put("hardware", hardware);
        value.put("slots", e.slots().stream().map(OtaRollbackPreflightJson::encodeSlot).toList());
        var j = e.journal(); Map<String, Object> journal = new LinkedHashMap<>();
        journal.put("atomicOperationProfile", j.atomicOperationProfile());
        journal.put("operationRevision", j.operationRevision()); journal.put("state", j.state());
        journal.put("commitOperations", j.commitOperations().stream().map(op -> {
            if (op == null) throw OtaConfirmationJson.invalid();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("permitId", String.valueOf(op.permitId())); entry.put("state", op.state()); return entry;
        }).toList());
        journal.put("rollbackOperationIds", j.rollbackOperationIds().stream().map(UUID::toString).toList());
        value.put("journal", journal); value.put("writeState", e.writeState()); return value;
    }
    /** 单槽白名单编码。 */
    private static Map<String, Object> encodeSlot(Slot slot) {
        if (slot == null) throw OtaConfirmationJson.invalid();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("slot", slot.slot());
        value.put("artifactSha256", slot.artifactSha256());
        value.put("securityVersion", slot.securityVersion());
        value.put("thingModelVersionId", String.valueOf(slot.thingModelVersionId()));
        value.put("thingModelSchemaDigestAlgorithm", slot.thingModelSchemaDigestAlgorithm());
        value.put("thingModelSchemaDigest", slot.thingModelSchemaDigest());
        value.put("propertyProfile", slot.propertyProfile());
        value.put("integrity", slot.integrity());
        value.put("bootable", slot.bootable());
        value.put("bootloaderVerified", slot.bootloaderVerified());
        value.put("health", slot.health());
        return value;
    }
}
