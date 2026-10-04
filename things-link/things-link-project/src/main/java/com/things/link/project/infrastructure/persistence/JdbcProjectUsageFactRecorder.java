package com.things.link.project.infrastructure.persistence;

import com.things.link.project.application.ProjectUsageFactRecorder;
import com.things.link.project.application.QuotaMetric;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.UUID;

/** 通过受限 SECURITY DEFINER 函数写入当前项目 REST 计量事实。 */
@Component
public class JdbcProjectUsageFactRecorder implements ProjectUsageFactRecorder, com.things.link.project.application.TrustedProjectUsageFactRecorder {
    /** 事务感知 JDBC 入口，沿用 TenantScopeFilter 设置的当前项目。 */
    private final JdbcTemplate jdbcTemplate;
    /** 以配额策略中的owner租户和当前已选项目建立完整事务局部RLS范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;

    /**
     * @param jdbcTemplate 事务感知 JDBC 入口
     * @param transactionLocalRlsScope 事务局部RLS完整范围组件
     */
    public JdbcProjectUsageFactRecorder(JdbcTemplate jdbcTemplate,
                                        TransactionLocalRlsScope transactionLocalRlsScope) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public boolean record(UUID ownerTenantId, UUID projectId, QuotaMetric metric,
                          String eventKey, Instant occurredAt) {
        validate(metric,eventKey,occurredAt);
        if (!projectId.equals(TenantContext.requireProjectId())) {
            throw new IllegalArgumentException("计量项目必须等于当前 JWT 已选项目");
        }
        return recordFact(ownerTenantId,projectId,metric,eventKey,occurredAt);
    }

    /** 显式可信入口不伪造Console身份，仍由同一函数检查owner/project并保存原子事实。 */
    @Override @Transactional
    public boolean recordTrusted(UUID ownerTenantId,UUID projectId,QuotaMetric metric,String eventKey,Instant occurredAt){
        return recordFact(ownerTenantId,projectId,metric,eventKey,occurredAt);
    }
    private boolean recordFact(UUID ownerTenantId,UUID projectId,QuotaMetric metric,String eventKey,Instant occurredAt){
        validate(metric,eventKey,occurredAt);
        // 两类调用均提供权威owner/project，原事务内建立范围，再由受控函数交叉核对。
        transactionLocalRlsScope.establish(ownerTenantId, projectId);
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT record_current_project_usage_fact(?, ?, ?, ?, ?, ?, ?)
                """, Boolean.class, ownerTenantId, projectId, Uuid7.generate(), metric.name(),
                occurredAt.atZone(ZoneOffset.UTC).toLocalDate(), eventKey, Timestamp.from(occurredAt)));
    }
    private static void validate(QuotaMetric metric,String eventKey,Instant occurredAt){
        if (metric != QuotaMetric.REST_API_CALL || eventKey == null || eventKey.isBlank()
                || eventKey.length() > 160 || occurredAt == null) {
            throw new IllegalArgumentException("REST 日用量事实参数不合法");
        }
    }

}
