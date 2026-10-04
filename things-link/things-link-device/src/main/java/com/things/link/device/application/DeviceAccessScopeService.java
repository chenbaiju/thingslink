package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectService.DeviceAccessScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * 为协议接入模块解析平台确权后的设备归属。
 *
 * <p>MQTT Topic 里的短标识只负责定位，不能直接进入可信消息信封。本服务在 device 模块内部
 * 完成项目范围解析、RLS 注入与设备档案查询，避免 ingestion 跨模块读取表或依赖领域对象。</p>
 */
@Service
public class DeviceAccessScopeService {

    /** ADR0192：配置缺席才是存量MQTT，原生禁用不得回落。 */
    private final DeviceAccessSessionRepository bindings;

    /** 项目域公开的设备接入范围契约。 */
    private final ProjectService projectService;
    /** 设备聚合仓储，只在本模块内部使用。 */
    private final DeviceRepository deviceRepository;
    /** 设备类型仓储，用于拒绝 SUB_DEVICE 独立接入。 */
    private final DeviceTypeRepository typeRepository;
    /** S12-2a1c集中保证当前事务连接上的完整租户与项目RLS范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** 保证 RLS 变量与设备查询使用同一事务连接。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建接入身份解析服务。
     *
     * @param projectService 项目范围服务
     * @param deviceRepository 设备仓储
     * @param typeRepository 设备类型仓储
     * @param transactionLocalRlsScope 事务局部RLS完整范围组件
     * @param transactionTemplate 事务模板
     * @param bindings 当前协议绑定，读取故障不得降级允许
     */
    public DeviceAccessScopeService(ProjectService projectService, DeviceRepository deviceRepository,
                                    DeviceTypeRepository typeRepository,
                                    TransactionLocalRlsScope transactionLocalRlsScope,
                                    TransactionTemplate transactionTemplate, DeviceAccessSessionRepository bindings) {
        this.bindings = bindings;
        this.projectService = projectService;
        this.deviceRepository = deviceRepository;
        this.typeRepository = typeRepository;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 按 MQTT Topic 的项目与设备短标识解析可信 UUID 范围。
     *
     * <p>不存在时统一返回空，调用方不得区分项目不存在与设备不存在，避免公开接入端点成为枚举器。</p>
     *
     * @param projectKey 项目 MQTT 标识
     * @param deviceKey 设备 MQTT 标识
     * @return 已确权范围
     */
    public Optional<ResolvedDeviceAccessScope> resolve(String projectKey, String deviceKey) {
        // 项目定位也必须在这一个外层事务内；ProjectService 的 REQUIRED 事务会加入当前连接，
        // 随后范围建立与设备查询继续复用它。拆成两个事务会让每条 Broker 消息重复借连接。
        return transactionTemplate.execute(status -> resolveInTransaction(projectKey, deviceKey));
    }

    /** 在单一事务中完成项目定位、RLS 注入和设备确权。 */
    private Optional<ResolvedDeviceAccessScope> resolveInTransaction(String projectKey, String deviceKey) {
        Optional<DeviceAccessScope> projectScope = projectService.findDeviceAccessScope(projectKey);
        if (projectScope.isEmpty()) {
            return Optional.empty();
        }
        return resolveInScope(projectScope.orElseThrow(), deviceKey);
    }

    /** 在已知项目范围内注入 RLS 并查询设备档案，SUB_DEVICE 无独立接入故在此拒绝。 */
    private Optional<ResolvedDeviceAccessScope> resolveInScope(DeviceAccessScope projectScope, String deviceKey) {
        // project公开解析结果是同一权威事实的完整二元组；组件拒绝无事务、残缺或异范围切换。
        transactionLocalRlsScope.establish(projectScope.tenantId(), projectScope.projectId());
        Device device = deviceRepository.findByDeviceKey(projectScope.projectId(), deviceKey).orElse(null);
        if (device == null || device.deviceTypeId() == null || !device.tenantId().equals(projectScope.tenantId())) {
            return Optional.empty();
        }
        DeviceType type = typeRepository.findById(projectScope.projectId(), device.deviceTypeId()).orElse(null);
        if (type == null || type.deviceKind() == DeviceType.DeviceKind.SUB_DEVICE) {
            return Optional.empty();
        }
        // 当前许可独立于认证成功缓存；旧Broker连接也必须经过这个交接边界。
        var binding = bindings.findBinding(projectScope.projectId(), device.id());
        if (binding.isPresent() && (!binding.get().tenantId().equals(projectScope.tenantId())
                || !binding.get().allows(TransportProtocol.MQTT))) return Optional.empty();
        return Optional.of(toResolvedScope(projectScope, device));
    }

    /** 只向跨模块调用者暴露稳定 UUID，不泄漏设备领域对象。 */
    private static ResolvedDeviceAccessScope toResolvedScope(DeviceAccessScope projectScope, Device device) {
        return new ResolvedDeviceAccessScope(projectScope.tenantId(), projectScope.projectId(), device.id());
    }

    /**
     * 接入消息信封所需的最小可信身份。
     *
     * @param tenantId 设备归属租户
     * @param projectId 设备归属项目
     * @param deviceId 设备主键，同时是 Kafka 分区键
     */
    public record ResolvedDeviceAccessScope(UUID tenantId, UUID projectId, UUID deviceId) {
    }
}
