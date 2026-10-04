package com.things.link.support.audit;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 以显式 tenant+project 条件读取 RLS 豁免审计表的游标适配器。
 *
 * <p>游标沿用平台既有的 keyset 口径：排序键是 {@code (created_at DESC, id DESC)}，
 * 续页条件是 {@code (created_at,id)<(?,?)}。不用 OFFSET，是因为审计表只会增长，
 * 深翻页在项目历史很长时会退化成全表扫描；同时新审计持续写入时 OFFSET 会
 * 漏读或重复读，排障时看到的就不是完整事实链。
 */
@Repository
public class JdbcAuditQueryRepository implements AuditQueryRepository {

    /** 固定列，不接受客户端 SQL 片段。 */
    private static final String COLUMNS = "id,tenant_id,project_id,actor_account_id,target_type,target_id,"
            + "action,trace_id,details,created_at";

    /** 当前事务与连接上的数据库访问器。 */
    private final JdbcTemplate jdbc;

    /**
     * 注入数据访问器。
     *
     * @param jdbc 当前事务使用的 JDBC 入口
     */
    public JdbcAuditQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public CursorPage<Record> page(UUID tenantId, UUID projectId, String actionPrefix, String action,
            String cursor, int limit) {
        // 范围或条数不合法时不继续拼 SQL：审计表没有 RLS 兜底，宁可 400 也不能放行一次无范围查询。
        if (tenantId == null || projectId == null || limit < 1 || limit > 100) {
            throw invalid();
        }
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM sys_audit_log"
                + " WHERE tenant_id=? AND project_id=?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId);
        arguments.add(projectId);
        if (actionPrefix != null) {
            // 前缀是数据不是模式：调用方给出 "ota." 只应匹配这几个字面字符。
            // 不转义时 "%" 会把收窄条件放大成“全部动作”，前缀约束形同虚设。
            sql.append(" AND action LIKE ? ESCAPE '\\'");
            arguments.add(literalPrefix(actionPrefix));
        }
        if (action != null) {
            sql.append(" AND action=?");
            arguments.add(action);
        }
        if (cursor == null || cursor.isEmpty()) {
            sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?");
        } else {
            Instant time;
            UUID id;
            try {
                if (cursor.length() > 256) {
                    throw invalid();
                }
                String[] parts = Cursor.decode(cursor).split("\\|", -1);
                // 载荷携带项目身份：跨项目复用同一游标直接判非法，不泄露其他项目的位置。
                if (parts.length != 3 || !projectId.toString().equals(parts[0])) {
                    throw invalid();
                }
                time = Instant.parse(parts[1]);
                id = UUID.fromString(parts[2]);
            } catch (RuntimeException failure) {
                throw invalid();
            }
            sql.append(" AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?");
            arguments.add(Timestamp.from(time));
            arguments.add(id);
        }
        // 多取一条用于判定是否还有下一页，返回前不暴露这一行。
        arguments.add(limit + 1);
        List<Record> rows = jdbc.query(sql.toString(), JdbcAuditQueryRepository::map, arguments.toArray());
        if (rows.size() <= limit) {
            return CursorPage.last(rows);
        }
        List<Record> items = List.copyOf(rows.subList(0, limit));
        Record last = items.getLast();
        return CursorPage.of(items, Cursor.encode(projectId + "|" + last.createdAt() + "|" + last.id()));
    }

    /**
     * 把前缀转成 LIKE 的字面前缀模式。
     *
     * @param prefix 调用方给出的字面前缀
     * @return 已转义并追加 {@code %} 的 LIKE 模式
     */
    private static String literalPrefix(String prefix) {
        StringBuilder escaped = new StringBuilder(prefix.length() + 1);
        for (int index = 0; index < prefix.length(); index++) {
            char value = prefix.charAt(index);
            if (value == '\\' || value == '%' || value == '_') {
                escaped.append('\\');
            }
            escaped.append(value);
        }
        return escaped.append('%').toString();
    }

    /** 数据库行到只读事实，不构造任何业务聚合。 */
    private static Record map(ResultSet rs, int row) throws SQLException {
        return new Record(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("actor_account_id", UUID.class),
                rs.getString("target_type"), rs.getObject("target_id", UUID.class), rs.getString("action"),
                rs.getString("trace_id"), rs.getString("details"), instant(rs.getTimestamp("created_at")));
    }

    /** 游标和范围无效不回显解码内容。 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER, "审计分页参数不合法");
    }

    /** 可空数据库时间映射，保留防御式处理以免旧库异常行导致 NPE。 */
    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
