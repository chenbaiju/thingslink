package com.things.link.ota.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.things.link.ota.application.OtaRollbackOperationCodec.Source;
import com.things.link.ota.application.OtaRollbackOperationReportCodec.Evidence;
import com.things.link.ota.application.OtaRollbackOperationReportCodec.Journal;
import com.things.link.ota.application.OtaRollbackOperationReportCodec.RollbackOperation;
import com.things.link.ota.application.OtaRollbackPreflightReportCodec.Hardware;
import com.things.link.ota.application.OtaRollbackPreflightReportCodec.Slot;
import com.things.link.ota.application.OtaRollbackPreflightReportCodec.CommitOperation;

/** 新原子执行嵌套协议，独立闭集不改变原预检合同。 */
final class OtaRollbackExecutionJson {
    /** 制造合同固定原子互斥标识，不是远程硬件证明。 */
    private static final String PROFILE = "TC_OTA_AB_COMMIT_ROLLBACK_JOURNAL_V1";
    /** 仅提供确定性词法工具。 */
    private OtaRollbackExecutionJson() { }
    /** 零至一个日志项，空数组表达当前无记录，不能接受空引用。 */
    private static List<?> operations(Object value) {
        if (!(value instanceof List<?> items) || items.size() > 1) throw OtaConfirmationJson.invalid();
        return items;
    }
    /** 原槽命令只允许完整七字段身份。 */
    static Source source(Object value) {
        var v = OtaTrustBundleCodec.object(value);
        OtaTrustBundleCodec.closed(v, Set.of("slot", "artifactSha256", "securityVersion", "thingModelVersionId",
                "thingModelSchemaDigestAlgorithm", "thingModelSchemaDigest", "propertyProfile"));
        return new Source(OtaConfirmationJson.literal(v, "slot", Set.of("A", "B")),
                OtaConfirmationJson.hex(v, "artifactSha256"),
                OtaConfirmationJson.integer(v, "securityVersion", 0, 9007199254740991L),
                OtaTrustBundleCodec.uuid(v.get("thingModelVersionId")),
                OtaConfirmationJson.literal(v, "thingModelSchemaDigestAlgorithm", Set.of("PG_JSONB_TEXT_V1_SHA256")),
                OtaConfirmationJson.hex(v, "thingModelSchemaDigest"),
                OtaConfirmationJson.literal(v, "propertyProfile", Set.of("TC_PROPERTY_COMPOSITE_V1")));
    }
    /** 完整观察结构，业务层决定日志和计数是否矛盾。 */
    static Evidence evidence(Object value) {
        var e = OtaTrustBundleCodec.object(value);
        OtaTrustBundleCodec.closed(e, Set.of("bootloaderVersion", "hardware", "slots", "journal",
                "writeState", "activeSlot", "committedSecurityVersion"));
        var h = OtaTrustBundleCodec.object(e.get("hardware"));
        OtaTrustBundleCodec.closed(h, Set.of("model", "boardRevision"));
        var hardware = new Hardware(OtaTrustBundleCodec.text(h.get("model"), "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"),
                OtaConfirmationJson.integer(h, "boardRevision", 0, 9007199254740991L));
        var slots = new ArrayList<Slot>();
        for (Object item : OtaTrustBundleCodec.list(e.get("slots"), 2)) slots.add(slot(item));
        if (slots.size() != 2 || slots.get(0).slot().equals(slots.get(1).slot())) throw OtaConfirmationJson.invalid();
        slots.sort(Comparator.comparing(Slot::slot));
        var j = OtaTrustBundleCodec.object(e.get("journal"));
        OtaTrustBundleCodec.closed(j, Set.of("atomicOperationProfile", "operationRevision", "commitOperations", "rollbackOperations"));
        var commits = new ArrayList<CommitOperation>();
        for (Object item : operations(j.get("commitOperations"))) {
            var op = OtaTrustBundleCodec.object(item);
            OtaTrustBundleCodec.closed(op, Set.of("permitId", "state"));
            commits.add(new CommitOperation(OtaTrustBundleCodec.uuid(op.get("permitId")),
                    OtaConfirmationJson.literal(op, "state", Set.of("NOT_ACCEPTED", "PENDING", "COMMITTED", "FENCED", "UNKNOWN"))));
        }
        var rollbacks = new ArrayList<RollbackOperation>();
        for (Object item : operations(j.get("rollbackOperations"))) {
            var op = OtaTrustBundleCodec.object(item);
            OtaTrustBundleCodec.closed(op, Set.of("operationId", "operationSha256", "state", "acceptedBootId", "acceptedRevision"));
            rollbacks.add(new RollbackOperation(OtaTrustBundleCodec.uuid(op.get("operationId")),
                    OtaConfirmationJson.hex(op, "operationSha256"),
                    OtaConfirmationJson.literal(op, "state", Set.of("ACCEPTED", "ROLLING_BACK", "ROLLED_BACK")),
                    OtaTrustBundleCodec.uuid(op.get("acceptedBootId")),
                    OtaConfirmationJson.integer(op, "acceptedRevision", 1, 9007199254740991L)));
        }
        var journal = new Journal(OtaConfirmationJson.literal(j, "atomicOperationProfile", Set.of(PROFILE)),
                OtaConfirmationJson.integer(j, "operationRevision", 0, 9007199254740991L), commits, rollbacks);
        return new Evidence(OtaRollbackPreflightJson.version(e.get("bootloaderVersion")), hardware, slots, journal,
                OtaConfirmationJson.literal(e, "writeState", Set.of("QUIESCENT", "WRITING", "UNKNOWN")),
                OtaConfirmationJson.literal(e, "activeSlot", Set.of("A", "B")),
                OtaConfirmationJson.integer(e, "committedSecurityVersion", 0, 9007199254740991L));
    }
    /** 原来源只编码七字段。 */
    static Map<String, Object> encode(Source source) {
        if (source == null) throw OtaConfirmationJson.invalid();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("slot", source.slot());
        v.put("artifactSha256", source.artifactSha256());
        v.put("securityVersion", source.securityVersion());
        v.put("thingModelVersionId", String.valueOf(source.thingModelVersionId()));
        v.put("thingModelSchemaDigestAlgorithm", source.thingModelSchemaDigestAlgorithm());
        v.put("thingModelSchemaDigest", source.thingModelSchemaDigest());
        v.put("propertyProfile", source.propertyProfile());
        return v;
    }
    /** 观察编码不会补造未接纳的回退记录。 */
    static Map<String, Object> encode(Evidence e) {
        if (e == null || e.hardware() == null || e.journal() == null) throw OtaConfirmationJson.invalid();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("bootloaderVersion", e.bootloaderVersion());
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("model", e.hardware().model());
        h.put("boardRevision", e.hardware().boardRevision());
        v.put("hardware", h);
        v.put("slots", e.slots().stream().map(OtaRollbackExecutionJson::encodeSlot).toList());
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("atomicOperationProfile", e.journal().atomicOperationProfile());
        j.put("operationRevision", e.journal().operationRevision());
        j.put("commitOperations", e.journal().commitOperations().stream().map(op -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("permitId", String.valueOf(op.permitId()));
            item.put("state", op.state());
            return item;
        }).toList());
        j.put("rollbackOperations", e.journal().rollbackOperations().stream().map(op -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("operationId", String.valueOf(op.operationId()));
            item.put("operationSha256", op.operationSha256());
            item.put("state", op.state());
            item.put("acceptedBootId", String.valueOf(op.acceptedBootId()));
            item.put("acceptedRevision", op.acceptedRevision());
            return item;
        }).toList());
        v.put("journal", j);
        v.put("writeState", e.writeState());
        v.put("activeSlot", e.activeSlot());
        v.put("committedSecurityVersion", e.committedSecurityVersion());
        return v;
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
