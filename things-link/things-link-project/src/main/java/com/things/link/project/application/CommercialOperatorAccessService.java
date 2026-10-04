package com.things.link.project.application;

import com.things.link.project.domain.CommercialOperatorRepository;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 平台商业权限与项目角色独立；菜单查询不能代替原写事务中的复核。 */
@Service
public class CommercialOperatorAccessService {
    private final CommercialOperatorRepository repository;
    /** @param repository 受限授权函数 */
    public CommercialOperatorAccessService(CommercialOperatorRepository repository) { this.repository=repository; }
    /** IAM在确认Console账号后查询菜单，权限不放进JWT或缓存。 */
    @Transactional(readOnly=true)
    public boolean isOperator(UUID accountId) { return accountId != null && repository.enabled(accountId,false); }
    /** 当前请求主体来自Console安全链；锁持续至租户调整及审计提交。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID requireOperator() {
        UUID actor=TenantContext.current().map(scope -> scope.accountId()).orElse(null);
        if (actor == null || !repository.enabled(actor,true))
            throw new BusinessException(ProjectErrorCode.COMMERCIAL_OPERATOR_REQUIRED);
        return actor;
    }
}
