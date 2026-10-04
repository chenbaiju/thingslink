package com.things.link.bootstrap.ota;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.things.link.ota.application.OtaNotificationCodec;
import com.things.link.ota.application.OtaNotificationPublisher;
import com.things.link.ota.infrastructure.EmqxOtaNotificationPublisher;
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
class OtaNotificationBrokerIntegrationTests {
    /** 隔离夹具专用发布身份，无生产权限。 */
    private static final String KEY = "ota-protocol-test";
    /** 隔离夹具专用API秘密。 */
    private static final String SECRET = "ota-protocol-secret-only-for-tests";
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

    /** Broker实际交付精确规范字节与QoS1；同事件重投不改正文，后订阅者不收到保留消息。 */
    @Test void actualPublisherDeliversCanonicalNonRetainedNotification() throws Exception {
        String project = "project";
        String device = "device";
        String topic = OtaNotificationCodec.topic(project, device);
        UUID event = UUID.randomUUID();
        var value = new OtaNotificationCodec.Notification("tc-ota-available/v1", event, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, "a".repeat(64), Instant.parse("2026-09-12T00:00:00.123456Z"));
        byte[] bytes = new OtaNotificationCodec().encode(value);
        try (var subscriber = new Subscriber(topic);
             var previous = new Subscriber(topic, 1884);
             var publisher = new EmqxOtaNotificationPublisher("http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(18083), KEY, SECRET)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                var result = publisher.publish(new com.things.link.device.application.DeviceMqttDownlinkRoute(
                        new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), 17, project, device), bytes, Duration.ofSeconds(5));
                assertEquals(OtaNotificationPublisher.Outcome.BROKER_ACCEPTED, result.outcome());
                assertArrayEquals(bytes, subscriber.message(topic));
            }
            previous.socket.setSoTimeout(500);
            assertThrows(SocketTimeoutException.class, () -> previous.message(topic));
            try (var later = new Subscriber(topic)) {
                later.socket.setSoTimeout(500);
                assertThrows(SocketTimeoutException.class, () -> later.message(topic));
            }
        }
    }

    /** 最小MQTT3.1.1订阅夹具，只处理本测试必需报文并有界读取。 */
    private static final class Subscriber implements AutoCloseable {
        /** 真实Broker连接。 */ private final Socket socket;
        /** 有界网络输入。 */ private final DataInputStream input;
        /** MQTT编码输出。 */ private final DataOutputStream output;
        /** 建立匿名协议夹具连接并等待SUBACK，避免订阅竞态。 */
        private Subscriber(String topic) throws IOException { this(topic, 1883); }
        /** 独立监听器代表受控的旧代次命名空间。 */
        private Subscriber(String topic, int port) throws IOException {
            socket = new Socket(BROKER.getHost(), BROKER.getMappedPort(port));
            socket.setSoTimeout(5000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            var connect = new ByteArrayOutputStream();
            var body = new DataOutputStream(connect);
            utf(body, "MQTT");
            body.writeByte(4);
            body.writeByte(2);
            body.writeShort(30);
            utf(body, "ota-protocol-" + UUID.randomUUID());
            send(0x10, connect.toByteArray());
            Packet ack = read();
            assertEquals(0x20, ack.header());
            assertArrayEquals(new byte[] {0, 0}, ack.body());
            var subscribe = new ByteArrayOutputStream();
            body = new DataOutputStream(subscribe);
            body.writeShort(1);
            utf(body, topic);
            body.writeByte(1);
            send(0x82, subscribe.toByteArray());
            ack = read();
            assertEquals(0x90, ack.header());
            assertArrayEquals(new byte[] {0, 1, 1}, ack.body());
        }
        /** 实际PUBLISH必须QoS1且非retained，返回精确剩余载荷。 */
        private byte[] message(String expectedTopic) throws IOException {
            Packet packet = read();
            assertEquals(0x32, packet.header());
            var body = new DataInputStream(new java.io.ByteArrayInputStream(packet.body()));
            int topicLength = body.readUnsignedShort();
            assertEquals(expectedTopic, new String(body.readNBytes(topicLength), StandardCharsets.UTF_8));
            int id = body.readUnsignedShort();
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
