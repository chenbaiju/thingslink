package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.DeviceModelBindingFacts;
import com.things.link.device.application.DeviceModelBindingFactsPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 从device权威目录提供未软删除设备的当前模型绑定事实。 */
@Repository
public class JdbcDeviceModelBindingFactsAdapter implements DeviceModelBindingFactsPort {

    /** 已配置项目RLS上下文的数据访问入口。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 共享当前事务与RLS上下文的JDBC模板 */
    public JdbcDeviceModelBindingFactsAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DeviceModelBindingFacts> find(UUID projectId, UUID deviceId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(deviceId, "deviceId");
        return jdbcTemplate.query("""
                SELECT id, project_id, thing_model_version_id
                  FROM dev_device
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                   AND thing_model_version_id IS NOT NULL
                """, (result, row) -> new DeviceModelBindingFacts(
                        result.getObject("id", UUID.class), result.getObject("project_id", UUID.class),
                        result.getObject("thing_model_version_id", UUID.class)),
                projectId, deviceId).stream().findFirst();
    }
}
