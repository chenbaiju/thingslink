package com.things.link.ota.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 已认证DIRECT下载申请接纳仓储，不授予下载额度或生成对象地址。 */
public interface OtaDownloadRequestRepository {
    /** 仅在已建立真实RLS后定位作业，不作为跨域scope引导端口。 */
    Optional<JobContext> locate(UUID jobId);
    /** 当前作用域下读取同设备请求恢复事实，重放不更新任何时间。 */
    Optional<Request> find(UUID deviceId, UUID requestId);
    /** 同一作业尝试只接纳一个请求ID。 */
    Optional<Request> findByJobAttempt(UUID jobId, int attemptNo);
    /** 当前运行图和数据库时钟CAS，接纳与不可变排队事实同事务。 */
    boolean create(Request request, long expectedJobRevision);
    /** 当前已确权设备持锁期间，精确作业围栏安全暂停；不伪造待准入租约。 */
    boolean safetyPause(JobContext context, long expectedCampaignRevision, String reason);

    /** 不可变申请接纳事实。
     * @param id 平台内部接纳身份
     * @param tenantId 真实租户
     * @param projectId 真实项目
     * @param deviceId 认证设备
     * @param credentialVersion 原认证代际
     * @param requestId 设备请求身份
     * @param jobId 目标作业
     * @param campaignId 所属活动
     * @param firmwareId 固件身份
     * @param attemptNo 原作业尝试
     * @param manifestSha256 原清单摘要
     * @param canonical 规范申请字节
     * @param canonicalSha256 规范申请摘要
     * @param originalDeadline 原作业期限
     * @param brokerReceivedAt 原Broker接收时间
     * @param acceptedAt 数据库时钟接纳时间
     * @param reportRevision 资格报告修订
     * @param reportHash 资格报告摘要
     */
    record Request(UUID id, UUID tenantId, UUID projectId, UUID deviceId, long credentialVersion,
            UUID requestId, UUID jobId, UUID campaignId, UUID firmwareId, int attemptNo, String manifestSha256,
            byte[] canonical, String canonicalSha256, Instant originalDeadline, Instant brokerReceivedAt,
            Instant acceptedAt, long reportRevision, String reportHash) {
        /** 冻结输入字节。 */
        public Request { canonical = canonical.clone(); }
        /** 返回独立字节副本。 */
        @Override public byte[] canonical() { return canonical.clone(); }
    }
    /** 仅当前RLS内的作业定位事实。
     * @param tenantId 权威租户
     * @param projectId 权威项目
     * @param deviceId 权威目标设备
     * @param campaignId 活动
     * @param firmwareId 固件
     * @param jobId 作业
     * @param jobStatus 当前作业状态
     * @param jobRevision 当前作业修订
     * @param attemptNo 原尝试号
     * @param credentialVersion 原通知身份代际
     * @param manifestSha256 原清单摘要
     * @param originalDeadline 原作业期限
     */
    record JobContext(UUID tenantId, UUID projectId, UUID deviceId, UUID campaignId, UUID firmwareId,
            UUID jobId, String jobStatus, long jobRevision, int attemptNo, long credentialVersion,
            String manifestSha256, Instant originalDeadline) { }
}
