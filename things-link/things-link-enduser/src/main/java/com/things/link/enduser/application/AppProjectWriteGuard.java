package com.things.link.enduser.application;

import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * ADR0064决策2/4：在原App写事务中持有项目许可，并将确定拒绝映射为App错误。
 * 本编排不增加事务拦截，避免改变能力消费入口的BusinessException不回滚合同；
 * 真实非只读事务由项目端口的MANDATORY合同检查，数据库异常保持原样传播。
 */
@Service
public class AppProjectWriteGuard {

    /** 项目域负责权威状态与事务行锁，App域不能自行跨域查询项目表。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;

    /** @param lifecycleAccessService 在调用方事务内授予项目写许可的公共端口 */
    public AppProjectWriteGuard(ProjectLifecycleAccessService lifecycleAccessService) {
        this.lifecycleAccessService = lifecycleAccessService;
    }

    /**
     * 取得持有至原事务结束的写许可；许可失败后的快照只能分类拒绝，不能重新放行。
     * @param tenantId 已验证App身份中的项目归属租户
     * @param projectId 已验证App身份中的项目
     * @return 已由同一项目行锁冻结的当前生命周期代次
     */
    public long requireWritable(UUID tenantId, UUID projectId) {
        if (lifecycleAccessService.lockActiveForWrite(tenantId, projectId)) {
            // 项目行锁仍由当前事务持有，后续快照与删除不能交错；返回值用于冻结或比较能力代次。
            return lifecycleAccessService.snapshot(tenantId, projectId).lifecycleGeneration();
        }
        ProjectAccessPolicy policy = lifecycleAccessService.snapshot(tenantId, projectId);
        throw new BusinessException(policy.readAllowed() && !policy.writeAllowed()
                ? EndUserErrorCode.PROJECT_READ_ONLY : EndUserErrorCode.END_USER_ACCESS_INVALID);
    }
}
