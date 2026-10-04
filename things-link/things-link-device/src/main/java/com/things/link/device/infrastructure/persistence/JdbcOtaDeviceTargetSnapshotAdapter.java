package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.OtaDeviceTargetSnapshot;
import com.things.link.device.application.OtaDeviceTargetSnapshotPort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 本域批量目标锁，完整匹配后才返回快照，不读报告、凭据或其他模块表。 */
@Repository
public class JdbcOtaDeviceTargetSnapshotAdapter implements OtaDeviceTargetSnapshotPort {
    /** 同调用方事务及精确RLS的普通连接。 */
    private final JdbcTemplate jdbc;

    /** 显式注入本域连接。 */
    public JdbcOtaDeviceTargetSnapshotAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 类型先锁，随后设备按PG UUID无符号顺序锁定，与规范UUID文本顺序一致。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<List<OtaDeviceTargetSnapshot>> lockExplicit(UUID tenantId, UUID projectId,
            UUID deviceTypeId, List<UUID> deviceIds) {
        if (tenantId == null || projectId == null || deviceTypeId == null || deviceIds == null
                || deviceIds.isEmpty() || deviceIds.size() > 1000 || deviceIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(deviceIds).size() != deviceIds.size()) {
            throw new IllegalArgumentException("OTA显式目标必须是1至1000个不重复设备身份");
        }
        List<UUID> sorted = deviceIds.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        var type = jdbc.query("""
                SELECT id FROM dev_type
                WHERE tenant_id=? AND project_id=? AND id=? AND status='PUBLISHED'
                  AND device_kind='DIRECT' AND deleted_at IS NULL FOR SHARE
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, projectId, deviceTypeId);
        if (type.isEmpty()) return Optional.empty();
        var arguments = new ArrayList<Object>(sorted.size() + 3);
        arguments.add(tenantId); arguments.add(projectId); arguments.add(deviceTypeId); arguments.addAll(sorted);
        String placeholders = String.join(",", Collections.nCopies(sorted.size(), "?"));
        var result = jdbc.query("""
                SELECT tenant_id,project_id,device_type_id,id,thing_model_version_id,credential_version
                FROM dev_device WHERE tenant_id=? AND project_id=? AND device_type_id=? AND deleted_at IS NULL
                  AND id IN (
                """ + placeholders + ") ORDER BY id FOR SHARE", (rs, row) -> new OtaDeviceTargetSnapshot(
                rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("device_type_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getObject("thing_model_version_id", UUID.class), rs.getLong("credential_version")), arguments.toArray());
        return result.size() == sorted.size() && DeviceMqttCapability.allAllowed(jdbc, tenantId, projectId, sorted)
                ? Optional.of(List.copyOf(result)) : Optional.empty();
    }
}
