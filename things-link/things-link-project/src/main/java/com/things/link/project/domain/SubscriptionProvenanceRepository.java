package com.things.link.project.domain;

import java.util.UUID;
import java.util.List;
import java.util.Optional;

/** ADR0166 原始付费来源的只增记录端口，不接受调用方编造金额或服务区间。 */
public interface SubscriptionProvenanceRepository {

    /**
     * 在支付生效事务内从已落库订单、订阅和前驱读取原始快照。
     *
     * @param tenantId 真实归属租户
     * @param subscriptionId 新激活订阅
     * @param predecessorId 被取代订阅，可为空
     */
    void recordActivation(UUID tenantId, UUID subscriptionId, UUID predecessorId);

    /** @param tenantId 真实租户 @param subscriptionId 订阅身份 @return 当前业务事实，缺失为空 */
    Optional<Facts> current(UUID tenantId, UUID subscriptionId);

    /** @param tenantId 真实租户 @param subscriptionId 订阅身份 @return 不可变来源记录 */
    Optional<Stored> find(UUID tenantId, UUID subscriptionId);

    /** @param tenantId 真实租户 @param subscriptionId 订阅身份 @return 同订阅已执行降级原始事实 */
    List<String> appliedChanges(UUID tenantId, UUID subscriptionId);

    /** @param tenantId 真实租户 @param subscriptionId 订阅身份 @param source 完整核验后的来源 */
    void recordVerified(UUID tenantId, UUID subscriptionId, Stored source);

    /** @param subscription 订阅JSON @param order 订单JSON，FREE为空 @param planCode 当前目录编码 */
    record Facts(String subscription, String order, String planCode) { }

    /**
     * @param orderId 来源订单 @param predecessorId 明确前驱 @param order 订单快照
     * @param subscription 原订阅快照 @param predecessor 前驱快照 @param evidenceKind 来源类型
     * @param evidenceAuditId 原激活审计ID，原支付事务直接登记时为空
     */
    record Stored(UUID orderId, UUID predecessorId, String order, String subscription,
                  String predecessor, String evidenceKind, UUID evidenceAuditId) { }
}
