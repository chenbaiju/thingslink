package com.things.link.project.application;

/**
 * ADR0076：领域只清理自己的表，通过project公开SPI参与顺序批次。
 * 实现不得自行提交、切换数据源、调用对象网络或删除未有界清空子表的父记录。
 */
public interface ProjectCleanupContributor {

    /** @return 本贡献器唯一拥有的清理阶段 */
    ProjectCleanupStage stage();

    /**
     * 在已锁定项目、复核租约并配置RLS的同一物理事务中执行。
     * @param claim 从project持久行派生的可信身份，不能从请求构造
     * @return 实际删除/等待/领域完成事实；异常令全部删除与进度回滚
     */
    ProjectCleanupBatchResult clean(ProjectCleanupClaim claim);
}
