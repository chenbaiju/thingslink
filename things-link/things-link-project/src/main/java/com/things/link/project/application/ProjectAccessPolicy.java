package com.things.link.project.application;

/**
 * ADR0064的项目生命周期快照，仅决定能否继续原授权，不承载成员或设备访问权。
 * 该值不是写许可，业务提交需要同事务调用生命周期服务的加锁入口。
 * @param readAllowed 项目未删且ACTIVE或ARCHIVED时可继续读取授权
 * @param writeAllowed 项目未删且ACTIVE时可继续业务写入授权
 * @param lifecycleGeneration 当前项目能力撤销代次；项目不可见时为-1
 */
public record ProjectAccessPolicy(boolean readAllowed, boolean writeAllowed, long lifecycleGeneration) {

    /**
     * 兼容代次字段引入前的策略构造；可读项目从迁移初始代次0开始，不可见项目保留-1哨兵。
     * @param readAllowed 读取资格
     * @param writeAllowed 写入资格
     */
    public ProjectAccessPolicy(boolean readAllowed, boolean writeAllowed) {
        this(readAllowed, writeAllowed, readAllowed || writeAllowed ? 0L : -1L);
    }

    /** @param expectedGeneration 凭据签发时冻结的代次 @return 是否与当前项目代次精确一致 */
    public boolean matchesGeneration(long expectedGeneration) {
        return lifecycleGeneration >= 0 && lifecycleGeneration == expectedGeneration;
    }
}
