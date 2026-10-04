package com.things.link.support.audit;

import com.things.link.shared.id.Uuid7;
import com.things.link.support.trace.TraceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 审计日志写入服务。
 *
 * <p>审计与业务写入默认处在同一个事务里：业务失败回滚时不留下“做过”的假记录；
 * 审计写失败时业务也失败。成员、权限、控制这类动作宁可让用户重试，也不能在
 * 没有持久审计的情况下悄悄成功（架构文档 7.3）。
 */
@Service
public class AuditLogService {

    /**
     * 直接使用 JDBC 写审计表。
     *
     * <p>审计表没有复杂聚合根，不走 JPA 可以避免持久化上下文缓存给“立即写、立即可查”
     * 这类追溯场景带来额外心智负担。
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 只用于把业务侧传来的结构化详情序列化成 jsonb。
     *
     * <p>不让调用方自己传 JSON 字符串，是为了把“禁止塞口令、令牌、密钥”这类约束留在
     * Java 类型边界上，而不是散落成各处字符串拼接。
     */
    private final ObjectMapper objectMapper;

    /**
     * 构造审计写入服务。
     *
     * @param jdbcTemplate 数据库访问器
     * @param objectMapper JSON 序列化器
     */
    public AuditLogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入审计事件。
     *
     * @param entry 事件
     */
    public void record(AuditLogEntry entry) {
        jdbcTemplate.update("""
                        INSERT INTO sys_audit_log (
                            id, tenant_id, project_id, actor_account_id,
                            target_type, target_id, action, trace_id, details
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        """,
                Uuid7.generate(),
                entry.tenantId(),
                entry.projectId(),
                entry.actorAccountId(),
                entry.targetType(),
                entry.targetId(),
                entry.action(),
                TraceContext.current(),
                detailsJson(entry));
    }

    /**
     * 序列化详情。
     *
     * @param entry 事件
     * @return JSON 字符串
     */
    private String detailsJson(AuditLogEntry entry) {
        Map<String, ?> details = entry.details() == null ? Map.of() : entry.details();
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("审计日志 details 无法序列化", e);
        }
    }

}
