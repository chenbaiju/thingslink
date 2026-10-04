package com.things.link.integration.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import com.things.link.integration.application.RealtimeMqttPublisher;
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
class EmqxRealtimePublisherTests {
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
            var result = publisher.publish(UUID.fromString("00000000-0000-0000-0000-000000000001"), payload(), Duration.ofSeconds(2));
            assertEquals(RealtimeMqttPublisher.Outcome.REJECTED, result);
            assertEquals(0, redirected.get());
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
            var result = publisher.publish(UUID.fromString("00000000-0000-0000-0000-000000000001"), payload(), Duration.ofSeconds(2));
            assertEquals(RealtimeMqttPublisher.Outcome.REJECTED, result);
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
            var result = publisher.publish(UUID.fromString("00000000-0000-0000-0000-000000000001"), payload(), Duration.ofMillis(250));
            assertEquals(RealtimeMqttPublisher.Outcome.UNKNOWN, result);
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
            var call = executor.submit(() -> publisher.publish(UUID.fromString("00000000-0000-0000-0000-000000000001"), payload(), Duration.ofSeconds(5)));
            assertTrue(received.await(2, TimeUnit.SECONDS));
            publisher.close();
            assertEquals(RealtimeMqttPublisher.Outcome.UNKNOWN, call.get(2, TimeUnit.SECONDS));
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
            assertEquals(RealtimeMqttPublisher.Outcome.UNKNOWN,
                    publisher.publish(UUID.fromString("00000000-0000-0000-0000-000000000001"), payload(), Duration.ofSeconds(1)));
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
            assertEquals(RealtimeMqttPublisher.Outcome.BROKER_ACCEPTED,
                    publisher.publish(UUID.fromString("00000000-0000-0000-0000-000000000001"), payload(), Duration.ofSeconds(2)));
            assertEquals(RealtimeMqttPublisher.Outcome.UNKNOWN, publisher.publish(UUID.fromString("00000000-0000-0000-0000-000000000001"), payload(), Duration.ofSeconds(2)));
        } finally { server.stop(0); }
    }
    /** 不完整配置不创建网络客户端。 */
    @Test void missingConfigurationIsNotSuccess() {
        try (var publisher = new EmqxRealtimePublisher("http://127.0.0.1:1", "", "")) {
            assertFalse(publisher.configured());
            assertEquals(RealtimeMqttPublisher.Outcome.UNKNOWN,
                    publisher.publish(UUID.fromString("00000000-0000-0000-0000-000000000001"), payload(), Duration.ofSeconds(1)));
        }
    }
    @Test void requestUsesExactTopicQosAndLosslessBase64()throws Exception{
        var body=new java.util.concurrent.atomic.AtomicReference<String>();
        var auth=new java.util.concurrent.atomic.AtomicReference<String>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v5/publish",exchange->{body.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));auth.set(exchange.getRequestHeaders().getFirst("Authorization"));exchange.sendResponseHeaders(202,-1);exchange.close();});server.start();
        UUID ticket=UUID.randomUUID();byte[] value="{\"valueJson\":\"1.123456789012345678901\",\"label\":\"温度\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(var publisher=publisher(server.getAddress().getPort())){
            assertEquals(RealtimeMqttPublisher.Outcome.BROKER_ACCEPTED,publisher.publish(ticket,value,Duration.ofSeconds(2)));
            var json=new tools.jackson.databind.ObjectMapper().readTree(body.get());
            assertEquals(RealtimeMqttPublisher.topic(ticket),json.path("topic").asString());assertEquals(1,json.path("qos").asInt());assertFalse(json.path("retain").asBoolean());
            assertEquals("base64",json.path("payload_encoding").asString());org.junit.jupiter.api.Assertions.assertArrayEquals(value,java.util.Base64.getDecoder().decode(json.path("payload").asString()));
            assertEquals("Basic "+java.util.Base64.getEncoder().encodeToString("test-key:test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)),auth.get());
        }finally{server.stop(0);}
    }
    /** 固定本机测试身份。 */
    private static EmqxRealtimePublisher publisher(int port) {
        return new EmqxRealtimePublisher("http://127.0.0.1:" + port, "test-key", "test-secret");
    }
    /** 通知公开载荷。 */
    private static byte[] payload() { return "{\"eventId\":\"00000000-0000-0000-0000-000000000002\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8); }
}
