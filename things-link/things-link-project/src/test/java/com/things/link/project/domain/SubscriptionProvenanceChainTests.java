package com.things.link.project.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0166 跨节点语义反例，不把本类作为原始审计核验替代。 */
class SubscriptionProvenanceChainTests {

    /** 每例独立租户。 */
    private final UUID tenant=UUID.randomUUID();
    /** 不随本机时钟变化的首购支付时刻。 */
    private final Instant start=Instant.parse("2026-01-01T00:00:00Z");

    /** 提前续费后升级，升级起点可早于预付周期起点，不能遗漏最初已付周期。 */
    @Test
    void includesOriginalPrepaidAndUpgradeOrders() {
        var first=node(UUID.randomUUID(),null,TenantOrderKind.PURCHASE,0,365);
        var renew=node(UUID.randomUUID(),first.subscriptionId(),TenantOrderKind.PURCHASE,365,730);
        var upgrade=node(UUID.randomUUID(),renew.subscriptionId(),TenantOrderKind.UPGRADE,100,730);
        var map=Map.of(first.subscriptionId(),first,renew.subscriptionId(),renew,upgrade.subscriptionId(),upgrade);
        assertThat(SubscriptionProvenanceChain.verify(tenant,upgrade.subscriptionId(),map::get))
                .containsExactly(upgrade,renew,first);
    }

    /** 真实FREE终点不制造零价付费订单。 */
    @Test
    void acceptsFreeRootWithoutReturningItAsPaid() {
        var free=new SubscriptionProvenanceChain.Node(UUID.randomUUID(),tenant,null,null,null,start,null,null,0,true);
        var first=node(UUID.randomUUID(),free.subscriptionId(),TenantOrderKind.PURCHASE,0,365);
        var map=Map.of(first.subscriptionId(),first,free.subscriptionId(),free);
        assertThat(SubscriptionProvenanceChain.verify(tenant,first.subscriptionId(),map::get)).containsExactly(first);
        assertThat(SubscriptionProvenanceChain.verify(tenant,free.subscriptionId(),map::get)).isEmpty();
    }

    /** 缺前驱不能返回已经读取的前缀。 */
    @Test
    void rejectsMissingAncestor() {
        var first=node(UUID.randomUUID(),UUID.randomUUID(),TenantOrderKind.PURCHASE,365,730);
        assertThatThrownBy(() -> SubscriptionProvenanceChain.verify(tenant,first.subscriptionId(),Map.of(first.subscriptionId(),first)::get))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("缺失");
    }

    /** 明确循环而非按时间排序猜一个终点。 */
    @Test
    void rejectsCycle() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        var first=node(a,b,TenantOrderKind.UPGRADE,100,365);
        var second=node(b,a,TenantOrderKind.UPGRADE,100,365);
        var map=Map.of(a,first,b,second);
        assertThatThrownBy(() -> SubscriptionProvenanceChain.verify(tenant,a,map::get))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("环");
    }

    /** 错误加载器不能越租户或把另一身份节点冒充目标。 */
    @Test
    void rejectsCrossTenantAndSubstitutedIdentity() {
        var first=node(UUID.randomUUID(),null,TenantOrderKind.PURCHASE,0,365);
        assertThatThrownBy(() -> SubscriptionProvenanceChain.verify(UUID.randomUUID(),first.subscriptionId(),id -> first))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionProvenanceChain.verify(tenant,UUID.randomUUID(),id -> first))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 续费断裂或重叠都不能解释成连续服务期。 */
    @Test
    void rejectsRenewalGapAndOverlap() {
        var first=node(UUID.randomUUID(),null,TenantOrderKind.PURCHASE,0,365);
        for (long day:new long[]{364,366}) {
            var renew=node(UUID.randomUUID(),first.subscriptionId(),TenantOrderKind.PURCHASE,day,730);
            var map=Map.of(first.subscriptionId(),first,renew.subscriptionId(),renew);
            assertThatThrownBy(() -> SubscriptionProvenanceChain.verify(tenant,renew.subscriptionId(),map::get))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("接续");
        }
    }

    /** 升级不能凭空建立新周期或改变已付终点。 */
    @Test
    void rejectsUpgradeWithoutParentOrWithDifferentEnd() {
        var first=node(UUID.randomUUID(),null,TenantOrderKind.PURCHASE,0,365);
        var upgrade=node(UUID.randomUUID(),first.subscriptionId(),TenantOrderKind.UPGRADE,100,366);
        var map=Map.of(first.subscriptionId(),first,upgrade.subscriptionId(),upgrade);
        assertThatThrownBy(() -> SubscriptionProvenanceChain.verify(tenant,upgrade.subscriptionId(),map::get))
                .isInstanceOf(IllegalArgumentException.class);
        var orphan=node(UUID.randomUUID(),null,TenantOrderKind.UPGRADE,0,365);
        assertThatThrownBy(() -> SubscriptionProvenanceChain.verify(tenant,orphan.subscriptionId(),id -> orphan))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 同一来源订单被复制到两个服务节点会造成重复退款，必须拒绝。 */
    @Test
    void rejectsReusedOrder() {
        var first=node(UUID.randomUUID(),null,TenantOrderKind.PURCHASE,0,365);
        var second=new SubscriptionProvenanceChain.Node(UUID.randomUUID(),tenant,first.orderId(),first.subscriptionId(),
                TenantOrderKind.PURCHASE,start.plus(365,ChronoUnit.DAYS),start.plus(730,ChronoUnit.DAYS),start,100,false);
        var map=Map.of(first.subscriptionId(),first,second.subscriptionId(),second);
        assertThatThrownBy(() -> SubscriptionProvenanceChain.verify(tenant,second.subscriptionId(),map::get))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复");
    }

    /** 无前驱首购不能把支付之前的服务期冒充已付历史。 */
    @Test
    void rejectsInitialPeriodDifferentFromPaymentTime() {
        var first=node(UUID.randomUUID(),null,TenantOrderKind.PURCHASE,-1,365);
        assertThatThrownBy(() -> SubscriptionProvenanceChain.verify(tenant,first.subscriptionId(),id -> first))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("支付时刻");
    }

    /** FREE不是升级补差可引用的付费来源。 */
    @Test
    void rejectsUpgradeFromFreeRoot() {
        var free=new SubscriptionProvenanceChain.Node(UUID.randomUUID(),tenant,null,null,null,start,null,null,0,true);
        var upgrade=node(UUID.randomUUID(),free.subscriptionId(),TenantOrderKind.UPGRADE,0,365);
        var map=Map.of(free.subscriptionId(),free,upgrade.subscriptionId(),upgrade);
        assertThatThrownBy(() -> SubscriptionProvenanceChain.verify(tenant,upgrade.subscriptionId(),map::get))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("付费前驱");
    }

    /** 假免费、资源包及非法付费服务期在结构核验前拒绝。 */
    @Test
    void rejectsMalformedLocalFacts() {
        assertThatThrownBy(() -> new SubscriptionProvenanceChain.Node(UUID.randomUUID(),tenant,null,null,
                null,start,null,null,1,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> node(UUID.randomUUID(),null,TenantOrderKind.PACKAGE,0,365))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> node(UUID.randomUUID(),null,TenantOrderKind.PURCHASE,1,1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 构造可独立读懂的原始节点；付费时间固定，续费代表提前购买。 */
    private SubscriptionProvenanceChain.Node node(UUID id,UUID parent,TenantOrderKind kind,long from,long to) {
        return new SubscriptionProvenanceChain.Node(id,tenant,UUID.randomUUID(),parent,kind,
                start.plus(from,ChronoUnit.DAYS),start.plus(to,ChronoUnit.DAYS),start,100,false);
    }
}
