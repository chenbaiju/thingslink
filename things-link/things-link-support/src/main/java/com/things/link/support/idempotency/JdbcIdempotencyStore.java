package com.things.link.support.idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 PostgreSQL 的幂等存储。
 *
 * <p>用 {@link JdbcTemplate} 而不是 JPA：本组件在<b>业务事务之外</b>运行，每条语句
 * 必须立即提交，否则并发的重复请求看不到抢占记录，幂等就失效了。JPA 的
 * EntityManager 会把写入攒在持久化上下文里，与这个要求不符。
 */
@Component
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getString("request_method"),
            rs.getString("request_path"),
            rs.getString("request_body_hash"),
            IdempotencyRecord.Status.valueOf(rs.getString("status")),
            rs.getTimestamp("expires_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdempotencyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryAcquire(IdempotencyRecord record) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO sys_idempotency_record (
                                id, tenant_id, project_id, idempotency_key,
                                request_method, request_path, request_body_hash,
                                status, expires_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    record.id(), record.tenantId(), record.projectId(), record.idempotencyKey(),
                    record.requestMethod(), record.requestPath(), record.requestBodyHash(),
                    record.status().name(), Timestamp.from(record.expiresAt()));
            return true;
        } catch (DuplicateKeyException e) {
            // 唯一约束冲突就是「别人已经抢到了」，属于正常的并发结果，不是错误。
            // 抢占失败与成功都由这一条 INSERT 原子决定，没有先查后插的竞态窗口。
            return false;
        }
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID tenantId, UUID projectId, String idempotencyKey,
                                            String method, String path) {
        // tenant_id / project_id 可能为 NULL（S1 之前）。SQL 里 `= NULL` 永远不成立，
        // 必须用 IS NOT DISTINCT FROM —— 它把 NULL 当作可相等的值，与表上唯一索引的
        // NULLS NOT DISTINCT 语义一致。写成 `= ?` 的话，未认证请求永远查不到已有记录。
        List<IdempotencyRecord> found = jdbcTemplate.query("""
                        SELECT * FROM sys_idempotency_record
                         WHERE tenant_id IS NOT DISTINCT FROM ?
                           AND project_id IS NOT DISTINCT FROM ?
                           AND idempotency_key = ?
                           AND request_method = ?
                           AND request_path = ?
                        """,
                ROW_MAPPER, tenantId, projectId, idempotencyKey, method, path);
        return found.stream().findFirst();
    }

    @Override
    public void complete(UUID id) {
        jdbcTemplate.update("""
                        UPDATE sys_idempotency_record
                           SET status = 'COMPLETED',
                               completed_at = now()
                         WHERE id = ?
                        """,
                id);
    }

    @Override
    public void release(UUID id) {
        jdbcTemplate.update("DELETE FROM sys_idempotency_record WHERE id = ?", id);
    }

    /** {@inheritDoc} */
    @Override
    public int deleteExpired(int maximumRows) {
        int boundedRows = Math.max(1, Math.min(maximumRows, 5_000));
        return jdbcTemplate.update("""
                WITH expired AS (
                    SELECT id
                      FROM sys_idempotency_record
                     WHERE expires_at < now()
                     ORDER BY expires_at, id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                DELETE FROM sys_idempotency_record target
                 USING expired
                 WHERE target.id = expired.id
                """, boundedRows);
    }

}
