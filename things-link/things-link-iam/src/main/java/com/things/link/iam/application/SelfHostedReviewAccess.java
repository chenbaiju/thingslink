package com.things.link.iam.application;

import java.util.UUID;

/** 发行方可选实现的独立审核资格查询；仅供界面菜单显示，不能代替审核事务授权。 */
public interface SelfHostedReviewAccess {
    boolean isReviewer(UUID accountId);
}
