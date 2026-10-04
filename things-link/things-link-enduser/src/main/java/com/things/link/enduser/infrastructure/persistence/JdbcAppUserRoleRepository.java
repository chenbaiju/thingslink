package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserAssignment;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 JDBC 的终端用户项目角色仓储实现。
 *
 * <p>app_user_role 受项目 RLS 保护：上下文由应用服务在写入前切到目标项目
 * （{@code set_config('app.tenant_id'/'app.project_id', ...)}）。查询显式带
 * {@code project_id = ?}，与 RLS 形成两道独立防线。
 */
@Repository
public class JdbcAppUserRoleRepository implements AppUserRoleRepository {

    private static final RowMapper<AppUserRole> ROLE_MAPPER = (rs, rowNum) -> new AppUserRole(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getObject("app_user_id", UUID.class),
            EndUserRole.valueOf(rs.getString("role")),
            AppUserRole.Status.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant());

    /**
     * 反规范化投影映射：app_user 的身份字段 + app_user_role 的赋值字段。
     * 两个 status 分别取别名，避免列名 {@code status} 撞车。
     */
    private static final RowMapper<AppUserAssignment> ASSIGNMENT_MAPPER = (rs, rowNum) -> new AppUserAssignment(
            rs.getObject("app_user_id", UUID.class),
            rs.getString("username"),
            rs.getString("display_name"),
            AppUser.Status.valueOf(rs.getString("user_status")),
            EndUserRole.valueOf(rs.getString("role")),
            AppUserRole.Status.valueOf(rs.getString("role_status")),
            rs.getTimestamp("assigned_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public JdbcAppUserRoleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void assign(AppUserRole role) {
        jdbcTemplate.update("""
                        INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                role.id(), role.tenantId(), role.projectId(), role.appUserId(),
                role.role().name(), role.status().name());
    }

    @Override
    public Optional<AppUserRole> findByProjectAndUser(UUID projectId, UUID appUserId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, project_id, app_user_id, role, status, created_at
                          FROM app_user_role
                         WHERE project_id = ? AND app_user_id = ?
                        """, ROLE_MAPPER, projectId, appUserId).stream().findFirst();
    }

    @Override
    public int updateStatus(UUID projectId, UUID appUserId, AppUserRole.Status status) {
        return jdbcTemplate.update("""
                        UPDATE app_user_role
                           SET status = ?, updated_at = now()
                         WHERE project_id = ? AND app_user_id = ?
                        """, status.name(), projectId, appUserId);
    }

    @Override
    public int updateRole(UUID projectId, UUID appUserId, EndUserRole role) {
        return jdbcTemplate.update("""
                        UPDATE app_user_role
                           SET role = ?, updated_at = now()
                         WHERE project_id = ? AND app_user_id = ?
                        """, role.name(), projectId, appUserId);
    }

    @Override
    public CursorPage<AppUserAssignment> findAssignmentsByProject(UUID projectId, String cursor, int limit) {
        CursorPosition position = decodeCursor(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT u.id AS app_user_id, u.username, u.display_name, u.status AS user_status,
                       r.role, r.status AS role_status, r.created_at AS assigned_at
                  FROM app_user_role r
                  JOIN app_user u ON u.id = r.app_user_id
                 WHERE r.project_id = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(projectId);
        if (position != null) {
            sql.append(" AND (r.created_at, r.app_user_id) < (?, ?)");
            arguments.add(Timestamp.from(position.assignedAt()));
            arguments.add(position.appUserId());
        }
        sql.append(" ORDER BY r.created_at DESC, r.app_user_id DESC LIMIT ?");
        arguments.add(limit + 1);

        List<AppUserAssignment> rows = jdbcTemplate.query(sql.toString(), ASSIGNMENT_MAPPER,
                arguments.toArray());
        if (rows.size() <= limit) {
            return CursorPage.last(rows);
        }
        List<AppUserAssignment> items = List.copyOf(rows.subList(0, limit));
        AppUserAssignment last = items.getLast();
        return CursorPage.of(items, Cursor.encode(last.assignedAt() + "|" + last.appUserId()));
    }

    /**
     * 解码 {@code assignedAt|appUserId} 键集位置。游标来自客户端属不可信输入，
     * 解不开时返回 400 而不是让 {@code IllegalArgumentException} 冒泡成 500。
     */
    private static CursorPosition decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String[] parts = Cursor.decode(cursor).split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("游标字段不完整");
            }
            return new CursorPosition(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "分页游标无效");
        }
    }

    /** @param assignedAt 分配时刻 @param appUserId 终端用户 ID */
    private record CursorPosition(Instant assignedAt, UUID appUserId) {
    }
}
