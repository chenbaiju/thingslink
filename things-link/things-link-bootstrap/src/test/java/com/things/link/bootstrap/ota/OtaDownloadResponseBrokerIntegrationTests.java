package com.things.link.bootstrap.ota;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.things.link.ota.application.OtaDownloadResponseCodec;
import com.things.link.ota.application.OtaDownloadResponsePublisher;
import com.things.link.ota.infrastructure.EmqxOtaDownloadResponsePublisher;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.images.builder.Transferable;

/** 实际Java发布器→EMQX→MQTT协议证据；隔离Broker允许匿名订阅，不冒充应用设备鉴权验收。 */
@Testcontainers
class OtaDownloadResponseBrokerIntegrationTests {
    /** 隔离夹具专用发布身份，无生产权限。 */
    private static final String KEY = "ota-response-protocol-test";
    /** 隔离夹具专用API秘密。 */
    private static final String SECRET = "ota-response-protocol-secret-only-for-tests";
    /** 固定Broker镜像及随机端口，不访问共享开发Broker。 */
    @Container
    private static final GenericContainer<?> BROKER = new GenericContainer<>(DockerImageName.parse("emqx/emqx:6.2.3"))
            .withExposedPorts(1883, 1884, 18083)
            .withCopyToContainer(Transferable.of((KEY + ":" + SECRET + ":publisher\n").getBytes(StandardCharsets.UTF_8), 0444),
                    "/opt/emqx/etc/ota-test-api-keys")
            .withCopyToContainer(Transferable.of("""
                    # 覆盖镜像base.hocon后须显式恢复Dashboard/API监听，不能以节点存活冒充API就绪。
                    dashboard.listeners.http.bind = 18083
                    listeners.tcp.default.mountpoint = "tc/private/device/00000000-0000-0000-0000-000000000003/17/"
                    listeners.tcp.previous { bind = "0.0.0.0:1884", mountpoint = "tc/private/device/00000000-0000-0000-0000-000000000003/16/" }
                    authentication = []
                    authorization { no_match = allow, sources = [] }
                    api_key.bootstrap_file = "/opt/emqx/etc/ota-test-api-keys"
                    """.getBytes(StandardCharsets.UTF_8), 0444), "/opt/emqx/etc/base.hocon")
            .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    /** Broker实际交付精确秘密字节、QoS1和MQTT5过期属性；同授权重投不改正文，后订阅者无保留消息。 */
    @Test void actualPublisherDeliversSecretResponseWithMqttExpiry() throws Exception {
        String project = "project";
        String device = "device";
        String topic = OtaDownloadResponseCodec.topic(project, device);
        Instant expires=Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        byte[] bytes=response(expires);
        try (var subscriber = new Subscriber(topic);
             var previous = new Subscriber(topic, 1884);
             var publisher = new EmqxOtaDownloadResponsePublisher("http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(18083), KEY, SECRET)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                var result = publisher.publish(new com.things.link.device.application.DeviceMqttDownlinkRoute(
                        new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), 17, project, device), bytes, expires, Duration.ofSeconds(5));
                assertEquals(OtaDownloadResponsePublisher.Outcome.BROKER_ACCEPTED, result.outcome());
                assertArrayEquals(bytes, subscriber.message(topic, expires));
            }
            previous.socket.setSoTimeout(500);
            assertThrows(SocketTimeoutException.class, () -> previous.message(topic, expires));
            try (var later = new Subscriber(topic)) {
                later.socket.setSoTimeout(500);
                assertThrows(SocketTimeoutException.class, () -> later.message(topic, expires));
            }
        }
    }

    /** 使用独立公开黄金向量生成合法秘密响应，不保留测试私钥。 */
    private static byte[] response(Instant expires) throws Exception {
        var json=new com.things.link.ota.application.OtaCanonicalJson();
        byte[] manifest=new com.things.link.ota.application.OtaManifestCodec().canonicalize(resource("manifest-v1.json"));
        byte[] signature=java.util.HexFormat.of().parseHex(new String(resource("manifest-v1.signature.hex"),StandardCharsets.UTF_8).trim());
        byte[] spki=java.util.HexFormat.of().parseHex(new String(resource("manifest-v1.spki.hex"),StandardCharsets.UTF_8).trim());
        var fields=json.parseObject(manifest);
        return new OtaDownloadResponseCodec().encode(new OtaDownloadResponseCodec.Response("tc-ota-download-response/v1",
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.fromString((String)fields.get("firmwareId")),1,
                sha(manifest),manifest,signature,spki,com.things.link.ota.application.OtaSignatureProfile.ED25519_V1,sha(spki),
                java.net.URI.create("https://storage.example/object?X-Amz-Signature=broker-test-secret"),expires));
    }
    /** 公开黄金材料SHA256。 */
    private static String sha(byte[] bytes) throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    /** 仅读取仓库公开材料，不接触生产配置。 */
    private static byte[] resource(String name) throws Exception {
        // OTA模块的test资源不属于依赖JAR；从仓库唯一黄金向量读取，避免复制后漂移。
        java.nio.file.Path cursor=java.nio.file.Path.of("").toAbsolutePath();
        while(cursor!=null){
            var candidate=cursor.resolve("things-link/things-link-ota/src/test/resources/ota").resolve(name);
            if(java.nio.file.Files.isRegularFile(candidate))return java.nio.file.Files.readAllBytes(candidate);
            cursor=cursor.getParent();
        }
        throw new IllegalStateException("公开OTA向量缺失");
    }

    /** 最小MQTT5订阅夹具，只处理本测试必需报文并有界读取。 */
    private static final class Subscriber implements AutoCloseable {
        /** 真实Broker连接。 */ private final Socket socket;
        /** 有界网络输入。 */ private final DataInputStream input;
        /** MQTT编码输出。 */ private final DataOutputStream output;
        /** 建立匿名协议夹具连接并等待SUBACK，避免订阅竞态。 */
        private Subscriber(String topic) throws IOException { this(topic, 1883); }
        /** 受控旧代次监听器验证秘密响应不跨空间交付。 */
        private Subscriber(String topic, int port) throws IOException {
            socket = new Socket(BROKER.getHost(), BROKER.getMappedPort(port));
            socket.setSoTimeout(5000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            var connect = new ByteArrayOutputStream();
            var body = new DataOutputStream(connect);
            utf(body, "MQTT");
            body.writeByte(5);
            body.writeByte(2);
            body.writeShort(30);
            body.writeByte(0); // CONNECT无额外属性。
            utf(body, "ota-response-protocol-" + UUID.randomUUID());
            send(0x10, connect.toByteArray());
            Packet ack = read();
            assertEquals(0x20, ack.header());
            assertEquals(0,ack.body()[0]);assertEquals(0,ack.body()[1]);
            var subscribe = new ByteArrayOutputStream();
            body = new DataOutputStream(subscribe);
            body.writeShort(1);
            body.writeByte(0); // SUBSCRIBE属性长度为零。
            utf(body, topic);
            body.writeByte(1);
            send(0x82, subscribe.toByteArray());
            ack = read();
            assertEquals(0x90, ack.header());
            assertArrayEquals(new byte[] {0, 1, 0, 1}, ack.body());
        }
        /** 实际PUBLISH必须QoS1且非retained，检查原生过期属性并返回精确载荷；不冒称验证离线到期删除。 */
        private byte[] message(String expectedTopic,Instant expires) throws IOException {
            Packet packet = read();
            assertEquals(0x32, packet.header());
            var body = new DataInputStream(new java.io.ByteArrayInputStream(packet.body()));
            int topicLength = body.readUnsignedShort();
            assertEquals(expectedTopic, new String(body.readNBytes(topicLength), StandardCharsets.UTF_8));
            int id = body.readUnsignedShort();
            int properties=body.readUnsignedByte();
            assertEquals(5,properties);assertEquals(0x02,body.readUnsignedByte());
            long remaining=Integer.toUnsignedLong(body.readInt());
            assertTrue(remaining>0);assertTrue(remaining<=Duration.between(Instant.now(),expires).getSeconds());
            byte[] payload = body.readAllBytes();
            send(0x40, new byte[] {(byte) (id >>> 8), (byte) id});
            return payload;
        }
        /** 剩余长度最多四字节且报文最多64KiB，坏协议立即失败。 */
        private Packet read() throws IOException {
            int header = input.readUnsignedByte();
            int size = 0;
            int multiplier = 1;
            for (int index = 0; index < 4; index++) {
                int encoded = input.readUnsignedByte();
                size += (encoded & 127) * multiplier;
                if (size > 65536) throw new IOException("MQTT测试报文超限");
                if ((encoded & 128) == 0) {
                    byte[] bytes = input.readNBytes(size);
                    if (bytes.length != size) throw new IOException("MQTT测试报文截断");
                    return new Packet(header, bytes);
                }
                multiplier *= 128;
            }
            throw new IOException("MQTT测试长度无效");
        }
        /** MQTT固定头与可变剩余长度。 */
        private void send(int header, byte[] body) throws IOException {
            output.writeByte(header);
            int remaining = body.length;
            do {
                int encoded = remaining % 128;
                remaining /= 128;
                output.writeByte(encoded | (remaining == 0 ? 0 : 128));
            } while (remaining != 0);
            output.write(body);
            output.flush();
        }
        /** MQTT采用UTF8字节长度，不能使用Java修改UTF格式。 */
        private static void utf(DataOutputStream output, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            output.writeShort(bytes.length);
            output.write(bytes);
        }
        /** 确保测试成功或失败都关闭物理连接。 */
        @Override public void close() throws IOException { socket.close(); }
    }
    /** 有界协议包。
     * @param header 固定头
     * @param body 剩余正文
     */
    private record Packet(int header, byte[] body) { }
}
