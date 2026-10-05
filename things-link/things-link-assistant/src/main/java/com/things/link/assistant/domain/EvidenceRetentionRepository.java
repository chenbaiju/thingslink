package com.things.link.assistant.domain;

import java.util.List;
import java.util.UUID;

/** 只回收自有到期事实，不接受任意表、年龄或删除条件。 */
public interface EvidenceRetentionRepository {
    /** 来自固定候选端口的真实项目身份，不包含创建者或正文。 */
    record Scope(UUID tenantId, UUID projectId) {}

    /** @param afterProject 上轮项目游标，空值从头扫描 @return 最多100个可读项目的到期身份 */
    List<Scope> candidates(UUID afterProject);

    /** @param scope 已建立双轴隔离且持有生命周期锁的项目 @param limit 本轮剩余预算，1至500 @return 实际删除行数 */
    int deleteExpired(Scope scope, int limit);
}
