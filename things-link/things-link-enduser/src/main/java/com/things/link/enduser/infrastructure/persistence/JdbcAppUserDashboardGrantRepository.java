package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.AppUserDashboardGrant;
import com.things.link.enduser.domain.AppUserDashboardGrantRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

/** 冻结§4：普通查询遵守RLS；CAS只能调用固定函数，禁止应用角色直接改授权表。 */
@Repository
public class JdbcAppUserDashboardGrantRepository implements AppUserDashboardGrantRepository {
    /** 映射数据库微秒事实，首次与后续读取不分别生成Java纳秒时间。 */
    private static final RowMapper<AppUserDashboardGrant> MAPPER = (row, index) -> {
        if (!"READ".equals(row.getString("permission"))) {
            throw new IllegalStateException("看板授权出现未支持权限");
        }
        return new AppUserDashboardGrant(row.getObject("id", UUID.class), row.getObject("tenant_id", UUID.class),
                row.getObject("project_id", UUID.class), row.getObject("app_user_id", UUID.class),
                row.getObject("dashboard_id", UUID.class), AppUserDashboardGrant.Status.valueOf(row.getString("status")),
                row.getLong("revision"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(), instant(row.getTimestamp("revoked_at")),
                row.getObject("created_by", UUID.class), row.getObject("updated_by", UUID.class),
                row.getObject("revoked_by", UUID.class));
    };
    /** 与项目许可、用户/看板锁及审计共享的原物理连接。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 事务绑定的数据源入口 */
    public JdbcAppUserDashboardGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AppUserDashboardGrant> find(UUID tenantId, UUID projectId, UUID appUserId, UUID dashboardId) {
        return jdbc.query("""
                SELECT * FROM public.app_user_dashboard
                WHERE tenant_id=? AND project_id=? AND app_user_id=? AND dashboard_id=?
                """, MAPPER, tenantId, projectId, appUserId, dashboardId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public CursorPage<AppUserDashboardGrant> list(UUID tenantId, UUID projectId, UUID appUserId,
                                                String cursor, int limit) {
        if (limit < 1 || limit > 200) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "分页数量必须为1至200");
        }
        UUID after = parseCursor(cursor);
        List<Object> arguments = new ArrayList<>(List.of(tenantId, projectId, appUserId));
        String continuation = "";
        if (after != null) {
            continuation = " AND dashboard_id > ?";
            arguments.add(after);
        }
        arguments.add(limit + 1);
        // SQL结构只按是否有游标选择；用户输入始终绑定为UUID参数，唯一索引覆盖稳定键集范围。
        List<AppUserDashboardGrant> found = jdbc.query("""
                SELECT * FROM public.app_user_dashboard
                WHERE tenant_id=? AND project_id=? AND app_user_id=?
                """ + continuation + " ORDER BY dashboard_id ASC LIMIT ?", MAPPER, arguments.toArray());
        if (found.size() <= limit) return CursorPage.last(found);
        List<AppUserDashboardGrant> items = found.subList(0, limit);
        return CursorPage.of(items, Cursor.encode(items.getLast().dashboardId().toString()));
    }

    /** {@inheritDoc} */
    @Override
    public Set<UUID> findActiveDashboardIds(UUID tenantId, UUID projectId, UUID appUserId, List<UUID> dashboardIds) {
        if (dashboardIds == null || dashboardIds.size() > 5 || dashboardIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(dashboardIds).size() != dashboardIds.size()) {
            throw new IllegalArgumentException("当前应用看板候选必须是至多5个唯一非空ID");
        }
        if (dashboardIds.isEmpty()) return Set.of();
        List<Object> arguments = new ArrayList<>(List.of(tenantId, projectId, appUserId));
        arguments.addAll(dashboardIds);
        // 只拼接有限个参数占位符；完整范围和ACTIVE/READ状态在数据库层限定，不列出用户全部grant。
        String placeholders = String.join(",", Collections.nCopies(dashboardIds.size(), "?"));
        return Set.copyOf(jdbc.queryForList("""
                SELECT dashboard_id FROM public.app_user_dashboard
                WHERE tenant_id=? AND project_id=? AND app_user_id=? AND status='ACTIVE' AND permission='READ'
                  AND dashboard_id IN (
                """ + placeholders + ")", UUID.class, arguments.toArray()));
    }

    /** UUID与其无填充Base64编码都必须规范，拒绝缩写、大小写别名和任意SQL载荷。 */
    private static UUID parseCursor(String cursor) {
        if (cursor == null) return null;
        if (cursor.length() != 48) throw invalidCursor();
        String decoded = Cursor.decode(cursor);
        try {
            UUID value = UUID.fromString(decoded);
            if (!value.toString().equals(decoded) || !Cursor.encode(decoded).equals(cursor)) {
                throw invalidCursor();
            }
            return value;
        } catch (IllegalArgumentException failure) {
            throw invalidCursor();
        }
    }

    /** 不回显未知游标内容，保持已有分页公共错误10001。 */
    private static BusinessException invalidCursor() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER, "分页游标无效");
    }

    /** {@inheritDoc} */
    @Override
    public WriteResult compareAndSet(UUID candidateId, UUID tenantId, UUID projectId, UUID appUserId,
                                     UUID dashboardId, long expectedRevision, AppUserDashboardGrant.Status status,
                                     UUID actorId) {
        requireTransaction();
        CasReceipt receipt = jdbc.queryForObject("""
                SELECT * FROM public.app_user_dashboard_cas(?,?,?,?,?,?,?,?)
                """, (row, index) -> new CasReceipt(Outcome.valueOf(row.getString("result")),
                row.getObject("grant_id", UUID.class), row.getObject("grant_revision", Long.class)),
                candidateId, tenantId, projectId, appUserId, dashboardId, expectedRevision,
                status == null ? null : status.name(), actorId);
        if (receipt == null) throw new IllegalStateException("看板授权CAS未返回仲裁结果");
        if (!receipt.outcome().successful()) return new WriteResult(receipt.outcome(), null);
        // 函数和仓储始终在原事务；同用户锁仍持有，此查询只能恢复已判定成功的本次事实。
        AppUserDashboardGrant grant = find(tenantId, projectId, appUserId, dashboardId)
                .orElseThrow(() -> new IllegalStateException("看板授权CAS成功却缺少持久事实"));
        if (!grant.id().equals(receipt.id()) || receipt.revision() == null || grant.revision() != receipt.revision()) {
            throw new IllegalStateException("看板授权CAS返回身份或revision漂移");
        }
        return new WriteResult(receipt.outcome(), grant);
    }

    /** ADR0097：锁后语句必须取得新快照；无事务、只读或自动提交一律不能调用CAS。 */
    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("看板授权CAS要求已有非只读事务");
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            int isolation = connection.getTransactionIsolation();
            if (connection.getAutoCommit() || connection.isReadOnly()
                    || (isolation != Connection.TRANSACTION_READ_COMMITTED
                    && isolation != Connection.TRANSACTION_READ_UNCOMMITTED)) {
                throw new IllegalStateException("看板授权CAS要求原READ COMMITTED连接");
            }
            return null;
        });
    }

    /** ACTIVE无撤销时间，不能把NULL变成当前时刻。 */
    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** @param outcome 明确仲裁分类 @param id 成功授权身份 @param revision 成功revision */
    private record CasReceipt(Outcome outcome, UUID id, Long revision) { }
}
