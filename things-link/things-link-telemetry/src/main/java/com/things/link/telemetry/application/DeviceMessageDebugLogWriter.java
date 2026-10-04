package com.things.link.telemetry.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调试消息日志的**尽力而为**写入器（接入合同 §7.3「调试采样不替代必须可靠保存的业务事实」）。
 *
 * <p>调试日志不是业务事实：命令派发成功、回复落终态这些事实**不得**因为写日志失败而回滚。因此本写入器在独立事务
 * （{@code REQUIRES_NEW}）里写，由**调用方**在事务边界之外捕获异常——调试面少一行可以接受，
 * "命令因为日志写不进去而失败"不可接受。调用方不得在本类内部自我调用：那样会绕过代理、失去独立事务。</p>
 */
@Component
public class DeviceMessageDebugLogWriter {

    /** 只记录命令与设备标识，不打印载荷。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceMessageDebugLogWriter.class);

    /** 消息日志服务。 */
    private final MessageLogService messageLogService;

    /** 独立事务里必须重新建立 RLS 范围：事务局部范围不会跨事务传递。 */
    private final com.things.link.support.tenant.TransactionLocalRlsScope rlsScope;

    /**
     * @param messageLogService 消息日志服务
     * @param rlsScope 事务局部 RLS 范围组件
     */
    public DeviceMessageDebugLogWriter(MessageLogService messageLogService,
                                       com.things.link.support.tenant.TransactionLocalRlsScope rlsScope) {
        this.messageLogService = messageLogService;
        this.rlsScope = rlsScope;
    }

    /**
     * 独立事务写入。
     *
     * <p>调用方必须在本方法**之外**捕获异常：事务边界在代理上，异常要在代理之外被吞掉，才能既保证调试日志失败不影响
     * 业务事务、又不让失败的事务污染调用方。</p>
     *
     * <p>租户与项目由调用方从已核验的事实传入：本写入器不解析身份，也不读请求字段。</p>
     *
     * @param tenantId 权威租户
     * @param projectId 权威项目
     * @param command 日志命令
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(java.util.UUID tenantId, java.util.UUID projectId, DeviceMessageLogCommand command) {
        rlsScope.establish(tenantId, projectId);
        messageLogService.log(command);
    }
}
