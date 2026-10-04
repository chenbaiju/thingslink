package com.things.link.device.application;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceType;
import com.things.link.shared.error.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** ADR0193能力约束的反向保护：类型编辑/换型不能绕过已有原生接入配置。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class DeviceAccessTypeGuard {
    private final JdbcTemplate jdbc;
    /** 调用者必须先持有当前设备或类型锁，配置写者共享相同锁。 */
    public DeviceAccessTypeGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 设备已锁且候选类型已共享锁定；禁用的原生配置也不能留下无法再管理的孤立配置。 */
    public void requireDevice(UUID tenant, UUID project, UUID device, DeviceType candidate) {
        if (candidate != null && nativeCapable(candidate.deviceKind(), candidate.payloadProtocol())) return;
        rejectIf(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM dev_access_binding
                 WHERE tenant_id=? AND project_id=? AND device_id=? AND protocol<>'MQTT')
                """, Boolean.class, tenant, project, device)));
    }

    /** 类型排他锁取得后另读设备/绑定；不在等待锁前用旧JOIN快照决定兼容性。 */
    public void requireType(UUID tenant, UUID project, UUID type, DeviceType.DeviceKind kind,
            DeviceType.PayloadProtocol payload) {
        if (nativeCapable(kind, payload)) return;
        rejectIf(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM dev_device d JOIN dev_access_binding b
                    ON b.device_id=d.id AND b.project_id=d.project_id AND b.tenant_id=d.tenant_id
                 WHERE d.tenant_id=? AND d.project_id=? AND d.device_type_id=? AND d.deleted_at IS NULL
                   AND b.protocol<>'MQTT')
                """, Boolean.class, tenant, project, type)));
    }

    private static boolean nativeCapable(DeviceType.DeviceKind kind, DeviceType.PayloadProtocol payload) {
        return kind == DeviceType.DeviceKind.DIRECT && payload == DeviceType.PayloadProtocol.STANDARD;
    }
    private static void rejectIf(boolean incompatible) {
        if (incompatible) throw new BusinessException(DeviceErrorCode.ACCESS_PROTOCOL_UNSUPPORTED);
    }
}
