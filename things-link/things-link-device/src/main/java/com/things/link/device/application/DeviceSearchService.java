package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceGroup;
import com.things.link.device.domain.DeviceGroupRepository;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceSearchQuery;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 设备组合筛选用例，负责项目成员授权和可选设备组归属校验。 */
@Service
public class DeviceSearchService {
    /** 设备查询持久化端口。 */
    private final DeviceRepository deviceRepository;
    /** 设备组归属和动态规则持久化端口。 */
    private final DeviceGroupRepository groupRepository;
    /** 项目成员授权端口。 */
    private final ProjectService projectService;

    /**
     * 创建设备组合筛选服务。
     *
     * @param deviceRepository 设备查询持久化端口
     * @param groupRepository 设备组持久化端口
     * @param projectService 项目成员授权端口
     */
    public DeviceSearchService(DeviceRepository deviceRepository,
                               DeviceGroupRepository groupRepository,
                               ProjectService projectService) {
        this.deviceRepository = deviceRepository;
        this.groupRepository = groupRepository;
        this.projectService = projectService;
    }

    /**
     * 按固定白名单执行项目内设备键集分页查询。
     *
     * @param query 已规整的组合筛选条件
     * @return 一页设备，按创建时间和 UUID 倒序
     */
    @Transactional(readOnly = true, timeout = 3)
    public CursorPage<Device> search(DeviceSearchQuery query) {
        // Controller 已做第一层授权；这里再次校验，保证内部调用也不能绕过项目成员边界。
        projectService.requireRoleInProject(query.projectId());
        DeviceGroup group = query.groupId() == null ? null
                : groupRepository.findGroup(query.projectId(), query.groupId())
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_GROUP_NOT_FOUND));
        return deviceRepository.search(query, group);
    }

    /**
     * Console当前任务关系的有界成员判定端口；返回匹配组ID，不暴露组规则或设备域类型。
     * 调用者在同一只读事务内按项目权威租户确立RLS并复验有效设备；此处仍复验成员。
     */
    @Transactional(readOnly = true)
    public java.util.Set<java.util.UUID> matchingTaskTargetGroups(java.util.UUID projectId,
            java.util.UUID deviceId, java.util.Set<java.util.UUID> groupIds) {
        projectService.requireRoleInProject(projectId);
        if (deviceId == null || groupIds == null || groupIds.size() > 100)
            throw new BusinessException(com.things.link.shared.error.CommonErrorCode.INVALID_PARAMETER);
        return deviceRepository.matchingGroups(projectId, deviceId, groupRepository.findGroupsByIds(projectId, groupIds));
    }

    /**
     * 为任务执行以键集分页读取目标设备。
     *
     * <p>这是跨模块受信端口：任务模块不直接读取 {@code dev_device}，也不接受任意筛选表达式。
     * 调用者每次至多取得 200 条，必须把结果落为 execution 快照后再投递，避免大项目一次性装入内存。</p>
     *
     * @param scope 受控的项目全部设备或设备组范围
     * @param cursor 上一页游标
     * @param limit 单页数量，取值 1 至 200
     * @return 仅含设备 ID 的稳定键集分页结果
     */
    @Transactional(readOnly = true)
    public CursorPage<TaskTargetDevice> listTaskTargets(TaskTargetScope scope, String cursor, int limit) {
        DeviceSearchQuery query = new DeviceSearchQuery(scope.projectId(), null, java.util.Set.of(),
                java.util.Set.of(), scope.groupId(), null, null, cursor, limit);
        // 调度线程没有控制台 TenantContext；该公开端口的调用者必须已经由 task 执行租约确权，
        // 因而不能转调 search() 的成员鉴权。设备组仍按 projectId 读取，跨项目 ID 一律表现为不存在。
        DeviceGroup group = scope.groupId() == null ? null : groupRepository.findGroup(scope.projectId(), scope.groupId())
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_GROUP_NOT_FOUND));
        return deviceRepository.search(query, group).map(device -> new TaskTargetDevice(device.id()));
    }
}
