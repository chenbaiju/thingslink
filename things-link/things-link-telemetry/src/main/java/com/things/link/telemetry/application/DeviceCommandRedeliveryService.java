package com.things.link.telemetry.application;

import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * 重连补投加速实现：在显式租户／项目范围内只改退避时刻，不改命令状态。
 *
 * <p>接入面（TCP 会话建立）是在设备请求线程上调用本服务的，那里没有 HTTP 项目范围，因此本实现自己开事务并用
 * {@link TransactionLocalRlsScope} 建立事务内 RLS 范围——与其它后台数据面服务同一口径，不依赖线程范围。</p>
 */
@Service
public class DeviceCommandRedeliveryService implements DeviceCommandRedeliveryPort {

    /** 命令持久化端口。 */
    private final DeviceCommandRepository repository;

    /** 事务局部 RLS 范围组件。 */
    private final TransactionLocalRlsScope rlsScope;

    /** 独立短事务执行器。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * @param repository 命令持久化端口
     * @param rlsScope 事务局部 RLS 范围组件
     * @param transactionTemplate 事务模板
     */
    public DeviceCommandRedeliveryService(DeviceCommandRepository repository, TransactionLocalRlsScope rlsScope,
                                          TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.rlsScope = rlsScope;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int accelerateOfflinePending(UUID tenantId, UUID projectId, UUID deviceId) {
        if (tenantId == null || projectId == null || deviceId == null) {
            throw new IllegalArgumentException("重连补投的归属不能为空");
        }
        return transactionTemplate.execute(status -> {
            rlsScope.establish(tenantId, projectId);
            return repository.accelerateOfflinePending(tenantId, projectId, deviceId);
        });
    }
}
