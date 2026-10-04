package com.things.link.issuer.application;

import com.things.link.project.application.CommercialOperatorAccessService;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** 仅运营人员可调用的待审申请接收；不具备审核或签发能力。 */
@Service
public class EnrollmentIntakeService {
    private final CommercialOperatorAccessService operators;
    private final EnrollmentRegistry registry;
    private final AuditLogService audit;

    public EnrollmentIntakeService(CommercialOperatorAccessService operators,
                                   EnrollmentRegistry registry, AuditLogService audit) {
        this.operators = operators;
        this.registry = registry;
        this.audit = audit;
    }

    /** 权限锁、登记事实和提交审计处在同一事务内；重复提交也记录本次操作。 */
    @Transactional
    public EnrollmentRegistry.Registration receive(byte[] envelope, EnrollmentRegistry.Channel channel) {
        UUID actor = operators.requireOperator();
        EnrollmentRegistry.Registration result = registry.register(envelope, channel);
        audit.record(new AuditLogEntry(null, null, actor, "shc_enrollment_request", result.requestId(),
                "shc.enrollment.request.received", Map.of(
                "deploymentId", result.deploymentId().toString(),
                "claimedTenantId", result.tenantId().toString(),
                "submittedChannel", channel.name(),
                "firstChannel", result.channel().name())));
        return result;
    }
}
