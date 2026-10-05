package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.AppUserDevice;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 基于 JDBC 的终端用户设备授权关系仓储实现。
 *
 * <p>app_user_device 受项目 RLS 保护：上下文由应用服务在写入前切到目标项目
 * （{@code set_config('app.tenant_id'/'app.project_id', ...)}）。查询和条件更新显式带
 * {@code project_id = ?}，与 RLS 形成两道独立防线。
 */
@Repository
public class JdbcAppUserDeviceRepository implements AppUserDeviceRepository {

    private static final RowMapper<AppUserDevice> DEVICE_MAPPER = (rs, rowNum) -> new AppUserDevice(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getObject("app_user_id", UUID.class),
            rs.getObject("device_id", UUID.class),
            AppUserDevice.RelationRole.valueOf(rs.getString("relation_role")),
            AppUserDevice.Status.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public JdbcAppUserDeviceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Set<UUID> findActiveDeviceIds(UUID tenantId, UUID projectId, UUID appUserId,
            Collection<UUID> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) return Set.of();
        if (deviceIds.size() > 20) throw new IllegalArgumentException("指定设备授权查询超过20台上限");
        UUID[] ids = deviceIds.toArray(UUID[]::new);
        return jdbcTemplate.queryForList("""
                        SELECT device_id
                          FROM app_user_device
                         WHERE tenant_id = ? AND project_id = ? AND app_user_id = ?
                           AND status = 'ACTIVE' AND device_id = ANY (?::uuid[])
                        """, UUID.class, tenantId, projectId, appUserId, ids)
                .stream().collect(Collectors.toUnmodifiableSet());
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<com.things.link.enduser.domain.AppRuntimeDeviceCatalogItem> findRuntimeCatalog(
            UUID tenantId, UUID projectId, UUID appUserId, UUID modelVersionId,
            Instant beforeCreatedAt, UUID beforeId, int fetchLimit) {
        if ((beforeCreatedAt == null) != (beforeId == null) || fetchLimit < 1 || fetchLimit > 51) {
            throw new IllegalArgumentException("运行目录keyset或读取上限不合法");
        }
        return jdbcTemplate.query("""
                        SELECT catalog.id AS device_id, catalog.name, catalog.status AS device_status,
                               catalog.current_model_version_id, catalog.created_at
                          FROM app_user_device binding
                          JOIN dev_device_runtime_catalog_v1 catalog
                            ON catalog.tenant_id = binding.tenant_id
                           AND catalog.project_id = binding.project_id
                           AND catalog.id = binding.device_id
                         WHERE binding.tenant_id = ? AND binding.project_id = ?
                           AND binding.app_user_id = ? AND binding.status = 'ACTIVE'
                           AND catalog.current_model_version_id = ?
                           AND (?::timestamptz IS NULL OR (catalog.created_at, catalog.id) < (?, ?))
                         ORDER BY catalog.created_at DESC, catalog.id DESC
                         LIMIT ?
                        """, (rs, rowNum) -> new com.things.link.enduser.domain.AppRuntimeDeviceCatalogItem(
                        rs.getObject("device_id", UUID.class), rs.getString("name"),
                        rs.getString("device_status"), rs.getObject("current_model_version_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()),
                tenantId, projectId, appUserId, modelVersionId,
                beforeCreatedAt == null ? null : Timestamp.from(beforeCreatedAt),
                beforeCreatedAt == null ? null : Timestamp.from(beforeCreatedAt), beforeId, fetchLimit);
    }

    @Override
    public List<AppUserDevice> findByProjectAndUser(UUID projectId, UUID appUserId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, project_id, app_user_id, device_id, relation_role, status, created_at
                          FROM app_user_device
                         WHERE project_id = ? AND app_user_id = ?
                         ORDER BY created_at ASC, id ASC
                        """, DEVICE_MAPPER, projectId, appUserId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>只关闭有效关系，既避免重复停用改写历史时间，也保证未来恢复项目角色时旧授权
     * 不会被意外复活。项目条件与 RLS 双重限制影响范围。
     */
    @Override
    public int closeActiveByProjectAndUser(UUID projectId, UUID appUserId) {
        return jdbcTemplate.update("""
                        UPDATE app_user_device
                           SET status = 'CLOSED', updated_at = now()
                         WHERE project_id = ?
                           AND app_user_id = ?
                           AND status = 'ACTIVE'
                        """, projectId, appUserId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<AppUserDevice> findActive(UUID projectId, UUID appUserId, UUID deviceId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, project_id, app_user_id, device_id,
                               relation_role, status, created_at
                          FROM app_user_device
                         WHERE project_id = ? AND app_user_id = ? AND device_id = ?
                           AND status = 'ACTIVE'
                        """, DEVICE_MAPPER, projectId, appUserId, deviceId)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<AppUserDevice> findActivePrimary(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, project_id, app_user_id, device_id,
                               relation_role, status, created_at
                          FROM app_user_device
                         WHERE project_id = ? AND device_id = ?
                           AND relation_role = 'PRIMARY' AND status = 'ACTIVE'
                        """, DEVICE_MAPPER, projectId, deviceId)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<AppUserDevice> findActivePrimaryForUpdate(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, project_id, app_user_id, device_id,
                               relation_role, status, created_at
                          FROM app_user_device
                         WHERE project_id = ? AND device_id = ?
                           AND relation_role = 'PRIMARY' AND status = 'ACTIVE'
                           FOR UPDATE
                        """, DEVICE_MAPPER, projectId, deviceId)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<AppUserDevice> findActiveForUpdate(UUID projectId, UUID appUserId, UUID deviceId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, project_id, app_user_id, device_id,
                               relation_role, status, created_at
                          FROM app_user_device
                         WHERE project_id = ? AND app_user_id = ? AND device_id = ?
                           AND status = 'ACTIVE'
                           FOR UPDATE
                        """, DEVICE_MAPPER, projectId, appUserId, deviceId)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public int demotePrimaryToMember(UUID projectId, UUID bindingId, UUID appUserId, UUID deviceId) {
        return jdbcTemplate.update("""
                        UPDATE app_user_device
                           SET relation_role = 'MEMBER', updated_at = now()
                         WHERE project_id = ? AND id = ? AND app_user_id = ? AND device_id = ?
                           AND relation_role = 'PRIMARY' AND status = 'ACTIVE'
                        """, projectId, bindingId, appUserId, deviceId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public int promoteActiveToPrimary(UUID projectId, UUID bindingId, UUID appUserId, UUID deviceId) {
        return jdbcTemplate.update("""
                        UPDATE app_user_device
                           SET relation_role = 'PRIMARY', updated_at = now()
                         WHERE project_id = ? AND id = ? AND app_user_id = ? AND device_id = ?
                           AND relation_role IN ('MEMBER', 'READ_ONLY') AND status = 'ACTIVE'
                        """, projectId, bindingId, appUserId, deviceId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>PostgreSQL {@code UPDATE ... RETURNING} 把状态仲裁与审计所需旧关系标识合并为一次
     * 原子数据库动作；并发请求中只有一个能得到返回行。返回映射中的状态已是 CLOSED，
     * relationRole、createdAt 等历史属性保持不变。
     */
    @Override
    public Optional<AppUserDevice> closeActive(UUID projectId, UUID appUserId, UUID deviceId) {
        return jdbcTemplate.query("""
                        UPDATE app_user_device
                           SET status = 'CLOSED', updated_at = now()
                         WHERE project_id = ?
                           AND app_user_id = ?
                           AND device_id = ?
                           AND status = 'ACTIVE'
                     RETURNING id, tenant_id, project_id, app_user_id, device_id,
                               relation_role, status, created_at
                        """, DEVICE_MAPPER, projectId, appUserId, deviceId)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public int createActivePrimary(AppUserDevice binding) {
        return jdbcTemplate.update("""
                        INSERT INTO app_user_device
                            (id, tenant_id, project_id, app_user_id, device_id,
                             relation_role, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'PRIMARY', 'ACTIVE', ?, ?)
                        ON CONFLICT DO NOTHING
                        """, binding.id(), binding.tenantId(), binding.projectId(),
                binding.appUserId(), binding.deviceId(), Timestamp.from(binding.createdAt()),
                Timestamp.from(binding.createdAt()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public int createActiveShared(AppUserDevice binding) {
        if (binding.relationRole() != AppUserDevice.RelationRole.MEMBER
                && binding.relationRole() != AppUserDevice.RelationRole.READ_ONLY) {
            throw new IllegalArgumentException("共享关系只能是 MEMBER 或 READ_ONLY");
        }
        return jdbcTemplate.update("""
                        INSERT INTO app_user_device
                            (id, tenant_id, project_id, app_user_id, device_id,
                             relation_role, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                        ON CONFLICT DO NOTHING
                        """, binding.id(), binding.tenantId(), binding.projectId(),
                binding.appUserId(), binding.deviceId(), binding.relationRole().name(),
                Timestamp.from(binding.createdAt()), Timestamp.from(binding.createdAt()));
    }
}
