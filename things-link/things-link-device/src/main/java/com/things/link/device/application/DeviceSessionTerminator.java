package com.things.link.device.application;

import java.util.UUID;

/**
 * 凭据安全变更后终止设备已建会话的应用端口。
 *
 * <p>D-038 要求撤销不能只拒绝新连接；实现必须在凭据事实提交后再通知 Broker，
 * 避免事务回滚却已把合法会话踢下线。</p>
 */
public interface DeviceSessionTerminator {

    /**
     * 事务提交后断开设备当前全部活跃 MQTT 会话。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     */
    void disconnectAfterCommit(UUID projectId, UUID deviceId);
}
