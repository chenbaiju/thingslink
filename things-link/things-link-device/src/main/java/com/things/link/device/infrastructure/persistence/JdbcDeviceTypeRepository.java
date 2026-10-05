package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 使用 Spring JDBC 持久化设备类型，避免在简单 CRUD 阶段引入 ORM 隐式查询。 */
@Repository
public class JdbcDeviceTypeRepository implements DeviceTypeRepository {
    /** JDBC 执行入口。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 已配置项目 RLS 上下文的数据访问模板 */
    public JdbcDeviceTypeRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void create(DeviceType type) {
        jdbcTemplate.update("""
                INSERT INTO dev_type
                    (id, tenant_id, project_id, type_key, name, device_kind, access_protocol,
                     network_type, version, status, product_key, product_secret_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, type.id(), type.tenantId(), type.projectId(), type.typeKey(), type.name(),
                type.deviceKind().name(), type.payloadProtocol().name(), type.networkType().name(),
                type.version(), type.status().name(), type.productKey(), type.productSecretHash());
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DeviceType> findById(UUID projectId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type,
                       version, status, product_key, product_secret_hash, created_at
                  FROM dev_type
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, this::map, projectId, id).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DeviceType> findByTypeKey(UUID projectId, String typeKey) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type,
                       version, status, product_key, product_secret_hash, created_at
                  FROM dev_type
                 WHERE project_id = ? AND type_key = ? AND deleted_at IS NULL
                """, this::map, projectId, typeKey).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DeviceType> findByIdForUpdate(UUID projectId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type,
                       version, status, product_key, product_secret_hash, created_at FROM dev_type
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL FOR UPDATE
                """, this::map, projectId, id).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DeviceType> findByIdForShareNowait(UUID projectId, UUID id) {
        // ADR0057：KEY SHARE 挡不住 kind/deleted_at 的非键更新；NOWAIT 保证设备→类型不形成阻塞反向边。
        try {
            return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type,
                       version, status, product_key, product_secret_hash, created_at
                  FROM dev_type
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                 FOR SHARE NOWAIT
                """, this::map, projectId, id).stream().findFirst();
        } catch (DataAccessException exception) {
            // P0-2c1真实反例：Spring默认SQLState翻译链不识别PG的55P03；仅此锁查询补足ADR0057瞬时故障合同。
            // 不全局重分类，也不把语法、权限或连接错误伪装成类型编辑冲突。
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sqlException && "55P03".equals(sqlException.getSQLState())) {
                    throw new CannotAcquireLockException("设备类型共享锁暂不可用", exception);
                }
            }
            throw exception;
        }
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public CursorPage<DeviceType> search(UUID projectId, String cursor, int limit) {
        CursorPosition position = decodeCursor(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type,
                       version, status, product_key, product_secret_hash, created_at
                  FROM dev_type
                 WHERE project_id = ? AND deleted_at IS NULL
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(projectId);
        if (position != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            arguments.add(Timestamp.from(position.createdAt()));
            arguments.add(position.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        arguments.add(limit + 1);
        List<DeviceType> rows = jdbcTemplate.query(sql.toString(), this::map, arguments.toArray());
        if (rows.size() <= limit) {
            return CursorPage.last(rows);
        }
        List<DeviceType> items = List.copyOf(rows.subList(0, limit));
        DeviceType last = items.getLast();
        return CursorPage.of(items, Cursor.encode(last.createdAt() + "|" + last.id()));
    }

    /** @param cursor 不透明游标 @return 已验证的位置 */
    private static CursorPosition decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] parts = Cursor.decode(cursor).split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("游标字段不完整");
            }
            return new CursorPosition(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "设备类型分页游标不合法");
        }
    }

    /** @param createdAt 创建时刻 @param id 稳定并列键 */
    private record CursorPosition(Instant createdAt, UUID id) { }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean update(DeviceType type) {
        return DeviceTopologyConstraintTranslator.execute(() -> jdbcTemplate.update("""
                UPDATE dev_type
                   SET type_key = ?, name = ?, device_kind = ?, access_protocol = ?, network_type = ?, updated_at = now()
                 WHERE project_id = ? AND id = ? AND status = 'DRAFT' AND deleted_at IS NULL
                """, type.typeKey(), type.name(), type.deviceKind().name(), type.payloadProtocol().name(),
                type.networkType().name(),
                type.projectId(), type.id()) == 1);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean softDelete(UUID projectId, UUID id) {
        return DeviceTopologyConstraintTranslator.execute(() -> jdbcTemplate.update("""
                UPDATE dev_type
                   SET deleted_at = now(), updated_at = now()
                 WHERE project_id = ? AND id = ? AND status = 'DRAFT' AND deleted_at IS NULL
                """, projectId, id) == 1);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean publish(UUID projectId, UUID id) {
        return jdbcTemplate.update("""
                UPDATE dev_type
                   SET status = 'PUBLISHED', updated_at = now()
                 WHERE project_id = ? AND id = ? AND status = 'DRAFT' AND deleted_at IS NULL
                """, projectId, id) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DeviceType> findPublishedByProductKey(UUID projectId, String productKey) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type,
                       version, status, product_key, product_secret_hash, created_at
                  FROM dev_type
                 WHERE project_id = ? AND product_key = ? AND status = 'PUBLISHED' AND deleted_at IS NULL
                """, this::map, projectId, productKey).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean updateProductCredential(UUID projectId, UUID id, String productKey, String productSecretHash) {
        return jdbcTemplate.update("""
                UPDATE dev_type
                   SET product_key = ?, product_secret_hash = ?, updated_at = now()
                 WHERE project_id = ? AND id = ? AND status = 'PUBLISHED' AND deleted_at IS NULL
                """, productKey, productSecretHash, projectId, id) == 1;
    }

    /** 数据库行到领域对象的唯一映射，避免列表与详情查询字段演进时发生分叉。 */
    private DeviceType map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DeviceType(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("type_key"), rs.getString("name"),
                DeviceType.DeviceKind.valueOf(rs.getString("device_kind")),
                DeviceType.PayloadProtocol.valueOf(rs.getString("access_protocol")),
                DeviceType.NetworkType.valueOf(rs.getString("network_type")), rs.getInt("version"),
                DeviceType.Status.valueOf(rs.getString("status")), rs.getString("product_key"),
                rs.getString("product_secret_hash"), rs.getTimestamp("created_at").toInstant());
    }
}
