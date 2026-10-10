package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.AppDeviceDetails;
import com.things.link.enduser.domain.AppDeviceReadRepository;
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
import java.util.Set;
import java.util.UUID;

/** 当前授权、字面搜索、状态与键集位置均先过滤，再做有界分页。 */
@Repository
public class JdbcAppDeviceReadRepository implements AppDeviceReadRepository {
    private static final String SELECT = """
            SELECT d.id,d.device_key,d.name,d.description,d.status,d.location,
                   d.last_online_at,d.created_at,d.device_type_name,d.last_data_report_at
              FROM app_user_device b JOIN dev_device_app_v1 d
                ON d.tenant_id=b.tenant_id AND d.project_id=b.project_id AND d.id=b.device_id
             WHERE b.tenant_id=? AND b.project_id=? AND b.app_user_id=? AND b.status='ACTIVE'
            """;
    private static final RowMapper<AppDeviceDetails> MAPPER = (rs, row) -> {
        Timestamp online = rs.getTimestamp("last_online_at");
        Timestamp report = rs.getTimestamp("last_data_report_at");
        return new AppDeviceDetails(rs.getObject("id", UUID.class), rs.getString("device_key"),
                rs.getString("name"), rs.getString("description"), rs.getString("status"),
                rs.getString("location"), online == null ? null : online.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getString("device_type_name"),
                report == null ? null : report.toInstant());
    };
    private final JdbcTemplate jdbc;
    /** @param jdbc 当前事务数据库访问器 */
    public JdbcAppDeviceReadRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 沿用接口定义的授权边界。{@inheritDoc} */
    @Override
    public CursorPage<AppDeviceDetails> list(UUID tenantId, UUID projectId, UUID appUserId,
                                             String cursor, int limit, String query, String status) {
        if (limit < 1 || limit > 200 || (query != null && query.length() > 100)
                || (status != null && !Set.of("ONLINE", "OFFLINE", "INACTIVE").contains(status))) {
            throw invalid();
        }
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> args = new ArrayList<>(List.of(tenantId, projectId, appUserId));
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_") + "%";
            sql.append(" AND (d.name ILIKE ? ESCAPE '!' OR d.device_key ILIKE ? ESCAPE '!')");
            args.add(pattern); args.add(pattern);
        }
        if (status != null) { sql.append(" AND d.status=?"); args.add(status); }
        if (cursor != null) {
            if (cursor.length() > 512) throw invalid();
            try {
                String[] parts = Cursor.decode(cursor).split("\\|", -1);
                if (parts.length != 2) throw invalid();
                sql.append(" AND (d.created_at,d.id)<(?,?)");
                args.add(Timestamp.from(Instant.parse(parts[0]))); args.add(UUID.fromString(parts[1]));
            } catch (java.time.DateTimeException | IllegalArgumentException failure) { throw invalid(); }
        }
        sql.append(" ORDER BY d.created_at DESC,d.id DESC LIMIT ?");
        args.add(limit + 1);
        List<AppDeviceDetails> rows = jdbc.query(sql.toString(), MAPPER, args.toArray());
        if (rows.size() <= limit) return CursorPage.last(rows);
        List<AppDeviceDetails> page = List.copyOf(rows.subList(0, limit));
        AppDeviceDetails last = page.getLast();
        return CursorPage.of(page, Cursor.encode(last.createdAt() + "|" + last.id()));
    }

    /** 沿用接口定义的授权边界。{@inheritDoc} */
    @Override
    public Optional<AppDeviceDetails> detail(UUID tenantId, UUID projectId, UUID appUserId, UUID deviceId) {
        return jdbc.query(SELECT + " AND d.id=?", MAPPER, tenantId, projectId, appUserId, deviceId)
                .stream().findFirst();
    }
    /** 返回统一参数错误，避免回显数据库或游标内容。 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER, "设备查询参数不合法");
    }
}
