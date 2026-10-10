package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppPushToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 本域安装持久端口；调用方须持用户锁并建立可信租户范围。 */
public interface PushInstallationRepository {
    /** 按已验签当前族定位仍有效的组；不接受正文提供组。 */
    Optional<UUID> currentGroup(UUID tenant, UUID user, UUID project, UUID sid);
    /** 按本人安装锁定并读回最新版本，不暴露token。 */
    Optional<Binding> latest(UUID tenant, UUID user, UUID installation);
    /** 旧事实保留，新版本独立ID并在同事务抢占全局摘要。 */
    void replace(Binding binding, AppPushToken token, EncryptedPushToken encrypted, byte[] digest);
    /** 只撤销精确事实；不按安装批量误伤后继。 */
    void revoke(UUID tenant, UUID user, UUID factId, Instant now);
    /** 当前事实必须同时满足租约、会话组及排他摘要占用。 */
    boolean eligible(UUID tenant, UUID user, UUID factId);
    /** 非敏感绑定事实。 */
    record Binding(UUID id, UUID installationId, UUID bindingId, UUID sessionGroupId,
                   long revision, UUID registrationId, String channelIdentity, String channelConfigurationId, Instant leaseExpiresAt, Instant updatedAt, String status) {}
}
