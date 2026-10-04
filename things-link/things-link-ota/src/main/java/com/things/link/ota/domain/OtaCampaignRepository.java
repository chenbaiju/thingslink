package com.things.link.ota.domain;

import com.things.link.shared.page.CursorPage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 完整活动事实事务仓储，全部方法加入调用方事务。 */
public interface OtaCampaignRepository {
    /** 同账号创建恢复键的事务锁。 */
    void lockCreation(UUID tenant, UUID project, UUID account, String keyDigest);
    /** 恢复键仅返回持久身份和请求摘要。 */
    Optional<Creation> findCreation(UUID project, UUID account, String keyDigest);
    /** 当前活动及完整规范快照。 */
    Optional<OtaCampaign> find(UUID project, UUID campaign, boolean exclusive);
    /** 控制台只读列表：按创建时间与身份倒序键集分页，绝不读取规范计划或清单字节。
     * @param project 精确项目
     * @param cursor 上一页返回的不透明位置，首页为空
     * @param limit 单页上限，1到100
     * @return 摘要游标页，游标绑定项目避免跨项目复用
     */
    CursorPage<Summary> search(UUID project, String cursor, int limit);
    /** 冻结批次只读投影，按批次号升序，计数只聚合真实作业状态。
     * @param project 精确项目
     * @param campaign 精确活动身份，调用方已确认归属
     * @return 批次投影，草稿活动为空列表
     */
    List<Batch> batches(UUID project, UUID campaign);
    /** 创建草稿、恢复映射、初始转移及待交付事件，同事务全有或全无。 */
    void create(OtaCampaign campaign, String keyDigest, String requestDigest);
    /** 冻结排序名单及稳定批次；设备占位冲突不部分写入。 */
    boolean schedule(long expectedRevision, OtaCampaign frozen, List<Target> targets, int batchSize,
            UUID actor, Instant occurredAt);
    /** 仅取消草稿或全部未派发的排程，完整记录中间转移。 */
    boolean cancelUndispatched(long expectedRevision, OtaCampaign current, UUID actor, Instant occurredAt,
            String reason);
    /** 最多一千个冻结目标，按规范UUID文本排序。 */
    List<Target> targets(UUID project, UUID campaign);
    /** 返回真实作业状态与稳定批次归属。 */
    List<Job> jobs(UUID project, UUID campaign);
    /** 控制台作业列表：按UUIDv7身份倒序键集分页，只投影运行状态机白名单列。
     * @param project 精确项目
     * @param campaign 精确活动身份，调用方已确认归属
     * @param cursor 上一页返回的不透明位置，首页为空
     * @param limit 单页上限，1到200
     * @return 摘要游标页，游标绑定项目避免跨项目复用
     */
    CursorPage<JobSummary> jobs(UUID project, UUID campaign, String cursor, int limit);
    /** 设备维度作业列表：按UUIDv7身份倒序键集分页，游标绑定项目与设备，禁止跨设备续页。
     * @param project 精确项目
     * @param device 精确设备身份，调用方已确认其存在
     * @param cursor 上一页返回的不透明位置，首页为空
     * @param limit 单页上限，1到100
     * @return 摘要游标页；空历史返回空页而不是错误
     */
    CursorPage<JobSummary> jobsByDevice(UUID project, UUID device, String cursor, int limit);
    /** 单个作业与不可变转移时间线；未知、跨项目或不属该活动都不返回事实。
     * @param project 精确项目
     * @param campaign 精确活动身份
     * @param job 精确作业身份
     * @return 作业详情；范围不匹配时为空
     */
    Optional<JobDetail> job(UUID project, UUID campaign, UUID job);
    /** 冻结作业投影。
     * @param id 作业身份
     * @param batchNumber 稳定批次号
     * @param deviceId 目标设备
     * @param deviceTypeId 目标类型
     * @param thingModelVersionId 当时绑定，可空
     * @param credentialVersion 当时代际
     * @param status 持久作业状态
     */
    record Job(UUID id, int batchNumber, UUID deviceId, UUID deviceTypeId,
            UUID thingModelVersionId, long credentialVersion, String status) { }
    /** 控制台作业摘要白名单；刻意不含租户、租约令牌、规范字节与资格报告摘要。
     * @param id 作业身份
     * @param batchNumber 从1开始的稳定批次号
     * @param deviceId 目标设备
     * @param deviceTypeId 目标类型
     * @param thingModelVersionId 冻结时绑定，可空
     * @param credentialVersion 冻结时代际
     * @param status 持久作业状态
     * @param attemptNo 当前尝试代次，首次派发为一
     * @param stateVersion 独立业务修订
     * @param failureCode 稳定失败或跳过原因，可空
     * @param firstDispatchedAt 首次成功派发时间，重试不刷新，可空
     * @param dispatchedAt 当前尝试派发时间，可空
     * @param deadlineAt 当前阶段期限，可空
     * @param nextAttemptAt 下次可领取时间，可空
     */
    record JobSummary(UUID id, int batchNumber, UUID deviceId, UUID deviceTypeId, UUID thingModelVersionId,
            long credentialVersion, String status, int attemptNo, long stateVersion, String failureCode,
            Instant firstDispatchedAt, Instant dispatchedAt, Instant deadlineAt, Instant nextAttemptAt) { }
    /** 作业不可变转移事实；to_revision从1起连续递增，既是历史顺序也是链式证据。
     * @param fromStatus 转移前状态
     * @param toStatus 转移后状态
     * @param fromRevision 期望业务修订
     * @param toRevision 新业务修订
     * @param actorKind 真实主体类型
     * @param actorId 真实管理账号，SYSTEM转移为空
     * @param occurredAt 数据库业务转移时间
     * @param reason 稳定转移原因，可空
     */
    record JobTransition(String fromStatus, String toStatus, long fromRevision, long toRevision, String actorKind,
            UUID actorId, Instant occurredAt, String reason) { }
    /** 作业详情：摘要加按to_revision升序的真实转移时间线。
     * @param summary 摘要投影
     * @param transitions 不可变转移时间线，最多保留最早的200条
     */
    record JobDetail(JobSummary summary, List<JobTransition> transitions) {
        /** 防御复制，避免外部修改时间线。 */
        public JobDetail { transitions = List.copyOf(transitions); }
    }
    /** 创建幂等恢复事实。
     * @param campaignId 原活动身份
     * @param requestDigest 原计划请求摘要
     */
    record Creation(UUID campaignId, String requestDigest) { }
    /** 控制台列表白名单投影；刻意不含租户、计划、清单、发布或创建账号。
     * @param id 活动身份
     * @param firmwareId 固件身份
     * @param status 活动状态
     * @param stateVersion 状态修订
     * @param targetCount 冻结目标数量，草稿为零
     * @param batchCount 冻结批次数量，草稿为零
     * @param createdAt 创建时间
     * @param updatedAt 最近转移时间
     * @param scheduledAt 首次排程时间，可空
     * @param cancelledAt 取消完成时间，可空
     */
    record Summary(UUID id, UUID firmwareId, String status, long stateVersion, int targetCount, int batchCount,
            Instant createdAt, Instant updatedAt, Instant scheduledAt, Instant cancelledAt) { }
    /** 冻结批次读投影：批次行事实加真实作业计数，终结信息来自不可变批次转移。
     * @param batchNumber 从1开始的稳定批次号
     * @param status 持久批次状态
     * @param targetCount 冻结目标数量
     * @param succeededCount 真实成功作业数
     * @param rolledBackCount 真实安全回退作业数
     * @param skippedCount 未准入跳过作业数
     * @param timedOutCount 明确失败且预算耗尽的作业数
     * @param cancelledCount 取消作业数
     * @param completedAt 终态批次转移时间，尚未终结为空
     * @param outcome 终态批次结论，尚未终结为空
     */
    record Batch(int batchNumber, String status, int targetCount, long succeededCount, long rolledBackCount,
            long skippedCount, long timedOutCount, long cancelledCount, Instant completedAt, String outcome) { }
    /** 排程时设备事实；不保存或声称当前升级资格。
     * @param deviceId 目标设备
     * @param deviceTypeId 目标类型
     * @param thingModelVersionId 当时绑定，可空
     * @param credentialVersion 当时代际
     */
    record Target(UUID deviceId, UUID deviceTypeId, UUID thingModelVersionId, long credentialVersion) { }
    /** 跨活动设备预占冲突，在写入之前明确判断。 */
    final class TargetOccupiedException extends RuntimeException {
        /** 固定业务边界，不暴露其他活动内容。 */
        public TargetOccupiedException() { super("目标设备已有非终态OTA作业"); }
    }
}
