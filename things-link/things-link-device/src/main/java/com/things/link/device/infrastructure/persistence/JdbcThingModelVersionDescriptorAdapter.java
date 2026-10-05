package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.ThingModelVersionDescriptor;
import com.things.link.device.application.ThingModelVersionDescriptorPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 从device领域权威版本表提供最小跨域摘要描述的JDBC适配器。
 *
 * <p>查询同时携带projectId和versionId并继续服从项目RLS；Dashboard等消费域只能依赖公开端口，
 * 不能复制本查询或扩张为物模型正文读取。</p>
 */
@Repository
public class JdbcThingModelVersionDescriptorAdapter implements ThingModelVersionDescriptorPort {

    /** 已配置项目RLS上下文的device领域数据访问入口。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建不可变模型版本描述适配器。
     *
     * @param jdbcTemplate 已配置项目RLS上下文的数据访问模板
     */
    public JdbcThingModelVersionDescriptorAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ThingModelVersionDescriptor> find(UUID projectId, UUID versionId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(versionId, "versionId");
        return jdbcTemplate.query("""
                SELECT id, project_id, digest_algorithm, schema_digest, schema_profile
                  FROM dev_thing_model_version
                 WHERE project_id = ? AND id = ?
                """, (result, row) -> new ThingModelVersionDescriptor(
                        result.getObject("id", UUID.class), result.getObject("project_id", UUID.class),
                        result.getString("digest_algorithm"), result.getString("schema_digest"),
                        result.getString("schema_profile")), projectId, versionId).stream().findFirst();
    }
}
