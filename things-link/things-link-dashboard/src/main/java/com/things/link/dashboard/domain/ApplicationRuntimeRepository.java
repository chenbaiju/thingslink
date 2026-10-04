package com.things.link.dashboard.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 应用公开运行入口的两阶段持久端口。
 *
 * <p>首阶段只能经固定SECURITY DEFINER函数取得最小身份；第二阶段必须在调用方用该身份建立项目RLS后
 * 重读当前发布事实。两个调用都加入同一个外层事务，但首阶段结果不是锁或运行资格租约。</p>
 */
public interface ApplicationRuntimeRepository {

    /**
     * 通过无范围受限函数定位当前已发布且未删除的应用身份。
     *
     * @param appKey 规范公开定位符
     * @return 最小可信身份；不存在、未发布、撤回或已软删时为空
     */
    Optional<ApplicationRuntimeLocation> locateByAppKey(String appKey);

    /**
     * 在已建立的项目RLS范围内重新读取当前不可变版本的公开展示名称。
     *
     * @param tenantId 首阶段返回的租户ID
     * @param projectId 首阶段返回的项目ID
     * @param applicationId 首阶段返回的应用ID
     * @param appKey 原始规范公开定位符
     * @return 身份、appKey、删除状态或当前指针任一变化时为空；持久正文损坏时抛异常
     */
    Optional<PublishedApplicationRuntimeProjection> findCurrent(
            UUID tenantId, UUID projectId, UUID applicationId, String appKey);
}
