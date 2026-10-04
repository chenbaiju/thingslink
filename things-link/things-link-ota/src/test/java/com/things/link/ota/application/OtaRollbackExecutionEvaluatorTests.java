package com.things.link.ota.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 公开黄金协议上的纯规则反例，不代替设备硬件原子日志验收。 */
class OtaRollbackExecutionEvaluatorTests {
    /** 严格规范化工具。 */ private final OtaCanonicalJson json=new OtaCanonicalJson();
    /** 原操作黄金合同。 */ private final OtaRollbackOperationCodec operations=new OtaRollbackOperationCodec();
    /** 完整报告闭集解析。 */ private final OtaRollbackOperationReportCodec reports=new OtaRollbackOperationReportCodec();

    /** 当前完整候选不健康才能触发，健康、未知或错摘要都不创建动作。 */
    @Test void onlyExactVerifiedUnhealthyCandidateTriggers() throws Exception {
        var raw=object(read("rollback-preflight-report-v1.json"));
        var evidence=map(raw.get("evidence"));var slots=(List<?>)evidence.get("slots");
        var candidate=map(slots.get(1));candidate.putAll(map(((List<?>)proof(body()).get("slots")).get(1)));candidate.put("health","UNHEALTHY");
        assertThat(candidate(raw)).isTrue();
        for(String state:List.of("HEALTHY","UNKNOWN")){candidate.put("health",state);assertThat(candidate(raw)).isFalse();}
        candidate.put("health","UNHEALTHY");candidate.put("artifactSha256","f".repeat(64));assertThat(candidate(raw)).isFalse();
        candidate.put("artifactSha256","b".repeat(64));candidate.put("bootloaderVerified",false);assertThat(candidate(raw)).isFalse();
    }
    /** 完整原槽终态和独立新boot匹配，同时保持接纳boot不变。 */
    @Test void acceptsExactDurableRollbackAndIndependentCurrentBoot() throws Exception {
        var decoded=reports.decode(read("rollback-operation-report-v1.json"));var op=operation();
        assertThat(OtaRollbackExecutionEvaluator.acceptedJournal(op.value(),op.sha256(),decoded.value().evidence())).isTrue();
        assertThat(mismatch(body())).isNull();
        var changed=body();changed.put("bootId",UUID.randomUUID().toString());assertThat(mismatch(changed)).isNull();
    }
    /** 接纳修订、原接纳boot和操作摘要任一错位都不能证明耐久CAS。 */
    @Test void rejectsForgedAcceptanceTuple() throws Exception {
        for(String field:List.of("acceptedRevision","acceptedBootId","operationSha256","operationId")){
            var raw=body();var entry=map(((List<?>)journal(raw).get("rollbackOperations")).getFirst());
            entry.put(field,switch(field){case "acceptedRevision"->7L;case "operationSha256"->"e".repeat(64);default->UUID.randomUUID().toString();});
            assertThat(mismatch(raw)).as(field).isEqualTo("ROLLBACK_ACCEPTED_JOURNAL_MISMATCH");
        }
        var raw=body();journal(raw).put("operationRevision",9L);assertThat(mismatch(raw)).isEqualTo("ROLLBACK_JOURNAL_REVISION_MISMATCH");
    }
    /** 外层状态必须与同一耐久日志阶段相符，终态必须证明新启动。 */
    @Test void rejectsStaleBootAndMismatchedJournalStage() throws Exception {
        var raw=body();raw.put("bootId",operation().value().expectedBootId().toString());assertThat(mismatch(raw)).isEqualTo("ROLLBACK_NEW_BOOT_MISSING");
        raw=body();map(((List<?>)journal(raw).get("rollbackOperations")).getFirst()).put("state","ACCEPTED");
        assertThat(mismatch(raw)).isEqualTo("ROLLBACK_JOURNAL_STAGE_MISMATCH");
        raw=body();raw.put("status","ACCEPTED");map(((List<?>)journal(raw).get("rollbackOperations")).getFirst()).put("state","ACCEPTED");
        assertThat(mismatch(raw)).isNull();
    }
    /** 原槽和反回滚事实绝不能被健康布尔或相同模型UUID替代。 */
    @Test void rejectsUnsafeSourceCounterAndMissingFence() throws Exception {
        var raw=body();map(((List<?>)proof(raw).get("slots")).getFirst()).put("artifactSha256","e".repeat(64));assertThat(mismatch(raw)).isEqualTo("ROLLBACK_SOURCE_TUPLE_MISMATCH");
        raw=body();map(((List<?>)proof(raw).get("slots")).getFirst()).put("integrity","UNKNOWN");assertThat(mismatch(raw)).isEqualTo("ROLLBACK_SOURCE_UNSAFE");
        raw=body();proof(raw).put("committedSecurityVersion",0L);assertThat(mismatch(raw)).isEqualTo("ROLLBACK_COMMITTED_COUNTER_CHANGED");
        raw=body();map(((List<?>)journal(raw).get("commitOperations")).getFirst()).put("state","NOT_ACCEPTED");assertThat(mismatch(raw)).isEqualTo("ROLLBACK_COMMIT_FENCE_MISSING");
    }
    /** 提交先赢须原许可提交、无回退接纳且目标实际健康运行。 */
    @Test void acceptsOnlyCompleteCommitWinnerProof() throws Exception {
        var raw=commitWon();assertThat(mismatch(raw)).isNull();
        proof(raw).put("committedSecurityVersion",1L);assertThat(mismatch(raw)).isEqualTo("COMMIT_WINNER_TARGET_MISMATCH");
        raw=commitWon();journal(raw).put("rollbackOperations",journal(body()).get("rollbackOperations"));assertThat(mismatch(raw)).isEqualTo("COMMIT_WINNER_JOURNAL_MISMATCH");
        raw=commitWon();map(((List<?>)proof(raw).get("slots")).get(1)).put("health","UNHEALTHY");assertThat(mismatch(raw)).isEqualTo("COMMIT_WINNER_TARGET_MISMATCH");
    }
    /** 物理硬件、bootloader或writer改变不能借旧观察提前终态。 */
    @Test void rejectsChangedPlatformAndNonQuiescentTerminal() throws Exception {
        var raw=body();proof(raw).put("bootloaderVersion","1.3.1");assertThat(mismatch(raw)).isEqualTo("ROLLBACK_PLATFORM_TUPLE_CHANGED");
        raw=body();proof(raw).put("writeState","WRITING");assertThat(mismatch(raw)).isEqualTo("ROLLBACK_SOURCE_NOT_RUNNING");
        raw=body();proof(raw).put("activeSlot","B");assertThat(mismatch(raw)).isEqualTo("ROLLBACK_SOURCE_NOT_RUNNING");
    }
    /** 拒绝或未知只观察，不能靠恰好完整的其他字段隐式推进。 */
    @Test void refusedAndUnknownNeverProvideProgressProof() throws Exception {
        for(String state:List.of("REFUSED","UNKNOWN")){var raw=body();raw.put("status",state);assertThat(mismatch(raw)).isEqualTo("ROLLBACK_RESULT_NOT_CONFIRMED");}
    }
    /** 单次纯规则使用已解码规范契约。 */
    private String mismatch(Map<String,Object> raw) throws Exception {
        var decoded=reports.decode(json.writeObject(raw)).value();var op=operation();
        return OtaRollbackExecutionEvaluator.proofMismatch(op.value(),op.sha256(),decoded.bootId(),decoded.status(),decoded.evidence(),
                new OtaRollbackPreflightReportCodec().decode(read("rollback-preflight-report-v1.json")).value(),manifest());
    }
    /** 父预检只用于比较物理信息，目标字段使用独立固定值。 */
    private boolean candidate(Map<String,Object> raw) {return OtaRollbackExecutionEvaluator.candidateUnhealthy(new OtaRollbackPreflightReportCodec().decode(json.writeObject(raw)).value(),manifest(),"B");}
    /** 完整合同目标用于纯规则；改动后的字节不冒充已有黄金签名。 */
    private byte[] manifest(){
        try {
            var fields=object(read("manifest-v1.json"));fields.put("artifactSha256","b".repeat(64));
            fields.put("securityVersion",2L);fields.put("thingModelVersionId","018f0000-0000-7000-8000-000000000004");
            fields.put("thingModelSchemaDigest","c".repeat(64));
            return new OtaManifestCodec().canonicalize(json.writeObject(fields));
        } catch(Exception failure) { throw new IllegalStateException("完整测试清单不能恢复",failure); }
    }
    /** 原操作具有公开固定摘要。 */
    private OtaRollbackOperationCodec.Decoded operation() throws Exception {return operations.decode(read("rollback-operation-v1.json"));}
    /** 每次独立可变报告用于针对单一事实构造反例。 */
    private Map<String,Object> body() throws Exception {return object(read("rollback-operation-report-v1.json"));}
    /** 完整提交先赢证明，不能保留回退接纳项。 */
    private Map<String,Object> commitWon() throws Exception {
        var raw=body();raw.put("status","COMMIT_WON");proof(raw).put("activeSlot","B");proof(raw).put("committedSecurityVersion",2L);
        journal(raw).put("rollbackOperations",List.of());map(((List<?>)journal(raw).get("commitOperations")).getFirst()).put("state","COMMITTED");return raw;
    }
    /** 内嵌证据可变视图。 */ private static Map<String,Object> proof(Map<String,Object> raw){return map(raw.get("evidence"));}
    /** 内嵌日志可变视图。 */ private static Map<String,Object> journal(Map<String,Object> raw){return map(proof(raw).get("journal"));}
    /** 固定资源读取，不访问环境密钥。 */
    private byte[] read(String file) throws Exception {try(var input=getClass().getResourceAsStream("/ota/"+file)){return input.readAllBytes();}}
    /** 复制严格解析器不可变返回值，不修改生产对象。 */
    private Map<String,Object> object(byte[] bytes){return map(copy(json.parseObject(bytes)));}
    /** 深复制嵌套对象和集合。 */
    private static Object copy(Object value){if(value instanceof Map<?,?> source){var result=new LinkedHashMap<String,Object>();source.forEach((key,item)->result.put((String)key,copy(item)));return result;}
        if(value instanceof List<?> list)return list.stream().map(OtaRollbackExecutionEvaluatorTests::copy).toList();return value;}
    /** 仅对已知严格解析对象转换类型。 */
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
}
