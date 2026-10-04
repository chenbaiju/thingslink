package com.things.link.project.application;

import com.things.link.project.domain.*;
import com.things.link.project.domain.plan.ResourcePackageDimension;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ADR0164：先锁定可信平台操作者，再操作显式目标租户；不借用项目角色。 */
@Service
@Transactional
public class CommercialOperationsService {
    private final CommercialOperatorAccessService access;
    private final CommercialOperatorRepository operators;
    private final TenantOrderRepository tenants;
    private final TenantSubscriptionLifecycleRepository subscriptions;
    private final TenantResourcePackageRepository packages;
    private final TenantEntitlementAdjustmentService adjustments;

    public CommercialOperationsService(CommercialOperatorAccessService access, CommercialOperatorRepository operators,
            TenantOrderRepository tenants, TenantSubscriptionLifecycleRepository subscriptions,
            TenantResourcePackageRepository packages, TenantEntitlementAdjustmentService adjustments) {
        this.access=access; this.operators=operators; this.tenants=tenants;
        this.subscriptions=subscriptions; this.packages=packages; this.adjustments=adjustments;
    }

    /** 单语句读取版本和当前额度；金额和版本均作为精确十进制字符串。 */
    public Preview preview(UUID tenantId) {
        access.requireOperator();
        requireTenant(tenantId);
        var snapshot=operators.snapshot(tenantId).orElseThrow(() ->
                new BusinessException(ProjectErrorCode.PLAN_CAPACITY_UNAVAILABLE));
        var dimensions=java.util.Arrays.stream(ResourcePackageDimension.values()).filter(d -> d.fixedUnit()!=null)
                .map(d -> new Dimension(d.name(),d.fixedUnit(),d.window(),snapshot.effectiveAmounts().get(d.name()))).toList();
        return new Preview(tenantId,snapshot.tenantName(),Long.toString(snapshot.assignmentVersion()),dimensions);
    }

    /** 旧键恢复先于新审批版本校验；领域服务仍逐项检查旧请求，不能用重放改事实。 */
    public Adjustment create(UUID tenantId, String dimension, String amount, Instant startsAt, Instant endsAt,
            String reason, String key, String expectedVersion) {
        UUID actor=access.requireOperator();
        requireTenant(tenantId);
        long quantity=parseInteger(amount,false);
        long expected=parseInteger(expectedVersion,true);
        long current=subscriptions.lockTenantAndReadAssignmentVersion(tenantId);
        if (packages.findAdjustmentByKey(tenantId,key).isEmpty()) {
            if (current!=expected) throw new BusinessException(ProjectErrorCode.COMMERCIAL_APPROVAL_STALE);
            if (operators.snapshot(tenantId).isEmpty()) throw new BusinessException(ProjectErrorCode.PLAN_CAPACITY_UNAVAILABLE);
        }
        var result=adjustments.createAdjustment(tenantId,new EntitlementAdjustmentRequest(
                dimension,quantity,startsAt,endsAt,reason,actor,key));
        return view(packages.findById(result.adjustmentId()).orElseThrow());
    }

    /** 状态恢复不按有效期筛选：未知结果、已到期、已撤销都能取回原事实。 */
    public Adjustment byKey(UUID tenantId,String key) {
        access.requireOperator();
        return view(packages.findAdjustmentByKey(tenantId,key).orElseThrow(() ->
                new BusinessException(ProjectErrorCode.ADJUSTMENT_NOT_FOUND)));
    }

    public boolean revoke(UUID tenantId,UUID adjustmentId,String reason) {
        UUID actor=access.requireOperator();
        requireTenant(tenantId);
        subscriptions.lockTenantAndReadAssignmentVersion(tenantId);
        return adjustments.revokeAdjustment(tenantId,adjustmentId,actor,reason);
    }

    private void requireTenant(UUID tenantId) {
        if (!tenants.tenantExists(tenantId)) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,"租户不存在");
    }
    private static long parseInteger(String value,boolean allowZero) {
        if (value==null || !value.matches("0|[1-9][0-9]{0,18}"))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,"数量及版本必须为精确十进制整数字符串");
        try {
            long result=Long.parseLong(value);
            if (result==0 && !allowZero) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException invalid) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,"数量及版本超出允许的64位整数范围");
        }
    }
    private static Adjustment view(TenantResourcePackage value) {
        var metadata=value.adjustment();
        return new Adjustment(value.id(),value.tenantId(),value.dimensionCode(),Long.toString(value.amount()),
                value.unit(),value.window(),value.startsAt(),value.endsAt(),value.status().name(),
                metadata.reason(),metadata.operatorId(),metadata.idempotencyKey());
    }
    public record Dimension(String code,String unit,String window,String effectiveAmount) { }
    public record Preview(UUID tenantId,String tenantName,String assignmentVersion,List<Dimension> dimensions) { }
    public record Adjustment(UUID id,UUID tenantId,String dimensionCode,String amount,String unit,String window,
            Instant startsAt,Instant endsAt,String status,String reason,UUID operatorId,String idempotencyKey) { }
}
