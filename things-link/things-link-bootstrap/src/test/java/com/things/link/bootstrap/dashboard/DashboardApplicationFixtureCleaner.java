package com.things.link.bootstrap.dashboard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.UUID;

/**
 * 清理共享Bootstrap数据库中由看板与应用HTTP测试创建的项目域事实。
 *
 * <p>S12-2a2b后的全量验证会在看板测试之后执行仍采用全项目重置的设备测试；若HTTP测试只依赖
 * 唯一ID隔离而不回收新建事实，{@code dash_dashboard_tenant_project_fk}会阻断后续项目重置。
 * 本夹具按生产外键的反向顺序只清调用方登记的项目，避免扩大到其他测试项目。</p>
 */
final class DashboardApplicationFixtureCleaner {

    /** 不允许实例化无状态测试夹具。 */
    private DashboardApplicationFixtureCleaner() {
    }

    /**
     * 清理指定项目的应用版本引用、应用事实和看板事实，保留项目身份给既有全项目重置入口处理。
     *
     * @param connection 迁移owner连接，用于清理普通应用角色无权物理删除的不可变事实
     * @param projectIds 当前测试创建的项目ID集合
     * @throws SQLException 任一清理语句失败时保留首因并使测试失败
     */
    static void clearProjects(Connection connection, Collection<UUID> projectIds) throws SQLException {
        for (UUID projectId : projectIds) {
            delete(connection, "DELETE FROM app_application_version_dashboard_ref WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM app_application_creation_result WHERE project_id = ?", projectId);
            delete(connection, "UPDATE app_application SET current_version_id = NULL WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM app_application_version WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM app_application_draft WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM app_application WHERE project_id = ?", projectId);

            // ADR0101分享scope和签发仲裁先于其精确版本，保持真实外键而不使用CASCADE。
            delete(connection, "DELETE FROM dash_share_creation_result WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM dash_share_scope_device WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM dash_share_scope WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM dash_share_token WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM dash_dashboard_creation_result WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM dash_dashboard_draft_model_ref WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM dash_dashboard_version_model_ref WHERE project_id = ?", projectId);
            delete(connection, "UPDATE dash_dashboard SET current_version_id = NULL WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM dash_dashboard_draft WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM dash_dashboard_version WHERE project_id = ?", projectId);
            delete(connection, "DELETE FROM dash_dashboard WHERE project_id = ?", projectId);
        }
    }

    /** 执行单项目参数化清理，避免把测试ID拼入SQL。 */
    private static void delete(Connection connection, String sql, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, projectId);
            statement.executeUpdate();
        }
    }
}
