package com.things.link.device.application;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceTopologyBusyException;
import com.things.link.device.domain.DeviceTopologyRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.shared.error.BusinessException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * ADR0057：在调用方已有事务中保护有效拓扑角色，不在设备锁后阻塞等待类型编辑锁。
 * 类型共享锁一直持有到外层事务结束；MANDATORY防止单独调用时锁在校验前已经释放。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class DeviceTopologyRoleGuard {
    /** 类型事实与共享锁来自同一行，不能先读分类再单独尝试锁。 */
    private final DeviceTypeRepository typeRepository;
    /** 角色以有效拓扑事实判断，不能根据gateway_id投影或旧类型猜测。 */
    private final DeviceTopologyRepository topologyRepository;

    /** @param typeRepository 类型锁仓储 @param topologyRepository 有效角色查询 */
    public DeviceTopologyRoleGuard(DeviceTypeRepository typeRepository, DeviceTopologyRepository topologyRepository) {
        this.typeRepository = typeRepository;
        this.topologyRepository = topologyRepository;
    }

    /** 控制面锁忙立即抛冲突，由外层事务回滚；失败的PostgreSQL事务内不再执行SQL。 */
    public Optional<DeviceType> findTypeForControlPlane(UUID projectId, UUID typeId) {
        try {
            return findTypeForDataPlane(projectId, typeId);
        } catch (CannotAcquireLockException exception) {
            BusinessException conflict = new BusinessException(DeviceErrorCode.DEVICE_TYPE_BUSY);
            conflict.initCause(exception);
            throw conflict;
        }
    }

    /** 数据面保持瞬时数据库异常，inbox/关系/回执一起回滚，交由既有Kafka重试及DLQ处理。 */
    public Optional<DeviceType> findTypeForDataPlane(UUID projectId, UUID typeId) {
        return typeId == null ? Optional.empty() : typeRepository.findByIdForShareNowait(projectId, typeId);
    }

    /**
     * ADR0058：即时数据库角色守卫也可能因设备共享锁竞争失败，不能误报为类型编辑忙。
     * 此纯错误转换不创建事务；调用方必须在既有事务中写入，抛错后立即交给事务代理回滚。
     */
    public static <T> T controlWrite(Supplier<T> write) {
        try {
            return write.get();
        } catch (DeviceTopologyBusyException exception) {
            BusinessException conflict = new BusinessException(DeviceErrorCode.DEVICE_TOPOLOGY_BUSY);
            conflict.initCause(exception);
            throw conflict;
        }
    }

    /** 设备行锁内验证候选分类；null表示清空/缺失类型，不能使有效关系成为无角色设备。 */
    public void requireDeviceRole(UUID projectId, UUID deviceId, DeviceType.DeviceKind candidateKind) {
        if (hasIncompatibleDeviceRole(projectId, deviceId, candidateKind)) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TOPOLOGY_ROLE_CONFLICT);
        }
    }

    /** 数据面已知角色非法可沿用业务拒绝回执；与类型锁忙必须回滚的系统故障区分。 */
    public boolean hasIncompatibleDeviceRole(UUID projectId, UUID deviceId, DeviceType.DeviceKind candidateKind) {
        return topologyRepository.hasIncompatibleRoleForDevice(projectId, deviceId, candidateKind);
    }

    /** 类型排他锁内验证分类变更/删除/发布；只读关系，不倒转为type到无序多设备行锁。 */
    public void requireTypeRole(UUID projectId, UUID typeId, DeviceType.DeviceKind candidateKind) {
        if (topologyRepository.hasIncompatibleRoleForType(projectId, typeId, candidateKind)) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TOPOLOGY_ROLE_CONFLICT);
        }
    }

    /** 进入多设备锁前检查网关及直接子设备涉及的有效角色，旧漂移不能靠级联悄悄改写。 */
    public void requireGatewayComponent(UUID projectId, UUID gatewayId) {
        if (topologyRepository.hasInvalidRolesInGatewayComponent(projectId, gatewayId)) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TOPOLOGY_ROLE_CONFLICT);
        }
    }
}
