package com.things.link.device.infrastructure.credential;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.support.cache.CacheInvalidationEvent;
import com.things.link.support.cache.CacheInvalidationMetrics;
import com.things.link.support.cache.CacheInvalidationOperation;
import com.things.link.support.cache.CacheResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 协议无关凭据校验端口：无 MQTT 前提、成功缓存、撤销立即驱逐与熔断 fail-closed。 */
class DeviceCredentialAuthenticationServiceTests {

    /** 认证身份租户。 */ private static final UUID TENANT = new UUID(0, 1);
    /** 认证身份项目。 */ private static final UUID PROJECT = new UUID(0, 2);
    /** 认证身份设备。 */ private static final UUID DEVICE = new UUID(0, 3);

    /** 数据库门面替身。 */
    private JdbcTemplate jdbc;
    /** 模拟真实事务提交返回时机的模板替身。 */
    private TransactionTemplate tx;
    /** 当前数据库中的设备行代际；置空表示凭据已被撤销/轮换。 */
    private final AtomicLong version = new AtomicLong(1);
    /** 设备行是否仍存在（模拟撤销后 SQL 查不到行）。 */
    private boolean deviceRowPresent = true;
    /** 被测共享实现。 */
    private DeviceCredentialAuthenticationService service;

    /** 每个用例重建替身与数据库事实，避免缓存与调用计数串场。 */
    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        when(jdbc.queryForList(anyString(), eq("project")))
                .thenReturn(List.of(Map.of("id", PROJECT, "tenant_id", TENANT)));
        when(jdbc.queryForList(anyString(), eq(PROJECT), eq(TENANT), eq("device"), eq("project"), anyString()))
                .thenAnswer(call -> deviceRowPresent
                        ? List.of(Map.of("id", DEVICE, "tenant_id", TENANT, "project_id", PROJECT,
                                "credential_version", version.get()))
                        : List.of());
        service = new DeviceCredentialAuthenticationService(jdbc, tx, mock(CacheInvalidationMetrics.class));
    }

    /** 新协议只需该端口即可完成认证：不存在任何 Broker 就绪或内部服务身份前提。 */
    @Test
    void authenticatesThroughPortWithoutBrokerPrerequisites() {
        Optional<AuthenticatedDeviceIdentity> identity = service.authenticate("project", "device", "secret");

        assertThat(identity).contains(new AuthenticatedDeviceIdentity(TENANT, PROJECT, DEVICE, 1));
        verify(jdbc).update(anyString(), eq(PROJECT), eq(DEVICE), anyString());
    }

    /** 非法标识与缺失密钥在哈希和数据库之前拒绝，不占用连接也不写入缓存。 */
    @Test
    void rejectsIllegalIdentifiersBeforeDatabase() {
        assertThat(service.authenticate("bad project", "device", "secret")).isEmpty();
        assertThat(service.authenticate("project", "bad/device", "secret")).isEmpty();
        assertThat(service.authenticate("project", "device", null)).isEmpty();

        verifyNoInteractions(jdbc, tx);
    }

    /** 成功结果缓存：同一凭据的第二次校验不再查库。 */
    @Test
    void servesRepeatedSuccessFromSharedCache() {
        assertThat(service.authenticate("project", "device", "secret")).isPresent();
        assertThat(service.authenticate("project", "device", "secret")).isPresent();

        verify(tx, times(1)).execute(any());
    }

    /** 失败不缓存：重复错误口令每次都重新校验，攻击流量不能撑大缓存。 */
    @Test
    void doesNotCacheFailedAttempts() {
        deviceRowPresent = false;

        assertThat(service.authenticate("project", "device", "wrong")).isEmpty();
        assertThat(service.authenticate("project", "device", "wrong")).isEmpty();

        verify(tx, times(2)).execute(any());
    }

    /** 撤销事件必须立即驱逐成功条目：否则新协议会继续放行已轮换的旧密钥直到 TTL 到期。 */
    @Test
    void revocationEventEvictsCachedIdentityImmediately() {
        assertThat(service.authenticate("project", "device", "secret")).isPresent();
        verify(tx, times(1)).execute(any());

        // 轮换提交后：旧凭据行被作废，同时广播携带新代际的失效事件。
        deviceRowPresent = false;
        service.handle(new CacheInvalidationEvent(UUID.randomUUID(), CacheResource.DEVICE_CREDENTIAL,
                CacheInvalidationOperation.REVOKE, DEVICE, null, 2, 0, Instant.now()));

        assertThat(service.authenticate("project", "device", "secret")).isEmpty();
        verify(tx, times(2)).execute(any());
    }

    /** 乱序失效事件不得降代际：迟到的低代际事件不能驱逐更新的成功缓存。 */
    @Test
    void outOfOrderInvalidationCannotLowerObservedGeneration() {
        version.set(3);
        assertThat(service.authenticate("project", "device", "secret")).isPresent();
        verify(tx, times(1)).execute(any());

        service.handle(event(2));
        service.handle(event(3));

        assertThat(service.authenticate("project", "device", "secret")).isPresent();
        verify(tx, times(1)).execute(any());
    }

    /** 连续数据库故障后熔断 fail-closed：冷却期内即使数据库恢复也不再放行。 */
    @Test
    void opensCircuitAfterConsecutiveFailuresAndFailsClosed() {
        List<Object> calls = new ArrayList<>();
        doAnswer(call -> {
            calls.add(call.getArgument(0));
            throw new DataAccessResourceFailureException("test-only database failure");
        }).when(tx).execute(any());

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(service.authenticate("project", "device", "secret")).isEmpty();
        }
        assertThat(service.authenticate("project", "device", "secret")).isEmpty();

        assertThat(calls).hasSize(5);
    }

    /**
     * 构造携带指定代际的凭据失效事件。
     *
     * @param credentialVersion 事件携带的代际
     * @return 统一缓存失效事件
     */
    private static CacheInvalidationEvent event(long credentialVersion) {
        return new CacheInvalidationEvent(UUID.randomUUID(), CacheResource.DEVICE_CREDENTIAL,
                CacheInvalidationOperation.REVOKE, DEVICE, null, credentialVersion, 0, Instant.now());
    }
}
