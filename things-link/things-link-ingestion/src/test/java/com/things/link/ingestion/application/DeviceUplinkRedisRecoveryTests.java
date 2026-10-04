package com.things.link.ingestion.application;

import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.QuotaRuntimeMetrics;
import com.things.link.support.observability.DataPlaneMetrics;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Actual Redis fault and recovery at the MQTT uplink limiter; no Broker, Kafka or hardware qualification. */
class DeviceUplinkRedisRecoveryTests {
    @Test
    @Timeout(60)
    void originalTenantMinuteWindowSurvivesNetworkFailure() throws Exception {
        UUID tenant = UUID.randomUUID(), otherTenant = UUID.randomUUID(), device = UUID.randomUUID();
        String key = "ingestion:uplink:rate:{" + tenant + "}:tenant:60000";
        try (var container = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)) {
            container.start();
            var observerFactory = client(container.getHost(), container.getMappedPort(6379));
            try (var proxy = new TcpFaultProxy(container.getHost(), container.getMappedPort(6379))) {
                var testedFactory = client("127.0.0.1", proxy.port());
                var meters = new SimpleMeterRegistry();
                try {
                    var observer = template(observerFactory);
                    var tested = template(testedFactory);
                    assertThat(ping(tested)).isEqualTo("PONG");
                    // Wait only if the real Redis minute is nearly over. Never modify TIME, hash timestamps or tokens.
                    awaitMinuteHeadroom(observer);
                    var policy = policy(tenant);
                    var limiter = new DeviceUplinkRateLimiter(tested, new DataPlaneMetrics(meters), new QuotaRuntimeMetrics(meters));
                    assertThat(limiter.tryAcquire(tenant, device, policy)).isTrue();
                    assertThat(limiter.tryAcquire(tenant, UUID.randomUUID(), policy)).isFalse();
                    var original = observer.opsForHash().entries(key);
                    assertThat(original).containsEntry("count", "2").containsKey("window_start");
                    proxy.block();
                    assertNetworkFailure(tested);
                    assertThat(ping(observer)).isEqualTo("PONG");
                    for (int i = 0; i < 2; i++) assertThat(limiter.tryAcquire(tenant, device, policy)).isTrue();
                    // Existing generic Redis I/O catch records UPLINK_DEVICE even when the tenant script failed.
                    assertThat(counter(meters, "uplink_device", "fail_open")).isEqualTo(2D);
                    assertThat(counter(meters, "uplink_tenant_minute", "rejected")).isEqualTo(1D);
                    assertThat(observer.opsForHash().entries(key)).isEqualTo(original);
                    proxy.restore();
                    awaitConnected(tested);
                    assertThat(observer.opsForHash().entries(key)).isEqualTo(original);
                    long now = redisTime(observer);
                    assertThat(now / 60_000L * 60_000L).isEqualTo(Long.parseLong(original.get("window_start").toString()));
                    assertThat(limiter.tryAcquire(tenant, UUID.randomUUID(), policy)).isFalse();
                    assertThat(observer.opsForHash().get(key, "count")).isEqualTo("3");
                    assertThat(counter(meters, "uplink_device", "fail_open")).isEqualTo(2D);
                    assertThat(counter(meters, "uplink_tenant_minute", "rejected")).isEqualTo(2D);
                    assertThat(meters.get("thingslink.ingestion.uplink.rate_limited").counter().count()).isEqualTo(2D);
                    assertThat(limiter.tryAcquire(otherTenant, UUID.randomUUID(), policy(otherTenant))).isTrue();
                    assertThat(observer.opsForHash().entries(key)).containsEntry("count", "3")
                            .containsEntry("window_start", original.get("window_start"));
                } finally { meters.close(); testedFactory.destroy(); }
            } finally { observerFactory.destroy(); }
        }
    }

    private static EffectiveQuotaPolicy policy(UUID tenant) {
        return new EffectiveQuotaPolicy(tenant, UUID.randomUUID(), 1L, 1L,
                null, null, null, 1L, null, null, null, null, null);
    }

    private static double counter(SimpleMeterRegistry meters, String dimension, String result) {
        return meters.get(QuotaRuntimeMetrics.RATE_LIMIT).tags("dimension", dimension, "result", result).counter().count();
    }

    private static long redisTime(StringRedisTemplate redis) {
        return Objects.requireNonNull(redis.execute((RedisCallback<Long>) connection ->
                connection.serverCommands().time(TimeUnit.MILLISECONDS)));
    }

    private static void awaitMinuteHeadroom(StringRedisTemplate redis) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(17).toNanos();
        while (redisTime(redis) % 60_000L >= 45_000L) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Real Redis minute did not advance naturally");
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    /** Test-owned client timeout only; production limits, Redis TIME and bucket TTL remain unchanged. */
    private static LettuceConnectionFactory client(String host, int port) {
        var options = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(250)).shutdownTimeout(Duration.ZERO).build();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), options);
        factory.afterPropertiesSet();
        return factory;
    }

    private static StringRedisTemplate template(LettuceConnectionFactory factory) {
        var redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        return redis;
    }

    private static String ping(StringRedisTemplate redis) {
        return redis.execute((RedisCallback<String>) connection -> connection.ping());
    }

    private static void assertNetworkFailure(StringRedisTemplate redis) {
        Throwable failure = catchThrowable(() -> ping(redis));
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        boolean networkCause = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException
                    || cause instanceof io.lettuce.core.RedisConnectionException
                    || cause instanceof io.lettuce.core.RedisCommandTimeoutException) networkCause = true;
        }
        assertThat(networkCause).as("Actual socket/Redis connection or command timeout, not stubbed null").isTrue();
    }

    private static void awaitConnected(StringRedisTemplate redis) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try { if ("PONG".equals(ping(redis))) return; }
            catch (RuntimeException reconnecting) { last = reconnecting; }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("Owned client did not reconnect through restored TCP proxy", last);
    }
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
