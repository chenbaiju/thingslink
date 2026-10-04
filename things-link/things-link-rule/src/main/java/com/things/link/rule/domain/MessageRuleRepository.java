package com.things.link.rule.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 规则定义与不可变版本事实的持久化端口。 */
public interface MessageRuleRepository {
    /** 持有定义行锁直到原事务结束，避免并发修订争抢同一版本号。 */
    Optional<MessageRule> lock(UUID projectId, UUID ruleId);
    /** 目录keyset分页；最多limit行，不返回源码。 */
    List<MessageRule> search(UUID projectId, String name, String status, java.time.Instant beforeTime, UUID beforeId, int limit);
    /** 按版本号倒序读取有界历史。 */
    List<MessageRuleVersion> history(UUID projectId, UUID ruleId, Long beforeVersion, int limit);


    /** @return 创建定义和首个版本是否各写入一行 */
    boolean create(MessageRule rule, MessageRuleVersion initialVersion);

    /** @return 当前项目内未删除规则 */
    Optional<MessageRule> find(UUID projectId, UUID ruleId);

    /** @return 指定规则的版本；跨规则或跨项目返回空 */
    Optional<MessageRuleVersion> findVersion(UUID projectId, UUID ruleId, UUID versionId);

    /** @return 按版本号升序排列的完整版本历史 */
    List<MessageRuleVersion> versions(UUID projectId, UUID ruleId);

    /** @return 下一展示版本号；调用方必须先成功取得定义 CAS 写资格 */
    long nextVersionNumber(UUID projectId, UUID ruleId);

    /** @return 定义 CAS 成功且新版本已追加 */
    boolean revise(MessageRule replacement, long expectedVersion, MessageRuleVersion newVersion);

    /** @return 活动版本指针 CAS 是否成功 */
    boolean activate(UUID projectId, UUID ruleId, UUID versionId, long expectedVersion);

    /** @return 活动规则暂停 CAS 是否成功 */
    boolean pause(UUID projectId, UUID ruleId, long expectedVersion);

    /** @return 软删除 CAS 是否成功 */
    boolean softDelete(UUID projectId, UUID ruleId, long expectedVersion);
}
