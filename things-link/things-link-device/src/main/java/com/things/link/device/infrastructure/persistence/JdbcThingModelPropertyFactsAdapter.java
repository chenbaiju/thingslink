package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.ThingModelPropertyFacts;
import com.things.link.device.application.ThingModelPropertyFactsPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 从device权威不可变版本快照投影单个顶层属性资格事实。 */
@Repository
public class JdbcThingModelPropertyFactsAdapter implements ThingModelPropertyFactsPort {

    /** 只解析数据库已经接受的不可变JSONB属性。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 已配置项目RLS上下文的数据访问入口。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 共享当前事务与RLS上下文的JDBC模板 */
    public JdbcThingModelPropertyFactsAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ThingModelPropertyFacts> find(
            UUID projectId, UUID versionId, String propertyKey) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(propertyKey, "propertyKey");
        return jdbcTemplate.query("""
                SELECT model_snapshot -> 'properties' -> ? AS property
                 FROM dev_thing_model_version
                 WHERE project_id = ? AND id = ?
                   AND model_snapshot -> 'properties' -> ? IS NOT NULL
                """, (result, row) -> property(propertyKey, JSON.readTree(result.getString("property"))),
                propertyKey, projectId, versionId, propertyKey).stream().findFirst();
    }

    /** 将单个不可变属性投影为类型和可选量程，异常结构直接失败关闭。 */
    private static ThingModelPropertyFacts property(String propertyKey, JsonNode property) {
        if (!property.isObject() || !property.path("dataType").isString()) {
            throw new IllegalStateException("物模型版本属性缺少dataType");
        }
        return new ThingModelPropertyFacts(
                propertyKey,
                ThingModelPropertyFacts.DataType.valueOf(property.path("dataType").asString()),
                decimal(property.get("minimum")), decimal(property.get("maximum")));
    }

    /** 空值保持为空；非数值数据库事实视为损坏并失败关闭。 */
    private static BigDecimal decimal(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (!value.isNumber()) {
            throw new IllegalStateException("物模型版本数值量程不是JSON number");
        }
        return value.decimalValue();
    }
}
