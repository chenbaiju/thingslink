package com.things.link.device.application;

import com.things.link.device.domain.DeviceAccessRequest;
import com.things.link.device.domain.DeviceAccessRequestRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;

/**
 * 接入幂等受理事实的唯一实现：判定、写入都在一个项目 RLS 范围内完成。
 *
 * <p>两次调用各自是一个短事务，事务内先建立完整 (租户, 项目) 范围再读写 {@code dev_access_request}；
 * 范围来自调用方已认证的信封，不接受请求字段。判定与写入都只依赖幂等键与摘要：判定返回首次事实供协议层
 * 复用响应，写入不改动既有行。</p>
 */
@Service
public class DeviceAccessAcceptanceService implements DeviceAccessAcceptancePort {

    /** 受理事实仓储。 */
    private final DeviceAccessRequestRepository repository;

    /** 事务局部 RLS 范围组件，保证查询落在认证项目内。 */
    private final TransactionLocalRlsScope rlsScope;

    /** 保证范围设置与读写使用同一事务连接。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * @param repository 受理事实仓储
     * @param rlsScope 事务局部 RLS 范围组件
     * @param transactionTemplate 事务模板
     */
    public DeviceAccessAcceptanceService(DeviceAccessRequestRepository repository,
                                         TransactionLocalRlsScope rlsScope,
                                         TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.rlsScope = rlsScope;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Decision decide(Attempt attempt) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            rlsScope.establish(attempt.tenantId(), attempt.projectId());
            return repository.find(attempt.projectId(), attempt.deviceId(), attempt.messageId())
                    .map(existing -> existing.payloadDigest().equals(attempt.payloadDigest())
                            ? (Decision) new Decision.Duplicate(toAcceptance(existing))
                            : (Decision) new Decision.Conflict(toAcceptance(existing)))
                    .orElseGet(Decision.Fresh::new);
        }), "受理判定不能返回空");
    }

    @Override
    public Recording record(Attempt attempt) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            rlsScope.establish(attempt.tenantId(), attempt.projectId());
            Optional<DeviceAccessRequest> inserted = repository.insertIfAbsent(new DeviceAccessRequest(
                    attempt.deviceId(), attempt.messageId(), attempt.tenantId(), attempt.projectId(),
                    attempt.protocol(), attempt.payloadDigest(), attempt.receivedAt(), null));
            if (inserted.isPresent()) {
                return new Recording(true, toAcceptance(inserted.orElseThrow()));
            }
            // 同键已被并发重试写入：以先写入的事实为准，本次不覆盖载荷摘要，也不改首次接收时刻。
            DeviceAccessRequest existing = repository.find(attempt.projectId(), attempt.deviceId(), attempt.messageId())
                    .orElseThrow(() -> new IllegalStateException("同键受理事实不可见"));
            return new Recording(false, toAcceptance(existing));
        }), "受理写入不能返回空");
    }

    /** 领域事实到跨模块事实的映射：只暴露协议层构造响应所需的字段。 */
    private static Acceptance toAcceptance(DeviceAccessRequest request) {
        return new Acceptance(request.deviceId(), request.messageId(), request.payloadDigest(),
                request.protocol(), request.receivedAt(), request.acceptedAt());
    }
}
