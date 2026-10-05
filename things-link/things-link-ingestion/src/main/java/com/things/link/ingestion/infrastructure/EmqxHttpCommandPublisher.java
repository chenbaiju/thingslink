package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;
import com.things.link.ingestion.application.CommandDispatchException;
import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandDispatchFailure;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.shared.message.ModbusRequest;
import com.things.link.shared.message.TopologyReplyMessage;
import com.things.link.support.resilience.FailureCircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 使用独立 EMQX HTTP API 身份发布 MQTT 命令。
 *
 * <p>ADR 0021 明确排除了“平台冒用设备 Access Token”：设备凭据只能访问设备自己的 Topic，而平台发布者
 * 是另一类服务身份。API Key/Secret 仅由环境变量注入，既不进入数据库，也绝不能进入 {@code VITE_} 前缀。</p>
 *
 * <p>D-037/D-050：发布必须能在有限时间内返回或抛错——否则命令状态机（派发失败重试/终态）无法推进，
 * 一个挂起的 EMQX HTTP 连接会随 concurrency=1 停滞整条下行链路。因此这里显式设置 connect/read timeout，
 * 并把凭据缺失/超时/连接失败/HTTP 拒绝统一分类为 {@link CommandDispatchException}，供消费者报告
 * telemetry 状态机，而不把可重试的发布失败抛回 Kafka 与业务重试叠加。</p>
 */
@Component
public class EmqxHttpCommandPublisher implements CommandDownlinkPublisher {

    /** EMQX 5 发布 API 固定路径。 */
    private static final String PUBLISH_PATH = "/api/v5/publish";

    /** 首次真实下行后缓存的 HTTP 客户端；Bean 构造时不得占用宿主 selector 资源。 */
    private volatile RestClient restClient;
    /** 客户端工厂；创建失败不缓存，使下一次持久下行尝试可以重新初始化。 */
    private final Supplier<RestClient> restClientFactory;
    /** Boot 统一 JSON 映射器，保证 input 与 HTTP 请求不经过手工字符串拼接。 */
    private final ObjectMapper objectMapper;
    /** 独立 API Key；空值允许应用启动，但每次发布都会明确失败并交给有限重试。 */
    private final String apiKey;
    /** 独立 API Secret；不会进入日志或异常文本。 */
    private final String apiSecret;
    /** command/config/topology/modbus 独立槽，不允许慢操作借用其他操作容量。 */
    private final Map<Operation, Semaphore> bulkheads;
    /** 命令专属断路器；其他三类没有等价持久恢复事实，冻结为不加断路器。 */
    private final FailureCircuitBreakerRegistry commandCircuit;
    /** 固定操作 bulkhead 与命令断路器低基数指标。 */
    private final EmqxDispatchMetrics metrics;

    /**
     * 创建带连接与读取上限的生产发布器（D-050）。
     *
     * @param objectMapper 统一 JSON 映射器
     * @param baseUrl EMQX 管理 API 根地址
     * @param apiKey 环境注入的最小权限 Key
     * @param apiSecret 环境注入的最小权限 Secret
     * @param connectTimeout 连接建立上限
     * @param readTimeout 读取响应上限
     */
    @Autowired
    public EmqxHttpCommandPublisher(
            ObjectMapper objectMapper,
            @Value("${things-link.ingestion.emqx-api.base-url:http://localhost:18083}") String baseUrl,
            @Value("${things-link.ingestion.emqx-api.api-key:}") String apiKey,
            @Value("${things-link.ingestion.emqx-api.api-secret:}") String apiSecret,
            @Value("${things-link.ingestion.emqx-api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${things-link.ingestion.emqx-api.read-timeout:5s}") Duration readTimeout,
            EmqxDispatchMetrics metrics) {
        this(() -> RestClient.builder()
                        .requestFactory(timeoutRequestFactory(connectTimeout, readTimeout))
                        .baseUrl(normalizeBaseUrl(baseUrl))
                        .build(),
                objectMapper, apiKey, apiSecret, metrics);
    }

    /**
     * 测试可注入绑定 MockRestServiceServer 的 builder；不设置超时，避免覆盖 mock 请求工厂。
     *
     * @param builder HTTP 客户端构建器
     * @param objectMapper 统一 JSON 映射器
     * @param baseUrl EMQX 管理 API 根地址
     * @param apiKey 环境注入的最小权限 Key
     * @param apiSecret 环境注入的最小权限 Secret
     */
    EmqxHttpCommandPublisher(RestClient.Builder builder, ObjectMapper objectMapper,
                             String baseUrl, String apiKey, String apiSecret) {
        this(builder, objectMapper, baseUrl, apiKey, apiSecret, null, null);
    }

    /**
     * 全参构造：超时非空时写入 JDK 请求工厂（连接 + 读取上限），供生产与超时测试使用。
     */
    EmqxHttpCommandPublisher(RestClient.Builder builder, ObjectMapper objectMapper,
                             String baseUrl, String apiKey, String apiSecret,
                             Duration connectTimeout, Duration readTimeout) {
        this(builder, objectMapper, baseUrl, apiKey, apiSecret, connectTimeout, readTimeout,
                new EmqxDispatchMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    /** 全参构造并允许注入统一指标门面。 */
    private EmqxHttpCommandPublisher(RestClient.Builder builder, ObjectMapper objectMapper,
                             String baseUrl, String apiKey, String apiSecret,
                             Duration connectTimeout, Duration readTimeout, EmqxDispatchMetrics metrics) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        RestClient.Builder configured = builder;
        if (connectTimeout != null && readTimeout != null) {
            configured = builder.requestFactory(timeoutRequestFactory(connectTimeout, readTimeout));
        }
        this.restClient = configured.baseUrl(normalizedBaseUrl).build();
        this.restClientFactory = null;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.apiSecret = apiSecret == null ? "" : apiSecret.strip();
        this.bulkheads = new EnumMap<>(Operation.class);
        this.bulkheads.put(Operation.COMMAND, new Semaphore(8));
        this.bulkheads.put(Operation.CONFIG, new Semaphore(4));
        this.bulkheads.put(Operation.TOPOLOGY_REPLY, new Semaphore(4));
        this.bulkheads.put(Operation.MODBUS_REQUEST, new Semaphore(4));
        this.commandCircuit = new FailureCircuitBreakerRegistry(
                1, Duration.ofMinutes(30), 5, Duration.ofSeconds(10), java.time.Clock.systemUTC());
        this.metrics = metrics;
        Map<String, Semaphore> observed = new LinkedHashMap<>();
        Map<String, Integer> capacities = new LinkedHashMap<>();
        this.bulkheads.forEach((operation, semaphore) -> {
            observed.put(operation.name(), semaphore);
            capacities.put(operation.name(), semaphore.availablePermits());
        });
        this.metrics.registerBulkheads(Map.copyOf(observed), Map.copyOf(capacities));
    }

    /**
     * 生产惰性入口与负向测试共用初始化；工厂不得在构造阶段调用。
     *
     * @param restClientFactory 可重试的客户端工厂
     * @param objectMapper 统一 JSON 映射器
     * @param apiKey EMQX 管理接口密钥标识
     * @param apiSecret EMQX 管理接口密钥秘密值
     * @param metrics 固定低基数指标
     */
    EmqxHttpCommandPublisher(
            Supplier<RestClient> restClientFactory,
            ObjectMapper objectMapper,
            String apiKey,
            String apiSecret,
            EmqxDispatchMetrics metrics) {
        this.restClientFactory = restClientFactory;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.apiSecret = apiSecret == null ? "" : apiSecret.strip();
        this.bulkheads = new EnumMap<>(Operation.class);
        this.bulkheads.put(Operation.COMMAND, new Semaphore(8));
        this.bulkheads.put(Operation.CONFIG, new Semaphore(4));
        this.bulkheads.put(Operation.TOPOLOGY_REPLY, new Semaphore(4));
        this.bulkheads.put(Operation.MODBUS_REQUEST, new Semaphore(4));
        this.commandCircuit = new FailureCircuitBreakerRegistry(
                1, Duration.ofMinutes(30), 5, Duration.ofSeconds(10), java.time.Clock.systemUTC());
        this.metrics = metrics;
        Map<String, Semaphore> observed = new LinkedHashMap<>();
        Map<String, Integer> capacities = new LinkedHashMap<>();
        this.bulkheads.forEach((operation, semaphore) -> {
            observed.put(operation.name(), semaphore);
            capacities.put(operation.name(), semaphore.availablePermits());
        });
        this.metrics.registerBulkheads(Map.copyOf(observed), Map.copyOf(capacities));
    }

    /**
     * 把冻结信封编码成设备侧 JSON，并等待 EMQX 返回成功状态。
     *
     * <p>HTTP 成功只说明 Broker 接受报文，所以返回后调用方只能推进 {@code DISPATCHED}；设备 ACK 与终态
     * 必须由独立的 {@code up/command/{commandId}/reply} 回调推进。</p>
     */
    @Override
    public Instant publish(DeviceCommandDispatch dispatch, DeviceMqttDownlinkRoute route) {
        requireRoute(route, dispatch.tenantId(), dispatch.projectId(), dispatch.connectionDeviceId());
        String topic = route.internalTopic(mqttTopic(dispatch));
        OperationPermit permit = acquire(Operation.COMMAND);
        try (permit) {
            requireCredentials();
            post(topic, mqttPayload(dispatch));
            permit.success();
            return Instant.now();
        } catch (CommandDispatchException exception) {
            permit.failure(exception.failure());
            throw exception;
        } catch (RuntimeException exception) {
            // 信封/编码等永久本地错误不计入 EMQX 故障，但 half-open 探针必须收口。
            permit.failure(DeviceCommandDispatchFailure.DISPATCH_FAILED);
            throw exception;
        }
    }

    /**
     * 把拓扑上报回执编码为 {@code down/topo/reply} 并发布到网关的 Topic。
     *
     * <p>Topic 只能从确权后的 projectKey/gatewayKey 生成，payload 只暴露冻结字段，不把内部租户/项目
     * 标识或 traceId 泄露给设备。</p>
     */
    @Override
    public Instant publishTopologyReply(TopologyReplyMessage reply, DeviceMqttDownlinkRoute route) {
        requireRoute(route, reply.tenantId(), reply.projectId(), reply.gatewayId());
        try (OperationPermit permit = acquire(Operation.TOPOLOGY_REPLY)) {
            requireCredentials();
            String topic = "tc/v1/%s/%s/down/topo/reply".formatted(reply.projectKey(), reply.gatewayKey());
            post(route.internalTopic(topic), topologyReplyPayload(reply));
            permit.success();
            return Instant.now();
        }
    }

    /**
     * 把配置下发编码为 {@code down/config} 并发布到网关的 Topic，payload 只暴露 configType/version/points。
     */
    @Override
    public Instant publishConfig(DeviceConfigPush push, DeviceMqttDownlinkRoute route) {
        requireRoute(route, push.tenantId(), push.projectId(), push.gatewayId());
        try (OperationPermit permit = acquire(Operation.CONFIG)) {
            requireCredentials();
            String topic = "tc/v1/%s/%s/down/config".formatted(push.projectKey(), push.gatewayKey());
            post(route.internalTopic(topic), configPayload(push));
            permit.success();
            return Instant.now();
        }
    }

    /**
     * 把 Modbus 读请求编码为 {@code down/modbus/request} 并发布到网关的 Topic。
     */
    @Override
    public Instant publishModbusRequest(ModbusRequest request, DeviceMqttDownlinkRoute route) {
        requireRoute(route, request.tenantId(), request.projectId(), request.gatewayId());
        try (OperationPermit permit = acquire(Operation.MODBUS_REQUEST)) {
            requireCredentials();
            String topic = "tc/v1/%s/%s/down/modbus/request".formatted(request.projectKey(), request.gatewayKey());
            post(route.internalTopic(topic), modbusRequestPayload(request));
            permit.success();
            return Instant.now();
        }
    }

    /** 网络只能使用提交后的同一接收者路由，不能缺失回退或移交其他设备命名空间。 */
    private static void requireRoute(DeviceMqttDownlinkRoute route, UUID tenant, UUID project, UUID device) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("MQTT网络发布不能加入调用方事务");
        }
        if (route == null || !route.tenantId().equals(tenant) || !route.projectId().equals(project)
                || !route.deviceId().equals(device)) {
            throw new InvalidDownlinkMessageException("MQTT发布缺少匹配的已提交路由");
        }
    }

    /** 以独立服务身份把一条已编码下行报文交给 Broker；网络/超时/HTTP 拒绝统一分类为派发失败。 */
    private void post(String topic, String payload) {
        EmqxPublishRequest request = new EmqxPublishRequest(topic, payload, 1, false);
        try {
            client().post()
                    .uri(PUBLISH_PATH)
                    .header(HttpHeaders.AUTHORIZATION, basicAuthorization())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    // 3xx 不在默认错误处理器范围内，必须显式拦截：重定向不代表 Broker 接受报文（D-037 复核）。
                    .onStatus(HttpStatusCode::is3xxRedirection, (clientRequest, response) -> {
                        throw new CommandDispatchException(DeviceCommandDispatchFailure.DISPATCH_HTTP_REJECTED,
                                "EMQX 发布 API 返回重定向 " + response.getStatusCode().value());
                    })
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw new CommandDispatchException(classifyHttpStatus(exception.getStatusCode().value()),
                    "EMQX 拒绝发布 HTTP " + exception.getStatusCode().value(), exception);
        } catch (ResourceAccessException exception) {
            throw new CommandDispatchException(classifyResourceAccess(exception),
                    "EMQX 发布 API 网络失败", exception);
        } catch (UncheckedIOException exception) {
            throw new CommandDispatchException(DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED,
                    "EMQX 发布客户端初始化失败", exception);
        }
    }

    /** @return 首次投递时创建、成功后复用的 EMQX 管理面客户端 */
    private RestClient client() {
        RestClient current = restClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = restClient;
            if (current == null) {
                current = restClientFactory.get();
                restClient = current;
            }
            return current;
        }
    }

    /** @return HTTP 拒绝的有限稳定分类，供命令断路器只统计 429/5xx */
    private static DeviceCommandDispatchFailure classifyHttpStatus(int status) {
        if (status == 429) {
            return DeviceCommandDispatchFailure.DISPATCH_HTTP_RATE_LIMITED;
        }
        if (status >= 500) {
            return DeviceCommandDispatchFailure.DISPATCH_HTTP_SERVER_ERROR;
        }
        if (status >= 400) {
            return DeviceCommandDispatchFailure.DISPATCH_HTTP_CLIENT_ERROR;
        }
        return DeviceCommandDispatchFailure.DISPATCH_HTTP_REJECTED;
    }

    /** 立即竞争固定操作槽；命令还必须通过专属断路器。 */
    private OperationPermit acquire(Operation operation) {
        Semaphore bulkhead = bulkheads.get(operation);
        if (!bulkhead.tryAcquire()) {
            metrics.recordBulkheadRejected(operation.name());
            throw new CommandDispatchException(
                    DeviceCommandDispatchFailure.DISPATCH_BULKHEAD_REJECTED,
                    "EMQX " + operation.name() + " 发布并发已达上限");
        }
        FailureCircuitBreakerRegistry.Permit circuit = null;
        if (operation == Operation.COMMAND) {
            circuit = commandCircuit.tryAcquire("EMQX_COMMAND");
            if (circuit == null) {
                bulkhead.release();
                metrics.recordCircuitOpen();
                throw new CommandDispatchException(
                        DeviceCommandDispatchFailure.DISPATCH_CIRCUIT_OPEN,
                        "EMQX 命令发布断路器已打开");
            }
        }
        return new OperationPermit(bulkhead, circuit);
    }

    /** @param exception I/O 异常 @return 超时或连接失败；不可细分的网络失败归连接失败 */
    private static DeviceCommandDispatchFailure classifyResourceAccess(ResourceAccessException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return DeviceCommandDispatchFailure.DISPATCH_TIMEOUT;
            }
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED;
            }
            cause = cause.getCause();
        }
        return DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED;
    }

    /** 冻结 Modbus 读请求载荷：requestId、从站/功能码/地址/数量，不泄露内部租户/项目/网关标识。 */
    private String modbusRequestPayload(ModbusRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("requestId", request.requestId().toString());
        payload.put("slaveAddress", request.slaveAddress());
        payload.put("functionCode", request.functionCode());
        payload.put("registerAddress", request.registerAddress());
        payload.put("quantity", request.quantity());
        return objectMapper.writeValueAsString(payload);
    }

    /** 冻结配置下发载荷：只携带 configType、version 与点位集，不泄露内部租户/项目/网关标识。 */
    private String configPayload(DeviceConfigPush push) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("configType", push.configType());
        payload.put("version", push.version());
        payload.set("points", objectMapper.valueToTree(push.points()));
        return objectMapper.writeValueAsString(payload);
    }

    /** 冻结拓扑回执载荷：只携带关联标识、子设备与结果，errorCode/message 仅失败时出现。 */
    private String topologyReplyPayload(TopologyReplyMessage reply) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("requestId", reply.requestId().toString());
        payload.put("subDeviceKey", reply.subDeviceKey());
        payload.put("status", reply.status().name());
        if (reply.errorCode() != null) {
            payload.put("errorCode", reply.errorCode());
        }
        if (reply.message() != null) {
            payload.put("message", reply.message());
        }
        return objectMapper.writeValueAsString(payload);
    }

    /** Topic 只能从确权后的连接设备生成，不能采用 input 中的任何自报字段。 */
    private static String mqttTopic(DeviceCommandDispatch dispatch) {
        if (dispatch == null || dispatch.commandId() == null || dispatch.projectKey() == null
                || dispatch.connectionDeviceKey() == null) {
            throw new InvalidDownlinkMessageException("命令派发信封缺少 MQTT 路由字段");
        }
        return dispatch.operationType() == DeviceCommandDispatch.OperationType.PROPERTY_SET
                ? "tc/v1/%s/%s/down/property/set".formatted(
                dispatch.projectKey(), dispatch.connectionDeviceKey())
                : "tc/v1/%s/%s/down/command/%s".formatted(
                dispatch.projectKey(), dispatch.connectionDeviceKey(), dispatch.commandId());
    }

    /** 冻结下行载荷只携带业务目标、命令输入和诊断 attemptNo，commandId 由 Topic 承载。 */
    private String mqttPayload(DeviceCommandDispatch dispatch) {
        try {
            JsonNode input = objectMapper.readTree(dispatch.inputJson());
            if (input == null || !input.isObject()) {
                throw new InvalidDownlinkMessageException("命令派发 input 必须是 JSON 对象");
            }
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("targetDeviceKey", dispatch.targetDeviceKey());
            if (dispatch.operationType() == DeviceCommandDispatch.OperationType.PROPERTY_SET) {
                payload.put("requestId", dispatch.commandId().toString());
                payload.put("targetDeviceKey", dispatch.targetDeviceKey());
                payload.set("properties", input);
            } else {
                payload.put("targetDeviceKey", dispatch.targetDeviceKey());
                payload.put("commandKey", dispatch.commandKey());
                payload.set("input", input);
            }
            payload.put("attempt", dispatch.attemptNo());
            return objectMapper.writeValueAsString(payload);
        } catch (InvalidDownlinkMessageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidDownlinkMessageException("命令派发 input 不是合法 JSON", exception);
        }
    }

    /** 空凭据不能在开发期阻断全部上行业务，但下行调用必须 fail-closed。 */
    private void requireCredentials() {
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            throw new CommandDispatchException(DeviceCommandDispatchFailure.DISPATCH_CREDENTIALS_MISSING,
                    "缺少 EMQX 下行发布 API 凭据");
        }
    }

    /** Basic 凭据只进入请求头，异常与日志均不输出原值。 */
    private String basicAuthorization() {
        String credential = apiKey + ":" + apiSecret;
        return "Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
    }

    /** @return 带连接与读取上限的 JDK 请求工厂（D-050） */
    private static ClientHttpRequestFactory timeoutRequestFactory(Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                // EMQX 5 Dashboard API 的 HTTP/1.1 监听器会直接关闭 JDK HttpClient 发出的 h2c Upgrade 请求，
                // 客户端只能得到 EOF，并被命令状态机误判为连接失败。管理 API 本身不需要 HTTP/2 多路复用，
                // 因此在适配器边界固定 HTTP/1.1；不能把这个兼容性选择扩散成全局 JVM 网络配置。
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(connectTimeout)
                        .build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /** 根地址尾部斜杠会让固定 API path 组合不一致，构造时一次归一化。 */
    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少 things-link.ingestion.emqx-api.base-url");
        }
        String normalized = value.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * EMQX 5 单条发布请求。
     *
     * @param topic MQTT 消息主题
     * @param payload UTF-8 JSON 文本
     * @param qos 固定为 1
     * @param retain 固定为 false，防止设备上线后执行过期命令
     */
    private record EmqxPublishRequest(String topic, String payload, int qos, boolean retain) {
    }

    /** 四类不共享槽位的固定 EMQX 操作。 */
    private enum Operation {
        /** 命令与属性设置。 */ COMMAND,
        /** 网关配置。 */ CONFIG,
        /** 拓扑回执。 */ TOPOLOGY_REPLY,
        /** Modbus 读请求。 */ MODBUS_REQUEST
    }

    /** 一次操作的 bulkhead/可选命令断路器许可。 */
    private static final class OperationPermit implements AutoCloseable {
        /** 固定操作槽。 */
        private final Semaphore bulkhead;
        /** 仅命令非空。 */
        private final FailureCircuitBreakerRegistry.Permit circuit;
        /** 防止重复释放。 */
        private boolean closed;

        /** 仅发布器创建。 */
        private OperationPermit(
                Semaphore bulkhead, FailureCircuitBreakerRegistry.Permit circuit) {
            this.bulkhead = bulkhead;
            this.circuit = circuit;
        }

        /** HTTP 成功关闭命令断路器。 */
        private void success() {
            if (circuit != null) {
                circuit.success();
            }
        }

        /** 只把连接/超时/429/5xx 计入命令连续失败。 */
        private void failure(DeviceCommandDispatchFailure failure) {
            if (circuit == null) {
                return;
            }
            if (failure == DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED
                    || failure == DeviceCommandDispatchFailure.DISPATCH_TIMEOUT
                    || failure == DeviceCommandDispatchFailure.DISPATCH_HTTP_RATE_LIMITED
                    || failure == DeviceCommandDispatchFailure.DISPATCH_HTTP_SERVER_ERROR) {
                circuit.retryableFailure();
            } else {
                circuit.ignoredFailure();
            }
        }

        /** 释放固定操作槽。 */
        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                bulkhead.release();
            }
        }
    }
}
