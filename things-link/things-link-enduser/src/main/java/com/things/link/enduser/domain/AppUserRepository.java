package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 终端用户登录身份的仓储契约。
 *
 * <p><b>app_user 受租户 RLS 保护</b>（{@code enable_tenant_rls()}，策略 {@code tenant_isolation}）。
 * 这意味着对它的读写必须先建立正确的租户上下文（{@code app.tenant_id}），否则 fail-closed
 * 一行都读不到。调用方（应用服务）在写入前必须把租户上下文切到<b>项目归属租户</b>，
 * 而不是调用者自己的租户 —— 见 {@code EndUserProvisioningService} 的注释。
 */
public interface AppUserRepository {

    /** 在原非只读READ COMMITTED事务串行该租户身份新增，锁持有至事务结束。 */
    void lockTenantCapacity(UUID tenantId);

    /** 统计归属租户全部登录身份，禁用身份不释放容量。调用方必须建立该租户RLS。 */
    long countByTenant(UUID tenantId);


    /**
     * 创建终端用户。
     *
     * @param user 终端用户
     */
    void create(AppUser user);

    /**
     * 按租户与用户名查用户。
     *
     * <p>用户名唯一性由 {@code (tenant_id, username)} 唯一约束仲裁，本方法供重复校验与
     * 后续登录定位使用。必须先以目标租户建立租户上下文。
     *
     * @param tenantId 归属租户
     * @param username 已规范化的用户名
     * @return 匹配的用户；不存在时为空
     */
    Optional<AppUser> findByTenantAndUsername(UUID tenantId, String username);

    /**
     * 按租户与 ID 查用户。
     *
     * <p>用于校验「目标用户确实属于目标项目的归属租户」，与复合外键形成应用层的第一道
     * 校验。必须先以目标租户建立租户上下文。
     *
     * @param tenantId 归属租户
     * @param userId   用户 ID
     * @return 匹配的用户；不存在或跨租户时为空
     */
    Optional<AppUser> findByIdAndTenant(UUID tenantId, UUID userId);

    /**
     * ADR0097：在原非只读READ COMMITTED事务锁定登录身份，验密只能消费锁后事实。
     * 用户行稳定承载该用户全部会话的互斥，不能改成只锁某个可清理的刷新令牌。
     * @param tenantId 可信项目归属租户，调用前已设置RLS范围
     * @param username 已规范化用户名
     * @return 锁后用户；不存在时为空，登录仍执行哑口令比对
     */
    Optional<AppUser> lockByTenantAndUsername(UUID tenantId, String username);

    /**
     * ADR0097：以NO KEY UPDATE持有用户会话互斥至原事务结束，不阻塞外键KEY SHARE。
     * 涉及项目写许可的入口必须先取得项目锁；锁后才可读取密码或重读刷新令牌。
     * @param tenantId 已确权租户，调用前已设置RLS范围
     * @param userId 已确权用户
     * @return 锁后用户；不存在或跨租户时为空
     */
    Optional<AppUser> lockByIdAndTenant(UUID tenantId, UUID userId);

    /**
     * 更新口令哈希与最后改密时刻。
     *
     * <p>两道防线：{@code WHERE tenant_id = ? AND id = ?} 的显式租户条件与租户 RLS。
     * 改密后调用方需撤销该用户全部会话（见 {@code AppRefreshTokenRepository#revokeAllForUser}）。
     *
     * @param tenantId          归属租户
     * @param appUserId         用户 ID
     * @param passwordHash      新口令哈希（DelegatingPasswordEncoder 格式）
     * @param passwordChangedAt 改密时刻
     * @return 实际更新的行数（0 表示用户不存在或跨租户）
     */
    int updatePassword(UUID tenantId, UUID appUserId, String passwordHash, Instant passwordChangedAt);
}
