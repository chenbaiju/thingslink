package com.things.link.project.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * ADR0166 原始付费来源链的结构核验，不读取数据库、不推断审计或退款资格。
 *
 * <p>加载器必须先交叉验证节点的原始订单/服务期与审计。本类再验证节点之间的关系；
 * 只在整链完整通过后返回，调用方不能边遍历边提交历史补录。
 */
public final class SubscriptionProvenanceChain {

    /** 无实例状态。 */
    private SubscriptionProvenanceChain() { }

    /**
     * 按明确前驱遍历，返回当前到最早的付费节点，FREE终点不计金额。
     *
     * @param tenantId 整链真实租户
     * @param currentSubscriptionId 当前付费或FREE订阅
     * @param verifiedLoader 先核验原始证据的加载器；不存在返回null
     * @return 全部付费节点的不可变列表，不能只返回已验证前缀
     * @throws IllegalArgumentException 缺节点、环、跨租户、重复订单或服务区间断裂
     */
    public static List<Node> verify(UUID tenantId, UUID currentSubscriptionId,
                                    Function<UUID, Node> verifiedLoader) {
        Objects.requireNonNull(tenantId, "来源租户不得为空");
        Objects.requireNonNull(currentSubscriptionId, "当前订阅不得为空");
        Objects.requireNonNull(verifiedLoader, "证据加载器不得为空");
        List<Node> paid = new ArrayList<>();
        var subscriptions = new HashSet<UUID>();
        var orders = new HashSet<UUID>();
        UUID next = currentSubscriptionId;
        Node child = null;
        while (next != null) {
            if (!subscriptions.add(next)) {
                throw invalid("来源前驱形成环");
            }
            Node node = verifiedLoader.apply(next);
            if (node == null || !next.equals(node.subscriptionId()) || !tenantId.equals(node.tenantId())) {
                throw invalid("来源节点缺失或身份不一致");
            }
            if (child != null) {
                verifyEdge(child, node);
            }
            if (node.free()) {
                return List.copyOf(paid);
            }
            if (!orders.add(node.orderId())) {
                throw invalid("来源订单被多个节点重复使用");
            }
            paid.add(node);
            if (node.predecessorId() == null) {
                verifyInitialPurchase(node);
            }
            child = node;
            next = node.predecessorId();
        }
        return List.copyOf(paid);
    }

    /** 续费必须相接；升级延续同一终点，不错误排除提前续费后立即升级。 */
    private static void verifyEdge(Node child, Node parent) {
        if (child.kind() == TenantOrderKind.PURCHASE) {
            if (parent.free()) {
                verifyInitialPurchase(child);
            } else if (!child.startsAt().equals(parent.endsAt())) {
                throw invalid("续费区间必须接续前驱终点");
            }
        } else if (parent.free() || !child.endsAt().equals(parent.endsAt())) {
            throw invalid("升级必须有同终点付费前驱");
        }
    }

    /** 无前驱或从FREE首次购买只能从实际支付时刻起算。 */
    private static void verifyInitialPurchase(Node node) {
        if (node.kind() != TenantOrderKind.PURCHASE || !node.startsAt().equals(node.paidAt())) {
            throw invalid("首购必须从支付时刻起算，升级不能缺少付费前驱");
        }
    }

    /** 结构拒绝由应用层映射商业错误，不把缺证据当成空结果。 */
    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }

    /**
     * 已经由订单和原始证据交叉验证的节点，不是客户端可提交的退款请求。
     *
     * @param subscriptionId 原订阅身份
     * @param tenantId 真实归属
     * @param orderId 付费来源订单，FREE为空
     * @param predecessorId 明确前驱身份
     * @param kind PURCHASE或UPGRADE，FREE为空
     * @param startsAt 原始服务区间起点
     * @param endsAt 原始终点，FREE为空
     * @param paidAt 订单实际支付时刻，FREE为空
     * @param amountCents 原始成交额或升级实际补差
     * @param free 仅由真实FREE目录及零价无来源事实判定
     */
    public record Node(UUID subscriptionId, UUID tenantId, UUID orderId, UUID predecessorId,
                       TenantOrderKind kind, Instant startsAt, Instant endsAt, Instant paidAt,
                       long amountCents, boolean free) {

        /** 节点局部事实不能表达假FREE、包订单、负金额或空服务期。 */
        public Node {
            Objects.requireNonNull(subscriptionId, "订阅身份不得为空");
            Objects.requireNonNull(tenantId, "来源租户不得为空");
            Objects.requireNonNull(startsAt, "原始起点不得为空");
            if (free) {
                if (orderId != null || predecessorId != null || kind != null || endsAt != null
                        || paidAt != null || amountCents != 0) {
                    throw invalid("FREE来源必须为零价、无订单、无期限根节点");
                }
            } else if (orderId == null || (kind != TenantOrderKind.PURCHASE && kind != TenantOrderKind.UPGRADE)
                    || endsAt == null || !endsAt.isAfter(startsAt) || paidAt == null || amountCents < 0) {
                throw invalid("付费来源必须保留合法订单与完整服务区间");
            }
        }
    }
}
