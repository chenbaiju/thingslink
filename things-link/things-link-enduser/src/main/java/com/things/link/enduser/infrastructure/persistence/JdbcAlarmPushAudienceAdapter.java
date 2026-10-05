package com.things.link.enduser.infrastructure.persistence;

import com.things.link.alarm.application.AlarmPushAudiencePort;
import com.things.link.project.application.ProjectLifecycleAccessService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 使用 enduser 权威事实实现告警 PUSH 受众查询。
 *
 * <p>查询必须运行在告警激活事务已建立的 tenant/project 双上下文中：{@code app_user} 与
 * {@code app_push_token} 走 tenant RLS，角色与设备关系走 project RLS。显式携带三条身份轴是
 * 纵深防御，不能替代 RLS，也不能在缺少上下文时回退为跨租户查询。
 * ADR 0064 决策 4 要求在该原事务持有项目写许可；本适配器不创建事务，不能把额度快照当作后续许可。
 */
@Component
public class JdbcAlarmPushAudienceAdapter implements AlarmPushAudiencePort {

    /** 租户感知 JDBC；由项目上下文数据源实施 RLS。 */
    private final JdbcTemplate jdbcTemplate;
    /** 公共项目端口在原告警事务持锁，拒绝只抑制本次PUSH受众，不撤销共用安装。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;

    /**
     * @param jdbcTemplate 租户感知 JDBC 模板
     * @param lifecycleAccessService 原事务项目生命周期许可
     */
    public JdbcAlarmPushAudienceAdapter(JdbcTemplate jdbcTemplate,
                                        ProjectLifecycleAccessService lifecycleAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.lifecycleAccessService = lifecycleAccessService;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<PushAudience> listActiveInstallations(
            UUID tenantId, UUID projectId, UUID deviceId) {
        // ADR 0064 决策 2/4：额度快照后仍可能冻结；先取得并持有项目许可，失败不读取App授权事实。
        if (!lifecycleAccessService.lockActiveForWrite(tenantId, projectId)) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT d.app_user_id, t.id AS push_token_id
                  FROM app_user_device d
                  JOIN app_user_role r
                    ON r.tenant_id = d.tenant_id
                   AND r.project_id = d.project_id
                   AND r.app_user_id = d.app_user_id
                   AND r.status = 'ACTIVE'
                  JOIN app_user u
                    ON u.tenant_id = d.tenant_id
                   AND u.id = d.app_user_id
                   AND u.status = 'ACTIVE'
                  JOIN app_push_token t
                    ON t.tenant_id = d.tenant_id
                   AND t.app_user_id = d.app_user_id
                   AND t.status = 'ACTIVE'
                 WHERE d.tenant_id = ?
                   AND d.project_id = ?
                   AND d.device_id = ?
                   AND d.status = 'ACTIVE'
                 ORDER BY d.app_user_id, t.id
                """,
                (resultSet, rowNumber) -> new PushAudience(
                        resultSet.getObject("app_user_id", UUID.class),
                        resultSet.getObject("push_token_id", UUID.class)),
                tenantId,
                projectId,
                deviceId);
    }
}
