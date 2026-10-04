package com.things.link.alarm.application;

import java.util.Optional;
import java.util.UUID;

/**
 * PUSH 外部调用前的 enduser 权威授权复核端口。
 *
 * <p>端口由 alarm 定义、enduser 实现，保持架构文档 10.4 的依赖方向。调用方已经恢复可信
 * tenant/project 双上下文；实现仍须显式核对用户、角色、设备关系和安装实例四项事实（ADR 0038）。
 */
public interface AlarmPushDeliveryAuthorizationPort {

    /**
     * 在一次短数据库事务中线性化发送资格；空结果表示解绑或任一授权事实已经失效。
     *
     * @param tenantId 可信投递事实中的租户 ID
     * @param projectId 可信投递事实中的项目 ID
     * @param deviceId 告警实例对应设备 ID
     * @param appUserId 投递展开时保存的终端用户 ID
     * @param pushTokenId 投递展开时保存的安装实例事实 ID
     * @return 当前仍获授权的短生命周期发送目标；不得记录或缓存
     */
    Optional<AuthorizedPushTarget> authorize(
            UUID tenantId, UUID projectId, UUID deviceId, UUID appUserId, UUID pushTokenId);

    /**
     * 只在授权复核到确定性 sender 调用之间短暂存在的敏感目标。
     *
     * @param provider 厂商通道名称
     * @param plainToken 解密后的厂商 token；禁止写库、日志、指标或异常
     */
    record AuthorizedPushTarget(String provider, String plainToken) {
        /** 构造时拒绝空值，避免把损坏密文伪装成厂商失败。 */
        public AuthorizedPushTarget {
            if (provider == null || provider.isBlank() || plainToken == null || plainToken.isBlank()) {
                throw new IllegalArgumentException("PUSH 授权目标不完整");
            }
        }

        /** @return 脱敏描述；禁止 record 默认实现泄露明文 token */
        @Override
        public String toString() {
            return "AuthorizedPushTarget[provider=" + provider + ", plainToken=***]";
        }
    }
}
