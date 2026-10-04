package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 终端用户设备授权关系的仓储契约。
 *
 * <p><b>app_user_device 受项目 RLS 保护</b>（{@code enable_project_rls()}，策略
 * {@code project_isolation}）。读写前必须建立正确的项目上下文（{@code app.project_id}）
 * 与租户上下文（{@code app.tenant_id}）。
 *
 * <p>S11-1b 只读（设备绑定概览）；S11-3 从角色停用级联关闭开始逐步落地写入，
 * 后续绑定/解绑/转移/共享继续复用本契约。
 */
public interface AppUserDeviceRepository {

    /**
     * 在客户端明确给出的最多20个设备内查询当前有效授权，不扫描该用户的完整关系集合。
     *
     * @param tenantId 已认证租户
     * @param projectId 已认证项目
     * @param appUserId 已认证App用户
     * @param deviceIds 客户端明确选择且已去重的设备ID
     * @return 仍有ACTIVE关系的设备ID集合
     */
    Set<UUID> findActiveDeviceIds(UUID tenantId, UUID projectId, UUID appUserId, Collection<UUID> deviceIds);

    /**
     * 通过ADR0106只读投影视图先按授权、模型和keyset过滤，再最多读取limit+1行。
     *
     * @param tenantId 已认证租户
     * @param projectId 已认证项目
     * @param appUserId 已认证App用户
     * @param modelVersionId Schema声明的精确模型版本
     * @param beforeCreatedAt 上页末行创建时刻；首页为空
     * @param beforeId 上页末行设备ID；首页为空
     * @param fetchLimit 包含探测下一页的一次SQL上限
     * @return 严格按createdAt、deviceId倒序的有界目录候选
     */
    List<AppRuntimeDeviceCatalogItem> findRuntimeCatalog(UUID tenantId, UUID projectId, UUID appUserId,
            UUID modelVersionId, Instant beforeCreatedAt, UUID beforeId, int fetchLimit);

    /**
     * 列某终端用户在本项目中的设备授权关系。
     *
     * <p>只返回可见（当前项目）的关系。S11-3 之前该表由绑定流程填充，此处恒为空
     * 属正常 —— 概览端点只陈述事实，不解析设备名（设备名属 device 模块，S11-2b 再拼）。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @return 该用户在本项目的设备关系，按建立时间正序
     */
    List<AppUserDevice> findByProjectAndUser(UUID projectId, UUID appUserId);

    /**
     * 关闭某终端用户在项目内的全部有效设备关系。
     *
     * <p>ADR 0035 与 D-041 要求项目角色停用后不能残留仍为 {@code ACTIVE} 的设备授权。
     * 本方法只做 {@code ACTIVE -> CLOSED} 条件更新，保留历史；重复调用返回 0，不能把恢复
     * 项目角色解释为恢复旧设备授权。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @return 实际关闭的关系数量
     */
    int closeActiveByProjectAndUser(UUID projectId, UUID appUserId);

    /**
     * 查用户与设备之间的有效关系，供 CLAIM 幂等重放判定。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @param deviceId  设备 ID
     * @return 有效关系
     */
    Optional<AppUserDevice> findActive(UUID projectId, UUID appUserId, UUID deviceId);

    /**
     * 查设备当前有效主控。
     *
     * @param projectId 项目 ID
     * @param deviceId  设备 ID
     * @return 当前 PRIMARY 关系
     */
    Optional<AppUserDevice> findActivePrimary(UUID projectId, UUID deviceId);

    /**
     * 锁定设备当前有效 PRIMARY，作为主控转移的串行化入口。
     *
     * <p>不同 TRANSFER 令牌可能锁住不同令牌行，因此令牌锁本身不能仲裁同一设备的
     * 并发转移。所有转移必须再以当前 PRIMARY 行锁形成设备级顺序（ADR 0037、G2-A1e）。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 被锁定的当前 PRIMARY
     */
    Optional<AppUserDevice> findActivePrimaryForUpdate(UUID projectId, UUID deviceId);

    /**
     * 锁定接收者与设备的有效关系，供转移时原位升级。
     *
     * @param projectId 项目 ID
     * @param appUserId 接收者
     * @param deviceId 设备 ID
     * @return 被锁定的有效关系；尚未共享时为空
     */
    Optional<AppUserDevice> findActiveForUpdate(UUID projectId, UUID appUserId, UUID deviceId);

    /**
     * 把仍为 PRIMARY 的旧主控原位降为 MEMBER。
     *
     * @param projectId 项目 ID
     * @param bindingId 旧主控关系 ID
     * @param appUserId 令牌签发人
     * @param deviceId 设备 ID
     * @return 恰好降级一行时为 1
     */
    int demotePrimaryToMember(UUID projectId, UUID bindingId, UUID appUserId, UUID deviceId);

    /**
     * 把接收者既有 MEMBER/READ_ONLY 关系原位升为 PRIMARY。
     *
     * @param projectId 项目 ID
     * @param bindingId 接收者关系 ID
     * @param appUserId 接收者
     * @param deviceId 设备 ID
     * @return 恰好升级一行时为 1
     */
    int promoteActiveToPrimary(UUID projectId, UUID bindingId, UUID appUserId, UUID deviceId);

    /**
     * 原子关闭一个用户—设备有效关系并返回被关闭的历史事实。
     *
     * <p>使用单条条件更新仲裁并发解绑：只有首个请求能把 {@code ACTIVE} 改为
     * {@code CLOSED}，后续重复请求得到空结果且不能改写 {@code updated_at}。返回关系用于
     * 在同一事务写不可篡改审计，不需要先查后改制造竞态窗口（ADR 0037、G2-A1d）。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @param deviceId  设备 ID
     * @return 本次实际关闭的关系；不存在或已关闭时为空
     */
    Optional<AppUserDevice> closeActive(UUID projectId, UUID appUserId, UUID deviceId);

    /**
     * 建立有效 PRIMARY 关系，唯一索引冲突时不抛 SQL 异常而返回 0。
     *
     * <p>使用 {@code ON CONFLICT DO NOTHING} 让调用方仍能在同一事务内保留已计入的
     * 令牌复核尝试，并返回稳定 60013；数据库唯一索引仍是最终并发仲裁者。
     *
     * @param binding 待建立的 PRIMARY 关系
     * @return 1 表示建立成功，0 表示已有冲突关系
     */
    int createActivePrimary(AppUserDevice binding);

    /**
     * 建立 MEMBER 或 READ_ONLY 有效共享关系，唯一关系冲突时返回 0。
     *
     * <p>本方法拒绝 PRIMARY，避免 SHARE 状态机绕过专用主控仲裁；数据库的用户—设备
     * 部分唯一索引仍是不同 SHARE 令牌并发消费的最终裁决者（ADR 0037、G2-A1f）。
     *
     * @param binding 待建立的共享关系
     * @return 1 表示建立成功，0 表示已有冲突关系
     */
    int createActiveShared(AppUserDevice binding);
}
