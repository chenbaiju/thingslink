package com.things.link.enduser.infrastructure.persistence;

import com.things.link.alarm.application.AlarmPushDeliveryAuthorizationPort;
import com.things.link.enduser.application.EncryptedPushToken;
import com.things.link.enduser.application.PushTokenCipher;
import com.things.link.enduser.domain.AppPushToken;
import com.things.link.project.application.ProjectLifecycleAccessService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 使用 enduser 权威事实实现 PUSH 发送前授权复核与短生命周期解密。
 *
 * <p>该查询必须位于 alarm 已恢复 tenant/project 双上下文的短事务中。一个 SQL 同时复核 ACTIVE 用户、项目角色、
 * 设备关系和安装实例，解绑或停用先提交时返回空；复核先完成时发送取得线性化资格。解密只发生在全部关系命中后，
 * 明文不会进入领域对象、投递快照、日志或数据库（ADR 0038、ADR 0051）。
 * ADR 0064 决策 4 要求先取得并持有原授权事务的项目写许可；本适配器不创建事务，外部发送仍在事务结束后执行。
 */
@Component
public class JdbcAlarmPushDeliveryAuthorizationAdapter
        implements AlarmPushDeliveryAuthorizationPort {

    /** 租户感知 JDBC；同时实施 tenant/project RLS。 */
    private final JdbcTemplate jdbcTemplate;
    /** 版本化 AES-256-GCM 解密端口。 */
    private final PushTokenCipher cipher;
    /** 项目域在原短事务持有写许可，确定拒绝只能返回空授权，不能撤销跨项目共用安装。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;

    /**
     * @param jdbcTemplate 租户感知 JDBC
     * @param cipher PUSH token 信封解密端口
     * @param lifecycleAccessService 原短授权事务项目许可
     */
    public JdbcAlarmPushDeliveryAuthorizationAdapter(
            JdbcTemplate jdbcTemplate, PushTokenCipher cipher,
            ProjectLifecycleAccessService lifecycleAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cipher = cipher;
        this.lifecycleAccessService = lifecycleAccessService;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AuthorizedPushTarget> authorize(
            UUID tenantId, UUID projectId, UUID deviceId, UUID appUserId, UUID pushTokenId) {
        // ADR 0064 决策 2/4：项目许可先于App事实查询和解密；SQL故障原传，不能伪装为授权已撤销。
        if (!lifecycleAccessService.lockActiveForWrite(tenantId, projectId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                        """
                        SELECT t.id, t.tenant_id, t.app_user_id, t.installation_id, t.provider,
                               t.status, t.created_at, t.updated_at, t.revoked_at,
                               t.token_cipher, t.token_nonce, t.key_id
                          FROM app_push_token t
                          JOIN app_user u
                            ON u.tenant_id = t.tenant_id
                           AND u.id = t.app_user_id
                           AND u.status = 'ACTIVE'
                          JOIN app_user_role r
                            ON r.tenant_id = t.tenant_id
                           AND r.project_id = ?
                           AND r.app_user_id = t.app_user_id
                           AND r.status = 'ACTIVE'
                          JOIN app_user_device d
                            ON d.tenant_id = r.tenant_id
                           AND d.project_id = r.project_id
                           AND d.app_user_id = r.app_user_id
                           AND d.device_id = ?
                           AND d.status = 'ACTIVE'
                         WHERE t.tenant_id = ?
                           AND t.app_user_id = ?
                           AND t.id = ?
                           AND t.status = 'ACTIVE'
                        """,
                        (resultSet, rowNumber) -> {
                            AppPushToken token = new AppPushToken(
                                    resultSet.getObject("id", UUID.class),
                                    resultSet.getObject("tenant_id", UUID.class),
                                    resultSet.getObject("app_user_id", UUID.class),
                                    resultSet.getObject("installation_id", UUID.class),
                                    AppPushToken.Provider.valueOf(resultSet.getString("provider")),
                                    AppPushToken.Status.valueOf(resultSet.getString("status")),
                                    resultSet.getTimestamp("created_at").toInstant(),
                                    resultSet.getTimestamp("updated_at").toInstant(),
                                    resultSet.getTimestamp("revoked_at") == null
                                            ? null : resultSet.getTimestamp("revoked_at").toInstant());
                            EncryptedPushToken encrypted = new EncryptedPushToken(
                                    resultSet.getBytes("token_cipher"),
                                    resultSet.getBytes("token_nonce"),
                                    resultSet.getString("key_id"));
                            return new AuthorizedPushTarget(
                                    token.provider().name(), cipher.decrypt(token, encrypted));
                        },
                        projectId,
                        deviceId,
                        tenantId,
                        appUserId,
                        pushTokenId)
                .stream()
                .findFirst();
    }
}
