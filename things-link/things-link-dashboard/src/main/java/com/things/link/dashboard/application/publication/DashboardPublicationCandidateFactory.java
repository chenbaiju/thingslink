package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.domain.DashboardDraft;
import com.things.link.dashboard.domain.DashboardVersion;

/** 从已持久化草稿或不可变历史版本准备尚待外部资格核验的发布候选。 */
public interface DashboardPublicationCandidateFactory {

    /**
     * 重跑完整内部Schema校验并绑定草稿身份、修订与数据库权威摘要。
     *
     * @param draft 已在发布事务中读取或锁定的持久化草稿
     * @return 不代表已发布或外部资格通过的不可变候选
     */
    DashboardPublicationCandidate prepare(DashboardDraft draft);

    /**
     * 重跑不可变历史版本的完整内部Schema校验，并复核封存摘要和派生清单。
     *
     * @param version 受控回滚准备恢复的同看板历史版本
     * @return 不代表当前外部资格仍然成立的不可变候选
     */
    DashboardPublicationCandidate prepareHistoricalVersion(DashboardVersion version);
}
