package com.things.link.dashboard.domain;

import com.things.link.shared.page.CursorPage;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * WebApp应用目录、独立草稿、创建恢复映射和不可变版本的聚合持久端口。
 *
 * <p>S12-1a1b开放基础事实读写；S12-1c2至S12-1c5新增的版本追加、回滚、撤回和软删只能由发布服务在同一事务
 * 经受控入口完成双revision仲裁与指针或删除状态切换，不能通过基础原语拼出半发布状态。</p>
 */
public interface ApplicationRepository {

    /**
     * 原子创建未发布目录及revision为0的独立草稿。
     *
     * @param application 初始目录，发布revision必须为0且当前版本和删除时刻为空
     * @param draft 与目录同归属、revision为0的初始草稿
     * @throws ApplicationKeyCollisionException appKey命中全局唯一索引；调用方可在原事务中换key有界重试
     */
    void create(ApplicationCatalogEntry application, ApplicationDraft draft);

    /**
     * 串行同一调用身份与幂等键摘要的应用创建。
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
     * 读取同一调用身份与幂等键摘要的既有创建结果。
     *
     * @param tenantId 项目所有者租户ID
     * @param projectId 创建目标项目ID
     * @param accountId 发起创建的Console账号ID
     * @param idempotencyKeyDigest 原始幂等键摘要
     * @return 已持久化映射；首次请求时为空
     */
    Optional<ApplicationCreationResult> findCreationResult(
            UUID tenantId, UUID projectId, UUID accountId, String idempotencyKeyDigest);

    /**
     * 读取并排他锁定映射指向的目录，包括软删除事实。
     *
     * <p>该锁必须阻塞只更新deleted_at的受控软删；返回软删除目录供服务稳定映射10014，
     * 不能使用普通find隐藏该状态，也不能使用不阻塞非键更新的FOR KEY SHARE。</p>
     *
     * @param projectId 已确权项目ID
     * @param applicationId 映射保存的应用ID
     * @return 精确目录；映射外键完整时必须存在
     * @throws IllegalStateException 未加入已有非只读事务
     */
    Optional<ApplicationCatalogEntry> findCreationApplication(
            UUID projectId, UUID applicationId);

    /**
     * 原子创建目录、revision 0草稿和域级幂等恢复映射。
     *
     * @param application 初始目录事实
     * @param draft 初始草稿事实
     * @param creationResult 与目录及当前调用身份一致的恢复映射
     * @throws ApplicationKeyCollisionException appKey命中全局唯一索引
     */
    void createIdempotent(
            ApplicationCatalogEntry application,
            ApplicationDraft draft,
            ApplicationCreationResult creationResult);

    /**
     * 查询当前项目中未删除的应用目录。
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @return 未删除目录；不存在、跨项目或已删除时为空
     */
    Optional<ApplicationCatalogEntry> find(UUID projectId, UUID applicationId);

    /**
     * 按最近更新时间倒序和稳定应用ID正序分页读取当前项目的未删除目录。
     *
     * @param projectId 已确权项目ID
     * @param cursor 上一页返回的不透明游标；首页为空
     * @param limit 1至200的页大小
     * @return 有界目录页
     */
    CursorPage<ApplicationCatalogEntry> page(UUID projectId, String cursor, int limit);

    /**
     * 按版本号倒序分页读取一个未删除应用的轻量不可变版本元数据。
     *
     * <p>外层Optional区分不可见应用与可见但尚无版本的空页；实现必须在单条数据库语句中形成该分类，
     * 避免READ COMMITTED下先检查目录、再读取历史时与软删产生错误的200空页。</p>
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @param cursor 上一页返回的不透明版本号游标；首页为空
     * @param limit 1至200的页大小
     * @return 不可见时为空；否则为不装载ApplicationSnapshot的有界历史页
     */
    Optional<CursorPage<ApplicationVersionSummary>> pageVersions(
            UUID projectId, UUID applicationId, String cursor, int limit);

    /**
     * 修改未删除应用的Console管理名称。
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @param managementName 新管理名称
     * @param updatedBy 操作账号ID
     * @param updatedAt 服务端更新时刻
     * @return 是否命中当前项目中的未删除目录
     */
    boolean rename(UUID projectId, UUID applicationId, String managementName,
                   UUID updatedBy, Instant updatedAt);

    /**
     * 查询未删除应用的独立草稿。
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @return 草稿；不存在、跨项目或目录已删除时为空
     */
    Optional<ApplicationDraft> findDraft(UUID projectId, UUID applicationId);

    /**
     * 以草稿revision执行单语句CAS完整保存。
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @param expectedRevision 调用方读取的草稿revision
     * @param content 完整tc.application/v1草稿内容
     * @param updatedBy 操作账号ID
     * @param updatedAt 服务端保存时刻
     * @return 与目录锁和CAS更新来自同一SQL语句的结构化结果
     */
    ApplicationDraftSaveResult saveDraft(UUID projectId, UUID applicationId, long expectedRevision,
                                         JsonNode content, UUID updatedBy, Instant updatedAt);

    /**
     * 精确读取未删除应用所属的不可变版本。
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @param versionId 版本ID
     * @return 精确版本；归属不符或目录已删除时为空
     */
    Optional<ApplicationVersion> findVersion(UUID projectId, UUID applicationId, UUID versionId);

    /**
     * 在单条数据库观察中区分应用不可见与精确版本不可见。
     *
     * <p>该入口只服务管理详情错误分类；发布、回滚、撤回及软删仍使用各自锁内读取，不能把本次
     * 无锁观察当作发布资格或状态租约。</p>
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @param versionId 精确版本ID
     * @return 应用不可见、版本不可见或完整版本三种封闭结果
     */
    ApplicationVersionLookupResult findVersionForManagement(
            UUID projectId, UUID applicationId, UUID versionId);

    /**
     * 读取未删除应用已分配版本号最大的版本，不把它当作当前发布指针。
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @return 最大历史版本；尚未发布时为空
     */
    Optional<ApplicationVersion> findLatestVersion(UUID projectId, UUID applicationId);

    /**
     * 一次读取应用的草稿、发布和已分配版本号三条轴；该观察不是锁或发布资格。
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @return 完整持久状态；不存在或跨项目时为空，软删除事实仍显式返回供后续服务拒绝
     */
    Optional<ApplicationPublicationState> findPublicationState(UUID projectId, UUID applicationId);

    /**
     * 锁定应用目录与草稿并读取双revision、指针和历史最大版本号。
     *
     * <p>锁持续到调用方事务结束；发布与回滚须继续锁定引用看板并重建候选，撤回与软删须保留原指针供审计。</p>
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @return 完整状态；不存在或跨项目时为空，软删除事实仍返回供服务统一隐藏
     * @throws IllegalStateException 未加入已有非只读事务，或实际连接不是PostgreSQL READ COMMITTED/READ UNCOMMITTED
     */
    Optional<ApplicationPublicationState> lockPublicationState(UUID projectId, UUID applicationId);

    /**
     * 经数据库受控入口追加不可变应用版本、完整精确看板关系并推进发布指针。
     *
     * <p>关系由版本快照的dashboardRefs唯一派生，并由数据库与锁内草稿及目标版本再次核对。</p>
     *
     * @param version 当前锁内重新生成并通过宿主资格的应用版本候选
     * @param expectedPublicationRevision 调用方读取的发布revision
     * @return 数据库锁内得出的封闭发布分类
     * @throws IllegalStateException 未加入已有非只读事务
     */
    ApplicationPublicationAppendResult appendPublication(
            ApplicationVersion version, long expectedPublicationRevision);

    /**
     * 经数据库受控入口把当前指针切回同应用既有不可变版本并推进发布revision。
     *
     * <p>目标版本内容不会重写；数据库按目标快照再次锁定并核对全部精确看板关系。</p>
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @param targetVersionId 已在应用和看板锁内重验当前资格的历史版本ID
     * @param expectedPublicationRevision 调用方读取的发布revision
     * @param updatedBy 操作Console账号ID
     * @param updatedAt 服务端状态变化时刻
     * @return 数据库锁内得出的封闭回滚分类
     * @throws IllegalStateException 未加入已有非只读事务
     */
    ApplicationPublicationRollbackResult rollbackPublication(
            UUID projectId, UUID applicationId, UUID targetVersionId,
            long expectedPublicationRevision, UUID updatedBy, Instant updatedAt);

    /**
     * 经数据库受控入口清空应用当前发布指针并推进publicationRevision。
     *
     * <p>撤回不删除、复制或改写不可变应用版本及其精确看板关系；成功结果携带原版本ID，供服务核对锁内事实并写最小审计。</p>
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @param expectedPublicationRevision 调用方读取的发布revision
     * @param updatedBy 操作Console账号ID
     * @param updatedAt 服务端状态变化时刻
     * @return 数据库锁内得出的封闭撤回分类
     * @throws IllegalStateException 未加入已有非只读事务
     */
    ApplicationPublicationWithdrawalResult withdrawPublication(
            UUID projectId, UUID applicationId, long expectedPublicationRevision,
            UUID updatedBy, Instant updatedAt);

    /**
     * 经数据库受控入口一次性软删应用、清空发布指针并推进publicationRevision。
     *
     * <p>软删保留草稿、不可变版本与全部精确看板关系；成功结果保留原指针和实际删除时刻，供服务核对并写最小审计。</p>
     *
     * @param projectId 已确权项目ID
     * @param applicationId 应用ID
     * @param expectedPublicationRevision 调用方读取的发布revision
     * @param updatedBy 操作Console账号ID
     * @param deletedAt 服务端软删除时刻，同时作为目录更新时间
     * @return 数据库锁内得出的封闭软删分类
     * @throws IllegalStateException 未加入已有非只读事务
     */
    ApplicationSoftDeleteResult softDelete(
            UUID projectId, UUID applicationId, long expectedPublicationRevision,
            UUID updatedBy, Instant deletedAt);
}
