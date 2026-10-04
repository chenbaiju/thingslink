package com.things.link.device.application;

import com.things.link.project.application.ProjectService;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Console设备运行读取的授权与普通RLS事务编排。
 *
 * <p>数据运行合同§3.1/3.2要求Console成员使用项目真实tenant，而不能沿用协作者账号所属tenant。
 * 因此成员复核、项目归属读取、双轴范围建立和设备事实查询必须在同一只读事务中完成。</p>
 */
@Service
public class ConsoleDeviceRuntimeDataService {

    /** 项目成员与真实归属公开端口。 */
    private final ProjectService projects;
    /** 当前普通事务的可信租户项目范围。 */
    private final TransactionLocalRlsScope scope;
    /** 不含Console身份假设的设备运行核心。 */
    private final DeviceRuntimeDataService devices;

    /** 独立Console精确模型目录，不修改分享候选限制。 */
    private final ConsoleDeviceCatalogService catalog;

    /**
     * @param catalog 精确模型目录分页
     * @param projects 项目成员与归属服务
     * @param scope 集中事务局部RLS组件
     * @param devices 设备运行核心服务
     */
    public ConsoleDeviceRuntimeDataService(ProjectService projects, TransactionLocalRlsScope scope,
                                           DeviceRuntimeDataService devices, ConsoleDeviceCatalogService catalog) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * @param projectId 已由HTTP首层校验、并在本层再次复核成员关系的项目
     * @param requests 设备与精确模型请求
     * @param models 请求声明的不可变模型身份
     * @return 三态设备与成功模型描述
     */
    @Transactional(readOnly = true)
    public RuntimeDeviceSnapshotResult querySnapshots(
            UUID projectId, List<RuntimeDeviceQuery> requests, List<RuntimeModelReference> models) {
        establish(projectId);
        return devices.querySnapshots(projectId, requests, models);
    }

    /**
     * @param projectId 已由HTTP首层校验、并在本层再次复核成员关系的项目
     * @param requests 设备、精确模型与顶层属性请求
     * @return 权威PG当前值状态
     */
    @Transactional(readOnly = true)
    public RuntimeDeviceCurrentResult queryCurrentValues(UUID projectId, List<RuntimeDeviceQuery> requests) {
        establish(projectId);
        return devices.queryCurrentValues(projectId, requests);
    }

    /**
     * @param projectId Console当前项目，读取前复核成员和真实tenant
     * @param deviceId 用户明确选择的单设备
     * @return 当前精确模型绑定元数据；不返回当前属性业务值
     */
    @Transactional(readOnly = true)
    public RuntimeDeviceSnapshotResult bindingMetadata(UUID projectId, UUID deviceId) {
        establish(projectId);
        return devices.bindingMetadata(projectId, deviceId);
    }

    /** @param projectId 路径项目 @param modelVersionId 精确模型 @param cursor 可选游标 @param limit 页大小
     * @return 同事务成员复核与真实双轴RLS约束的设备目录
     */
    @Transactional(readOnly = true, timeout = 3)
    public ConsoleDeviceCatalogService.Page catalog(UUID projectId, UUID modelVersionId, String cursor, int limit) {
        UUID tenantId = establish(projectId);
        return catalog.query(tenantId, projectId, TenantContext.require().accountId(), modelVersionId, cursor, limit);
    }

    /** 成员许可先于真实tenant读取和任何设备RLS SQL，避免跨租户协作者得到错误空集。 */
    private UUID establish(UUID projectId) {
        projects.requireRoleInProject(projectId);
        UUID tenantId = projects.requireProjectTenant(projectId);
        scope.establish(tenantId, projectId);
        return tenantId;
    }
}
