package com.things.link.assistant.application;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 本人选定设备的历史事实集合视图；不包含模型结论、实时查询或独立持久任务。
 * @param schemaVersion 固定第一版数值格式
 * @param mode 固定纯事实模式
 * @param scope 覆盖范围仅为选定个人记录
 * @param sourceRecords 按规范标识排序的有效来源，最多五设备各一条
 * @param coverage 所选来源的属性与可用值统计，不代表项目或健康覆盖
 * @param earliestCollectionAt 最早历史采集起点
 * @param latestCollectionAt 最晚历史采集终点，包络内不承诺连续数据
 * @param expiresAt 最早来源可见期限，删除和撤权仍可提前失效
 * @param contentSha256 完整纯文本的字节摘要
 * @param markdown 最多一兆字节的固定格式正文，不执行其中指令
 */
@Schema(name="PersonalFactCollectionReport", requiredProperties={"schemaVersion","mode","scope","sourceRecords",
        "coverage","earliestCollectionAt","latestCollectionAt","expiresAt","contentSha256","markdown"})
public record PersonalFactCollectionReport(@Schema(minimum="1",maximum="1") int schemaVersion,
        @Schema(allowableValues={"FACTS_ONLY"}) String mode,
        @Schema(allowableValues={"SELECTED_PERSONAL_RECORDS"}) String scope,
        @ArraySchema(minItems=1,maxItems=5) List<PersonalEvidenceRecordView> sourceRecords,
        Coverage coverage, Instant earliestCollectionAt, Instant latestCollectionAt, Instant expiresAt,
        @Schema(pattern="[0-9a-f]{64}") String contentSha256,
        @Schema(maxLength=1048576) String markdown) {
    /** 防止调用者修改来源集合；记录正文不进入默认字符串表示。 */
    public PersonalFactCollectionReport { sourceRecords=List.copyOf(sourceRecords); }
    @Override public String toString() { return "个人集合事实报告[来源数="+sourceRecords.size()+"]"; }

    /**
     * 缺项包含已省略值，不把无值当成零或正常。
     * @param devices 明确选择的不同设备数
     * @param selectedProperties 各原记录选择的属性总数
     * @param availableValues 数值或布尔可用值数
     * @param unavailableValues 未取得可用值数，包含省略数
     * @param omittedValues 非数值或布尔省略数，属于缺项子集
     */
    @Schema(name="PersonalFactCollectionCoverage",requiredProperties={"devices","selectedProperties","availableValues","unavailableValues","omittedValues"})
    public record Coverage(@Schema(minimum="1",maximum="5") int devices,
            @Schema(minimum="1",maximum="50") int selectedProperties,
            @Schema(minimum="0",maximum="50") int availableValues,
            @Schema(minimum="0",maximum="50") int unavailableValues,
            @Schema(minimum="0",maximum="50") int omittedValues) { }
}
