package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceGroup;
import com.things.link.device.domain.DeviceGroupRepository;
import com.things.link.device.domain.DeviceGroupRule;
import com.things.link.device.domain.DeviceTag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC 设备组仓储；动态规则只展开为固定列和标签表上的绑定参数。 */
@Repository
public class JdbcDeviceGroupRepository implements DeviceGroupRepository {
    /** 数据库访问器。 */
    private final JdbcTemplate jdbcTemplate;
    /** 受控规则 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /** @param jdbcTemplate 数据库访问器 @param objectMapper JSON 映射器 */
    public JdbcDeviceGroupRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void createGroup(DeviceGroup group) {
        jdbcTemplate.update("""
                INSERT INTO dev_group
                    (id, tenant_id, project_id, name, description, group_type, rule_json)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, group.id(), group.tenantId(), group.projectId(), group.name(), group.description(),
                group.type().name(), ruleJson(group.rule()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<DeviceGroup> findGroups(UUID projectId) {
        return jdbcTemplate.query(groupSelect() + """
                 WHERE project_id = ? AND deleted_at IS NULL
                 ORDER BY created_at DESC, id DESC
                """, this::mapGroup, projectId);
    }

    /** 有界项目轴读取，过滤删除组后才交给成员判定。 */
    @Override
    public List<DeviceGroup> findGroupsByIds(UUID projectId, java.util.Set<UUID> groupIds) {
        if (groupIds.size() > 100) throw new IllegalArgumentException("设备组候选超过100条");
        if (groupIds.isEmpty()) return List.of();
        return jdbcTemplate.query(groupSelect() + " WHERE project_id=? AND id=ANY(?::uuid[]) AND deleted_at IS NULL",
                this::mapGroup, projectId, groupIds.toArray(UUID[]::new));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DeviceGroup> findGroup(UUID projectId, UUID groupId) {
        return jdbcTemplate.query(groupSelect() + """
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, this::mapGroup, projectId, groupId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean updateGroup(DeviceGroup group) {
        return jdbcTemplate.update("""
                UPDATE dev_group
                   SET name = ?, description = ?, rule_json = ?::jsonb, updated_at = now()
                 WHERE project_id = ? AND id = ? AND group_type = ? AND deleted_at IS NULL
                """, group.name(), group.description(), ruleJson(group.rule()), group.projectId(), group.id(),
                group.type().name()) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean softDeleteGroup(UUID projectId, UUID groupId) {
        // 软删除组后显式清边，避免同名重建时历史成员在审计或误写路径中复活。
        jdbcTemplate.update("DELETE FROM dev_group_member WHERE project_id = ? AND group_id = ?",
                projectId, groupId);
        return jdbcTemplate.update("""
                UPDATE dev_group SET deleted_at = now(), updated_at = now()
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, projectId, groupId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void addMember(UUID projectId, UUID groupId, UUID deviceId) {
        jdbcTemplate.update("""
                INSERT INTO dev_group_member (tenant_id, project_id, group_id, device_id)
                SELECT tenant_id, project_id, ?, ?
                  FROM dev_group
                 WHERE project_id = ? AND id = ? AND group_type = 'STATIC' AND deleted_at IS NULL
                ON CONFLICT DO NOTHING
                """, groupId, deviceId, projectId, groupId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void replaceMembers(UUID projectId, UUID groupId, List<UUID> deviceIds) {
        jdbcTemplate.update("DELETE FROM dev_group_member WHERE project_id = ? AND group_id = ?",
                projectId, groupId);
        deviceIds.forEach(deviceId -> addMember(projectId, groupId, deviceId));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void upsertTag(DeviceTag tag) {
        jdbcTemplate.update("""
                INSERT INTO dev_tag (id, tenant_id, project_id, device_id, tag_key, tag_value)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_id, device_id, tag_key)
                DO UPDATE SET tag_value = EXCLUDED.tag_value, updated_at = now()
                """, tag.id(), tag.tenantId(), tag.projectId(), tag.deviceId(), tag.key(), tag.value());
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<DeviceTag> findTags(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_id, tag_key, tag_value
                  FROM dev_tag WHERE project_id = ? AND device_id = ? ORDER BY tag_key
                """, (resultSet, row) -> new DeviceTag(resultSet.getObject("id", UUID.class),
                        resultSet.getObject("tenant_id", UUID.class),
                        resultSet.getObject("project_id", UUID.class),
                        resultSet.getObject("device_id", UUID.class),
                        resultSet.getString("tag_key"), resultSet.getString("tag_value")), projectId, deviceId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean deleteTag(UUID projectId, UUID deviceId, String key) {
        return jdbcTemplate.update("""
                DELETE FROM dev_tag WHERE project_id = ? AND device_id = ? AND tag_key = ?
                """, projectId, deviceId, key) == 1;
    }

    /** @return 设备组基础查询 */
    private static String groupSelect() {
        return """
                SELECT id, tenant_id, project_id, name, description, group_type, rule_json, created_at
                  FROM dev_group
                """;
    }

    /** @param rule 动态规则 @return JSON 数据库值 */
    private String ruleJson(DeviceGroupRule rule) {
        return rule == null ? null : objectMapper.writeValueAsString(rule);
    }

    /** 把数据库行还原为设备组。 */
    private DeviceGroup mapGroup(ResultSet resultSet, int row) throws SQLException {
        String ruleJson = resultSet.getString("rule_json");
        return new DeviceGroup(resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class), resultSet.getObject("project_id", UUID.class),
                resultSet.getString("name"), resultSet.getString("description"),
                DeviceGroup.Type.valueOf(resultSet.getString("group_type")),
                ruleJson == null ? null : objectMapper.readValue(ruleJson, DeviceGroupRule.class),
                resultSet.getTimestamp("created_at").toInstant());
    }

}
