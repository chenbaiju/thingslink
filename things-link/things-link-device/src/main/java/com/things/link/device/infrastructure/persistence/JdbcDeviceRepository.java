package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceGroup;
import com.things.link.device.domain.DeviceGroupRule;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceSearchQuery;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 以显式 SQL 持久化设备实例，保持与设备域其他仓储一致的 JDBC 风格。 */
@Repository
public class JdbcDeviceRepository implements DeviceRepository {
    /** JDBC 访问器。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate JDBC 访问器 */
    public JdbcDeviceRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** {@inheritDoc} */
    @Override
    public void lockTenantDeviceQuota(UUID tenantId) {
        // 事务级 advisory lock 不读取 project 表，却能让同一 owner tenant 的跨项目创建共享一把锁。
        jdbcTemplate.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 735::bigint))",
                Long.class, tenantId.toString());
    }

    @Override public void create(Device device) {
        jdbcTemplate.update("""
                INSERT INTO dev_device
                    (id, tenant_id, project_id, device_type_id, gateway_id, device_key,
                     name, description, status, location, thing_model_version_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, (
                    SELECT id FROM dev_thing_model_version
                     WHERE project_id = ? AND device_type_id = ?
                     ORDER BY version_major DESC, version_minor DESC, version_patch DESC LIMIT 1))
                """, device.id(), device.tenantId(), device.projectId(), device.deviceTypeId(),
                device.gatewayId(), device.deviceKey(), device.name(), device.description(),
                device.status().name(), device.location(), device.projectId(), device.deviceTypeId());
        // 类型尚未发布时指针为空且不伪造历史；已发布类型的新设备必须在同事务形成 INITIAL 事实。
        jdbcTemplate.update("""
                INSERT INTO dev_device_model_binding_history
                    (id, tenant_id, project_id, device_id, from_model_version_id, to_model_version_id,
                     transition_key, transition_type, effective_at)
                SELECT gen_random_uuid(), tenant_id, project_id, id, NULL, thing_model_version_id,
                       gen_random_uuid(), 'INITIAL', now()
                  FROM dev_device
                 WHERE project_id = ? AND id = ? AND thing_model_version_id IS NOT NULL
                """, device.projectId(), device.id());
    }

    @Override
    public Optional<Boolean> lockAutomationIdentity(UUID tenant, UUID project, UUID device) {
        return jdbcTemplate.query("SELECT deleted_at IS NULL FROM dev_device WHERE tenant_id=? AND project_id=? AND id=? FOR KEY SHARE",
                (r,n)->r.getBoolean(1),tenant,project,device).stream().findFirst();
    }

    @Override public Optional<Device> findById(UUID projectId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, gateway_id, device_key,
                       name, description, status, location, last_online_at, created_at
                  FROM dev_device WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, this::map, projectId, id).stream().findFirst();
    }

    @Override public Optional<Device> findByDeviceKey(UUID projectId, String deviceKey) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, gateway_id, device_key,
                       name, description, status, location, last_online_at, created_at
                  FROM dev_device WHERE project_id = ? AND device_key = ? AND deleted_at IS NULL
                """, this::map, projectId, deviceKey).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override public Optional<Device> findByIdForUpdate(UUID projectId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, gateway_id, device_key,
                       name, description, status, location, last_online_at, created_at
                  FROM dev_device WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                   FOR UPDATE
                """, this::map, projectId, id).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override public Optional<Device> findByIdForKeyShare(UUID projectId, UUID id) {
        // ADR0056删除入口显式FOR UPDATE；仅UPDATE deleted_at取得的NO KEY UPDATE不足以互斥本锁。
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, gateway_id, device_key,
                       name, description, status, location, last_online_at, created_at
                  FROM dev_device WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                   FOR KEY SHARE
                """, this::map, projectId, id).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasModelVersionBinding(UUID projectId, UUID deviceId) {
        // ADR0059：只看当前指针会把“指针被清空但历史仍在”的异常设备误当成可重新选型的新身份。
        Boolean bound = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.dev_device d
                     WHERE d.project_id = ? AND d.id = ? AND d.thing_model_version_id IS NOT NULL
                ) OR EXISTS (
                    SELECT 1 FROM public.dev_device_model_binding_history h
                     WHERE h.project_id = ? AND h.device_id = ?
                )
                """, Boolean.class, projectId, deviceId, projectId, deviceId);
        return Boolean.TRUE.equals(bound);
    }

    /** {@inheritDoc} */
    @Override
    public CursorPage<Device> search(DeviceSearchQuery query, DeviceGroup group) {
        CursorPosition position = decodeCursor(query.cursor());
        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.tenant_id, d.project_id, d.device_type_id, d.gateway_id, d.device_key,
                       d.name, d.description, d.status, d.location, d.last_online_at, d.created_at
                  FROM dev_device d
                 WHERE d.project_id = ? AND d.deleted_at IS NULL
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(query.projectId());
        appendExplicitPredicates(sql, arguments, query);
        appendGroupPredicate(sql, arguments, group);
        if (position != null) {
            sql.append(" AND (d.created_at, d.id) < (?, ?)");
            arguments.add(Timestamp.from(position.createdAt()));
            arguments.add(position.id());
        }
        sql.append(" ORDER BY d.created_at DESC, d.id DESC LIMIT ?");
        arguments.add(query.limit() + 1);

        List<Device> rows = jdbcTemplate.query(sql.toString(), this::map, arguments.toArray());
        if (rows.size() <= query.limit()) {
            return CursorPage.last(rows);
        }
        List<Device> items = List.copyOf(rows.subList(0, query.limit()));
        Device last = items.getLast();
        return CursorPage.of(items, Cursor.encode(last.createdAt() + "|" + last.id()));
    }

    /** {@inheritDoc} */
    @Override
    public CursorPage<Device> searchByIds(UUID projectId, Set<UUID> deviceIds, String cursor, int limit) {
        CursorPosition position = decodeCursor(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.tenant_id, d.project_id, d.device_type_id, d.gateway_id, d.device_key,
                       d.name, d.description, d.status, d.location, d.last_online_at, d.created_at
                  FROM dev_device d
                 WHERE d.project_id = ? AND d.deleted_at IS NULL
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(projectId);
        if (deviceIds == null || deviceIds.isEmpty()) {
            return CursorPage.last(List.of());
        }
        sql.append(" AND d.id = ANY(?::uuid[])");
        arguments.add(deviceIds.toArray(UUID[]::new));
        if (position != null) {
            sql.append(" AND (d.created_at, d.id) < (?, ?)");
            arguments.add(Timestamp.from(position.createdAt()));
            arguments.add(position.id());
        }
        sql.append(" ORDER BY d.created_at DESC, d.id DESC LIMIT ?");
        arguments.add(limit + 1);

        List<Device> rows = jdbcTemplate.query(sql.toString(), this::map, arguments.toArray());
        if (rows.size() <= limit) {
            return CursorPage.last(rows);
        }
        List<Device> items = List.copyOf(rows.subList(0, limit));
        Device last = items.getLast();
        return CursorPage.of(items, Cursor.encode(last.createdAt() + "|" + last.id()));
    }

    /**
     * 追加客户端直接传入的固定筛选维度。
     *
     * <p>SQL 片段完全由服务端白名单决定；关键词、UUID、枚举和标签始终作为 JDBC 参数绑定。</p>
     */
    private static void appendExplicitPredicates(StringBuilder sql, List<Object> arguments,
                                                 DeviceSearchQuery query) {
        if (query.keyword() != null) {
            sql.append(" AND (d.name ILIKE ? OR d.device_key ILIKE ? OR d.location ILIKE ?)");
            String pattern = "%" + query.keyword() + "%";
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (!query.deviceTypeIds().isEmpty()) {
            sql.append(" AND d.device_type_id = ANY(?::uuid[])");
            arguments.add(query.deviceTypeIds().toArray(UUID[]::new));
        }
        if (!query.statuses().isEmpty()) {
            sql.append(" AND d.status = ANY(?::varchar[])");
            arguments.add(query.statuses().stream().map(Enum::name).toArray(String[]::new));
        }
        if (query.tagKey() != null) {
            sql.append("""
                     AND EXISTS (
                           SELECT 1 FROM dev_tag explicit_tag
                            WHERE explicit_tag.project_id = d.project_id
                              AND explicit_tag.device_id = d.id
                              AND explicit_tag.tag_key = ?
                              AND explicit_tag.tag_value = ?)
                    """);
            arguments.add(query.tagKey());
            arguments.add(query.tagValue());
        }
    }

    /** 静态组按显式成员求交；动态组把 S5-1 冻结的受控规则继续作为 AND 维度。 */
    /** 复用搜索谓词并合并成一次SQL；结果规模受候选组数量约束。 */
    @Override
    public java.util.Set<UUID> matchingGroups(UUID projectId, UUID deviceId, List<DeviceGroup> groups) {
        if (groups.size() > 100 || groups.stream().anyMatch(g -> !projectId.equals(g.projectId())))
            throw new IllegalArgumentException("设备组成员判定范围不合法");
        if (groups.isEmpty()) return java.util.Set.of();
        List<Object> arguments = new ArrayList<>();
        List<String> branches = new ArrayList<>();
        for (DeviceGroup group : groups) {
            StringBuilder sql = new StringBuilder("SELECT ?::uuid AS group_id WHERE EXISTS (SELECT 1 FROM dev_device d WHERE d.project_id=? AND d.id=? AND d.deleted_at IS NULL");
            arguments.add(group.id()); arguments.add(projectId); arguments.add(deviceId);
            appendGroupPredicate(sql, arguments, group);
            sql.append(")");
            branches.add(sql.toString());
        }
        return java.util.Set.copyOf(jdbcTemplate.queryForList(String.join(" UNION ALL ", branches), UUID.class, arguments.toArray()));
    }

    private static void appendGroupPredicate(StringBuilder sql, List<Object> arguments, DeviceGroup group) {
        if (group == null) {
            return;
        }
        if (group.type() == DeviceGroup.Type.STATIC) {
            sql.append("""
                     AND EXISTS (
                           SELECT 1 FROM dev_group_member member
                            WHERE member.project_id = d.project_id
                              AND member.device_id = d.id
                              AND member.group_id = ?)
                    """);
            arguments.add(group.id());
            return;
        }
        DeviceGroupRule rule = group.rule();
        if (!rule.deviceTypeIds().isEmpty()) {
            sql.append(" AND d.device_type_id = ANY(?::uuid[])");
            arguments.add(rule.deviceTypeIds().toArray(UUID[]::new));
        }
        if (!rule.statuses().isEmpty()) {
            sql.append(" AND d.status = ANY(?::varchar[])");
            arguments.add(rule.statuses().stream().map(Enum::name).toArray(String[]::new));
        }
        appendDynamicGroupTags(sql, arguments, rule);
    }

    /** 多标签规则只展开键值相等谓词，ANY/ALL 不允许客户端注入其他运算符。 */
    private static void appendDynamicGroupTags(StringBuilder sql, List<Object> arguments, DeviceGroupRule rule) {
        if (rule.tags().isEmpty()) {
            return;
        }
        sql.append(" AND (SELECT count(*) FROM dev_tag group_tag WHERE group_tag.project_id = d.project_id")
                .append(" AND group_tag.device_id = d.id AND (");
        List<String> predicates = new ArrayList<>();
        rule.tags().forEach((key, value) -> {
            predicates.add("(group_tag.tag_key = ? AND group_tag.tag_value = ?)");
            arguments.add(key);
            arguments.add(value);
        });
        sql.append(String.join(" OR ", predicates)).append("))");
        if (rule.tagMatch() == DeviceGroupRule.TagMatch.ALL) {
            sql.append(" = ?");
            arguments.add(rule.tags().size());
        } else {
            sql.append(" > 0");
        }
    }

    /** 严格解码创建时间与稳定 UUID 组成的键集位置。 */
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
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "设备分页游标不合法");
        }
    }

    @Override public boolean update(Device device) {
        return DeviceTopologyConstraintTranslator.execute(() -> jdbcTemplate.update("""
                UPDATE dev_device
                   SET device_type_id = ?, name = ?, description = ?, location = ?, updated_at = now()
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, device.deviceTypeId(), device.name(), device.description(), device.location(),
                device.projectId(), device.id()) == 1);
    }

    /** {@inheritDoc} */
    @Override
    public boolean bindInitialModelVersion(UUID projectId, UUID deviceId, UUID deviceTypeId) {
        // ADR0059：目标类型锁阻止正常发布并发，单条SQL固定最新版本ID并以UPDATE RETURNING驱动唯一INITIAL。
        // 再验指针、历史、类型及存活状态；零行不能当成功，否则此前的类型更新会留下缺少版本事实的设备。
        return jdbcTemplate.update("""
                WITH selected_version AS (
                    SELECT v.id FROM public.dev_thing_model_version v
                    JOIN public.dev_type t ON t.project_id = v.project_id AND t.id = v.device_type_id
                     WHERE v.project_id = ? AND v.device_type_id = ?
                       AND t.status = 'PUBLISHED' AND t.deleted_at IS NULL
                     ORDER BY v.version_major DESC, v.version_minor DESC, v.version_patch DESC
                     LIMIT 1
                ), bound AS (
                    UPDATE public.dev_device d
                       SET thing_model_version_id = v.id, updated_at = now()
                      FROM selected_version v
                     WHERE d.project_id = ? AND d.id = ? AND d.device_type_id = ?
                       AND d.deleted_at IS NULL AND d.thing_model_version_id IS NULL
                       AND NOT EXISTS (
                           SELECT 1 FROM public.dev_device_model_binding_history h
                            WHERE h.project_id = d.project_id AND h.device_id = d.id
                       )
                    RETURNING d.id, d.tenant_id, d.project_id, d.thing_model_version_id
                )
                INSERT INTO public.dev_device_model_binding_history
                    (id, tenant_id, project_id, device_id, from_model_version_id, to_model_version_id,
                     transition_key, transition_type, effective_at)
                SELECT gen_random_uuid(), tenant_id, project_id, id, NULL, thing_model_version_id,
                       gen_random_uuid(), 'INITIAL', now()
                  FROM bound
                """, projectId, deviceTypeId, projectId, deviceId, deviceTypeId) == 1;
    }

    @Override public boolean softDelete(UUID projectId, UUID id) {
        return DeviceTopologyConstraintTranslator.execute(() -> jdbcTemplate.update("""
                UPDATE dev_device SET deleted_at = now(), updated_at = now()
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, projectId, id) == 1);
    }

    @Override public boolean setGatewayId(UUID projectId, UUID deviceId, UUID gatewayId) {
        return jdbcTemplate.update("""
                UPDATE dev_device SET gateway_id = ?, updated_at = now()
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, gatewayId, projectId, deviceId) == 1;
    }

    @Override public boolean setStatus(UUID projectId, UUID deviceId, Device.Status status, Instant lastOnlineAt) {
        return jdbcTemplate.update("""
                UPDATE dev_device
                   SET status = ?, last_online_at = COALESCE(?, last_online_at), updated_at = now()
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, status.name(),
                lastOnlineAt == null ? null : Timestamp.from(lastOnlineAt), projectId, deviceId) == 1;
    }

    /** @param rs 结果集 @param rowNum 行号 @return 设备领域对象 */
    private Device map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lastOnline = rs.getTimestamp("last_online_at");
        return new Device(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("device_type_id", UUID.class),
                rs.getObject("gateway_id", UUID.class), rs.getString("device_key"), rs.getString("name"),
                rs.getString("description"), Device.Status.valueOf(rs.getString("status")),
                rs.getString("location"), lastOnline == null ? null : lastOnline.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /** 设备键集游标的内部排序位置。 */
    private record CursorPosition(Instant createdAt, UUID id) {
    }
}
