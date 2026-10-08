package com.things.link.export.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 项目导出任务、尝试租约和孤儿清理持久化端口。 */
public interface ProjectExportJobRepository {

    /** @return true表示建立新任务，false表示已有同代非终态任务 */
    boolean create(UUID id, UUID tenantId, UUID projectId, long generation, UUID requesterAccountId);

    /** @return 同项目代次唯一非终态任务 */
    Optional<ProjectExportJob> findActive(UUID tenantId, UUID projectId, long generation);

    /**
     * 查询当前项目代次由指定账号申请的最新一条任务，包含终态。
     * @param tenantId 当前权威租户
     * @param projectId 已确权删除项目
     * @param generation 当前删除代次
     * @param requesterAccountId 当前请求账号
     * @return 最新任务或空，不创建任务
     */
    Optional<ProjectExportJob> findLatest(UUID tenantId, UUID projectId, long generation, UUID requesterAccountId);

    /** @return 完整持久身份匹配的任务 */
    Optional<ProjectExportJob> findByIdentity(UUID tenantId, UUID projectId, UUID exportId);

    /**
     * 按锁后项目身份和原requester锁定仍可签名的成功任务。
     *
     * <p>锁后必须用数据库时钟再检查到期点，并把采用cleanup的可领取时刻
     * 至少推到五分钟后；事务回滚时该保护也必须回滚。</p>
     *
     * @return 任务与数据库时钟产生的五分钟截止；锁后已到期时为空
     */
    Optional<ProjectExportDownloadClaim> lockDownloadCandidate(
            UUID tenantId, UUID projectId, UUID exportId, long generation, UUID requesterAccountId);

    /** @param workerName worker实例名 @return 一条已提交两分钟租约的任务 */
    Optional<ProjectExportClaim> claimReady(String workerName);

    /** @return 仅当前未过期token成功续租时为true */
    boolean renew(UUID exportId, UUID leaseToken);

    /**
     * 上传前登记独立清理事实并保存本次快照和upload身份。
     * @return 当前token仍有效且登记成功时为true
     */
    boolean registerUpload(UUID exportId, UUID leaseToken, UUID cleanupId, UUID uploadId,
                           String objectKey, Instant snapshotAt);

    /** @return 当前token成功采用对象并终结任务时为true */
    boolean complete(UUID exportId, UUID leaseToken, UUID uploadId, String objectKey,
                     long objectSize, String objectSha256);

    /** @return 当前token成功退避或永久失败时为true */
    boolean fail(UUID exportId, UUID leaseToken, String failureCode, boolean permanent);

    /** @return 一条已提交租约的待删孤儿对象 */
    Optional<ProjectExportCleanupClaim> claimCleanup();

    /** @return 当前清理token成功确认删除时为true */
    boolean completeCleanup(UUID cleanupId, UUID leaseToken);

    /** @return 当前清理token成功保留退避时为true */
    boolean failCleanup(UUID cleanupId, UUID leaseToken, String failureCode);

    /** @return 一条已到期、并已对采用对象提交两分钟租约的领取 */
    Optional<ProjectExportExpiryClaim> claimExpired();

    /** @return 对象已删除且当前token原子收束cleanup与任务时为true */
    boolean completeExpired(UUID exportId, UUID cleanupId, UUID leaseToken);

    /** @return 当前到期清理token成功保留退避时为true */
    boolean failExpired(UUID cleanupId, UUID leaseToken, String failureCode);
}
