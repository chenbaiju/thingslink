package com.things.link.iam.infrastructure.security;

import com.things.link.iam.application.RestQuotaPolicy;
import com.things.link.project.application.*;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;

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

/** Real Redis outage/recovery at the Filter boundary; recorder/day-policy are explicit test ports, not PG proof. */
class RestQuotaRedisRecoveryTests {
    @ParameterizedTest(name = "{0} keeps usage callbacks during Redis outage and preserves the original bucket")
    @ValueSource(strings = {"GET", "POST"})
    @Timeout(60)
    void originalMinuteBucketSurvivesNetworkFailure(String method) throws Exception {
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID foreignTenant = UUID.randomUUID(), foreignProject = UUID.randomUUID();
        String kind = method.equals("GET") ? "read" : "write";
        String projectKey = "quota:rest:rate:" + kind + ":project:" + project;
        String tenantKey = "quota:rest:rate:" + kind + ":tenant:" + tenant;
        var facts = new ArrayList<UsageCall>();
        var daily = mock(ProjectDailyQuotaDecisionService.class);
        when(daily.decideTrustedProject(any(), any(), eq(QuotaMetric.REST_API_CALL))).thenReturn(QuotaStatus.NORMAL);
        ProjectUsageFactRecorder recorder = (owner, selected, metric, event, at) -> {
            facts.add(new UsageCall(owner, selected, metric, event, at));
            return true;
        };
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
                    var filter = new QuotaRestRateLimitFilter(selected -> {
                        UUID owner = selected.equals(project) ? tenant : selected.equals(foreignProject) ? foreignTenant : null;
                        return Optional.ofNullable(owner).map(id -> new RestQuotaPolicy(id, null, null, 1L, 1L));
                    }, tested, new RestQuotaRateLimitMetrics(meters), new ObjectMapper(), daily, recorder);
                    var scope = new TenantScope(tenant, project, actor);
                    long start = System.nanoTime();
                    assertThat(invoke(filter, scope, method)).isEqualTo(200);
                    assertThat(invoke(filter, scope, method)).isEqualTo(429);
                    assertThat(facts).hasSize(1);
                    var originalProject = observer.opsForHash().entries(projectKey);
                    var originalTenant = observer.opsForHash().entries(tenantKey);
                    assertThat(originalProject).containsKeys("tokens", "updated_at");
                    assertThat(originalTenant).containsKeys("tokens", "updated_at");
                    double failBefore = meters.get(RestQuotaRateLimitMetrics.FAIL_OPEN).tag("kind", kind).counter().count();
                    proxy.block();
                    assertNetworkFailure(tested);
                    assertThat(ping(observer)).isEqualTo("PONG");
                    assertThat(invoke(filter, scope, method)).isEqualTo(200);
                    assertThat(facts).hasSize(2);
                    assertThat(facts).allSatisfy(fact -> {
                        assertThat(fact.owner()).isEqualTo(tenant);
                        assertThat(fact.project()).isEqualTo(project);
                        assertThat(fact.metric()).isEqualTo(QuotaMetric.REST_API_CALL);
                        assertThat(fact.at()).isNotNull();
                    });
                    assertThat(facts.stream().map(UsageCall::event).toList()).doesNotHaveDuplicates();
                    // Account bucket is explicitly unlimited; the two real failed Lua calls are project and tenant.
                    assertThat(meters.get(RestQuotaRateLimitMetrics.FAIL_OPEN).tag("kind", kind).counter().count() - failBefore)
                            .isEqualTo(2D);
                    assertThat(observer.opsForHash().entries(projectKey)).isEqualTo(originalProject);
                    assertThat(observer.opsForHash().entries(tenantKey)).isEqualTo(originalTenant);
                    proxy.restore();
                    awaitConnected(tested);
                    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
                    assertThat(observer.opsForHash().entries(projectKey)).isEqualTo(originalProject);
                    assertThat(invoke(filter, scope, method)).isEqualTo(429);
                    assertThat(facts).hasSize(2);
                    assertThat(meters.get(RestQuotaRateLimitMetrics.REJECTED)
                            .tags("kind", kind, "scope", "project_minute").counter().count()).isEqualTo(2D);
                    assertThat(meters.get(RestQuotaRateLimitMetrics.FAIL_OPEN).tag("kind", kind).counter().count() - failBefore)
                            .isEqualTo(2D);
                    assertThat(invoke(filter, new TenantScope(foreignTenant, foreignProject, UUID.randomUUID()), method))
                            .isEqualTo(200);
                    assertThat(facts).hasSize(3);
                    assertThat(facts.get(2).owner()).isEqualTo(foreignTenant);
                    assertThat(facts.get(2).project()).isEqualTo(foreignProject);
                } finally { meters.close(); testedFactory.destroy(); }
            } finally { observerFactory.destroy(); TenantContext.clear(); }
        }
    }

    private static int invoke(QuotaRestRateLimitFilter filter, TenantScope scope, String method) throws Exception {
        TenantContext.set(scope);
        try {
            var request = new MockHttpServletRequest(method, "/api/v1/projects/" + scope.projectId() + "/devices");
            var response = new MockHttpServletResponse();
            var called = new java.util.concurrent.atomic.AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> called.set(true));
            assertThat(called.get()).isEqualTo(response.getStatus() == 200);
            return response.getStatus();
        } finally { TenantContext.clear(); }
    }

    private record UsageCall(UUID owner, UUID project, QuotaMetric metric, String event, java.time.Instant at) { }

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
