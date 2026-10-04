package com.things.link.bootstrap.telemetry.overview;

import com.things.link.alarm.application.ProjectAlarmStatisticsService;
import com.things.link.device.application.ProjectDeviceStatisticsService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.telemetry.application.OverviewCacheMetrics;
import com.things.link.telemetry.application.OverviewService;
import com.things.link.telemetry.application.OverviewSnapshot;
import com.things.link.telemetry.domain.MessageLogRepository;
import com.things.link.telemetry.infrastructure.cache.OverviewCacheProperties;
import com.things.link.telemetry.infrastructure.cache.RedisOverviewCache;
import com.things.link.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * R4-4c: real PG/Redis recovery through this test's loopback TCP fault proxy.
 * Shared Redis is never stopped. Authorization, repositories, cache codec and the default 30s TTL
 * are production implementations; only this client's command timeout is shortened to bound failures.
 * This verifies the application-service boundary, not browser/HTTP authentication or Redis process restart.
 */
class ProjectOverviewRedisRecoveryTests extends AbstractIntegrationTest {
    @Autowired private ProjectService projects;
    @Autowired private ProjectDeviceStatisticsService devices;
    @Autowired private ProjectAlarmStatisticsService alarms;
    @Autowired private MessageLogRepository messages;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    @Autowired private StringRedisTemplate directRedis;

    private final List<Fixture> fixtures = new ArrayList<>();
    private JdbcTemplate owner;

    @Test
    @Timeout(75)
    void networkFailureKeepsAuthorizedPgFactsAndRecoveryRefillsDefaultTtlCache() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Fixture first = seed(1, 10);
        Fixture second = seed(4, 70);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try (TcpFaultProxy proxy = new TcpFaultProxy(REDIS.getHost(), REDIS.getMappedPort(6379))) {
            var client = LettuceClientConfiguration.builder()
                    .commandTimeout(Duration.ofMillis(250)).shutdownTimeout(Duration.ZERO).build();
            var factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration("127.0.0.1", proxy.port()), client);
            factory.afterPropertiesSet();
            try {
                var redis = new StringRedisTemplate(factory);
                redis.afterPropertiesSet();
                var properties = new OverviewCacheProperties();
                assertThat(properties.getCacheTtl()).isEqualTo(Duration.ofSeconds(30));
                var cache = new RedisOverviewCache(redis, json, properties);
                var beans = new StaticListableBeanFactory();
                beans.addBean("meters", meters);
                var metrics = new OverviewCacheMetrics(beans.getBeanProvider(MeterRegistry.class));
                var target = new OverviewService(devices, messages, alarms, projects, cache, metrics);
                var serviceProxy = new ProxyFactory(target);
                serviceProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
                var service = (OverviewService) serviceProxy.getProxy();

                assertFacts(read(service, first), first, 1, 10);
                assertFacts(read(service, second), second, 4, 280);
                assertThat(directRedis.getExpire(key(first))).isBetween(1L, 30L);
                assertThat(directRedis.getExpire(key(second))).isBetween(1L, 30L);
                double errorsBefore = counter(meters, "error");
                double writesBefore = writeErrors(meters);

                proxy.block();
                Throwable ioFailure = catchThrowable(() -> cache.find(first.projectId()));
                assertThat(ioFailure).as("The real Redis client must fail through the closed TCP path")
                        .isInstanceOf(DataAccessException.class);
                assertThat(hasNetworkCause(ioFailure)).as("Actual network/Redis connection or timeout cause required").isTrue();
                addDevice(first, 20);
                // PG now differs from the still-cached warm snapshot; a fabricated cached fallback cannot pass.
                assertFacts(read(service, first), first, 2, 30);
                assertFacts(read(service, second), second, 4, 280);
                assertThat(counter(meters, "error") - errorsBefore).isEqualTo(2D);
                assertThat(writeErrors(meters) - writesBefore).isEqualTo(2D);
                assertDeniedBeforeCache(service, first, second, meters);

                // Introduce a new fact after the outage responses, then restore actual network forwarding.
                addDevice(first, 30);
                proxy.restore();
                await(Duration.ofSeconds(8), () -> {
                    try { return "PONG".equals(redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping())); }
                    catch (RuntimeException unavailable) { return false; }
                });
                // No key deletion or fake clock: wait for production's existing 30-second TTL to expire.
                await(Duration.ofSeconds(35), () -> !Boolean.TRUE.equals(directRedis.hasKey(key(first)))
                        && !Boolean.TRUE.equals(directRedis.hasKey(key(second))));
                double missesBefore = counter(meters, "miss");
                OverviewSnapshot recoveredFirst = read(service, first);
                OverviewSnapshot recoveredSecond = read(service, second);
                assertFacts(recoveredFirst, first, 3, 60);
                assertFacts(recoveredSecond, second, 4, 280);
                assertThat(counter(meters, "miss") - missesBefore).isEqualTo(2D);
                assertThat(cache.find(first.projectId())).contains(recoveredFirst);
                assertThat(cache.find(second.projectId())).contains(recoveredSecond);
                assertThat(directRedis.getExpire(key(first))).isBetween(1L, 30L);
                double hitsBefore = counter(meters, "hit");
                assertThat(read(service, first)).isEqualTo(recoveredFirst);
                assertThat(read(service, second)).isEqualTo(recoveredSecond);
                assertThat(counter(meters, "hit") - hitsBefore).isEqualTo(2D);
                assertDeniedBeforeCache(service, first, second, meters);
            } finally {
                factory.destroy();
            }
        } finally {
            meters.close();
        }
    }

    private OverviewSnapshot read(OverviewService service, Fixture fixture) {
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        return service.get(fixture.projectId());
    }

    private void assertDeniedBeforeCache(OverviewService service, Fixture first, Fixture second, SimpleMeterRegistry meters) {
        TenantContext.set(new TenantScope(first.tenantId(), first.projectId(), first.accountId()));
        double before = totalRequests(meters);
        var failure = catchThrowableOfType(() -> service.get(second.projectId()), BusinessException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.errorCode().code()).isEqualTo(50001);
        assertThat(totalRequests(meters)).as("Non-member rejected before both working and failed cache reads").isEqualTo(before);
    }

    private void assertFacts(OverviewSnapshot snapshot, Fixture fixture, long count, long bytes) {
        Long actualDevices = owner.queryForObject("SELECT count(*) FROM dev_device WHERE tenant_id = ? AND project_id = ?",
                Long.class, fixture.tenantId(), fixture.projectId());
        Long actualBytes = owner.queryForObject("SELECT sum(raw_bytes) FROM ts_device_message_log WHERE tenant_id = ? AND project_id = ?",
                Long.class, fixture.tenantId(), fixture.projectId());
        assertThat(actualDevices).isEqualTo(count);
        assertThat(actualBytes).isEqualTo(bytes);
        assertThat(snapshot.devices().total()).isEqualTo(count);
        assertThat(snapshot.devices().online()).isEqualTo(count);
        assertThat(snapshot.messages24h().count()).isEqualTo(count);
        assertThat(snapshot.messages24h().bytes()).isEqualTo(bytes);
        assertThat(snapshot.alarmSeverityDeviceCounts().normal()).isEqualTo(count);
        assertThat(snapshot.alarmRate().available()).isTrue();
        assertThat(snapshot.alarmRate().value()).isZero();
    }

    private Fixture seed(int deviceCount, int bytesPerDevice) {
        var fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        fixtures.add(fixture);
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?, 'R4 Redis recovery tenant')", fixture.tenantId());
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'fixture-only','R4 Redis recovery')",
                fixture.accountId(), fixture.accountId() + "@example.test");
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,region,project_key)
                VALUES (?,?,'R4 Redis recovery project','sh-1',?)
                """, fixture.projectId(), fixture.tenantId(), "r4redis" + fixture.projectId().toString().replace("-", ""));
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                UUID.randomUUID(), fixture.projectId(), fixture.accountId());
        for (int i = 0; i < deviceCount; i++) addDevice(fixture, bytesPerDevice);
        return fixture;
    }

    private void addDevice(Fixture fixture, int bytes) {
        UUID deviceId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now().minusSeconds(1));
        owner.update("""
                INSERT INTO dev_device(id,tenant_id,project_id,device_key,name,status,last_online_at)
                VALUES (?,?,?,?,?,'ONLINE',?)
                """, deviceId, fixture.tenantId(), fixture.projectId(), "r4-" + deviceId, "R4 Redis device", now);
        owner.update("""
                INSERT INTO ts_device_message_log(id,project_id,device_id,message_id,tenant_id,protocol,direction,raw_bytes,ts,received_at)
                VALUES (?,?,?,?,?,'MQTT','UP',?,?,?)
                """, UUID.randomUUID(), fixture.projectId(), deviceId, UUID.randomUUID(), fixture.tenantId(), bytes, now, now);
    }

    private static double counter(SimpleMeterRegistry meters, String result) {
        return meters.get("thingslink.overview.cache.requests").tag("result", result).counter().count();
    }
    private static double totalRequests(SimpleMeterRegistry meters) {
        return counter(meters, "hit") + counter(meters, "miss") + counter(meters, "error") + counter(meters, "invalid");
    }
    private static double writeErrors(SimpleMeterRegistry meters) {
        return meters.get("thingslink.overview.cache.write.errors").counter().count();
    }
    private static String key(Fixture fixture) { return "things-link:overview:{" + fixture.projectId() + "}:v3"; }
    private static boolean hasNetworkCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof IOException || current instanceof io.lettuce.core.RedisConnectionException
                    || current instanceof io.lettuce.core.RedisCommandTimeoutException) return true;
        }
        return false;
    }
    private static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).as("Condition met within %s", timeout).isTrue();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        if (owner == null) return;
        for (Fixture fixture : fixtures) {
            directRedis.delete(key(fixture));
            owner.update("DELETE FROM ts_device_message_log WHERE tenant_id = ? AND project_id = ?", fixture.tenantId(), fixture.projectId());
            owner.update("DELETE FROM dev_device WHERE tenant_id = ? AND project_id = ?", fixture.tenantId(), fixture.projectId());
            owner.update("DELETE FROM sys_project_member WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_project WHERE tenant_id = ? AND id = ?", fixture.tenantId(), fixture.projectId());
            owner.update("DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            owner.update("DELETE FROM sys_account WHERE id = ?", fixture.accountId());
        }
    }

    private record Fixture(UUID tenantId, UUID projectId, UUID accountId) {}

    /** Raw TCP only: responses always come from real Redis. Faults reset this proxy's owned sockets. */
    private static final class TcpFaultProxy implements AutoCloseable {
        private final ServerSocket listener;
        private final InetSocketAddress upstream;
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final Set<Socket> sockets = new HashSet<>();
        private boolean blocked;
        private boolean closed;

        private TcpFaultProxy(String host, int port) throws IOException {
            upstream = new InetSocketAddress(host, port);
            listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            workers.submit(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket client = listener.accept();
                        synchronized (this) {
                            if (blocked || closed) { reset(client); continue; }
                            sockets.add(client);
                        }
                        workers.submit(() -> bridge(client));
                    } catch (IOException ended) {
                        if (!listener.isClosed()) throw new IllegalStateException(ended);
                    }
                }
            });
        }
        private int port() { return listener.getLocalPort(); }
        private void bridge(Socket client) {
            Socket remote = new Socket();
            synchronized (this) {
                if (blocked || closed) { reset(client); return; }
                sockets.add(remote);
            }
            try (client; remote) {
                remote.connect(upstream, 500);
                workers.submit(() -> copy(remote, client));
                copy(client, remote);
            } catch (IOException expectedDuringFault) {
                // Network failure belongs to the client under test, not a fabricated Redis result.
            } finally {
                synchronized (this) { sockets.remove(client); sockets.remove(remote); }
            }
        }
        private static void copy(Socket from, Socket to) {
            try { from.getInputStream().transferTo(to.getOutputStream()); }
            catch (IOException expectedDuringFault) { /* reset by the owned proxy */ }
            finally { reset(from); reset(to); }
        }
        private static void reset(Socket socket) {
            try { socket.setSoLinger(true, 0); } catch (IOException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
        }
        private synchronized void block() {
            blocked = true;
            sockets.forEach(TcpFaultProxy::reset);
            sockets.clear();
        }
        private synchronized void restore() { blocked = false; }
        @Override public void close() throws Exception {
            synchronized (this) {
                closed = true;
                blocked = true;
                listener.close();
                sockets.forEach(TcpFaultProxy::reset);
                sockets.clear();
            }
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).as("Owned proxy threads reaped").isTrue();
        }
    }
}
