package com.things.link.project.application;

import com.things.link.project.domain.PlanCapacityRepository;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** S14-R4：仅供已确权业务入口调用的权威容量端口，不采用缓存或故障默认权益。 */
@Service
public class PlanCapacityService {
    private final PlanCapacityRepository repository;
    private final DeploymentEntitlementPolicy entitlementPolicy;

    /** @param repository 单语句有效容量读取端口 */
    @Autowired
    public PlanCapacityService(PlanCapacityRepository repository, DeploymentEntitlementPolicy entitlementPolicy) {
        this.repository = repository;
        this.entitlementPolicy = entitlementPolicy;
    }

    /** 保留非 Spring 单测原构造入口。 */
    public PlanCapacityService(PlanCapacityRepository repository) {
        this(repository, DeploymentEntitlementPolicy.commercial());
    }

    /** 调用方须先完成授权，并在新增事务持有租户终端用户容量锁。 */
    @Transactional(readOnly = true)
    public long endUsersLimit(UUID tenantId, UUID projectId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        long commercial = repository.findEndUsersLimit(tenantId, projectId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PLAN_CAPACITY_UNAVAILABLE));
        return entitlementPolicy.nonCommercial()
                ? entitlementPolicy.capacity(DeploymentEntitlementPolicy.Capacity.END_USERS) : commercial;
    }
    /** 已确权调用方须在新增事务持有租户看板容量锁，既有幂等结果不调用此门禁。 */
    @Transactional(readOnly = true)
    public long dashboardsLimit(UUID tenantId, UUID projectId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        long commercial = repository.findDashboardsLimit(tenantId, projectId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PLAN_CAPACITY_UNAVAILABLE));
        return entitlementPolicy.nonCommercial()
                ? entitlementPolicy.capacity(DeploymentEntitlementPolicy.Capacity.DASHBOARDS) : commercial;
    }

    /** 已持账号及租户协作容量锁的成员添加入口读取有效外部席位上限。 */
    @Transactional(readOnly = true)
    public long externalCollaboratorSeats(UUID tenantId, UUID projectId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        long commercial = repository.findExternalSeatsLimit(tenantId, projectId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PLAN_CAPACITY_UNAVAILABLE));
        return entitlementPolicy.nonCommercial()
                ? entitlementPolicy.capacity(DeploymentEntitlementPolicy.Capacity.EXTERNAL_SEATS) : commercial;
    }

    /** 已完成设备授权的历史入口读取权威窗口；不使用JVM时钟或缓存权益。 */
    @Transactional(readOnly = true)
    public PlanHistoryWindow historyWindow(UUID tenantId, UUID projectId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        var window = repository.findHistoryWindow(tenantId, projectId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PLAN_CAPACITY_UNAVAILABLE));
        if (entitlementPolicy.nonCommercial()) {
            return new PlanHistoryWindow(window.to().minus(java.time.Duration.ofDays(
                    entitlementPolicy.capacity(DeploymentEntitlementPolicy.Capacity.HISTORY_DAYS))), window.to());
        }
        return new PlanHistoryWindow(window.from(), window.to());
    }

}
