package com.things.link.telemetry.application;

import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.DeviceCommandClaimRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 命令领取用例：把请求收敛到冻结的租约与条数边界，在一个项目 RLS 范围内完成领取。
 *
 * <p>租约与条数在这里收敛而不是信任调用方：协议层可以传设备请求的值，但越界值必须被夹到合同 §5.2／§6 的
 * 冻结区间，否则一台设备可以给自己签发超长租约把命令锁死，或一次拉走整队命令。命令状态推进与租约写入由
 * 仓储在同一事务内完成。</p>
 *
 * <p>待发队列预算只**打点不丢单**（合同 §5.2）：超预算时命令继续待发，逾期仍按既有终态处理；若在这里拒绝
 * 或丢弃，慢设备会永久丢失已经受理的命令。</p>
 */
@Service
public class DeviceCommandClaimService implements DeviceCommandClaimPort {

    /** 领取事实仓储。 */
    private final DeviceCommandClaimRepository repository;

    /** 事务局部 RLS 范围组件，保证领取落在认证项目内。 */
    private final TransactionLocalRlsScope rlsScope;

    /** 保证范围设置与领取使用同一事务连接。 */
    private final TransactionTemplate transactionTemplate;

    /** 单设备待发命令预算；超出只打点不丢单。 */
    private final int pendingBudget;

    /** 待发预算超限计数（低基数，不带设备标签）。 */
    private final MeterRegistry meterRegistry;

    /**
     * @param repository 领取事实仓储
     * @param rlsScope 事务局部 RLS 范围组件
     * @param transactionTemplate 事务模板
     * @param pendingBudget 单设备待发命令预算（接入合同 §6 默认 100）
     * @param meterRegistry 指标注册表
     */
    public DeviceCommandClaimService(DeviceCommandClaimRepository repository,
                                     TransactionLocalRlsScope rlsScope,
                                     TransactionTemplate transactionTemplate,
                                     @Value("${things-link.access.budget.pending-per-device:100}") int pendingBudget,
                                     MeterRegistry meterRegistry) {
        this.repository = repository;
        this.rlsScope = rlsScope;
        this.transactionTemplate = transactionTemplate;
        this.pendingBudget = pendingBudget;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<Claimed> claim(ClaimRequest request) {
        Duration lease = clampLease(request.lease());
        int limit = clampLimit(request.limit());
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            rlsScope.establish(request.tenantId(), request.projectId());
            List<Claimed> claimed = repository.claimDue(request.projectId(), request.deviceId(), lease, limit)
                    .stream()
                    .map(claim -> new Claimed(claim.commandId(), claim.commandKey(), claim.input(),
                            claim.attempt(), claim.leaseExpiresAt()))
                    .toList();
            recordPendingBudget(request, claimed.size());
            return claimed;
        }), "命令领取不能返回空事务结果");
    }

    /** 待发量超预算只打点：命令必须继续待发，不能因为队列长就把已受理的命令丢掉。 */
    private void recordPendingBudget(ClaimRequest request, int claimedNow) {
        if (claimedNow == 0) {
            return;
        }
        long pending = repository.countPending(request.projectId(), request.deviceId());
        if (pending > pendingBudget) {
            // 与受理侧共用同一条指标名定义，避免两处字符串各自漂移。
            meterRegistry.counter(DeviceCommandMetrics.PENDING_OVER_BUDGET).increment();
        }
    }

    /** 空值取默认租约，越界收敛到冻结上下限。 */
    private static Duration clampLease(Duration requested) {
        if (requested == null) {
            return DEFAULT_LEASE;
        }
        if (requested.compareTo(MIN_LEASE) < 0) {
            return MIN_LEASE;
        }
        if (requested.compareTo(MAX_LEASE) > 0) {
            return MAX_LEASE;
        }
        return requested;
    }

    /** 小于 1 视为未指定并取默认条数，超过上限收敛到上限。 */
    private static int clampLimit(int requested) {
        if (requested < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
