package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceRuntimeCatalogRepository;
import com.things.link.device.domain.ConsoleDeviceRuntimeCatalogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 复用0270 security_invoker视图，候选、模型、项目及软删全部在keyset分页前过滤。 */
@Repository
public class JdbcDeviceRuntimeCatalogRepository implements DeviceRuntimeCatalogRepository, ConsoleDeviceRuntimeCatalogRepository, com.things.link.device.domain.PublicDeviceCatalogRepository {
    /** 普通APP连接上的项目RLS是读取的第二道边界。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 普通应用数据源 */
    public JdbcDeviceRuntimeCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<Item> find(UUID projectId, UUID modelVersionId, List<UUID> candidateIds,
                           Instant beforeTime, UUID beforeId, int limit) {
        // 仓储也拒绝无界或不完整参数，避免未来调用方绕过application校验形成全项目扫描。
        if (projectId == null || modelVersionId == null || candidateIds == null || candidateIds.isEmpty()
                || candidateIds.size() > 20 || candidateIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(candidateIds).size() != candidateIds.size()
                || (beforeTime == null) != (beforeId == null) || limit < 1 || limit > 51) {
            throw new IllegalArgumentException("运行设备目录范围或分页参数不合法");
        }
        Timestamp before = beforeTime == null ? null : Timestamp.from(beforeTime);
        return jdbc.query("""
                SELECT id, name, status, current_model_version_id, created_at
                  FROM dev_device_runtime_catalog_v1
                 WHERE project_id = ? AND current_model_version_id = ?
                   AND id = ANY (?::uuid[])
                   AND (?::timestamptz IS NULL OR (created_at, id) < (?, ?))
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """, (rs, row) -> new Item(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("status"), rs.getObject("current_model_version_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()), projectId, modelVersionId,
                candidateIds.toArray(UUID[]::new), before, before, beforeId, limit);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<Item> findConsole(UUID projectId, UUID modelVersionId, Instant beforeTime, UUID beforeId, int limit) {
        if (projectId == null || modelVersionId == null || (beforeTime == null) != (beforeId == null)
                || limit < 1 || limit > 51) {
            throw new IllegalArgumentException("Console目录范围或分页参数不合法");
        }
        Timestamp before = beforeTime == null ? null : Timestamp.from(beforeTime);
        return jdbc.query("""
                SELECT id, name, status, current_model_version_id, created_at
                  FROM dev_device_runtime_catalog_v1
                 WHERE project_id = ? AND current_model_version_id = ?
                   AND (?::timestamptz IS NULL OR (created_at, id) < (?, ?))
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """, (rs, row) -> new Item(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("status"), rs.getObject("current_model_version_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()), projectId, modelVersionId, before, before, beforeId, limit);
    }

    /** 公开目录允许省略模型过滤，仍只读受RLS约束的安全视图。 */
    @Override public List<Item> findPublic(UUID projectId, UUID modelVersionId, Instant beforeTime, UUID beforeId, int limit) {
        if (projectId == null || (beforeTime == null) != (beforeId == null) || limit < 1 || limit > 51)
            throw new IllegalArgumentException("公开目录分页范围无效");
        Timestamp before = beforeTime == null ? null : Timestamp.from(beforeTime);
        return jdbc.query("""
                SELECT id, name, status, current_model_version_id, created_at
                  FROM dev_device_runtime_catalog_v1
                 WHERE project_id = ? AND (?::uuid IS NULL OR current_model_version_id = ?)
                   AND (?::timestamptz IS NULL OR (created_at, id) < (?, ?))
                 ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new Item(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("status"), rs.getObject("current_model_version_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()), projectId, modelVersionId, modelVersionId,
                before, before, beforeId, limit);
    }
}
