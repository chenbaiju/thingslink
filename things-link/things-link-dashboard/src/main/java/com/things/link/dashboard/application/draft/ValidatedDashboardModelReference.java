package com.things.link.dashboard.application.draft;

import com.things.link.dashboard.domain.DashboardModelReference;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 从规范Schema models数组派生的模型关系和外部摘要核验需求。
 *
 * @param position models数组零基位置
 * @param modelKey Schema内模型别名
 * @param thingModelVersionId 精确不可变模型版本ID
 * @param digestAlgorithm Schema声明的摘要算法
 * @param digest Schema声明的模型摘要
 * @param profile Schema声明的模型Profile
 */
public record ValidatedDashboardModelReference(
        int position,
        String modelKey,
        UUID thingModelVersionId,
        String digestAlgorithm,
        String digest,
        String profile) {

    /** 当前Dashboard合同接受的物模型摘要算法。 */
    public static final String REQUIRED_DIGEST_ALGORITHM = "PG_JSONB_TEXT_V1_SHA256";
    /** 当前Dashboard合同接受的复合属性Profile。 */
    public static final String REQUIRED_PROFILE = "TC_PROPERTY_COMPOSITE_V1";
    /** SHA-256小写十六进制表示的精确语法。 */
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    /** 校验纯Schema层已经证明的字段形状，不把该校验冒充device外部事实命中。 */
    public ValidatedDashboardModelReference {
        // 复用持久关系值对象的position、LocalKey和非空版本ID约束，避免公开门面产生另一套语法。
        new DashboardModelReference(position, modelKey, thingModelVersionId);
        Objects.requireNonNull(digestAlgorithm, "digestAlgorithm");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(profile, "profile");
        if (!REQUIRED_DIGEST_ALGORITHM.equals(digestAlgorithm)) {
            throw new IllegalArgumentException("digestAlgorithm未登记");
        }
        if (!SHA256.matcher(digest).matches()) {
            throw new IllegalArgumentException("digest必须是64位小写十六进制");
        }
        if (!REQUIRED_PROFILE.equals(profile)) {
            throw new IllegalArgumentException("profile未登记");
        }
    }

    /**
     * 投影关系表需要的最小字段，不重复持久化摘要或Profile。
     *
     * @return 与规范Schema同位置的持久模型关系
     */
    public DashboardModelReference persistenceReference() {
        return new DashboardModelReference(position, modelKey, thingModelVersionId);
    }
}
