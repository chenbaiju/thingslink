package com.things.link.device.application;

import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.support.outbox.OutboxRouteCatalog;
import com.things.link.support.outbox.TransactionalOutboxReader;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR0071决策2：在HTTP外发前以原Outbox信封和项目持续许可完成配置交付准入。
 *
 * <p>本服务只执行短数据库事务；调用方必须等待提交成功后才能访问EMQX，项目SHARE绝不跨网络。</p>
 */
@Service
public class DeviceConfigDeliveryAdmissionService {

    /** 配置Outbox聚合类型，由原生产端固定。 */
    private static final String AGGREGATE_TYPE = "DEVICE_CONFIG";

    /** S12-2a1c集中保证准入事务连接上的完整租户与项目RLS范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** 查询同项目不可变配置Outbox。 */
    private final TransactionalOutboxReader outboxReader;
    /** 持有项目ACTIVE共享锁至准入事务提交。 */
    private final ProjectLifecycleAccessService lifecycle;
    /** 生成与原Outbox可作JSON语义比较的完整信封。 */
    private final ObjectMapper objectMapper;

    /**
     * @param transactionLocalRlsScope 事务局部RLS完整范围组件
     * @param outboxReader 原Outbox等价查询端口
     * @param lifecycle 项目持续写许可
     * @param objectMapper 冻结共享信封序列化器
     */
    public DeviceConfigDeliveryAdmissionService(TransactionLocalRlsScope transactionLocalRlsScope,
                                                TransactionalOutboxReader outboxReader,
                                                ProjectLifecycleAccessService lifecycle,
                                                ObjectMapper objectMapper) {
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.outboxReader = outboxReader;
        this.lifecycle = lifecycle;
        this.objectMapper = objectMapper;
    }

    /**
     * 核验配置来自同项目等价原Outbox，并把ACTIVE许可持有到本事务提交。
     *
     * @param push Kafka收到的完整配置
     * @return 始终为true；拒绝通过专用异常保留可归因DLQ事实
     */
    @Transactional
    public boolean admit(DeviceConfigPush push) {
        requireValid(push);
        // 配置推送身份来自原持久Outbox信封；组件在任何受RLS查询前绑定同一完整二元组。
        transactionLocalRlsScope.establish(push.tenantId(), push.projectId());
        String payload = objectMapper.writeValueAsString(push);
        boolean equivalent = outboxReader.existsEquivalent(push.tenantId(), push.projectId(), AGGREGATE_TYPE,
                push.gatewayId(), DeviceConfigPush.EVENT_TYPE,
                OutboxRouteCatalog.topicFor(DeviceConfigPush.EVENT_TYPE), push.gatewayId().toString(), payload);
        if (!equivalent) {
            throw new InvalidDeviceConfigPushException("配置下发与原持久交付身份不一致");
        }
        if (!lifecycle.lockActiveForWrite(push.tenantId(), push.projectId())) {
            throw new ProjectFrozenConfigDeliveryException();
        }
        return true;
    }

    /** 缺失基础身份属于永久信封错误，不得先发送SQL或网络。 */
    private static void requireValid(DeviceConfigPush push) {
        if (push == null || push.tenantId() == null || push.projectId() == null || push.gatewayId() == null
                || push.projectKey() == null || push.projectKey().isBlank()
                || push.gatewayKey() == null || push.gatewayKey().isBlank()
                || !DeviceConfigPush.CONFIG_TYPE.equals(push.configType()) || push.version() < 1
                || push.points() == null || push.points().stream().anyMatch(java.util.Objects::isNull)) {
            throw new InvalidDeviceConfigPushException("配置下发信封不完整");
        }
    }
}
