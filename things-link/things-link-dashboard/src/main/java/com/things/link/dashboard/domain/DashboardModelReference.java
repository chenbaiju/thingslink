package com.things.link.dashboard.domain;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 看板Schema派生的精确物模型版本关系。
 *
 * <p>该记录只冻结原数组位置、模型别名和版本ID。摘要、Profile及同项目存在性须由后续device公开
 * application端口验证，不能从关系外键成立推导业务校验已经完成。</p>
 *
 * @param position Schema models数组中的零基位置
 * @param modelKey Schema内唯一模型别名
 * @param thingModelVersionId 精确不可变物模型版本ID
 */
public record DashboardModelReference(int position, String modelKey, UUID thingModelVersionId) {

    /** ADR0098冻结的LocalKey语法。 */
    private static final Pattern LOCAL_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    /** Schema合同冻结的单看板最大模型引用数。 */
    private static final int MAXIMUM_REFERENCE_COUNT = 20;

    /** 校验单条关系能够由迁移约束表达。 */
    public DashboardModelReference {
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(thingModelVersionId, "thingModelVersionId");
        if (position < 0 || position >= MAXIMUM_REFERENCE_COUNT) {
            throw new IllegalArgumentException("position必须在0至19之间");
        }
        if (!LOCAL_KEY.matcher(modelKey).matches()) {
            throw new IllegalArgumentException("modelKey必须符合LocalKey语法");
        }
    }

    /**
     * 冻结并校验一份Schema派生的完整关系集合。
     *
     * <p>连续位置保证列表就是原models数组的完整顺序；别名和版本双唯一与迁移约束一致，避免同一
     * 规范内容在Java与数据库形成两种关系解释。</p>
     *
     * @param references 待冻结关系集合
     * @return 保持原顺序的不可变关系集合
     */
    public static List<DashboardModelReference> requireCompleteSet(List<DashboardModelReference> references) {
        List<DashboardModelReference> snapshot = List.copyOf(Objects.requireNonNull(references, "references"));
        if (snapshot.size() > MAXIMUM_REFERENCE_COUNT) {
            throw new IllegalArgumentException("单个看板最多引用20个物模型版本");
        }
        Set<String> modelKeys = new HashSet<>();
        Set<UUID> modelVersionIds = new HashSet<>();
        for (int index = 0; index < snapshot.size(); index++) {
            DashboardModelReference reference = Objects.requireNonNull(snapshot.get(index), "reference");
            if (reference.position() != index) {
                throw new IllegalArgumentException("模型引用position必须从0开始连续排列");
            }
            if (!modelKeys.add(reference.modelKey())) {
                throw new IllegalArgumentException("同一看板不能重复引用modelKey");
            }
            if (!modelVersionIds.add(reference.thingModelVersionId())) {
                throw new IllegalArgumentException("同一看板不能以不同别名重复引用物模型版本");
            }
        }
        return snapshot;
    }

    /**
     * 冻结关系集合并核对它与规范Schema {@code models}数组的持久投影完全一致。
     *
     * <p>{@code models}可省略并唯一解释为空数组；显式提供时，数量、数组顺序、{@code key}和
     * {@code versionId}必须逐项对应关系集合。该入口只证明需要入关系表的三个投影字段一致，
     * 不验证摘要、Profile、版本存在性或项目归属，后四项仍由device公开application端口负责。</p>
     *
     * @param schema 已完成最小格式校验且与调用方隔离的看板Schema
     * @param references 从同一Schema派生的模型关系集合
     * @return 保持Schema顺序的不可变完整关系集合
     */
    public static List<DashboardModelReference> requireExactSchemaProjection(
            JsonNode schema, List<DashboardModelReference> references) {
        Objects.requireNonNull(schema, "schema");
        List<DashboardModelReference> snapshot = requireCompleteSet(references);
        JsonNode models = schema.get("models");
        if (models == null) {
            if (!snapshot.isEmpty()) {
                throw new IllegalArgumentException("Schema省略models时模型关系必须为空");
            }
            return snapshot;
        }
        if (!models.isArray()) {
            throw new IllegalArgumentException("Schema models必须是数组");
        }
        if (models.size() != snapshot.size()) {
            throw new IllegalArgumentException("Schema models与模型关系数量不一致");
        }
        for (int index = 0; index < snapshot.size(); index++) {
            JsonNode model = models.get(index);
            DashboardModelReference reference = snapshot.get(index);
            if (model == null || !model.isObject()
                    || !model.path("key").isString()
                    || !model.path("versionId").isString()
                    || !reference.modelKey().equals(model.path("key").asString())
                    || !reference.thingModelVersionId().toString()
                            .equals(model.path("versionId").asString())) {
                throw new IllegalArgumentException(
                        "Schema models第" + index + "项与模型关系的key或versionId不一致");
            }
        }
        return snapshot;
    }
}
