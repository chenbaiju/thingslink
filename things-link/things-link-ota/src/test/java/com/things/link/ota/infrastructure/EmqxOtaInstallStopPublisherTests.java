package com.things.link.ota.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.things.link.ota.application.OtaInstallStopOperationCodec;
import com.things.link.ota.application.OtaInstallStopPublisher;
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
class EmqxOtaInstallStopPublisherTests {
    /** 本测试类共享足够长且固定的外层截止，避免独立取时不一致。 */
    private static final Instant EXPIRY=Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
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
            var result = publisher.publish("OPERATION", route(), payload(), EXPIRY, EXPIRY, Duration.ofSeconds(2));
            assertEquals(OtaInstallStopPublisher.Outcome.REJECTED, result.outcome());
            assertEquals(307, result.status());
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
            var result = publisher.publish("OPERATION", route(), payload(), EXPIRY, EXPIRY, Duration.ofSeconds(2));
            assertEquals(OtaInstallStopPublisher.Outcome.REJECTED, result.outcome());
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
            var result = publisher.publish("OPERATION", route(), payload(), EXPIRY, EXPIRY, Duration.ofMillis(250));
            assertEquals(OtaInstallStopPublisher.Outcome.UNKNOWN, result.outcome());
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
            var call = executor.submit(() -> publisher.publish("OPERATION", route(), payload(), EXPIRY, EXPIRY, Duration.ofSeconds(5)));
            assertTrue(received.await(2, TimeUnit.SECONDS));
            publisher.close();
            assertEquals(OtaInstallStopPublisher.Outcome.UNKNOWN, call.get(2, TimeUnit.SECONDS).outcome());
            assertTrue(eof.get(2, TimeUnit.SECONDS));
            assertFalse(publisher.configured());
        }
    }
    /** 两种停止协议固定路由与原规范字节，短预算仍为Broker保留至少一秒。 */
    @Test void publishesBothExactEnvelopesWithBoundedExpiry() throws Exception {
        var captured=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        AtomicInteger closeHeaders = new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v5/publish",exchange->{
            if ("close".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Connection"))) closeHeaders.incrementAndGet();
            captured.set(exchange.getRequestBody().readAllBytes());exchange.sendResponseHeaders(202,-1);exchange.close();
        });
        server.start();
        try(var publisher=publisher(server.getAddress().getPort())) {
            Instant shortExpiry = Instant.now().plusSeconds(4).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            byte[] secret = payload(shortExpiry);
            var result = publisher.publish("OPERATION", route(), secret, shortExpiry, shortExpiry, Duration.ofSeconds(5));
            assertEquals(OtaInstallStopPublisher.Outcome.BROKER_ACCEPTED,result.outcome());
            var body=new com.things.link.ota.application.OtaCanonicalJson().parseObject(captured.get());
            assertEquals(route().internalTopic("tc/v1/project/device/down/ota/install-stop/operation"),body.get("topic"));
            assertEquals(1L,body.get("qos"));assertEquals(false,body.get("retain"));assertEquals("base64",body.get("payload_encoding"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(secret,java.util.Base64.getDecoder().decode((String)body.get("payload")));
            var properties=(java.util.Map<?,?>)body.get("properties");long expiry=((Number)properties.get("message_expiry_interval")).longValue();
            assertTrue(expiry>0);assertTrue(expiry<=Duration.between(Instant.now(),shortExpiry).getSeconds());
            assertFalse(result.toString().contains("test-secret"));assertFalse(result.toString().contains("storage.example"));
            Instant statusExpiry = Instant.now().plusSeconds(4).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            var json = new com.things.link.ota.application.OtaCanonicalJson();
            var fields = new java.util.LinkedHashMap<>(json.parseObject(resource("install-stop-status-query-v1.json")));
            fields.put("expiresAt", statusExpiry.getEpochSecond());
            byte[] query = new com.things.link.ota.application.OtaInstallStopStatusQueryCodec().decode(json.writeObject(fields)).canonical();
            var status = publisher.publish("STATUS", route(), query, statusExpiry, statusExpiry, Duration.ofSeconds(5));
            assertEquals(OtaInstallStopPublisher.Outcome.BROKER_ACCEPTED, status.outcome());
            var statusBody = json.parseObject(captured.get());
            assertEquals(route().internalTopic("tc/v1/project/device/down/ota/install-stop/status/query"), statusBody.get("topic"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(query, java.util.Base64.getDecoder().decode((String) statusBody.get("payload")));
            assertEquals(1L, statusBody.get("qos"));
            assertEquals(false, statusBody.get("retain"));
            long statusTtl = ((Number) ((java.util.Map<?, ?>) statusBody.get("properties")).get("message_expiry_interval")).longValue();
            assertTrue(statusTtl >= 1);
            assertTrue(statusTtl <= Duration.between(Instant.now(), statusExpiry).getSeconds());
            assertEquals(0, closeHeaders.get());
        }finally{server.stop(0);}
    }
    /** 过期、不一致期限和非规范正文均在发送前拒绝，不得开始实际请求。 */
    @Test void rejectsExpiredMismatchAndNoncanonicalBeforeNetwork() throws Exception {
        AtomicInteger calls=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v5/publish",exchange->{calls.incrementAndGet();exchange.close();});server.start();
        try(var publisher=publisher(server.getAddress().getPort())){
            byte[] body=payload();
            assertEquals("INVALID_INSTALL_STOP_ENVELOPE", publisher.publish("OTHER", route(), body,
                    EXPIRY, EXPIRY, Duration.ofSeconds(1)).reason());
            assertEquals("INVALID_INSTALL_STOP_ENVELOPE", publisher.publish("STATUS", route(), body,
                    EXPIRY, EXPIRY, Duration.ofSeconds(1)).reason());
            assertEquals("INVALID_INSTALL_STOP_ENVELOPE",publisher.publish("OPERATION", route(),body,EXPIRY.plusSeconds(1), EXPIRY, Duration.ofSeconds(1)).reason());
            byte[] noncanonical=(new String(body,java.nio.charset.StandardCharsets.UTF_8)+" ").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("INVALID_INSTALL_STOP_ENVELOPE",publisher.publish("OPERATION", route(),noncanonical,EXPIRY, EXPIRY, Duration.ofSeconds(1)).reason());
            var json=new com.things.link.ota.application.OtaCanonicalJson();var fields=new java.util.LinkedHashMap<>(json.parseObject(body));
            Instant past=Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);fields.put("expiresAt",past.getEpochSecond());
            var codec=new OtaInstallStopOperationCodec();byte[] expired=codec.decode(json.writeObject(fields)).canonical();
            assertEquals("DEADLINE_EXPIRED",publisher.publish("OPERATION", route(),expired,past,past,Duration.ofSeconds(1)).reason());
            assertEquals("DEADLINE_EXPIRED", publisher.publish("OPERATION", route(), body, EXPIRY,
                    Instant.now().minusSeconds(1), Duration.ofSeconds(1)).reason());
            Instant tooShort = Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            assertEquals("DEADLINE_EXPIRED", publisher.publish("OPERATION", route(), payload(tooShort), tooShort,
                    tooShort, Duration.ofSeconds(5)).reason());
            assertEquals(0,calls.get());
        }finally{server.stop(0);}
    }
    /** 缺路由不得回退裸主题或产生物理操作。 */
    @Test void missingRouteNeverPublishes() throws Exception {
        AtomicInteger calls=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v5/publish",exchange->{calls.incrementAndGet();exchange.close();});server.start();
        try(var publisher=publisher(server.getAddress().getPort())) {
            assertEquals("INVALID_INSTALL_STOP_ENVELOPE",publisher.publish("OPERATION",null,payload(),EXPIRY,EXPIRY,Duration.ofSeconds(1)).reason());
            assertEquals(0,calls.get());
        }finally{server.stop(0);}
    }
    /** 外层事务拒绝物理网络，不能让数据库锁覆盖发送等待。 */
    @Test void ambientTransactionNeverPublishes() {
        try(var publisher=publisher(1)) {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        ()->publisher.publish("OPERATION",route(),payload(),EXPIRY,EXPIRY,Duration.ofSeconds(1)));
            }finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
        }
    }
    /** 明确受控MQTT配置代次，不允许缺路由默认存量。 */
    private static com.things.link.device.application.DeviceMqttDownlinkRoute route() {
        return new com.things.link.device.application.DeviceMqttDownlinkRoute(
                new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), 17, "project", "device");
    }
    /** 固定本机测试身份。 */
    private static EmqxOtaInstallStopPublisher publisher(int port) {
        return new EmqxOtaInstallStopPublisher("http://127.0.0.1:" + port, "test-key", "test-secret");
    }
    /** 固定公开许可向量仅替换测试期限，不含密钥。 */
    private static byte[] payload() { return payload(EXPIRY); }
    /** 固定原期限允许验证短合法预算。 */
    private static byte[] payload(Instant expiry) {
        try {
            var json = new com.things.link.ota.application.OtaCanonicalJson();
            var fields = new java.util.LinkedHashMap<>(json.parseObject(resource("install-stop-operation-v1.json")));
            fields.put("expiresAt", expiry.getEpochSecond());
            return new OtaInstallStopOperationCodec().decode(json.writeObject(fields)).canonical();
        } catch (Exception failure) { throw new IllegalStateException("公开许可向量缺失", failure); }
    }
    /** 只读取仓库公开向量，禁止输出秘密正文。 */
    private static byte[] resource(String name) throws Exception {
        try(var stream=EmqxOtaInstallStopPublisherTests.class.getResourceAsStream("/ota/"+name)){
            if(stream==null)throw new IllegalStateException("公开向量缺失");return stream.readAllBytes();
        }
    }
}
