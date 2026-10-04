package com.things.link.support.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ADR0166 内部商业来源核验端口，不放宽项目审计HTTP接口。
 *
 * <p>范围固定为非项目、指定租户和精确目标，只开放三种商业来源动作；至多读取两条，
 * 让调用方识别“唯一证据”或“重复冲突”，禁止取最新一条掩盖矛盾。
 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class TenantAuditEvidenceQuery {

    /** 审计所属模块的数据库访问器。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 当前事务数据库访问器 */
    public TenantAuditEvidenceQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取订阅的激活或升级证据；两类同时存在也作为重复冲突交给调用方。
     * @param tenantId 真实租户，不允许缺省
     * @param subscriptionId 精确订阅身份，不允许模糊查询
     * @return 零条、唯一一条或两条冲突证据
     */
    public List<Evidence> activation(UUID tenantId, UUID subscriptionId) {
        return query(tenantId, subscriptionId, "tenant_subscription",
                "commercial.subscription.activated", "commercial.subscription.upgraded");
    }

    /**
     * 读取已执行预约降级的精确证据，不能把预约或撤销当成已执行。
     * @param tenantId 真实租户
     * @param pendingChangeId 预约变更身份
     * @return 零条、唯一一条或两条冲突证据
     */
    public List<Evidence> appliedDowngrade(UUID tenantId, UUID pendingChangeId) {
        return query(tenantId, pendingChangeId, "tenant_subscription_pending_change",
                "commercial.subscription.downgrade.applied", "commercial.subscription.downgrade.applied");
    }

    /** 内部固定查询：用户不能传入动作、目标类型或SQL，身份空值在查询前拒绝。 */
    private List<Evidence> query(UUID tenant, UUID target, String type, String first, String second) {
        if (tenant == null || target == null) {
            throw new IllegalArgumentException("商业来源审计要求明确租户和目标");
        }
        return List.copyOf(jdbc.query("""
                SELECT id,action,details::text,created_at FROM sys_audit_log
                 WHERE tenant_id=? AND project_id IS NULL AND target_type=? AND target_id=?
                   AND action IN (?,?) ORDER BY id LIMIT 2
                """, (rs, row) -> new Evidence(rs.getObject("id",UUID.class),rs.getString("action"),
                rs.getString("details"),rs.getTimestamp("created_at").toInstant()),tenant,type,target,first,second));
    }

    /**
     * 原始证据内容；不在support解释商业金额，也不通过记录时刻推断前驱。
     * @param id 不可篡改审计ID
     * @param action 精确动作
     * @param details 原始JSON，保留长整数精度
     * @param recordedAt 数据库记录时刻
     */
    public record Evidence(UUID id, String action, String details, Instant recordedAt) { }
}
