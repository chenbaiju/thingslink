package com.things.link.enduser.domain;

import com.things.link.enduser.application.EncryptedPushToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 受租户 RLS 保护的安装实例仓储合同。 */
public interface AppPushTokenRepository {

    /**
     * 对不存在行也建立事务级串行点，阻断同一安装实例并发首次注册绕过行锁。
     *
     * @param tenantId 租户 ID
     * @param appUserId App 用户 ID
     * @param installationId 安装实例 ID
     */
    void lockRegistration(UUID tenantId, UUID appUserId, UUID installationId);

    /** 尝试新建；并发唯一键冲突返回 0。 */
    int insert(AppPushToken token, EncryptedPushToken encrypted);

    /** 按用户与安装 ID 加行锁，供原位轮换稳定事实 ID。 */
    Optional<AppPushToken> findForUpdate(UUID tenantId, UUID appUserId, UUID installationId);

    /** 原位更新 provider、密文并恢复 ACTIVE。 */
    int activate(AppPushToken token, EncryptedPushToken encrypted);

    /** 幂等吊销 ACTIVE 安装实例。 */
    int revoke(UUID tenantId, UUID appUserId, UUID installationId, Instant revokedAt);
}
