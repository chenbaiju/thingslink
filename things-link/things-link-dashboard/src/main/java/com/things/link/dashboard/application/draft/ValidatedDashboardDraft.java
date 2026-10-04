package com.things.link.dashboard.application.draft;

import com.things.link.dashboard.domain.DashboardModelReference;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/**
 * 已通过看板草稿原文、内部Schema语义和关系投影一致性校验的保存候选。
 *
 * <p>models仍只表达待核验需求；只有管理服务经device公开端口逐项核验后，才能把本结果交给仓储。</p>
 *
 * @param expectedRevision 调用方读取的草稿revision
 * @param content 注入唯一默认值后的完整tc.dashboard/v1 Schema
 * @param models 按Schema顺序派生的模型摘要核验需求
 */
public record ValidatedDashboardDraft(
        long expectedRevision,
        JsonNode content,
        List<ValidatedDashboardModelReference> models) {

    /** 冻结可变输入并复核规范Schema与持久关系投影一致。 */
    public ValidatedDashboardDraft {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision不得为负数");
        }
        content = Objects.requireNonNull(content, "content").deepCopy();
        models = List.copyOf(Objects.requireNonNull(models, "models"));
        DashboardModelReference.requireExactSchemaProjection(content,
                models.stream().map(ValidatedDashboardModelReference::persistenceReference).toList());
    }

    /** @return 与内部校验事实隔离的规范Schema副本 */
    @Override
    public JsonNode content() {
        return content.deepCopy();
    }

    /**
     * 返回关系表需要的完整有序投影。
     *
     * @return 不含摘要和Profile的不可变关系集合
     */
    public List<DashboardModelReference> persistenceReferences() {
        return models.stream().map(ValidatedDashboardModelReference::persistenceReference).toList();
    }
}
