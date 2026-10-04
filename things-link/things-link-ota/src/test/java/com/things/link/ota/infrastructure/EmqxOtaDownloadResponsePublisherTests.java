package com.things.link.ota.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.things.link.ota.application.OtaDownloadResponseCodec;
import com.things.link.ota.application.OtaDownloadResponsePublisher;
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
class EmqxOtaDownloadResponsePublisherTests {
    /** 本测试类共享足够长且固定的外层截止，避免独立取时不一致。 */
    private static final Instant EXPIRY=Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
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
            var result = publisher.publish(route(), payload(), EXPIRY, Duration.ofSeconds(2));
            assertEquals(OtaDownloadResponsePublisher.Outcome.REJECTED, result.outcome());
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
            var result = publisher.publish(route(), payload(), EXPIRY, Duration.ofSeconds(2));
            assertEquals(OtaDownloadResponsePublisher.Outcome.REJECTED, result.outcome());
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
            var result = publisher.publish(route(), payload(), EXPIRY, Duration.ofMillis(250));
            assertEquals(OtaDownloadResponsePublisher.Outcome.UNKNOWN, result.outcome());
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
            var call = executor.submit(() -> publisher.publish(route(), payload(), EXPIRY, Duration.ofSeconds(5)));
            assertTrue(received.await(2, TimeUnit.SECONDS));
            publisher.close();
            assertEquals(OtaDownloadResponsePublisher.Outcome.UNKNOWN, call.get(2, TimeUnit.SECONDS).outcome());
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
            assertEquals(OtaDownloadResponsePublisher.Outcome.UNKNOWN,
                    publisher.publish(route(), payload(), EXPIRY, Duration.ofSeconds(1)).outcome());
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
            assertEquals(OtaDownloadResponsePublisher.Outcome.BROKER_ACCEPTED,
                    publisher.publish(route(), payload(), EXPIRY, Duration.ofSeconds(2)).outcome());
            assertEquals("RESPONSE_TOO_LARGE", publisher.publish(route(), payload(), EXPIRY, Duration.ofSeconds(2)).reason());
        } finally { server.stop(0); }
    }
    /** 不完整配置不创建网络客户端。 */
    @Test void missingConfigurationIsNotSuccess() {
        try (var publisher = new EmqxOtaDownloadResponsePublisher("http://127.0.0.1:1", "", "")) {
            assertFalse(publisher.configured());
            assertEquals(OtaDownloadResponsePublisher.Outcome.UNKNOWN,
                    publisher.publish(route(), payload(), EXPIRY, Duration.ofSeconds(1)).outcome());
        }
    }
    /** 真实HTTP请求携带精确响应Topic、Base64秘密及保守消息过期秒数。 */
    @Test void publishesExactSecretEnvelopeWithBoundedExpiry() throws Exception {
        var captured=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v5/publish",exchange->{captured.set(exchange.getRequestBody().readAllBytes());exchange.sendResponseHeaders(202,-1);exchange.close();});
        server.start();
        try(var publisher=publisher(server.getAddress().getPort())) {
            byte[] secret=payload();var result=publisher.publish(route(),secret,EXPIRY,Duration.ofSeconds(2));
            assertEquals(OtaDownloadResponsePublisher.Outcome.BROKER_ACCEPTED,result.outcome());
            var body=new com.things.link.ota.application.OtaCanonicalJson().parseObject(captured.get());
            assertEquals(route().internalTopic("tc/v1/project/device/down/ota/download/response"),body.get("topic"));
            assertEquals(1L,body.get("qos"));assertEquals(false,body.get("retain"));assertEquals("base64",body.get("payload_encoding"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(secret,java.util.Base64.getDecoder().decode((String)body.get("payload")));
            var properties=(java.util.Map<?,?>)body.get("properties");long expiry=((Number)properties.get("message_expiry_interval")).longValue();
            assertTrue(expiry>0);assertTrue(expiry<=Duration.between(Instant.now(),EXPIRY).getSeconds());
            assertFalse(result.toString().contains("test-secret"));assertFalse(result.toString().contains("storage.example"));
        }finally{server.stop(0);}
    }
    /** 过期、不一致期限和非规范正文均在发送前拒绝，不能泄露秘密。 */
    @Test void rejectsExpiredMismatchAndNoncanonicalBeforeNetwork() throws Exception {
        AtomicInteger calls=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v5/publish",exchange->{calls.incrementAndGet();exchange.close();});server.start();
        try(var publisher=publisher(server.getAddress().getPort())){
            byte[] body=payload();
            assertEquals("INVALID_DOWNLOAD_RESPONSE",publisher.publish(route(),body,EXPIRY.plusSeconds(1),Duration.ofSeconds(1)).reason());
            byte[] noncanonical=(new String(body,java.nio.charset.StandardCharsets.UTF_8)+" ").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("INVALID_DOWNLOAD_RESPONSE",publisher.publish(route(),noncanonical,EXPIRY,Duration.ofSeconds(1)).reason());
            var json=new com.things.link.ota.application.OtaCanonicalJson();var fields=new java.util.LinkedHashMap<>(json.parseObject(body));
            Instant past=Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);fields.put("expiresAt",past.toString());
            var codec=new OtaDownloadResponseCodec();byte[] expired=codec.encode(codec.decode(json.writeObject(fields)));
            assertEquals("DEADLINE_EXPIRED",publisher.publish(route(),expired,past,Duration.ofSeconds(1)).reason());
            assertEquals(0,calls.get());
        }finally{server.stop(0);}
    }
    /** 完整合法大清单的Base64单字段超过16KiB，仍可交由Broker接受。 */
    @Test void acceptsLargeCanonicalResponseWithoutOldStringBudget() throws Exception {
        var captured=new java.util.concurrent.atomic.AtomicReference<byte[]>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v5/publish",exchange->{captured.set(exchange.getRequestBody().readAllBytes());exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try(var publisher=publisher(server.getAddress().getPort())){
            byte[] body=payload(true);assertTrue(java.util.Base64.getEncoder().encodeToString(body).length()>16384);
            assertEquals(OtaDownloadResponsePublisher.Outcome.BROKER_ACCEPTED,publisher.publish(route(),body,EXPIRY,Duration.ofSeconds(2)).outcome());
            var envelope=tools.jackson.databind.json.JsonMapper.builder().build().readTree(captured.get());
            org.junit.jupiter.api.Assertions.assertArrayEquals(body,java.util.Base64.getDecoder().decode(envelope.path("payload").asText()));
        }finally{server.stop(0);}
    }
    /** 缺路由不能将封存秘密回退发布到裸主题。 */
    @Test void missingRouteNeverPublishesSecret() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v5/publish",exchange->{calls.incrementAndGet();exchange.close();});server.start();
        try(var publisher=publisher(server.getAddress().getPort())) {
            assertEquals("INVALID_DOWNLOAD_RESPONSE",publisher.publish(null,payload(),EXPIRY,Duration.ofSeconds(1)).reason());
            assertEquals(0,calls.get());
        }finally{server.stop(0);}
    }
    /** 数据库事务未结束时禁止任何物理秘密发送。 */
    @Test void ambientTransactionRejectsPhysicalSend() {
        try(var publisher=publisher(1)) {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        ()->publisher.publish(route(),payload(),EXPIRY,Duration.ofSeconds(1)));
            }finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
        }
    }
    /** 显式测试代次，缺路由不回退存量。 */
    private static com.things.link.device.application.DeviceMqttDownlinkRoute route() {
        return new com.things.link.device.application.DeviceMqttDownlinkRoute(
                new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), 17, "project", "device");
    }
    /** 固定本机测试身份。 */
    private static EmqxOtaDownloadResponsePublisher publisher(int port) {
        return new EmqxOtaDownloadResponsePublisher("http://127.0.0.1:" + port, "test-key", "test-secret");
    }
    /** 使用独立公开签名向量，正文包含测试URL但结果不能泄露它。 */
    private static byte[] payload() {return payload(false);}
    /** 大合法manifest仍允许通过HTTP Base64信封，不受旧单字段预算误拒绝。 */
    private static byte[] payload(boolean large) {
        try {
            var json=new com.things.link.ota.application.OtaCanonicalJson();
            byte[] manifest=new com.things.link.ota.application.OtaManifestCodec().canonicalize(resource("manifest-v1.json"));
            byte[] signature=java.util.HexFormat.of().parseHex(new String(resource("manifest-v1.signature.hex"),java.nio.charset.StandardCharsets.UTF_8).trim());
            byte[] spki=java.util.HexFormat.of().parseHex(new String(resource("manifest-v1.spki.hex"),java.nio.charset.StandardCharsets.UTF_8).trim());
            if(large){
                var fields=new java.util.LinkedHashMap<>(json.parseObject(manifest));
                var ids=new java.util.ArrayList<String>();for(int i=1;i<=256;i++)ids.add(new UUID(0,i).toString());
                fields.put("allowedSourceThingModelVersionIds",ids);fields.put("firmwareVersion","固".repeat(128));
                var pair=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();spki=pair.getPublic().getEncoded();
                fields.put("signingKeyFingerprint",sha(spki));manifest=new com.things.link.ota.application.OtaManifestCodec().canonicalize(json.writeObject(fields));
                var signing=java.security.Signature.getInstance("Ed25519");signing.initSign(pair.getPrivate());
                signing.update("thingslink-ota-release-manifest-v1\u0000".getBytes(java.nio.charset.StandardCharsets.UTF_8));signing.update(manifest);signature=signing.sign();
            }
            var fields=json.parseObject(manifest);
            return new OtaDownloadResponseCodec().encode(new OtaDownloadResponseCodec.Response("tc-ota-download-response/v1",
                    UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.fromString((String)fields.get("firmwareId")),1,
                    sha(manifest),manifest,signature,spki,com.things.link.ota.application.OtaSignatureProfile.ED25519_V1,sha(spki),
                    java.net.URI.create("https://storage.example/object?X-Amz-Signature=test-secret"),EXPIRY));
        } catch(Exception failure){throw new IllegalStateException("公开测试向量读取失败",failure);}
    }
    /** 公开黄金向量摘要。 */
    private static String sha(byte[] bytes) throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    /** 只读取仓库公开向量，禁止输出秘密正文。 */
    private static byte[] resource(String name) throws Exception {
        try(var stream=EmqxOtaDownloadResponsePublisherTests.class.getResourceAsStream("/ota/"+name)){
            if(stream==null)throw new IllegalStateException("公开向量缺失");return stream.readAllBytes();
        }
    }
}
