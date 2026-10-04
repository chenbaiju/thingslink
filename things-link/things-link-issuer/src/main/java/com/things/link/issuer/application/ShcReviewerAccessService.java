package com.things.link.issuer.application;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.iam.application.SelfHostedReviewAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 自部署审核专用资格；控制台身份和数据库授权在写事务内同时复核。 */
@Service
public class ShcReviewerAccessService implements SelfHostedReviewAccess {
    private final ShcReviewerRepository repository;

    public ShcReviewerAccessService(ShcReviewerRepository repository) {
        this.repository = repository;
    }

    /** 菜单查询不持锁；提交审核仍走 requireReviewer 的持锁校验。 */
    @Override
    @Transactional(readOnly = true)
    public boolean isReviewer(UUID accountId) {
        return accountId != null && repository.enabled(accountId, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID requireReviewer() {
        UUID actor = TenantContext.current().map(scope -> scope.accountId()).orElse(null);
        if (actor == null || !repository.enabled(actor, true)) {
            throw new BusinessException(ShcReviewerErrorCode.REVIEWER_REQUIRED);
        }
        return actor;
    }
}
