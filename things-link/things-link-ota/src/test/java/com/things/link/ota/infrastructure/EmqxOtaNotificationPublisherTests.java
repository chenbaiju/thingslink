package com.things.link.ota.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.things.link.ota.application.OtaNotificationCodec;
import com.things.link.ota.application.OtaNotificationPublisher;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 真实HTTP单次交换和物理取消反例；不冒充Broker设备订阅验收。 */
class EmqxOtaNotificationPublisherTests {
    /** HTTP成功与拒绝按状态区分，重定向不能泄露发布身份到另一端点。 */
    @Test void acceptsOnlySuccessAndNeverFollowsRedirect() throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/publish", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "/redirected");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> { redirected.incrementAndGet(); exchange.close(); });
        server.start();
        try (var publisher = publisher(server.getAddress().getPort())) {
            var result = publisher.publish(route(), payload(), Duration.ofSeconds(2));
            assertEquals(OtaNotificationPublisher.Outcome.REJECTED, result.outcome());
            assertEquals(307, result.status());
            assertEquals(0, redirected.get());
        } finally { server.stop(0); }
    }
    /** 批量发布复用短时连接，空闲五秒后新建连接，且不主动要求逐次断连。 */
    @Test void reusesBurstConnectionButExpiresIdleConnection() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger closeHeaders = new AtomicInteger();
        var ports = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/publish", exchange -> {
            calls.incrementAndGet();
            ports.add(exchange.getRemoteAddress().getPort());
            if ("close".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Connection"))) {
                closeHeaders.incrementAndGet();
            }
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (var publisher = publisher(server.getAddress().getPort())) {
            assertEquals(OtaNotificationPublisher.Outcome.BROKER_ACCEPTED,
                    publisher.publish(route(), payload(), Duration.ofSeconds(2)).outcome());
            assertEquals(OtaNotificationPublisher.Outcome.BROKER_ACCEPTED,
                    publisher.publish(route(), payload(), Duration.ofSeconds(2)).outcome());
            assertEquals(2, calls.get());
            assertEquals(0, closeHeaders.get());
            assertEquals(ports.get(0), ports.get(1));
            Thread.sleep(6000);
            assertEquals(OtaNotificationPublisher.Outcome.BROKER_ACCEPTED,
                    publisher.publish(route(), payload(), Duration.ofSeconds(2)).outcome());
            assertEquals(3, calls.get());
            assertFalse(ports.get(1).equals(ports.get(2)));
        } finally { server.stop(0); }
    }
    /** 状态重试也不能创建第二次HTTP调用。 */
    @Test void statusRetryCannotCreateSecondPhysicalRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/publish", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try (var publisher = publisher(server.getAddress().getPort())) {
            var result = publisher.publish(route(), payload(), Duration.ofSeconds(2));
            assertEquals(OtaNotificationPublisher.Outcome.REJECTED, result.outcome());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
    /** 服务端保持黑洞，截止时间主动关闭连接而非等待夹具释放。 */
    @Test void blackHoleTimesOutAndPhysicallyClosesSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newSingleThreadExecutor();
             var publisher = publisher(server.getLocalPort())) {
            server.setSoTimeout(3000);
            var eof = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    while (socket.getInputStream().read() != -1) { }
                    return true;
                }
            });
            long started = System.nanoTime();
            var result = publisher.publish(route(), payload(), Duration.ofMillis(250));
            assertEquals(OtaNotificationPublisher.Outcome.UNKNOWN, result.outcome());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
            assertTrue(eof.get(3, TimeUnit.SECONDS));
        }
    }
    /** 活跃调用取消观察真实socket EOF且publish退出后才能视为物理结束。 */
    @Test void closeCancelsActiveCallAndRejectsNewWork() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newFixedThreadPool(2);
             var publisher = publisher(server.getLocalPort())) {
            server.setSoTimeout(3000);
            var eof = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    int first = socket.getInputStream().read();
                    received.countDown();
                    while (first != -1) first = socket.getInputStream().read();
                    return true;
                }
            });
            var call = executor.submit(() -> publisher.publish(route(), payload(), Duration.ofSeconds(5)));
            assertTrue(received.await(2, TimeUnit.SECONDS));
            publisher.close();
            assertEquals(OtaNotificationPublisher.Outcome.UNKNOWN, call.get(2, TimeUnit.SECONDS).outcome());
            assertTrue(eof.get(2, TimeUnit.SECONDS));
            assertFalse(publisher.configured());
        }
    }
    /** 服务端收到连接立即断开时不能假定未发送，更不能自动重试。 */
    @Test void disconnectedResponseIsUnknown() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newSingleThreadExecutor();
             var publisher = publisher(server.getLocalPort())) {
            server.setSoTimeout(3000);
            var peer = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.getInputStream().read();
                }
                return true;
            });
            assertEquals(OtaNotificationPublisher.Outcome.UNKNOWN,
                    publisher.publish(route(), payload(), Duration.ofSeconds(1)).outcome());
            assertTrue(peer.get(2, TimeUnit.SECONDS));
        }
    }

    /** HTTP成功与超大回执都不保留响应正文。 */
    @Test void responseBudgetIsBounded() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/publish", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = new byte[calls.incrementAndGet() == 1 ? 2 : 65537];
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try (var publisher = publisher(server.getAddress().getPort())) {
            assertEquals(OtaNotificationPublisher.Outcome.BROKER_ACCEPTED,
                    publisher.publish(route(), payload(), Duration.ofSeconds(2)).outcome());
            assertEquals("RESPONSE_TOO_LARGE", publisher.publish(route(), payload(), Duration.ofSeconds(2)).reason());
        } finally { server.stop(0); }
    }
    /** 不完整配置不创建网络客户端。 */
    @Test void missingConfigurationIsNotSuccess() {
        try (var publisher = new EmqxOtaNotificationPublisher("http://127.0.0.1:1", "", "")) {
            assertFalse(publisher.configured());
            assertEquals(OtaNotificationPublisher.Outcome.UNKNOWN,
                    publisher.publish(route(), payload(), Duration.ofSeconds(1)).outcome());
        }
    }
    /** 真正HTTP正文使用冻结空间，设备正文、QoS与retain不变。 */
    @Test void publishesFrozenNamespaceWithoutChangingCanonicalPayload() throws Exception {
        var request = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/publish", exchange -> {
            request.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(202, -1); exchange.close();
        });
        server.start();
        try (var publisher = publisher(server.getAddress().getPort())) {
            byte[] canonical = payload();
            assertEquals(OtaNotificationPublisher.Outcome.BROKER_ACCEPTED,
                    publisher.publish(route(), canonical, Duration.ofSeconds(2)).outcome());
            var body = new tools.jackson.databind.ObjectMapper().readTree(request.get());
            assertEquals(route().internalTopic("tc/v1/project/device/down/ota/available"), body.path("topic").asText());
            assertEquals(1, body.path("qos").asInt()); assertFalse(body.path("retain").asBoolean());
            assertEquals("base64", body.path("payload_encoding").asText());
            org.junit.jupiter.api.Assertions.assertArrayEquals(canonical,
                    java.util.Base64.getDecoder().decode(body.path("payload").asText()));
        } finally { server.stop(0); }
    }
    /** 缺少冻结路由不能退回裸主题，也不能产生HTTP交换。 */
    @Test void missingRouteNeverPublishes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/publish", exchange -> { calls.incrementAndGet(); exchange.close(); });
        server.start();
        try (var publisher = publisher(server.getAddress().getPort())) {
            assertEquals("INVALID_NOTIFICATION", publisher.publish(null, payload(), Duration.ofSeconds(1)).reason());
            assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }
    /** 有外层事务时直接拒绝，不允许数据库锁跨越物理网络。 */
    @Test void ambientTransactionNeverPublishes() {
        try (var publisher = publisher(1)) {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        () -> publisher.publish(route(), payload(), Duration.ofSeconds(1)));
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }
    }
    /** 明确受控配置空间，不以缺路由回退存量。 */
    private static com.things.link.device.application.DeviceMqttDownlinkRoute route() {
        return new com.things.link.device.application.DeviceMqttDownlinkRoute(
                new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), 17, "project", "device");
    }
    /** 固定本机测试身份。 */
    private static EmqxOtaNotificationPublisher publisher(int port) {
        return new EmqxOtaNotificationPublisher("http://127.0.0.1:" + port, "test-key", "test-secret");
    }
    /** 通知公开载荷。 */
    private static byte[] payload() {
        UUID id = new UUID(0, 1);
        return new OtaNotificationCodec().encode(new OtaNotificationCodec.Notification("tc-ota-available/v1",
                id, id, id, id, 1, "a".repeat(64), Instant.parse("2026-09-12T00:00:00Z")));
    }
}
