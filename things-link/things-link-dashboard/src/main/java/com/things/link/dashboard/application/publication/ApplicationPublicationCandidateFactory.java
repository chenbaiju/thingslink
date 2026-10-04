package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.domain.ApplicationDraft;
import com.things.link.dashboard.domain.ApplicationVersion;

/** 从指定持久应用草稿准备包含精确看板引用的零写入规范候选。 */
public interface ApplicationPublicationCandidateFactory {

    /**
     * 重校草稿、读取精确看板版本并生成PostgreSQL权威应用快照摘要。
     *
     * @param draft 已由调用方按指定revision取得的持久应用草稿
     * @return 尚未写入版本、关系、指针或审计的候选
     * @throws ApplicationPublicationQualificationException 草稿、看板或聚合需求不满足冻结合同时抛出
     */
    ApplicationPublicationCandidate prepare(ApplicationDraft draft);

    /**
     * 在已有可写事务中按稳定看板ID顺序锁定全部引用，再重建同一规范候选。
     *
     * @param draft 已在应用目录与草稿行锁内重新读取的持久草稿
     * @return 持有全部引用看板锁期间完成校验的零写入候选
     * @throws ApplicationPublicationQualificationException 草稿、看板或聚合需求不满足冻结合同时抛出
     * @throws IllegalStateException 未加入已有可写事务
     */
    ApplicationPublicationCandidate prepareLocked(ApplicationDraft draft);

    /**
     * 在已有可写事务中从既有不可变应用版本锁定全部精确看板引用并重建当前资格候选。
     *
     * @param version 已在同应用目录锁内读取的回滚目标版本
     * @return 与目标快照、摘要和精确看板历史完全一致的零写入候选
     * @throws ApplicationPublicationQualificationException 目标内容或引用不再满足冻结合同时抛出
     * @throws IllegalStateException 未加入已有RC/RU可写事务
     */
    ApplicationPublicationCandidate prepareHistoricalVersionLocked(ApplicationVersion version);
}
