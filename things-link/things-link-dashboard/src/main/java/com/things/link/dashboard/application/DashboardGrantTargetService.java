package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.DashboardGrantTargetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 向终端用户授权编排公开稳定看板目录锁。
 *
 * <p>S12-2a3a2 要求本服务加入调用方已经完成项目 ACTIVE 许可并取得用户锁的同一可写事务；
 * 本层只投影锁定身份，不执行用户授权、不建立数据库范围，也不推导发布或运行资格。</p>
 */
@Service
public class DashboardGrantTargetService {

    /** 只承担稳定目录共享锁的窄持久端口。 */
    private final DashboardGrantTargetRepository repository;

    /**
     * 创建看板授权目标锁服务。
     *
     * @param repository 稳定看板目录锁端口
     */
    public DashboardGrantTargetService(DashboardGrantTargetRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * 在原授权事务内锁定完整身份对应的未软删看板目录。
     *
     * @param tenantId 已确认项目许可的租户ID
     * @param projectId 已确认项目许可的项目ID
     * @param dashboardId 待授权稳定看板ID
     * @return 目录锁成功时返回完整目标，目录不存在、跨范围或已软删时为空
     * @throws IllegalStateException 调用方缺少外层事务或仓储事务边界不满足
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DashboardGrantTarget> lockForGrant(
            UUID tenantId, UUID projectId, UUID dashboardId) {
        DashboardGrantTarget target = new DashboardGrantTarget(tenantId, projectId, dashboardId);
        if (!repository.lockForGrant(target.tenantId(), target.projectId(), target.dashboardId())) {
            return Optional.empty();
        }
        return Optional.of(target);
    }
}
