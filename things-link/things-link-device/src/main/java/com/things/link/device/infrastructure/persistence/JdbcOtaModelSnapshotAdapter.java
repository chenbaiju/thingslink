package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.OtaModelSnapshot;
import com.things.link.device.application.OtaModelSnapshotPort;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 在device边界内联合权威类型和不可变版本，继续服从项目RLS；不复制Schema正文。 */
@Repository
public class JdbcOtaModelSnapshotAdapter implements OtaModelSnapshotPort {
    /** 携带调用方事务与RLS上下文的数据库入口。 */
    private final JdbcTemplate jdbc;

    /** 显式声明只读投影依赖，禁止通过新连接绕过RLS。 */
    public JdbcOtaModelSnapshotAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<OtaModelSnapshot> find(UUID projectId, UUID deviceTypeId, UUID thingModelVersionId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(deviceTypeId, "deviceTypeId");
        Objects.requireNonNull(thingModelVersionId, "thingModelVersionId");
        return jdbc.query("""
                SELECT v.project_id, v.device_type_id, v.id, t.product_key,
                       v.digest_algorithm, v.schema_digest, v.schema_profile
                  FROM dev_thing_model_version v
                  JOIN dev_type t ON t.id = v.device_type_id
                     AND t.project_id = v.project_id AND t.tenant_id = v.tenant_id
                 WHERE v.project_id = ? AND v.device_type_id = ? AND v.id = ?
                   AND t.status = 'PUBLISHED' AND t.product_key IS NOT NULL AND t.product_key <> ''
                """, (row, index) -> new OtaModelSnapshot(row.getObject("project_id", UUID.class),
                        row.getObject("device_type_id", UUID.class), row.getObject("id", UUID.class),
                        row.getString("product_key"), row.getString("digest_algorithm"),
                        row.getString("schema_digest"), row.getString("schema_profile")),
                projectId, deviceTypeId, thingModelVersionId).stream().findFirst();
    }
}
