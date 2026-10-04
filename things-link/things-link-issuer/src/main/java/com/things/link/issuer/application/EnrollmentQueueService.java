package com.things.link.issuer.application;

import com.things.link.project.application.CommercialOperatorAccessService;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 运营人员查看待审事实；读取不改变状态或构成审核。 */
@Service
public class EnrollmentQueueService {
    private final CommercialOperatorAccessService operators;
    private final EnrollmentQueue queue;
    private final AuditLogService audit;

    public EnrollmentQueueService(CommercialOperatorAccessService operators,
                                  EnrollmentQueue queue, AuditLogService audit) {
        this.operators = operators;
        this.queue = queue;
        this.audit = audit;
    }

    @Transactional
    public List<EnrollmentQueue.Pending> page(int limit, Instant beforeReceivedAt, UUID beforeRequestId) {
        UUID actor = operators.requireOperator();
        List<EnrollmentQueue.Pending> result = queue.page(limit, beforeReceivedAt, beforeRequestId);
        audit.record(new AuditLogEntry(null, null, actor, "shc_enrollment_queue", null,
                "shc.enrollment.queue.viewed", Map.of("limit", limit, "returned", result.size())));
        return result;
    }
}
