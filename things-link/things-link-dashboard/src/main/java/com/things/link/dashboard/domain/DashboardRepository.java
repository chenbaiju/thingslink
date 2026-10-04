package com.things.link.dashboard.domain;

import com.things.link.shared.page.CursorPage;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 看板目录、独立草稿、创建恢复映射和不可变版本的聚合持久端口。
 *
 * <p>S12-1b2a开放基础聚合读写，S12-1b2c3/1b2d增加只能加入发布服务事务的锁定、原子追加和回滚入口。
 * 模型摘要/Profile与全部外部资格仍由应用服务核验，调用方不能借仓储端口拼接半发布状态。</p>
 */
public interface DashboardRepository {

    /** 原RC写事务串行同一租户看板新增；先持项目许可及可选幂等键锁。 */
    void lockTenantCapacity(UUID tenantId);

    /** 核验当前项目归属后返回租户跨项目未软删目录总数；归属/RLS不匹配拒绝。 */
    long countTenantDashboards(UUID tenantId, UUID projectId);


    /**
     * 原子创建未发布目录、revision为0的草稿及其完整模型关系。
     *
     * @param dashboard 初始目录，发布revision必须为0且当前版本和删除时刻为空
     * @param draft 与目录同归属、revision为0的初始草稿及关系
     */
    void create(DashboardCatalogEntry dashboard, DashboardDraft draft);

    /**
     * 串行同一调用身份与幂等键摘要的看板创建。
     *
     * <p>不存在的映射行无法用行锁保护，因此实现必须使用事务级锁；摘要碰撞最多让无关请求串行，
     * 不得让不同身份共享结果。</p>
     *
     * @param tenantId 项目所有者租户ID
     * @param projectId 创建目标项目ID
     * @param accountId 发起创建的Console账号ID
     * @param idempotencyKeyDigest 原始幂等键的域分离SHA-256摘要
     * @throws IllegalStateException 未加入已有非只读事务
     */
    void lockCreationRequest(
            UUID tenantId, UUID projectId, UUID accountId, String idempotencyKeyDigest);

    /**
     * 读取同一调用身份与幂等键摘要的既有看板创建结果。
     *
     * @param tenantId 项目所有者租户ID
     * @param projectId 创建目标项目ID
     * @param accountId 发起创建的Console账号ID
     * @param idempotencyKeyDigest 原始幂等键摘要
     * @return 已持久化映射；首次请求时为空
     */
    Optional<DashboardCreationResult> findCreationResult(
            UUID tenantId, UUID projectId, UUID accountId, String idempotencyKeyDigest);

    /**
     * 读取并排他锁定映射指向的看板目录，包括软删除事实。
     *
     * <p>该锁必须与只更新deleted_at的受控软删互斥，使重放稳定落在成功或10014之一。</p>
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 映射保存的看板ID
     * @return 精确目录；映射外键完整时必须存在
     * @throws IllegalStateException 未加入已有非只读事务
     */
    Optional<DashboardCatalogEntry> findCreationDashboard(UUID projectId, UUID dashboardId);

    /**
     * 原子创建目录、revision 0草稿、完整模型关系和域级幂等恢复映射。
     *
     * @param dashboard 初始目录事实
     * @param draft 初始草稿与模型关系事实
     * @param creationResult 与目录及当前调用身份一致的恢复映射
     */
    void createIdempotent(
            DashboardCatalogEntry dashboard,
            DashboardDraft draft,
            DashboardCreationResult creationResult);

    /**
     * 查询当前项目中未删除的看板目录。
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @return 未删除目录；不存在、跨项目或已删除时为空
     */
    Optional<DashboardCatalogEntry> find(UUID projectId, UUID dashboardId);

    /**
     * 按最近更新时间倒序和稳定看板ID正序分页读取未删除目录。
     *
     * @param projectId 已确权项目ID
     * @param cursor 上一页返回的不透明游标；首页为空
     * @param limit 1至200的页大小
     * @return 有界目录页
     */
    CursorPage<DashboardCatalogEntry> page(UUID projectId, String cursor, int limit);

    /**
     * 按版本号倒序分页读取一个未删除看板的轻量不可变版本元数据。
     *
     * <p>外层Optional区分不可见看板与可见但尚无版本的空页；实现必须在单条数据库语句中形成该分类，
     * 避免READ COMMITTED下先检查目录、再读取历史时与软删产生错误的200空页。</p>
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @param cursor 上一页返回的不透明版本号游标；首页为空
     * @param limit 1至200的页大小
     * @return 不可见时为空；否则为不装载Schema和关系的有界历史页
     */
    Optional<CursorPage<DashboardVersionSummary>> pageVersions(
            UUID projectId, UUID dashboardId, String cursor, int limit);

    /**
     * 修改未删除看板的Console管理名称。
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @param managementName 新管理名称
     * @param updatedBy 操作账号ID
     * @param updatedAt 服务端更新时刻
     * @return 是否命中当前项目中的未删除目录
     */
    boolean rename(UUID projectId, UUID dashboardId, String managementName,
                   UUID updatedBy, Instant updatedAt);

    /**
     * 查询未删除看板的独立草稿及同revision模型关系。
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @return 草稿；不存在、跨项目或目录已删除时为空
     */
    Optional<DashboardDraft> findDraft(UUID projectId, UUID dashboardId);

    /**
     * 以草稿revision完整保存内容，并在同一事务整组替换模型关系。
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @param expectedRevision 调用方读取的草稿revision
     * @param content 完整规范tc.dashboard/v1草稿内容
     * @param modelReferences 从同一规范内容派生的完整有序模型关系
     * @param updatedBy 操作账号ID
     * @param updatedAt 服务端保存时刻
     * @return 在目录行锁内确定的结构化结果
     * @throws IllegalArgumentException models缺省语义、数量、顺序、key或versionId与关系集合不一致
     */
    DashboardDraftSaveResult saveDraft(
            UUID projectId, UUID dashboardId, long expectedRevision, JsonNode content,
            List<DashboardModelReference> modelReferences, UUID updatedBy, Instant updatedAt);

    /**
     * 精确读取未删除看板所属的不可变版本及模型关系。
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @param versionId 版本ID
     * @return 精确版本；归属不符或目录已删除时为空
     */
    Optional<DashboardVersion> findVersion(UUID projectId, UUID dashboardId, UUID versionId);

    /**
     * 在单条数据库观察中区分看板不可见与精确版本不可见。
     *
     * <p>该入口只服务管理详情错误分类；发布、回滚、撤回及软删仍使用各自锁内读取，不能把本次
     * 无锁观察当作发布资格或状态租约。</p>
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @param versionId 精确版本ID
     * @return 看板不可见、版本不可见或完整版本三种封闭结果
     */
    DashboardVersionLookupResult findVersionForManagement(
            UUID projectId, UUID dashboardId, UUID versionId);

    /**
     * 读取未删除看板已分配版本号最大的版本，不把它当作当前发布指针。
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @return 最大历史版本；尚未发布时为空
     */
    Optional<DashboardVersion> findLatestVersion(UUID projectId, UUID dashboardId);

    /**
     * 一次读取看板的草稿、发布和已分配版本号三条轴。
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @return 完整状态；不存在或跨项目时为空，软删除事实仍返回供后续服务拒绝
     */
    Optional<DashboardPublicationState> findPublicationState(UUID projectId, UUID dashboardId);

    /**
     * 锁定看板目录并读取草稿、发布和版本号三条轴。
     *
     * <p>发布调用方须随后重取草稿，回滚调用方须精确读取历史版本，撤回与软删调用方须保留原指针供审计。
     * 锁持续到事务完成，从而把双revision、版本号分配、指针与删除状态串行化。</p>
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @return 完整状态；不存在或跨项目时为空，软删除事实仍返回供服务统一隐藏
     * @throws IllegalStateException 未加入已有非只读事务，或实际连接不是PostgreSQL READ COMMITTED/READ UNCOMMITTED
     */
    Optional<DashboardPublicationState> lockPublicationState(UUID projectId, UUID dashboardId);

    /**
     * 经数据库受控入口追加不可变版本、复制完整草稿模型关系并推进发布指针。
     *
     * @param version 当前锁内重新取得并通过资格的版本候选
     * @param expectedPublicationRevision 调用方读取的发布revision
     * @return 行锁内的封闭发布分类
     * @throws IllegalStateException 未加入已有非只读事务
     */
    DashboardPublicationAppendResult appendPublication(
            DashboardVersion version, long expectedPublicationRevision);

    /**
     * 经数据库受控入口把当前指针切回同看板既有不可变版本并推进发布revision。
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @param targetVersionId 已在锁内重验当前资格的历史版本ID
     * @param expectedPublicationRevision 调用方读取的发布revision
     * @param updatedBy 操作Console账号ID
     * @param updatedAt 服务端状态变化时刻
     * @return 行锁内的封闭回滚分类
     * @throws IllegalStateException 未加入已有非只读事务
     */
    DashboardPublicationRollbackResult rollbackPublication(
            UUID projectId, UUID dashboardId, UUID targetVersionId,
            long expectedPublicationRevision, UUID updatedBy, Instant updatedAt);

    /**
     * 经数据库受控入口清空看板当前发布指针并推进发布revision。
     *
     * <p>撤回不删除、复制或更新不可变版本；成功结果携带撤回前版本ID，供服务核对锁内事实并写最小审计。</p>
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @param expectedPublicationRevision 调用方读取的发布revision
     * @param updatedBy 操作Console账号ID
     * @param updatedAt 服务端状态变化时刻
     * @return 行锁内的封闭撤回分类
     * @throws IllegalStateException 未加入已有非只读事务
     */
    DashboardPublicationWithdrawalResult withdrawPublication(
            UUID projectId, UUID dashboardId, long expectedPublicationRevision,
            UUID updatedBy, Instant updatedAt);

    /**
     * 经数据库受控入口一次性软删看板、清空发布指针并推进发布revision。
     *
     * <p>软删保留草稿、版本与全部关系；成功结果保留原指针和实际删除时刻，供服务核对锁内事实并写审计。</p>
     *
     * @param projectId 已确权项目ID
     * @param dashboardId 看板ID
     * @param expectedPublicationRevision 调用方读取的发布revision
     * @param updatedBy 操作Console账号ID
     * @param deletedAt 服务端软删除时刻，同时作为目录更新时间
     * @return 行锁内的封闭软删分类
     * @throws IllegalStateException 未加入已有非只读事务
     */
    DashboardSoftDeleteResult softDelete(
            UUID projectId, UUID dashboardId, long expectedPublicationRevision,
            UUID updatedBy, Instant deletedAt);
}
