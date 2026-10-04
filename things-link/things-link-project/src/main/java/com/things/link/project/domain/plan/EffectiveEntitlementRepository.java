package com.things.link.project.domain.plan;

import java.util.Optional;
import java.util.UUID;

/**
 * 产品修订版功能权益的持久化读取端口（S14-1b）。
 *
 * <p>权益是平台级只读事实（无租户列、不走 RLS），只按修订版身份读取；不提供任何写入，
 * 修订版一经引用即不可变。
 */
public interface EffectiveEntitlementRepository {

    /**
     * 按产品修订版 ID 读取权益投影。
     *
     * @param planRevisionId 产品修订版 ID
     * @return 完整权益闭集；修订版不存在时返回空
     */
    Optional<EffectiveEntitlement> findByPlanRevisionId(UUID planRevisionId);

    /**
     * 按产品修订版标识与档位编码读取权益投影。
     *
     * @param revisionCode 产品修订版标识，如 {@code product-revision-1}
     * @param planCode 档位编码，如 {@code FREE}
     * @return 完整权益闭集；没有匹配修订版时返回空
     */
    Optional<EffectiveEntitlement> findByRevisionAndPlan(String revisionCode, String planCode);
}
