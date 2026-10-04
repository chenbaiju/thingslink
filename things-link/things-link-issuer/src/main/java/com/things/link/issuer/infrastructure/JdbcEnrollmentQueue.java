package com.things.link.issuer.infrastructure;

import com.things.link.issuer.application.EnrollmentQueue;
import com.things.link.issuer.application.EnrollmentRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 发行方数据库的稳定键集分页；不加载签名材料。 */
@Repository
public class JdbcEnrollmentQueue implements EnrollmentQueue {
    private final JdbcTemplate jdbc;

    public JdbcEnrollmentQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Pending> page(int limit, Instant beforeReceivedAt, UUID beforeRequestId) {
        if (beforeReceivedAt == null) {
            return jdbc.query("""
                    SELECT request_id, deployment_id, tenant_id, channel, received_at
                      FROM public.sys_shc_enrollment_request
                     WHERE status = 'PENDING'
                     ORDER BY received_at DESC, request_id DESC
                     LIMIT ?
                    """, JdbcEnrollmentQueue::map, limit);
        }
        return jdbc.query("""
                SELECT request_id, deployment_id, tenant_id, channel, received_at
                  FROM public.sys_shc_enrollment_request
                 WHERE status = 'PENDING'
                   AND (received_at, request_id) < (?, ?)
                 ORDER BY received_at DESC, request_id DESC
                 LIMIT ?
                """, JdbcEnrollmentQueue::map, Timestamp.from(beforeReceivedAt), beforeRequestId, limit);
    }

    private static Pending map(ResultSet rs, int rowNumber) throws SQLException {
        return new Pending(rs.getObject("request_id", UUID.class),
                rs.getObject("deployment_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                EnrollmentRegistry.Channel.valueOf(rs.getString("channel")),
                rs.getTimestamp("received_at").toInstant());
    }
}
