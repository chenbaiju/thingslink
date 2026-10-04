package com.things.link.project.application;

import com.things.link.project.domain.AutomationQuotaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.UUID;

/** ADR0153：只接受已确权后台资源范围；事务RLS与项目许可由调用者建立。 */
@Service
public class AutomationQuotaService {
    private final AutomationQuotaRepository repository;
    public AutomationQuotaService(AutomationQuotaRepository repository) { this.repository = repository; }

    /** 发布只检查开关，锁定当前策略直到调用者原事务提交，不扣执行次数。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enabled(UUID tenantId, UUID projectId) { return repository.enabled(tenantId,projectId); }

    /** 数据库错误原样回滚；NOT_ENABLED等确定性拒绝不会产生预留。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation reserve(UUID tenantId, UUID projectId, UUID executionId) {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(projectId); Objects.requireNonNull(executionId);
        if (executionId.version() != 7) throw new IllegalArgumentException("执行ID必须为UUIDv7");
        return Reservation.valueOf(repository.reserve(tenantId, projectId, executionId));
    }
    /** 封闭结果，ALREADY_RESERVED包括跨UTC日重试。 */
    public enum Reservation { RESERVED, ALREADY_RESERVED, NOT_ENABLED, QUOTA_EXCEEDED, SCOPE_REJECTED }
}
