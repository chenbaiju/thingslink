package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.SubscriptionNotificationIntentRepository;
import com.things.link.project.domain.SubscriptionNotificationKind;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 以 PostgreSQL 写入订阅到期通知意图（S14-3c）。
 *
 * <p>幂等仲裁完全交给唯一约束 {@code sys_tenant_subscription_notification_intent_uk}
 * 与 {@code ON CONFLICT DO NOTHING}：本类不做「先查后插」，因此多实例并发、worker 重跑或
 * 事务重试都只能落一条意图。返回空即表示「该时间点此前已发过」，调用方据此决定是否写审计，
 * 不给同一时间点写第二条审计。
 *
 * <p>与订阅表一致不套租户 RLS：服务以显式 tenantId 为参数，访问控制留在应用入口。
 */
@Repository
public class JdbcSubscriptionNotificationIntentRepository implements SubscriptionNotificationIntentRepository {

    /** 意图事实的 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcSubscriptionNotificationIntentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<UUID> insertIfAbsent(UUID tenantId, UUID subscriptionId,
                                         SubscriptionNotificationKind kind,
                                         Instant periodEndsAt, Instant fireAt) {
        UUID intentId = Uuid7.generate();
        int inserted = jdbcTemplate.update("""
                INSERT INTO sys_tenant_subscription_notification_intent (
                    id, tenant_id, subscription_id, kind, period_ends_at, fire_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (tenant_id, kind, period_ends_at) DO NOTHING
                """, intentId, tenantId, subscriptionId, kind.name(),
                Timestamp.from(periodEndsAt), Timestamp.from(fireAt));
        return inserted == 1 ? Optional.of(intentId) : Optional.empty();
    }
}
