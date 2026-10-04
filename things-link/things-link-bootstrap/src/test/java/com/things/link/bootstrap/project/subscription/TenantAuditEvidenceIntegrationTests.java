package com.things.link.bootstrap.project.subscription;

import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.audit.TenantAuditEvidenceQuery;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0166 真实审计读取隔离，唯一UUID证据随测试容器回收。 */
class TenantAuditEvidenceIntegrationTests extends AbstractIntegrationTest {

    /** 不可篡改审计真实写入。 */
    @Autowired private AuditLogService writer;
    /** 内部证据查询真实入口。 */
    @Autowired private TenantAuditEvidenceQuery query;

    /** 同目标但错误租户、项目、类型、动作的事实都不能混入。 */
    @Test
    void activationIsBoundToTenantNonProjectTargetAndAllowedActions() {
        UUID tenant=UUID.randomUUID(), target=UUID.randomUUID();
        write(UUID.randomUUID(),null,"tenant_subscription",target,"commercial.subscription.activated");
        write(tenant,UUID.randomUUID(),"tenant_subscription",target,"commercial.subscription.activated");
        write(tenant,null,"tenant_order",target,"commercial.subscription.activated");
        write(tenant,null,"tenant_subscription",target,"commercial.order.paid");
        write(tenant,null,"tenant_subscription",UUID.randomUUID(),"commercial.subscription.activated");
        assertThat(query.activation(tenant,target)).isEmpty();
        write(tenant,null,"tenant_subscription",target,"commercial.subscription.activated");
        assertThat(query.activation(tenant,target)).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo("commercial.subscription.activated");
            assertThat(e.details()).contains("9223372036854775807");
            assertThat(e.id()).isNotNull();
            assertThat(e.recordedAt()).isNotNull();
        });
    }

    /** 激活/升级重复不能靠取最后一条消除，读取最多两条足以拒绝。 */
    @Test
    void returnsTwoConflictsInsteadOfChoosingLatest() {
        UUID tenant=UUID.randomUUID(), target=UUID.randomUUID();
        write(tenant,null,"tenant_subscription",target,"commercial.subscription.activated");
        write(tenant,null,"tenant_subscription",target,"commercial.subscription.upgraded");
        write(tenant,null,"tenant_subscription",target,"commercial.subscription.upgraded");
        assertThat(query.activation(tenant,target)).hasSize(2);
    }

    /** 预约、撤销、未执行动作均不是已经降级的证据。 */
    @Test
    void downgradeRequiresAppliedEventForExactChange() {
        UUID tenant=UUID.randomUUID(), change=UUID.randomUUID();
        write(tenant,null,"tenant_subscription_pending_change",change,"commercial.subscription.downgrade.not_applied");
        write(tenant,null,"tenant_subscription_pending_change",change,"commercial.subscription.downgrade.cancelled");
        write(tenant,null,"tenant_subscription",change,"commercial.subscription.downgrade.applied");
        write(UUID.randomUUID(),null,"tenant_subscription_pending_change",change,"commercial.subscription.downgrade.applied");
        assertThat(query.appliedDowngrade(tenant,change)).isEmpty();
        write(tenant,null,"tenant_subscription_pending_change",change,"commercial.subscription.downgrade.applied");
        assertThat(query.appliedDowngrade(tenant,change)).hasSize(1);
        write(tenant,null,"tenant_subscription_pending_change",change,"commercial.subscription.downgrade.applied");
        assertThat(query.appliedDowngrade(tenant,change)).hasSize(2);
    }

    /** 缺少任一身份不得退化成无范围查询。 */
    @Test
    void missingScopeFailsBeforeDatabaseQuery() {
        assertThatThrownBy(() -> query.activation(null,UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.activation(UUID.randomUUID(),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.appliedDowngrade(null,UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }

    /** 写入足够表达精度及隔离反例的审计，不包含凭据。 */
    private void write(UUID tenant, UUID project, String type, UUID target, String action) {
        writer.record(new AuditLogEntry(tenant,project,null,type,target,action, Map.of("amount",Long.MAX_VALUE)));
    }
}
