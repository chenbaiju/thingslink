package com.things.link.enduser.domain;

import com.things.link.shared.page.CursorPage;

import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 冻结§4.1：只读事实和受控CAS；Console管理授权、项目许可、看板锁由原事务调用方负责。 */
public interface AppUserDashboardGrantRepository {
    /** 按完整四元组读取，包括已撤销事实；不存在/错范围为空，数据库故障保留首因。 */
    Optional<AppUserDashboardGrant> find(UUID tenantId, UUID projectId, UUID appUserId, UUID dashboardId);

    /**
     * 按稳定看板ID升序读取用户全部ACTIVE/REVOKED授权，不联查看板标题或软删状态。
     * @param tenantId 权威项目租户
     * @param projectId 已授权项目
     * @param appUserId 已确认在项目内的用户
     * @param cursor 上一页返回的不透明规范UUID游标，首页为null
     * @param limit 单页1至200行，非法游标/数量沿10001
     * @return 有界授权历史页
     */
    CursorPage<AppUserDashboardGrant> list(UUID tenantId, UUID projectId, UUID appUserId,
                                         String cursor, int limit);

    /**
     * 一次读取当前应用至多5个稳定看板中，当前用户持有ACTIVE READ授权的ID交集。
     * @param tenantId 已认证App身份的可信租户
     * @param projectId 已认证App身份的可信项目
     * @param appUserId 已复核有效的终端用户
     * @param dashboardIds 当前应用公开描述给出的0至5个唯一稳定看板ID
     * @return 指定集合内当前ACTIVE授权ID，不扫描或返回用户其他授权
     */
    Set<UUID> findActiveDashboardIds(UUID tenantId, UUID projectId, UUID appUserId, List<UUID> dashboardIds);

    /**
     * 原非只读RC事务内执行CAS，调用前已按项目许可→用户锁→看板锁完成业务资格。
     * 函数重复取得同用户锁并复验有效角色，防止缺行首次写竞争或绕过用户互斥。
     * @param candidateId 仅首次插入使用的新身份
     * @param tenantId 权威项目租户
     * @param projectId 已授权项目
     * @param appUserId 目标用户
     * @param dashboardId 已锁定的同域稳定看板
     * @param expectedRevision 缺行首次为0，后续为当前正revision
     * @param status ACTIVE或REVOKED，业务解析在外层
     * @param actorId 已认证Console操作者
     * @return 明确仲裁分类；成功含原锁内数据库事实，不通过回查猜测失败原因
     */
    WriteResult compareAndSet(UUID candidateId, UUID tenantId, UUID projectId, UUID appUserId,
                              UUID dashboardId, long expectedRevision, AppUserDashboardGrant.Status status,
                              UUID actorId);

    /** @param outcome 函数唯一仲裁结果 @param grant 仅成功/无变化时存在的数据库事实 */
    record WriteResult(Outcome outcome, AppUserDashboardGrant grant) {
        /** 持久函数结果与事实必须一致，未知或缺行不伪装成功。 */
        public WriteResult {
            if (outcome == null || (outcome.successful() != (grant != null))) {
                throw new IllegalArgumentException("看板授权CAS结果与事实不一致");
            }
        }
    }

    /** 只用于内部持久仲裁；HTTP错误映射由管理服务负责。 */
    enum Outcome {
        /** 首次0→1。 */
        CREATED,
        /** 原行推进一个revision。 */
        UPDATED,
        /** 匹配revision且同状态，时间和操作者均不变。 */
        UNCHANGED,
        /** 用户/角色/缺行撤销不可用。 */
        NOT_FOUND,
        /** 旧revision或递增空间耗尽。 */
        CONFLICT,
        /** 内部调用输入不符合持久语法。 */
        INVALID;

        /** 成功分类才允许读取锁内事实；失败不得另查资源推断原因。 */
        public boolean successful() {
            return this == CREATED || this == UPDATED || this == UNCHANGED;
        }
    }
}
