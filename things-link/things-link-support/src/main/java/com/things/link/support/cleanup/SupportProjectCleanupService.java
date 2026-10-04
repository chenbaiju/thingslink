package com.things.link.support.cleanup;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** ADR0090：只调用固定support数据库入口，完整项目身份由数据库授权端口复核。 */
@Service
public class SupportProjectCleanupService {
    /** 继承调用方原事务，不自行创建或延长清理预算。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 原物理事务连接 */
    public SupportProjectCleanupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param tenantId 持久归属
     * @param projectId 实际清理项目
     * @param generation 当前代次
     * @param token 实际领取能力
     * @return 删除、等待或完整空域，不接收动态表名或阶段
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupportProjectCleanupResult clean(UUID tenantId, UUID projectId, long generation, UUID token) {
        return jdbc.queryForObject("SELECT * FROM public.support_project_cleanup_batch(?,?,?,?)",
                (result, row) -> new SupportProjectCleanupResult(result.getInt("deleted_rows"),
                        result.getBoolean("complete"), result.getString("blocked_reason")),
                tenantId, projectId, generation, token);
    }
}
