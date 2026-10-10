package com.things.link.project.domain;

import com.things.link.shared.authz.ProjectRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 项目与项目成员的仓储契约。
 *
 * <p><b>本仓储访问的两张表都豁免了 RLS</b>（project、project_member），因为它们正是
 * 用来确定「当前用户能进哪些项目」的 —— 加策略就是先有鸡还是先有蛋（ADR 0012）。
 *
 * <p>这意味着这里<b>没有数据库层的兜底</b>：任何按用户范围的查询都必须在方法签名里
 * 显式带上 accountId，并在 SQL 里 join {@code project_member}。
 * 忘了写条件的后果是把全平台的项目列给所有人看，而这类错误不会有任何症状。
 */
public interface ProjectRepository {

    /** @param tenantId 可信租户 @param after 最后扫描ID，可空 @param limit 有界扫描数 @return 未删除ACTIVE候选，调用者仍须核验App角色 */
    List<Project> scanActiveOwned(UUID tenantId, UUID after, int limit);

    /**
     * 创建项目。
     *
     * @param project 项目
     */
    void create(Project project);

    /**
     * 建立项目成员关系。
     *
     * @param memberId  成员记录 ID
     * @param projectId 项目 ID
     * @param accountId 账号 ID。这里只是一个 UUID —— project 模块<b>不依赖 iam</b>，
     *                  账号的完整性由数据库外键保证
     * @param role      项目内角色
     */
    void addMember(UUID memberId, UUID projectId, UUID accountId, ProjectRole role);

    /**
     * 取租户级自有项目配额事务锁，持有至调用方原事务结束。
     *
     * <p>S14-2b：{@code projects_max} 是「先数后插」的判据，只有把同一租户的计数与创建放进
     * 同一把锁，并发的两次创建才不可能都看到「还没到上限」。锁用哈希到 64 位的租户键，不读取
     * project 表，因此跨项目、跨入口共享同一串行点；设备配额使用另一命名空间，二者不互相阻塞。
     *
     * <p>调用方必须处在真实非只读事务中，且必须先取锁再计数、再创建。
     *
     * @param tenantId 项目归属租户
     */
    void lockTenantProjectQuota(UUID tenantId);

    /**
     * 统计租户当前<b>自有</b>且未软删的项目数。
     *
     * <p>只按 {@code sys_project.tenant_id} 数，不 join 成员关系：被邀请成为其他租户项目成员的
     * 账号不应消耗自己租户的名额（ADR 0012 区分「自有项目」与「接受外部协作」）。软删项目
     * （{@code deleted_at} 非空）不计，删除后名额立即释放；ARCHIVED 仍占用名额，因为它尚未被删除。
     *
     * @param tenantId 项目归属租户
     * @return 未软删项目数
     */
    int countOwnedProjects(UUID tenantId);

    /**
     * 列出某账号参与的全部项目，附带他在其中的角色。
     *
     * <p>结果<b>跨租户</b>：既包含他自己创建的项目，也包含被邀请加入的、
     * 属于其他租户的项目。这正是校准后模型的形态（ADR 0012）。
     *
     * @param accountId 账号 ID
     * @return ACTIVE/ARCHIVED未删项目及角色，按创建时间倒序（ADR0064决策5）
     */
    List<ProjectMembership> findMembershipsByAccount(UUID accountId);

    /**
     * 查某账号参与的单个项目，附带他在其中的角色。
     *
     * <p>用于编辑项目名称后回读最新项目信息。仍然必须带 accountId，
     * 因为 {@code project} 与 {@code project_member} 都没有 RLS 兜底。
     *
     * @param projectId 项目 ID
     * @param accountId 账号 ID
     * @return ACTIVE/ARCHIVED未删项目及角色；非成员或删除时为空（ADR0064决策5）
     */
    Optional<ProjectMembership> findMembership(UUID projectId, UUID accountId);

    /**
     * 列出某项目的全部有效成员。
     *
     * <p>不带 accountId 参数 —— 调用方必须<b>先</b>用 {@link #findRole} 确认自己是该
     * 项目成员，再调用本方法。这是本仓储里唯一一个「不自带隔离条件」的查询，
     * 因此它的安全性完全依赖调用顺序（见 {@code ProjectMemberService} 里的检查）。
     *
     * @param projectId 项目 ID
     * @return 成员列表，按加入时间正序（先来的排前面，OWNER 通常在最上）
     */
    List<ProjectMember> findMembers(UUID projectId);

    /**
     * 改成员角色。
     *
     * @param projectId 项目 ID
     * @param accountId 账号 ID
     * @param role      新角色
     * @return 实际更新的行数；0 表示对方已经不是成员（并发移除）
     */
    int updateMemberRole(UUID projectId, UUID accountId, ProjectRole role);

    /**
     * 转让项目所有权。
     *
     * <p>这不是普通的「改角色」：它要把当前 OWNER 降为 ADMIN，再把目标成员升为
     * OWNER，两个更新必须处在同一个事务里。调用方负责先确认 {@code currentOwnerId}
     * 的确是当前请求人且角色为 OWNER。
     *
     * @param projectId      项目 ID
     * @param currentOwnerId 当前 OWNER 账号 ID
     * @param newOwnerId     新 OWNER 账号 ID
     * @return 是否完成两条角色更新；false 表示项目或目标成员在并发中消失
     */
    boolean transferOwnership(UUID projectId, UUID currentOwnerId, UUID newOwnerId);

    /**
     * 把某账号移出项目。
     *
     * <p><b>物理删除本表的行</b>，账号本身不受任何影响 —— 这正是需求里
     * 「删除的用户失去与该项目的关联，账户并没有删除」的表达方式。
     *
     * <p>不用软删除：软删除会让「重新邀请同一个人」撞上
     * {@code (project_id, account_id)} 唯一索引，得额外写一套「复活旧行」的逻辑，
     * 而这里没有任何需要保留历史行的理由（ProjectMemberService 已通过 AuditLogService 写成员移除审计）。
     *
     * @param projectId 项目 ID
     * @param accountId 账号 ID
     * @return 实际删除的行数；0 表示对方本就不是成员
     */
    int removeMember(UUID projectId, UUID accountId);

    /**
     * 修改项目名称。
     *
     * <p>只改 name，不提供 region 参数。区域创建后不可变更（架构文档 7.1），
     * 跨区域迁移是独立的数据迁移流程，不是一个 UPDATE。
     *
     * @param projectId 项目 ID
     * @param name      新名称
     * @return 实际更新行数；0 表示项目已删除或不存在
     */
    int updateName(UUID projectId, String name);

    /**
     * 统计项目有效成员数。
     * 删除调用方须先持项目排他锁，避免在计数后混入并发邀请；非活跃历史成员不计入但须保留。
     *
     * @param projectId 项目 ID
     * @return ACTIVE 成员数量
     */
    int countActiveMembers(UUID projectId);

    /**
     * 软删除项目。
     *
     * <p>项目表不物理删除：后续审计、计量、设备数据会引用项目 ID。删除语义通过
     * {@code DELETING/deleted_at} 表达，普通授权统一拒绝。ADR0073要求同一SQL递增生命周期代次，
     * 使删除前凭据在未来恢复后仍保持失效。ADR0064决策5要求保留全部成员原行，
     * 调用方须持项目排他锁并重新核验OWNER及活跃成员数，不在删除短事务清理其他领域。
     *
     * @param projectId 项目 ID
     * @return 实际更新行数；0 表示已删除或不存在
     */
    int softDelete(UUID projectId);

    /**
     * 列出当前账号仍以ACTIVE OWNER身份持有的软删项目。
     * 普通成员与项目查询继续隐藏DELETING；本端口只供回收站用例使用。
     * @param accountId 已认证控制台账号
     * @return 按删除时刻倒序排列的保留项目
     */
    List<DeletedProject> findDeletedOwnedBy(UUID accountId);

    /**
     * 恢复锁前及锁后检查保留的ACTIVE OWNER关系，不区分缺项目、缺成员或角色不符。
     * @param projectId 待恢复项目
     * @param accountId 已认证控制台账号
     * @return 项目仍处于DELETING且账号仍是ACTIVE OWNER时为true
     */
    boolean isRetainedActiveOwner(UUID projectId, UUID accountId);

    /**
     * 锁定仍处于DELETING的项目行直到原恢复事务结束。
     * 本锁与删除管理锁争用同一项目行；未来清理也必须复用该行锁才能与恢复互斥。
     * @param projectId 待恢复项目
     * @return 已锁定删除事实；状态已变化、不存在或未软删时为空
     */
    Optional<DeletedProject> lockDeletedForRecovery(UUID projectId);

    /**
     * 在持有项目排他锁后按数据库墙钟恢复仍处于三十天窗口的项目。
     * @param projectId 已锁定项目
     * @return 更新行数；0表示恢复窗口已过或状态被异常旁路修改
     */
    int restoreWithinWindow(UUID projectId);

    /**
     * 查某账号在某项目中的角色。
     *
     * <p>这是<b>授权判定的入口</b>：能查到即说明他是该项目成员，查不到就该当作
     * 项目不存在（而不是「无权限」——后者会泄露项目是否存在）。
     *
     * @param projectId 项目 ID
     * @param accountId 账号 ID
     * @return ACTIVE/ARCHIVED未删项目的成员角色；非成员或删除时为空（ADR0064决策5）
     */
    Optional<ProjectRole> findRole(UUID projectId, UUID accountId);
    default Optional<ProjectRole> lockMemberRole(UUID projectId,UUID accountId){throw new UnsupportedOperationException("未实现成员出站锁");}


    /** @param projectKey MQTT 项目标识 @return 未删除且有效的项目 */
    Optional<Project> findByProjectKey(String projectKey);

    /** @param projectId 项目 ID @return 未删除且有效的项目 */
    Optional<Project> findById(UUID projectId);

    /**
     * ADR0064快照查询：严格匹配归属二元组并排除deleted_at；保留状态供读写策略区分，不假定仅ACTIVE可读。
     * @param tenantId 项目真实归属租户
     * @param projectId 项目身份
     * @return 未软删项目事实；缺失/错配均为空，不承担成员授权
     */
    Optional<Project> findLiveByIdentity(UUID tenantId, UUID projectId);

    /** 后台可信二元组锁定存活项目，保留ARCHIVED供拒绝元数据分类。 */
    Optional<Project> lockLiveForBackground(UUID tenantId, UUID projectId);

    /**
     * 按已验签控制台账号与项目读取包括删除状态在内的代次事实。
     * @param accountId 已验签账号
     * @param projectId 令牌中的项目
     * @return ACTIVE成员对应的项目；项目删除不隐藏代次，非成员为空
     */
    Optional<Project> findForProjectToken(UUID accountId, UUID projectId);

    /**
     * 按账号成员关系取得ACTIVE项目当前代次并持有项目SHARE锁至原事务结束。
     * @param accountId 已完成账号认证的控制台账号
     * @param projectId 将写入凭据的项目
     * @return 成员仍有效且项目ACTIVE时的当前代次，否则为空
     */
    OptionalLong lockActiveGenerationForProjectToken(UUID accountId, UUID projectId);

    /**
     * ADR0064决策2/5：管理变更按已授权项目身份直接取得排他锁，保持至调用方原非只读事务结束。
     * 调用方须先预检当前账号角色，锁后再次授权并分类状态；不能以调用者tenant替代项目真实归属。
     * 实际原连接必须为READ COMMITTED或PG等价的READ UNCOMMITTED；更高隔离级别可能保留旧成员快照，明确拒绝而不修改外层事务。
     * @param projectId 已通过成员授权预检的项目身份
     * @return ACTIVE或ARCHIVED且未软删的真实项目；不存在/删除为空，SQL异常原传
     * @throws IllegalStateException 缺少非只读事务或实际连接隔离级别不支持锁后成员新快照
     */
    Optional<Project> lockForManagement(UUID projectId);

    /**
     * ADR0064：仅ACTIVE未删项目取得SHARE行锁，调用方必须提供原业务非只读事务。
     * 实际连接仅支持READ COMMITTED或PG等价的READ UNCOMMITTED；更高隔离级明确拒绝且不修改外层事务。
     * @param tenantId 项目真实归属租户
     * @param projectId 项目身份
     * @return 是否持有行锁至原事务结束；等待/SQL错误传播而非变false
     * @throws IllegalStateException 缺少非只读事务或实际隔离级不支持锁后新快照
     */
    boolean lockActiveForWrite(UUID tenantId, UUID projectId);

    /** ADR0177：受理在线读取事件时锁定可读项目代次，防止清理阶段推进后新增集成事实。 */
    default java.util.OptionalLong lockReadableGeneration(UUID tenantId,UUID projectId){
        throw new UnsupportedOperationException("仓储未实现可读项目锁");
    }

    /**
     * ADR0073：写许可同时匹配凭据签发时项目代次，删除后旧凭据不能因恢复重新放行。
     * @param tenantId 项目真实归属租户
     * @param projectId 项目身份
     * @param expectedGeneration 凭据签发时冻结的非负代次
     * @return 是否同时持有ACTIVE项目行锁且代次一致
     */
    boolean lockActiveForWrite(UUID tenantId, UUID projectId, long expectedGeneration);

    /**
     * S14-3c：按创建时刻正序返回租户全部未删除自有项目 ID。
     *
     * <p>P4「超额自有项目保留最早创建的 1 个可写」需要稳定顺序：同一创建时刻以 ID 兜底，
     * 因此 worker 无论重跑多少次都选出同一个「最早项目」。
     *
     * @param tenantId 项目归属租户
     * @return 未软删自有项目 ID，按 created_at、id 正序
     */
    List<UUID> findOwnedLiveProjectIds(UUID tenantId);

    /** @param tenantId 真实租户 @return 按创建顺序排列、未删除且ACTIVE的自有项目 */
    List<UUID> findActiveOwnedLiveProjectIds(UUID tenantId);

    /**
     * S14-3c：把给定项目转为既有 {@code ARCHIVED} 只读状态（P4 超额自有项目只读，不删除）。
     *
     * <p><b>为什么复用 ARCHIVED 而不是新增项目列</b>：项目域只有一道写门禁
     * （{@code ProjectLifecycleAccessService} / {@code ProjectManagementWriteGuard}），ARCHIVED 已在
     * 这道门禁上统一转成 50017 PROJECT_READ_ONLY；device/ingestion/alarm/ota/rule/task 等模块的写入口
     * 本就都经过它，因此复用既有状态不需要任何跨模块改动，也不改动写门禁 SQL。
     * 商业受限与用户主动归档的区分由 {@code sys_project_commercial_restriction} 台账承担。
     *
     * @param projectIds 待受限项目 ID
     * @return 实际从 ACTIVE 转为 ARCHIVED 的项目数
     */
    int restrictCommercialWrite(Collection<UUID> projectIds);

    /**
     * S14-3c：把台账登记的商业受限项目恢复为 {@code ACTIVE}（续费/升级恢复，P4）。
     *
     * <p>只恢复调用方显式给出的项目：调用方必须先从台账取出「本片写入的受限集合」，
     * 否则会把用户主动归档的项目误恢复。
     *
     * @param projectIds 待恢复项目 ID
     * @return 实际从 ARCHIVED 恢复为 ACTIVE 的项目数
     */
    int restoreCommercialWrite(Collection<UUID> projectIds);

}
