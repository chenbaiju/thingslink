package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.ingestion.application.MqttDownlinkAdmissionService;
import static com.things.link.ingestion.infrastructure.MqttRouteFixtures.route;

import com.things.link.ingestion.application.CommandDispatchException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandDispatchFailure;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.shared.message.TopologyReplyMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** EMQX HTTP 发布适配器测试，固定独立身份、Topic、QoS 和 retained 契约。 */
class EmqxHttpCommandPublisherTests {

    /** 有效派发必须以 Basic API 身份调用 v5 publish，且 payload 不重复携带 commandId。 */
    @Test
    void publishesQosOneNonRetainedCommand() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String expectedAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                "downlink-key:downlink-secret".getBytes(StandardCharsets.UTF_8));
        DeviceCommandDispatch dispatch = dispatch();
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode expectedBody = objectMapper.createObjectNode();
        expectedBody.put("topic", route(dispatch).internalTopic("tc/v1/project_1/gateway_1/down/command/" + dispatch.commandId()));
        expectedBody.put("payload", "{\"targetDeviceKey\":\"child_1\",\"commandKey\":\"reboot\","
                + "\"input\":{\"force\":true},\"attempt\":2}");
        expectedBody.put("qos", 1);
        expectedBody.put("retain", false);
        server.expect(once(), requestTo("http://emqx:18083/api/v5/publish"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, expectedAuthorization))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(objectMapper.writeValueAsString(expectedBody)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        EmqxHttpCommandPublisher publisher = new EmqxHttpCommandPublisher(
                builder, objectMapper, "http://emqx:18083/", "downlink-key", "downlink-secret");

        assertThat(publisher.publish(dispatch, route(dispatch))).isNotNull();
        server.verify();
    }

    /** 属性设置必须走固定 property/set Topic，并在载荷中携带可关联回复的 requestId。 */
    @Test
    void publishesPropertySetWithStableRequestId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeviceCommandDispatch dispatch = propertySetDispatch();
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode expectedBody = objectMapper.createObjectNode();
        expectedBody.put("topic", route(dispatch).internalTopic("tc/v1/project_1/gateway_1/down/property/set"));
        expectedBody.put("payload", "{\"targetDeviceKey\":\"child_1\",\"requestId\":\""
                + dispatch.commandId() + "\",\"properties\":{\"enabled\":true},\"attempt\":1}");
        expectedBody.put("qos", 1);
        expectedBody.put("retain", false);
        server.expect(once(), requestTo("http://emqx:18083/api/v5/publish"))
                .andExpect(content().json(objectMapper.writeValueAsString(expectedBody)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        EmqxHttpCommandPublisher publisher = new EmqxHttpCommandPublisher(
                builder, objectMapper, "http://emqx:18083/", "downlink-key", "downlink-secret");

        assertThat(publisher.publish(dispatch, route(dispatch))).isNotNull();
        server.verify();
    }

    /** 拓扑回执必须走固定 down/topo/reply Topic，且只暴露 requestId/subDeviceKey/status 与失败原因。 */
    @Test
    void publishesTopologyReplyToFixedTopic() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper objectMapper = new ObjectMapper();
        TopologyReplyMessage reply = topologyReply();
        ObjectNode expectedBody = objectMapper.createObjectNode();
        expectedBody.put("topic", route(reply).internalTopic("tc/v1/project_1/gateway_1/down/topo/reply"));
        expectedBody.put("payload", "{\"requestId\":\"" + reply.requestId()
                + "\",\"subDeviceKey\":\"sub_01\",\"status\":\"FAILED\","
                + "\"errorCode\":\"sub_device_invalid\",\"message\":\"子设备不存在\"}");
        expectedBody.put("qos", 1);
        expectedBody.put("retain", false);
        server.expect(once(), requestTo("http://emqx:18083/api/v5/publish"))
                .andExpect(content().json(objectMapper.writeValueAsString(expectedBody)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        EmqxHttpCommandPublisher publisher = new EmqxHttpCommandPublisher(
                builder, objectMapper, "http://emqx:18083/", "downlink-key", "downlink-secret");

        assertThat(publisher.publishTopologyReply(reply, route(reply))).isNotNull();
        server.verify();
    }

    /** 凭据缺失时必须 fail-closed，并分类为 DISPATCH_CREDENTIALS_MISSING，不能回退为设备 Token 或匿名发布。 */
    @Test
    void rejectsMissingServiceCredential() {
        EmqxHttpCommandPublisher publisher = new EmqxHttpCommandPublisher(
                RestClient.builder(), new ObjectMapper(), "http://emqx:18083", "", "");

        assertThatThrownBy(() -> publish(publisher, dispatch()))
                .isInstanceOf(CommandDispatchException.class)
                .satisfies(e -> assertThat(((CommandDispatchException) e).failure())
                        .isEqualTo(DeviceCommandDispatchFailure.DISPATCH_CREDENTIALS_MISSING));
    }

    /** 客户端资源必须惰性创建；首次 selector I/O 失败后，下一次持久派发可重新初始化并成功。 */
    @Test
    void lazilyRetriesHttpClientInitialization() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicInteger attempts = new AtomicInteger();
        EmqxHttpCommandPublisher publisher = new EmqxHttpCommandPublisher(
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new UncheckedIOException(new IOException("loopback unavailable"));
                    }
                    return builder.baseUrl("http://emqx:18083").build();
                },
                new ObjectMapper(), "downlink-key", "downlink-secret",
                new EmqxDispatchMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        assertThat(attempts).hasValue(0);
        assertThatThrownBy(() -> publish(publisher, dispatch()))
                .isInstanceOf(CommandDispatchException.class)
                .satisfies(error -> assertThat(((CommandDispatchException) error).failure())
                        .isEqualTo(DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED));
        server.expect(once(), requestTo("http://emqx:18083/api/v5/publish"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(publish(publisher, dispatch())).isNotNull();
        assertThat(attempts).hasValue(2);
        server.verify();
    }

    /** EMQX 5xx 表示服务端暂时不可用，必须进入命令断路器的可重试失败集合。 */
    @Test
    void classifiesHttpRejection() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("http://emqx:18083/api/v5/publish"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        EmqxHttpCommandPublisher publisher = new EmqxHttpCommandPublisher(
                builder, new ObjectMapper(), "http://emqx:18083/", "downlink-key", "downlink-secret");

        assertThatThrownBy(() -> publish(publisher, dispatch()))
                .isInstanceOf(CommandDispatchException.class)
                .satisfies(e -> assertThat(((CommandDispatchException) e).failure())
                        .isEqualTo(DeviceCommandDispatchFailure.DISPATCH_HTTP_SERVER_ERROR));
        server.verify();
    }

    /** 3xx 重定向不代表 Broker 接受报文，必须按派发拒绝分类，不能被默认错误处理器静默当作成功。 */
    @Test
    void classifiesRedirectAsRejection() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("http://emqx:18083/api/v5/publish"))
                .andRespond(withStatus(HttpStatus.FOUND));
        EmqxHttpCommandPublisher publisher = new EmqxHttpCommandPublisher(
                builder, new ObjectMapper(), "http://emqx:18083/", "downlink-key", "downlink-secret");

        assertThatThrownBy(() -> publish(publisher, dispatch()))
                .isInstanceOf(CommandDispatchException.class)
                .satisfies(e -> assertThat(((CommandDispatchException) e).failure())
                        .isEqualTo(DeviceCommandDispatchFailure.DISPATCH_HTTP_REJECTED));
        server.verify();
    }

    /** 连续五次服务端故障后第六次必须在本进程内快速拒绝，不能继续占用 EMQX 连接。 */
    @Test
    void opensCommandCircuitAfterFiveRetryableFailures() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(5), requestTo("http://emqx:18083/api/v5/publish"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        EmqxHttpCommandPublisher publisher = new EmqxHttpCommandPublisher(
                builder, new ObjectMapper(), "http://emqx:18083/", "downlink-key", "downlink-secret");

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> publish(publisher, dispatch()))
                    .isInstanceOf(CommandDispatchException.class)
                    .satisfies(exception -> assertThat(((CommandDispatchException) exception).failure())
                            .isEqualTo(DeviceCommandDispatchFailure.DISPATCH_HTTP_SERVER_ERROR));
        }
        assertThatThrownBy(() -> publish(publisher, dispatch()))
                .isInstanceOf(CommandDispatchException.class)
                .satisfies(exception -> assertThat(((CommandDispatchException) exception).failure())
                        .isEqualTo(DeviceCommandDispatchFailure.DISPATCH_CIRCUIT_OPEN));
        server.verify();
    }

    /** D-050：EMQX 接受连接但不响应时必须按读取超时失败并分类为 DISPATCH_TIMEOUT，不能无限阻塞。 */
    @Test
    void timesOutWhenServerAcceptsButNeverResponds() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Socket> accepted = executor.submit(server::accept);
                EmqxHttpCommandPublisher publisher = new EmqxHttpCommandPublisher(
                        RestClient.builder(), new ObjectMapper(),
                        "http://localhost:" + server.getLocalPort(), "downlink-key", "downlink-secret",
                        Duration.ofSeconds(1), Duration.ofSeconds(1));

                assertThatThrownBy(() -> publish(publisher, dispatch()))
                        .isInstanceOf(CommandDispatchException.class)
                        .satisfies(e -> assertThat(((CommandDispatchException) e).failure())
                                .isEqualTo(DeviceCommandDispatchFailure.DISPATCH_TIMEOUT));
                accepted.get(5, TimeUnit.SECONDS).close();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /** EMQX 5 会关闭 h2c Upgrade 请求；生产客户端必须直接使用 HTTP/1.1，不能把 EOF 误报为连接失败。 */
    @Test
    void usesHttpOneWithoutH2cUpgradeForEmqxCompatibility() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> requestHeaders = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(3_000);
                        InputStream input = socket.getInputStream();
                        String headers = readRequestHeaders(input);
                        if (headers.toLowerCase().contains("upgrade: h2c")) {
                            // 等价于本地 EMQX 5 实测行为：收到 h2c Upgrade 后直接断开，调用侧只能看到 EOF。
                            return headers;
                        }
                        // Linux 会在服务端带未读请求体关闭连接时向仍在写正文的客户端发送 RST；先读完正文才能只验证协议版本。
                        drainRequestBody(input, headers);
                        OutputStream output = socket.getOutputStream();
                        output.write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                        return headers;
                    }
                });
                EmqxHttpCommandPublisher publisher = new EmqxHttpCommandPublisher(
                        RestClient.builder(), new ObjectMapper(), "http://localhost:" + server.getLocalPort(),
                        "downlink-key", "downlink-secret", Duration.ofSeconds(1), Duration.ofSeconds(3));

                assertThat(publish(publisher, dispatch())).isNotNull();
                assertThat(requestHeaders.get(5, TimeUnit.SECONDS).toLowerCase()).doesNotContain("upgrade: h2c");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /** 慢命令占用 command 槽时，config 必须使用独立 bulkhead 并完成，不能被命令故障域饿死。 */
    @Test
    void slowCommandDoesNotBlockConfigOperation() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService serverExecutor = Executors.newFixedThreadPool(3);
            ExecutorService clientExecutor = Executors.newSingleThreadExecutor();
            CountDownLatch commandAccepted = new CountDownLatch(1);
            CountDownLatch releaseCommand = new CountDownLatch(1);
            CountDownLatch configResponded = new CountDownLatch(1);
            try {
                serverExecutor.submit(() -> acceptTwoRequests(
                        server, serverExecutor, commandAccepted, releaseCommand, configResponded));
                EmqxHttpCommandPublisher publisher = new EmqxHttpCommandPublisher(
                        RestClient.builder(), new ObjectMapper(), "http://localhost:" + server.getLocalPort(),
                        "downlink-key", "downlink-secret", Duration.ofSeconds(1), Duration.ofSeconds(5));

                Future<Instant> command = clientExecutor.submit(() -> publish(publisher, dispatch()));
                assertThat(commandAccepted.await(5, TimeUnit.SECONDS)).isTrue();

                assertThat(publishConfig(publisher, deviceConfigPush())).isNotNull();
                assertThat(configResponded.await(2, TimeUnit.SECONDS)).isTrue();

                releaseCommand.countDown();
                assertThat(command.get(2, TimeUnit.SECONDS)).isNotNull();
            } finally {
                releaseCommand.countDown();
                clientExecutor.shutdownNow();
                serverExecutor.shutdownNow();
            }
        }
    }

    /** 接受两条 HTTP 请求并交给独立处理线程，模拟同一 EMQX 上命令慢而配置正常。 */
    private static void acceptTwoRequests(
            ServerSocket server,
            ExecutorService executor,
            CountDownLatch commandAccepted,
            CountDownLatch releaseCommand,
            CountDownLatch configResponded) {
        try {
            for (int index = 0; index < 2; index++) {
                Socket socket = server.accept();
                boolean command = index == 0;
                executor.submit(() -> handleIsolatedRequest(
                        socket, command, commandAccepted, releaseCommand, configResponded));
            }
        } catch (java.io.IOException exception) {
            throw new AssertionError("EMQX bulkhead 测试接受连接失败", exception);
        }
    }

    /** 按测试已冻结的发起顺序区分命令与配置：只阻塞首条命令响应，配置立即返回 202。 */
    private static void handleIsolatedRequest(
            Socket socket,
            boolean command,
            CountDownLatch commandAccepted,
            CountDownLatch releaseCommand,
            CountDownLatch configResponded) {
        try (socket) {
            socket.setSoTimeout(3_000);
            InputStream input = socket.getInputStream();
            String headers = readRequestHeaders(input);
            drainRequestBody(input, headers);
            if (command) {
                commandAccepted.countDown();
                if (!releaseCommand.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("慢命令未在期限内释放");
                }
            }
            OutputStream output = socket.getOutputStream();
            output.write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
            if (!command) {
                configResponded.countDown();
            }
        } catch (Exception exception) {
            throw new AssertionError("EMQX bulkhead 测试处理请求失败", exception);
        }
    }

    /**
     * 从原始流精确读到 CRLFCRLF，不使用会预读正文的字符 Reader。
     * 测试夹具若吞掉正文前几个字节，就无法再按 Content-Length 可靠排空并会重新引入 Linux socket 竞态。
     */
    private static String readRequestHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 64 * 1024) {
            int value = input.read();
            if (value < 0) {
                throw new IOException("HTTP 请求头未完整到达");
            }
            bytes.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : value == '\r' ? 1 : 0;
                default -> 4;
            };
            if (matched == 4) {
                return bytes.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("HTTP 请求头超过测试夹具 64 KiB 上限");
    }

    /** 读取完整请求体后才返回响应，避免服务端 close 与 Java HttpClient 写正文之间产生 Broken pipe。 */
    private static void drainRequestBody(InputStream input, String headers) throws IOException {
        for (String line : headers.split("\\r\\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                int length = Integer.parseInt(line.substring("Content-Length:".length()).trim());
                if (input.readNBytes(length).length != length) {
                    throw new IOException("HTTP 请求体未按 Content-Length 完整到达");
                }
                return;
            }
        }
        if (headers.toLowerCase().contains("transfer-encoding: chunked")) {
            drainChunkedBody(input);
        }
    }

    /** Java HttpClient 可按平台与请求转换器选择 chunked；夹具必须完整消费各块和 trailer 后才能安全关闭 socket。 */
    private static void drainChunkedBody(InputStream input) throws IOException {
        while (true) {
            String sizeLine = readAsciiLine(input);
            String sizeText = sizeLine.split(";", 2)[0].trim();
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(sizeText, 16);
            } catch (NumberFormatException exception) {
                throw new IOException("非法 HTTP chunk 大小", exception);
            }
            if (chunkSize < 0 || chunkSize > 1024 * 1024) {
                throw new IOException("HTTP chunk 超过测试夹具 1 MiB 上限");
            }
            if (chunkSize == 0) {
                while (!readAsciiLine(input).isEmpty()) {
                    // 忽略 trailer 内容；空行表示 chunked 正文结束。
                }
                return;
            }
            if (input.readNBytes(chunkSize).length != chunkSize) {
                throw new IOException("HTTP chunk 未完整到达");
            }
            if (input.read() != '\r' || input.read() != '\n') {
                throw new IOException("HTTP chunk 结尾缺少 CRLF");
            }
        }
    }

    /** 从原始 HTTP 流读取一行 ASCII 控制信息，避免字符 Reader 跨过 chunk 边界预读正文。 */
    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean carriageReturn = false;
        while (bytes.size() < 8 * 1024) {
            int value = input.read();
            if (value < 0) {
                throw new IOException("HTTP 控制行未完整到达");
            }
            if (carriageReturn) {
                if (value == '\n') {
                    return bytes.toString(StandardCharsets.US_ASCII);
                }
                bytes.write('\r');
                carriageReturn = false;
            }
            if (value == '\r') {
                carriageReturn = true;
            } else {
                bytes.write(value);
            }
        }
        throw new IOException("HTTP 控制行超过测试夹具 8 KiB 上限");
    }

    /** 每条随机信封只生成一次，以同一身份构造显式测试许可。 */
    private static Instant publish(EmqxHttpCommandPublisher publisher, DeviceCommandDispatch dispatch) {
        return publisher.publish(dispatch, route(dispatch));
    }

    /** 配置bulkhead测试同样使用显式服务器路由。 */
    private static Instant publishConfig(EmqxHttpCommandPublisher publisher, DeviceConfigPush push) {
        return publisher.publishConfig(push, route(push));
    }

    /** 创建目标子设备、实际连接网关不同的第二次尝试，验证 Topic 使用连接设备。 */
    private static DeviceCommandDispatch dispatch() {
        return new DeviceCommandDispatch(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), 2, Uuid7.generate(), "child_1", Uuid7.generate(), "gateway_1", "project_1",
                "reboot", "{\"force\":true}", Instant.parse("2026-08-08T03:00:10Z"),
                "0123456789abcdef0123456789abcdef");
    }

    /** 构造不依赖命令定义的属性设置派发信封。 */
    private static DeviceCommandDispatch propertySetDispatch() {
        return new DeviceCommandDispatch(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), 1, Uuid7.generate(), "child_1", Uuid7.generate(), "gateway_1", "project_1",
                DeviceCommandDispatch.OperationType.PROPERTY_SET, null, "{\"enabled\":true}",
                Instant.parse("2026-08-08T03:00:10Z"), "0123456789abcdef0123456789abcdef");
    }

    /** @return 使用独立 CONFIG bulkhead 的最小有效配置下发信封 */
    private static DeviceConfigPush deviceConfigPush() {
        return new DeviceConfigPush(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "project_1", "gateway_1", DeviceConfigPush.CONFIG_TYPE, 1, List.of());
    }

    /** 构造一条失败回执，验证 Topic 与失败字段编码。 */
    private static TopologyReplyMessage topologyReply() {
        return new TopologyReplyMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "project_1", "gateway_1", "sub_01", TopologyReplyMessage.Status.FAILED,
                "sub_device_invalid", "子设备不存在", Instant.parse("2026-08-14T09:00:00Z"),
                "0123456789abcdef0123456789abcdef");
    }
}
