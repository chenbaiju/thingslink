package com.things.link.bootstrap.ota;

import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OTA恢复出口的独占认证Broker；直接复用部署配置，不允许匿名或模拟认证通过。 */
final class OtaAuthenticatedBrokerFixture implements AutoCloseable {
    /** 仅独占容器内有效的发布身份，不访问开发Broker。 */
    static final String KEY = "ota-route-qualification", SECRET = "ota-route-owned-test-secret";
    /** 当前测试JVM独占的认证回调秘密。 */
    static final String CALLBACK = java.util.UUID.randomUUID().toString();
    /** 随当前测试关闭的真实EMQX节点。 */
    private final GenericContainer<?> broker;

    /** 先启动Broker再创建有期限的业务事实，避免启动耗时侵蚀原许可预算。 */
    OtaAuthenticatedBrokerFixture(int applicationPort, String callbackSecret) throws IOException {
        Testcontainers.exposeHostPorts(applicationPort);
        String config = Files.readString(Path.of("../../deploy/emqx/base.hocon"))
                .replace("http://host.docker.internal:8080", "http://host.testcontainers.internal:" + applicationPort)
                .replace("dev-only-broker-callback-secret-do-not-use-in-production", callbackSecret)
                + "\napi_key.bootstrap_file = \"/opt/emqx/etc/ota-route-keys\"\n";
        broker = new GenericContainer<>("emqx/emqx:6.2.3")
                .withExposedPorts(1883, 18083)
                .withCopyToContainer(Transferable.of(config.getBytes(StandardCharsets.UTF_8), 0444),
                        "/opt/emqx/etc/base.hocon")
                .withCopyToContainer(Transferable.of((KEY + ":" + SECRET + ":publisher\n")
                        .getBytes(StandardCharsets.UTF_8), 0444), "/opt/emqx/etc/ota-route-keys")
                .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(120)));
        try { broker.start(); } catch (RuntimeException failure) { broker.stop(); throw failure; }
    }

    /** 真实生产发布器使用本节点HTTP API。 */
    String api() { return "http://" + broker.getHost() + ":" + broker.getMappedPort(18083); }

    /** 通过真实认证和ACL订阅设备原Topic，服务器独立决定配置命名空间。 */
    Subscriber subscribe(String clientId, String username, String password, String topic) throws IOException {
        return new Subscriber(broker.getHost(), broker.getMappedPort(1883), clientId, username, password, topic);
    }

    /** 仅清理本对象创建的容器。 */
    @Override public void close() { broker.stop(); }

    /** 有界MQTT5线协议对照；显式核验原Topic、QoS、retain和消息过期属性。 */
    static final class Subscriber implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;

        /** 同一线协议Client ID可用于跨配置连接，由生产认证生成不同有效身份。 */
        Subscriber(String host, int port, String clientId, String username, String password, String topic)
                throws IOException {
            socket = new Socket(host, port);
            socket.setSoTimeout(5000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            try {
                var bytes = new ByteArrayOutputStream();
                var body = new DataOutputStream(bytes);
                utf(body, "MQTT"); body.writeByte(5); body.writeByte(0xC2); body.writeShort(120);
                body.writeByte(0); utf(body, clientId); utf(body, username); utf(body, password);
                send(0x10, bytes.toByteArray());
                Packet ack = read();
                assertThat(ack.header()).isEqualTo(0x20);
                assertThat(ack.body()[1]).as("真实认证必须允许设备").isZero();
                bytes.reset(); body.writeShort(1); body.writeByte(0); utf(body, topic); body.writeByte(1);
                send(0x82, bytes.toByteArray());
                Packet suback = read();
                assertThat(suback.header()).isEqualTo(0x90);
                assertThat(suback.body()).containsExactly((byte) 0, (byte) 1, (byte) 0, (byte) 1);
            } catch (IOException | RuntimeException | AssertionError failure) { socket.close(); throw failure; }
        }

        /** 收到规范正文才确认QoS1，不以HTTP成功冒充设备交付。 */
        byte[] message(String expectedTopic) throws IOException {
            Packet packet = read();
            assertThat(packet.header()).as("首次QoS1、非retain发布").isEqualTo(0x32);
            var body = new DataInputStream(new ByteArrayInputStream(packet.body()));
            String topic = new String(body.readNBytes(body.readUnsignedShort()), StandardCharsets.UTF_8);
            assertThat(topic).isEqualTo(expectedTopic);
            int id = body.readUnsignedShort();
            int length = variable(body);
            var properties = new DataInputStream(new ByteArrayInputStream(body.readNBytes(length)));
            assertThat(properties.readUnsignedByte()).as("保留原消息过期合同").isEqualTo(2);
            assertThat(Integer.toUnsignedLong(properties.readInt())).isPositive();
            assertThat(properties.available()).isZero();
            byte[] canonical = body.readAllBytes();
            send(0x40, new byte[]{(byte) (id >>> 8), (byte) id});
            return canonical;
        }

        /** 旧连接无首字节且仍可PING，排除被踢断才收不到的伪隔离。 */
        void assertQuietAndAlive() throws IOException {
            socket.setSoTimeout(700);
            assertThatThrownBy(input::readUnsignedByte).isInstanceOf(SocketTimeoutException.class);
            socket.setSoTimeout(5000);
            send(0xC0, new byte[0]);
            Packet pong = read();
            assertThat(pong.header()).isEqualTo(0xD0);
            assertThat(pong.body()).isEmpty();
        }

        /** 有限长度读取避免损坏报文造成无限分配。 */
        private Packet read() throws IOException {
            int header = input.readUnsignedByte(), length = variable(input);
            if (length > 65536) throw new IOException("MQTT测试报文超出64KiB");
            byte[] body = input.readNBytes(length);
            if (body.length != length) throw new EOFException();
            return new Packet(header, body);
        }

        /** 写入一个完整MQTT控制报文。 */
        private void send(int header, byte[] body) throws IOException {
            output.writeByte(header);
            int length = body.length;
            do { int part = length % 128; length /= 128; output.writeByte(part | (length == 0 ? 0 : 128)); }
            while (length != 0);
            output.write(body); output.flush();
        }

        /** MQTT变长整数最多四字节。 */
        private static int variable(DataInputStream stream) throws IOException {
            int value = 0, factor = 1;
            for (int index = 0; index < 4; index++) {
                int part = stream.readUnsignedByte(); value += (part & 127) * factor;
                if ((part & 128) == 0) return value;
                factor *= 128;
            }
            throw new IOException("无效MQTT变长整数");
        }

        /** MQTT UTF-8长度使用编码后字节数。 */
        private static void utf(DataOutputStream stream, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            stream.writeShort(bytes.length); stream.write(bytes);
        }

        /** 断开本用例客户端，不操作其他会话。 */
        @Override public void close() throws IOException { socket.close(); }

        /** 原始控制包头与内容，不记录凭据。 */
        private record Packet(int header, byte[] body) { }
    }
}
