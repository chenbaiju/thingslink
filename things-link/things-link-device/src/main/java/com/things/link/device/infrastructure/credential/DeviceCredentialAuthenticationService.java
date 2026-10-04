package com.things.link.device.infrastructure.credential;

import com.things.link.device.application.DeviceAccessIdentifiers;
import com.things.link.device.application.DeviceAuthenticationPort;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.support.cache.CacheInvalidationEvent;
import com.things.link.support.cache.CacheInvalidationHandler;
import com.things.link.support.cache.CacheInvalidationMetrics;
import com.things.link.support.cache.CacheResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一机一密凭据校验的唯一实现：EMQX 回调与三协议接入共用。
 *
 * <p>单条 SQL 快照同时读取设备档案与有效凭据，避免「设备→凭据」反向锁与两次查询之间的替换窗口；
 * 短时成功缓存吸收重连风暴，数据库连续失败打开熔断并一律拒绝（架构文档 8.3 的 fail-closed）。
 * 认证成功只比较 SHA-256 摘要，明文密钥既不落库也不进日志。</p>
 *
 * <p>缓存键沿用 {@code projectKey/deviceKey:sha256(secret)}：与 EMQX 用户名完全同形，因此 MQTT 既有的
 * 缓存语义与跨协议复用同一凭据时的命中行为都不改变。缓存读取后仍校验凭据代际，乱序失效事件无法让旧
 * 代际复活。</p>
 */
@Service
public class DeviceCredentialAuthenticationService implements DeviceAuthenticationPort, CacheInvalidationHandler {

    /** 成功认证缓存时长；足以吸收快速重连，同时把凭据撤销延迟限制在五秒（失效事件到达则立即生效）。 */
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);
    /** 连续数据库故障阈值；达到后短暂熔断，避免重连风暴继续压垮数据库。 */
    private static final int FAILURE_THRESHOLD = 5;
    /** 熔断冷却时间，之后允许单次请求探测数据库是否恢复。 */
    private static final Duration CIRCUIT_COOLDOWN = Duration.ofSeconds(10);

    /** 数据库访问入口。 */ private final JdbcTemplate jdbc;
    /** 保证设置 RLS 与查询使用同一连接。 */ private final TransactionTemplate tx;
    /** 只缓存成功结果；错误口令不缓存，避免攻击流量撑大内存。 */
    private final Map<String, SuccessCacheEntry> successCache = new ConcurrentHashMap<>();
    /** 按设备记录已观察到的凭据版本，乱序事件不能让旧凭据缓存复活。 */
    private final Map<UUID, Long> credentialVersions = new ConcurrentHashMap<>();
    /** 三类缓存共享的低基数指标；设备 ID 只用于内存定向驱逐，不进入标签。 */
    private final CacheInvalidationMetrics cacheMetrics;
    /** 连续数据库失败次数。 */ private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** 熔断截止时间；到期后允许请求探测。 */ private volatile Instant circuitOpenUntil = Instant.EPOCH;

    /**
     * 创建设备凭据校验服务。
     *
     * @param jdbc 数据库访问入口
     * @param tx 事务模板
     * @param cacheMetrics 统一缓存低基数指标
     */
    public DeviceCredentialAuthenticationService(JdbcTemplate jdbc, TransactionTemplate tx,
                                                 CacheInvalidationMetrics cacheMetrics) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.cacheMetrics = cacheMetrics;
    }

    @Override
    public Optional<AuthenticatedDeviceIdentity> authenticate(String projectKey, String deviceKey, String secret) {
        // 字符集校验必须在哈希与数据库之前：非法标识既不可能命中，也不应占用连接与缓存键空间。
        if (!DeviceAccessIdentifiers.isValid(projectKey) || !DeviceAccessIdentifiers.isValid(deviceKey)
                || secret == null) {
            return Optional.empty();
        }
        String cacheKey = projectKey + '/' + deviceKey + ':' + sha256(secret);
        Instant now = Instant.now();
        SuccessCacheEntry cached = successCache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(now)
                && cached.identity().credentialVersion() >= credentialVersions.getOrDefault(cached.identity().deviceId(), 0L)) {
            cacheMetrics.recordAccess(CacheResource.DEVICE_CREDENTIAL,
                    CacheInvalidationMetrics.AccessResult.HIT);
            return Optional.of(cached.identity());
        }
        successCache.remove(cacheKey);
        cacheMetrics.recordAccess(CacheResource.DEVICE_CREDENTIAL,
                CacheInvalidationMetrics.AccessResult.MISS);
        if (circuitOpenUntil.isAfter(now)) {
            cacheMetrics.recordFallback(CacheResource.DEVICE_CREDENTIAL,
                    CacheInvalidationMetrics.FallbackResult.FAIL_CLOSED);
            return Optional.empty();
        }

        try {
            Optional<AuthenticatedDeviceIdentity> result =
                    tx.execute(status -> authenticateInTransaction(projectKey, deviceKey, cacheKey));
            consecutiveFailures.set(0);
            circuitOpenUntil = Instant.EPOCH;
            if (result == null || result.isEmpty()) {
                return Optional.empty();
            }
            AuthenticatedDeviceIdentity identity = result.orElseThrow();
            credentialVersions.merge(identity.deviceId(), identity.credentialVersion(), Math::max);
            if (identity.credentialVersion() >= credentialVersions.getOrDefault(identity.deviceId(), 0L)) {
                successCache.put(cacheKey, new SuccessCacheEntry(identity, Instant.now().plus(CACHE_TTL)));
            }
            return result;
        } catch (DataAccessException e) {
            if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD)
                circuitOpenUntil = now.plus(CIRCUIT_COOLDOWN);
            cacheMetrics.recordAccess(CacheResource.DEVICE_CREDENTIAL,
                    CacheInvalidationMetrics.AccessResult.ERROR);
            cacheMetrics.recordFallback(CacheResource.DEVICE_CREDENTIAL,
                    CacheInvalidationMetrics.FallbackResult.FAIL_CLOSED);
            return Optional.empty();
        }
    }

    /** 在同一事务连接上完成项目定位、RLS 注入和凭据验证。 */
    private Optional<AuthenticatedDeviceIdentity> authenticateInTransaction(
            String projectKey, String deviceKey, String cacheKey) {
        List<Map<String, Object>> projects = jdbc.queryForList(
                "SELECT id, tenant_id FROM sys_project WHERE project_key = ? AND deleted_at IS NULL", projectKey);
        if (projects.size() != 1) return Optional.empty();
        UUID projectId = (UUID) projects.getFirst().get("id");
        UUID tenantId = (UUID) projects.getFirst().get("tenant_id");
        setScope(tenantId, projectId);

        // 同一SQL快照同时读取有效凭据与设备代际，不额外引入设备→凭据反向锁。
        String hash = cacheKey.substring(cacheKey.indexOf(':') + 1);
        List<Map<String, Object>> devices = jdbc.queryForList("""
                SELECT d.id, d.tenant_id, d.project_id, d.credential_version
                  FROM dev_device d
                  JOIN dev_type t ON t.id = d.device_type_id AND t.project_id = d.project_id
                    AND t.tenant_id = d.tenant_id AND t.deleted_at IS NULL
                  JOIN sys_project p ON p.id = d.project_id AND p.tenant_id = d.tenant_id
                    AND p.deleted_at IS NULL
                 WHERE d.project_id = ? AND d.tenant_id = ? AND d.device_key = ?
                   AND p.project_key = ? AND d.deleted_at IS NULL AND t.device_kind <> 'SUB_DEVICE'
                   AND EXISTS (
                       SELECT 1 FROM dev_credential c
                        WHERE c.project_id = d.project_id AND c.tenant_id = d.tenant_id AND c.device_id = d.id
                          AND c.auth_type = 'ACCESS_TOKEN' AND c.credential_hash = ?
                          AND c.deleted_at IS NULL AND (c.expires_at IS NULL OR c.expires_at > now()))
                """, projectId, tenantId, deviceKey, projectKey, hash);
        if (devices.size() != 1) return Optional.empty();
        Map<String, Object> row = devices.getFirst();
        UUID deviceId = (UUID) row.get("id");
        AuthenticatedDeviceIdentity identity = new AuthenticatedDeviceIdentity(
                (UUID) row.get("tenant_id"), (UUID) row.get("project_id"), deviceId,
                ((Number) row.get("credential_version")).longValue());

        jdbc.update("""
                UPDATE dev_credential SET last_used_at = now(), updated_at = now()
                 WHERE project_id = ? AND device_id = ? AND credential_hash = ? AND deleted_at IS NULL
                """, projectId, deviceId, hash);
        cacheMetrics.recordAccess(CacheResource.DEVICE_CREDENTIAL,
                CacheInvalidationMetrics.AccessResult.LOAD);
        return Optional.of(identity);
    }

    /**
     * 应用凭据撤销事件并立即驱逐该设备的成功认证条目。
     *
     * @param event 统一缓存失效事件
     * @return 是否识别并处理了该安全缓存事件
     */
    @Override
    public boolean handle(CacheInvalidationEvent event) {
        if (event.resource() != CacheResource.DEVICE_CREDENTIAL) return false;
        credentialVersions.merge(event.resourceId(), event.version(), Math::max);
        successCache.entrySet().removeIf(entry -> entry.getValue().identity().deviceId().equals(event.resourceId())
                && entry.getValue().identity().credentialVersion() < credentialVersions.get(event.resourceId()));
        return true;
    }

    /** RLS 会话变量由数据源在下次借出连接时覆盖，不会跨请求沿用。 */
    private void setScope(UUID tenantId, UUID projectId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, false)", String.class, tenantId.toString());
        jdbc.queryForObject("SELECT set_config('app.project_id', ?, false)", String.class, projectId.toString());
    }

    /** @param input 原文 @return SHA-256 十六进制摘要 */
    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 没有 SHA-256", e);
        }
    }

    /** 成功认证缓存同时绑定设备与凭据版本，使定向撤销可以跨口令哈希生效。 */
    private record SuccessCacheEntry(AuthenticatedDeviceIdentity identity, Instant expiresAt) {
    }
}
