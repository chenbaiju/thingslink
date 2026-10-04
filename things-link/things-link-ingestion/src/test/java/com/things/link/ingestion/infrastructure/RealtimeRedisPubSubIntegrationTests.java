package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.RealtimeConnection;
import com.things.link.ingestion.application.RealtimeAuthorizationService;
import com.things.link.ingestion.application.RealtimeMetrics;
import com.things.link.ingestion.application.RealtimeProjectPublisher;
import com.things.link.ingestion.application.RealtimePrincipal;
import com.things.link.ingestion.application.RealtimeSubscriptionRegistry;
import com.things.link.ingestion.application.TenantConnectionLease;
import com.things.link.ingestion.infrastructure.websocket.RealtimeWebSocketProperties;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import com.things.link.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 以真实 Redis Pub/Sub 验证不同应用实例的实时增量项目隔离与取消订阅语义。
 *
 * <p>单元测试直接调用注册表只能证明本机扇出；本测试显式创建两个生产
 * {@link RealtimeRedisSubscriber} 监听容器，连接 {@link AbstractIntegrationTest} 提供的 Redis，
 * 才能覆盖 ADR 0016 中“一个 Kafka 消费组、Redis 广播到全部 WebSocket 实例”的关键边界。</p>
 */
class RealtimeRedisPubSubIntegrationTests {

    /** 从测试原文保留的长小数期望，不能先由默认mapper生成舍入后的期望。 */
    private static final String PRECISE_DECIMAL = "9007199254740993.123456789012345678901";
    /** 超出Long的原生JSON整数。 */
    private static final String PRECISE_INTEGER = "9223372036854775808123456789";
    /** 独立观察器只读最终发送文本，绝不注入生产发布器/订阅器来掩盖失真。 */
    private static final ObjectMapper EXACT_OBSERVER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    /** 默认JSON工具仅用于生产发布和订阅；精度期望另由原文及独立观察器验证。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** S4-4 默认频道配置，发布器和两个生产订阅器共享此隔离前缀。 */
    private final RealtimeProperties realtimeProperties = new RealtimeProperties();

    /** 真实 Testcontainers Redis 的独立客户端工厂，测试结束时必须主动释放网络资源。 */
    private LettuceConnectionFactory connectionFactory;

    /** 第一个实例的项目会话，用于在失败路径也可注销其 sender worker。 */
    private TestConnection firstProjectConnection;

    /** 第一个实例的异项目会话，用于验证项目隔离与清理。 */
    private TestConnection otherProjectConnection;

    /** 第二个实例的项目会话，用于验证跨实例扇出与注销。 */
    private TestConnection secondProjectConnection;

    /** 第一个模拟应用实例本地会话索引。 */
    private RealtimeSubscriptionRegistry firstRegistry;

    /** 第二个模拟应用实例本地会话索引。 */
    private RealtimeSubscriptionRegistry secondRegistry;

    /** 第一个实例在 Redis 上建立的生产订阅容器。 */
    private RedisMessageListenerContainer firstContainer;

    /** 第二个实例在 Redis 上建立的生产订阅容器。 */
    private RedisMessageListenerContainer secondContainer;

    /**
     * 回收手工创建的监听容器与会话，避免共享 Testcontainers Redis 遗留模式订阅影响后续测试。
     */
    @AfterEach
    void tearDown() throws Exception {
        if (firstContainer != null) {
            firstContainer.destroy();
        }
        if (secondContainer != null) {
            secondContainer.destroy();
        }
        if (firstRegistry != null && firstProjectConnection != null) {
            firstRegistry.unregister(firstProjectConnection.id());
        }
        if (firstRegistry != null && otherProjectConnection != null) {
            firstRegistry.unregister(otherProjectConnection.id());
        }
        if (secondRegistry != null && secondProjectConnection != null) {
            secondRegistry.unregister(secondProjectConnection.id());
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /**
     * 同一项目须由 Redis 同时投递到两个实例；异项目会话和已取消的会话绝不能收到增量。
     */
    @Test
    void fansOutToTwoInstancesKeepsProjectsIsolatedAndCleansUpAfterUnregister() throws Exception {
        UUID projectId = Uuid7.generate();
        UUID otherProjectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        firstRegistry = newRegistry();
        secondRegistry = newRegistry();
        firstProjectConnection = new TestConnection("node-a-project", projectId);
        otherProjectConnection = new TestConnection("node-a-other-project", otherProjectId);
        secondProjectConnection = new TestConnection("node-b-project", projectId);

        assertThat(firstRegistry.register(firstProjectConnection)).isTrue();
        assertThat(firstRegistry.register(otherProjectConnection)).isTrue();
        assertThat(secondRegistry.register(secondProjectConnection)).isTrue();
        firstRegistry.replaceSubscriptions(firstProjectConnection.id(), Map.of(deviceId, Set.of("temperature")));
        firstRegistry.replaceSubscriptions(otherProjectConnection.id(), Map.of(deviceId, Set.of("temperature")));
        secondRegistry.replaceSubscriptions(secondProjectConnection.id(), Map.of(deviceId, Set.of("temperature")));
        firstContainer = startContainer(firstRegistry);
        secondContainer = startContainer(secondRegistry);

        RealtimeProjectPublisher publisher = new RealtimeProjectPublisher(redisTemplate(), objectMapper,
                realtimeProperties, new RealtimeMetrics(new SimpleMeterRegistry()));
        publisher.publish(update(projectId, deviceId, 1, "26.5"));

        assertThat(firstProjectConnection.awaitMessage()).contains("PROPERTY_BATCH", "temperature", "26.5");
        assertThat(secondProjectConnection.awaitMessage()).contains("PROPERTY_BATCH", "temperature", "26.5");
        assertThat(otherProjectConnection.pollMessage()).isNull();

        // 注销先删除本机索引；即便 Redis 随后仍投递频道消息，也不能重新把数据入旧会话队列。
        secondRegistry.unregister(secondProjectConnection.id());
        assertThat(secondRegistry.connectionCount()).isZero();
        publisher.publish(update(projectId, deviceId, 2, "27.0"));

        assertThat(firstProjectConnection.awaitMessage()).contains("temperature", "27.0");
        assertThat(secondProjectConnection.pollMessage()).isNull();
        assertThat(otherProjectConnection.pollMessage()).isNull();
    }

    /** 两实例真实Pub/Sub再经原sender序列化；平面及复合数值到最终文本仍保持JSON数值语义。 */
    @Test
    void preservesOriginalDecimalAndNestedIntegersInBothInstancesFinalFrames() throws Exception {
        UUID projectId = Uuid7.generate(), otherProjectId = Uuid7.generate(), deviceId = Uuid7.generate();
        firstRegistry = newRegistry();
        secondRegistry = newRegistry();
        firstProjectConnection = new TestConnection("precision-node-a", projectId);
        otherProjectConnection = new TestConnection("precision-other-project", otherProjectId);
        secondProjectConnection = new TestConnection("precision-node-b", projectId);
        assertThat(firstRegistry.register(firstProjectConnection)).isTrue();
        assertThat(firstRegistry.register(otherProjectConnection)).isTrue();
        assertThat(secondRegistry.register(secondProjectConnection)).isTrue();
        Map<UUID, Set<String>> subscription = Map.of(deviceId, Set.of("decimal", "integer", "object"));
        firstRegistry.replaceSubscriptions(firstProjectConnection.id(), subscription);
        firstRegistry.replaceSubscriptions(otherProjectConnection.id(), subscription);
        secondRegistry.replaceSubscriptions(secondProjectConnection.id(), subscription);
        firstContainer = startContainer(firstRegistry);
        secondContainer = startContainer(secondRegistry);
        var publisher = new RealtimeProjectPublisher(redisTemplate(), objectMapper, realtimeProperties,
                new RealtimeMetrics(new SimpleMeterRegistry()));
        String composite = "{\"nested\":[" + PRECISE_DECIMAL + "," + PRECISE_INTEGER
                + ",{\"decimal\":" + PRECISE_DECIMAL + ",\"integer\":" + PRECISE_INTEGER + "}]}";
        var update = new DeviceRealtimeUpdate(Uuid7.generate(), Uuid7.generate(), projectId, deviceId,
                Uuid7.generate(), "1.0.0", Instant.parse("2026-08-09T08:00:00Z"), 1,
                "0123456789abcdef012345678abcdef", Map.of("decimal", PRECISE_DECIMAL,
                "integer", PRECISE_INTEGER, "object", composite),
                Map.of("decimal", "NUMBER", "integer", "NUMBER", "object", "OBJECT"),
                Map.of("decimal", "9007199254740993", "integer", "9007199254740994", "object", "9007199254740995"));
        // 默认解析器的舍入反例独立保留；最终断言不能与生产一起改用同一个mapper后自证通过。
        assertThat(objectMapper.readTree(PRECISE_DECIMAL).decimalValue())
                .isNotEqualByComparingTo(PRECISE_DECIMAL);
        publisher.publish(update);
        assertPreciseFrames(firstProjectConnection, projectId, deviceId, update.thingModelVersionId());
        assertPreciseFrames(secondProjectConnection, projectId, deviceId, update.thingModelVersionId());
        assertThat(otherProjectConnection.pollMessage()).isNull();
        secondRegistry.unregister(secondProjectConnection.id());
        publisher.publish(update);
        // 重复旧提交不能越过已发送的序号水位；注销实例同样不再接收。
        assertThat(firstProjectConnection.pollMessage()).isNull();
        assertThat(secondProjectConnection.pollMessage()).isNull();
        assertThat(otherProjectConnection.pollMessage()).isNull();
    }

    /** 最终每属性帧按闭集取回，数值须是真实JSON number而非等值字符串。 */
    private static void assertPreciseFrames(TestConnection connection, UUID projectId, UUID deviceId, UUID modelId)
            throws InterruptedException {
        Map<String, JsonNode> values = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            String frame = connection.awaitMessage();
            assertThat(frame).isNotNull();
            JsonNode root = EXACT_OBSERVER.readTree(frame);
            assertThat(root.get("type").asString()).isEqualTo("PROPERTY_BATCH");
            assertThat(root.get("projectId").asString()).isEqualTo(projectId.toString());
            assertThat(root.get("deviceId").asString()).isEqualTo(deviceId.toString());
            assertThat(root.get("occurredAt").asString()).isEqualTo("2026-08-09T08:00:00Z");
            assertThat(root.get("shadowVersion").asInt()).isEqualTo(1);
            JsonNode properties = root.get("properties");
            assertThat(properties.size()).isEqualTo(1);
            for (String key : new String[]{"decimal", "integer", "object"}) {
                if (!properties.has(key)) continue;
                assertThat(root.get("reportedRevisions").get(key).isString()).isTrue();
                assertThat(root.get("reportedRevisions").get(key).asString()).isEqualTo(
                        Map.of("decimal", "9007199254740993", "integer", "9007199254740994", "object", "9007199254740995").get(key));
                assertThat(root.get("thingModelVersionIds").get(key).asString()).isEqualTo(modelId.toString());
            }
            properties.properties().forEach(entry -> assertThat(values.put(entry.getKey(), entry.getValue())).isNull());
        }
        assertThat(values).containsOnlyKeys("decimal", "integer", "object");
        assertPreciseDecimal(values.get("decimal"));
        assertPreciseInteger(values.get("integer"));
        JsonNode nested = values.get("object").get("nested");
        assertThat(nested.isArray()).isTrue();
        assertThat(nested.size()).isEqualTo(3);
        assertPreciseDecimal(nested.get(0));
        assertPreciseInteger(nested.get(1));
        assertPreciseDecimal(nested.get(2).get("decimal"));
        assertPreciseInteger(nested.get(2).get("integer"));
    }

    /** 只比较十进制数学值，不把JSON合法格式或BigDecimal scale变化当作失败。 */
    private static void assertPreciseDecimal(JsonNode value) {
        assertThat(value.isNumber()).isTrue();
        assertThat(value.decimalValue()).isEqualByComparingTo(new BigDecimal(PRECISE_DECIMAL));
    }

    /** 超Long整数必须保持整数节点和完整原值。 */
    private static void assertPreciseInteger(JsonNode value) {
        assertThat(value.isIntegralNumber()).isTrue();
        assertThat(value.bigIntegerValue()).isEqualTo(new BigInteger(PRECISE_INTEGER));
    }

    /** 频道项目与正文项目不一致时整批丢弃，不能把攻击者正文路由到另一项目连接。 */
    @Test
    void rejectsRedisPayloadWhoseProjectDoesNotMatchChannelSuffix() throws Exception {
        UUID channelProjectId = Uuid7.generate();
        UUID payloadProjectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RealtimeMetrics metrics = new RealtimeMetrics(meters);
        firstRegistry = newRegistry();
        firstProjectConnection = new TestConnection("payload-project", payloadProjectId);
        assertThat(firstRegistry.register(firstProjectConnection)).isTrue();
        firstRegistry.replaceSubscriptions(firstProjectConnection.id(), Map.of(deviceId, Set.of("temperature")));
        firstContainer = startContainer(firstRegistry, metrics);

        redisTemplate().convertAndSend(realtimeProperties.getChannelPrefix() + channelProjectId,
                objectMapper.writeValueAsString(update(payloadProjectId, deviceId, 1, "26.5")));

        assertThat(firstProjectConnection.pollMessage()).isNull();
        assertThat(meters.find("thingslink.realtime.redis.publish")
                .tag("result", "failure").counter().count()).isEqualTo(1);
    }

    /**
     * 创建配额宽松但仍有界的本机注册表，避免配额行为干扰本测试的跨实例语义。
     *
     * @return 一个独立模拟应用实例的注册表
     */
    private RealtimeSubscriptionRegistry newRegistry() {
        EffectiveQuotaPolicyProvider policyProvider = mock(EffectiveQuotaPolicyProvider.class);
        when(policyProvider.resolveTrustedProject(any(UUID.class)))
                .thenAnswer(invocation -> EffectiveQuotaPolicy.safeDefault(invocation.getArgument(0)));
        TenantConnectionLease connectionLease = mock(TenantConnectionLease.class);
        when(connectionLease.create(any(UUID.class), any(String.class)))
                .thenAnswer(invocation -> new TenantConnectionLease.ConnectionLease(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(connectionLease.acquire(any(), any()))
                .thenReturn(TenantConnectionLease.LeaseDecision.ACQUIRED);
        when(connectionLease.renew(any())).thenReturn(TenantConnectionLease.RenewDecision.RENEWED);
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get())
                .when(authorization).inPrincipalScope(any(), any());
        return new RealtimeSubscriptionRegistry(objectMapper, new RealtimeWebSocketProperties(20,
                20, 20, 20, 100, 100, 16, 32 * 1024, 5_000),
                new RealtimeMetrics(new SimpleMeterRegistry()), policyProvider, connectionLease, authorization);
    }

    /**
     * 用生产订阅器创建并等待一个 Redis 模式订阅就绪，防止 Pub/Sub 非持久特性造成测试竞态。
     *
     * @param registry 目标实例本机会话索引
     * @return 已开始监听项目频道的容器
     */
    private RedisMessageListenerContainer startContainer(RealtimeSubscriptionRegistry registry) {
        return startContainer(registry, new RealtimeMetrics(new SimpleMeterRegistry()));
    }

    /** 使用调用方指标承载Redis正文身份拒绝结果。 */
    private RedisMessageListenerContainer startContainer(RealtimeSubscriptionRegistry registry,
                                                          RealtimeMetrics metrics) {
        RedisMessageListenerContainer container = new RealtimeRedisSubscriber().realtimeRedisMessageListenerContainer(
                connectionFactory(), realtimeProperties, objectMapper, registry, metrics,
                org.mockito.Mockito.mock(com.things.link.ingestion.application.DashboardRealtimeRegistry.class));
        container.afterPropertiesSet();
        container.start();
        assertThat(container.isListening()).isTrue();
        return container;
    }

    /**
     * 为生产发布器创建连接 Testcontainers Redis 的字符串模板，不借 Mock 隐藏 Pub/Sub 真实行为。
     *
     * @return 已初始化的 Redis 发布模板
     */
    private StringRedisTemplate redisTemplate() {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 延迟初始化连接工厂，保证发布器和两个订阅容器使用同一个真实 Redis 端点。
     *
     * @return 已初始化的 Lettuce 连接工厂
     */
    private LettuceConnectionFactory connectionFactory() {
        if (connectionFactory == null) {
            GenericContainer<?> redis = RedisContainerAccess.redis();
            connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redis.getHost(),
                    redis.getMappedPort(6379)));
            connectionFactory.afterPropertiesSet();
        }
        return connectionFactory;
    }

    /**
     * 构造经影子 CAS 接受后的共享实时消息；属性值保持 JSON 文本以覆盖生产反序列化路径。
     *
     * @param projectId 项目隔离键
     * @param deviceId 设备隔离键
     * @param shadowVersion 已提交影子版本
     * @param temperature JSON 数值文本
     * @return 可由生产发布器序列化的实时增量
     */
    private static DeviceRealtimeUpdate update(UUID projectId, UUID deviceId, int shadowVersion, String temperature) {
        return new DeviceRealtimeUpdate(Uuid7.generate(), Uuid7.generate(), projectId, deviceId,
                Uuid7.generate(), "1.0.0", Instant.parse("2026-08-09T08:00:00Z"), shadowVersion,
                "0123456789abcdef0123456789abcdef", Map.of("temperature", temperature),
                Map.of("temperature", "NUMBER"));
    }

    /**
     * 捕获注册表异步发送文本帧的最小连接替身。
     */
    private static final class TestConnection implements RealtimeConnection {

        /** 本机会话唯一标识。 */
        private final String id;

        /** 经握手固定的项目身份；账号和租户对本测试不参与扇出决策。 */
        private final RealtimePrincipal principal;

        /** sender 虚拟线程写入的文本帧队列。 */
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

        /**
         * @param id 会话标识
         * @param projectId 会话已授权项目
         */
        private TestConnection(String id, UUID projectId) {
            this.id = id;
            principal = new RealtimePrincipal(Uuid7.generate(), Uuid7.generate(), projectId,
                    Instant.parse("2030-01-01T00:00:00Z"));
        }

        /** {@inheritDoc} */
        @Override
        public String id() {
            return id;
        }

        /** {@inheritDoc} */
        @Override
        public RealtimePrincipal principal() {
            return principal;
        }

        /** {@inheritDoc} */
        @Override
        public void sendText(String payload) {
            messages.add(payload);
        }

        /** 本测试只验证正常扇出，不需要记录关闭状态。 */
        @Override
        public void close(int statusCode, String reason) {
            // 注册表关闭路径由 RealtimeSubscriptionRegistryTests 单独覆盖。
        }

        /** @return 两秒内收到的实时协议帧，超时则返回 null 供断言显示失败。 */
        private String awaitMessage() throws InterruptedException {
            return messages.poll(2, TimeUnit.SECONDS);
        }

        /** @return 短暂观察期内的帧，用于证明项目隔离和注销后无泄漏。 */
        private String pollMessage() throws InterruptedException {
            return messages.poll(300, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 仅暴露既有测试基类中的共享 Redis；不继承基类以免为纯 Pub/Sub 用例启动 JPA/Flyway 上下文。
     */
    private static final class RedisContainerAccess extends AbstractIntegrationTest {

        /** @return 全 JVM 共享的真实 Redis Testcontainer */
        private static GenericContainer<?> redis() {
            return REDIS;
        }
    }
}
