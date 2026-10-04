package com.things.link.bootstrap.ingestion.mqtt;

import com.things.link.bootstrap.fixture.DeviceProtocolKafkaTopicsTestConfiguration;

import com.things.link.alarm.application.AlarmRuleCommand;
import com.things.link.alarm.application.AlarmRuleService;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.device.application.DeviceAuthenticationPort;
import com.things.link.device.infrastructure.emqx.EmqxAuthService;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.ingestion.application.BrokerHandoffDispatcher;
import com.things.link.ingestion.application.HandoffDisposition;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import com.things.link.support.observability.DataPlaneMetrics;
import com.things.link.support.cache.CacheInvalidationEvent;
import com.things.link.support.cache.CacheInvalidationOperation;
import com.things.link.support.cache.CacheInvalidationPublisher;
import com.things.link.support.cache.CacheResource;
import com.things.link.support.trace.TraceContext;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.UplinkKafkaConfiguration.DEAD_LETTER_TOPIC;
import static com.things.link.ingestion.infrastructure.ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC;
import static com.things.link.rule.infrastructure.messaging.KafkaRuleRecoveryPublisher.RETRY_ONE_MINUTE_TOPIC;
import static com.things.link.rule.infrastructure.messaging.KafkaRuleRecoveryPublisher.RETRY_FIVE_MINUTES_TOPIC;

/** EMQX 认证/ACL 回调接口端到端测试。 */
@AutoConfigureMockMvc
@Import({EmqxCallbackTests.KafkaTopicTestConfiguration.class, DeviceProtocolKafkaTopicsTestConfiguration.class})
@TestPropertySource(properties = {
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true"
})
@DisplayName("EMQX 回调接口（S3-5）")
class EmqxCallbackTests extends AbstractKafkaIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    /** B-X1b 数据面夹具 1.0.0 快照：仅温度 NUMBER 上报属性，与设备类型定义一致。 */
    private static final String TEMPERATURE_SNAPSHOT =
            "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\","
                    + "\"minimum\":-40,\"maximum\":125}},\"events\":{},\"commands\":{}}";
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 与三协议接入共用的协议无关凭据校验端口。 */
    @Autowired private DeviceAuthenticationPort authenticationPort;
    /** 共用同一实现与缓存的 EMQX 认证门面。 */
    @Autowired private EmqxAuthService emqxAuthService;
    /** 生产缓存失效发布器，用于复现轮换提交后的定向驱逐。 */
    @Autowired private CacheInvalidationPublisher cacheInvalidationPublisher;
    @Autowired private AuthRateLimiter rateLimiter;
    @Autowired private TransactionTemplate transactionTemplate;
    /** 通过生产规则配置服务建立 S6 端到端告警夹具，禁止测试直写 alarm_* 表。 */
    @Autowired private AlarmRuleService alarmRuleService;
    /** 观察未支持上行类型的统一死信结果。 */ @Autowired private DeadLetterProbe deadLetterProbe;
    /** 验证各真实链路最终汇入同一个 Prometheus 注册表。 */ @Autowired private MeterRegistry meterRegistry;
    /** 端点格式验收需要为每类指标建立样本，实际接线另由对应链路测试断言。 */
    @Autowired private DataPlaneMetrics dataPlaneMetrics;

    /** 验证真实认证HTTP至raw Kafka持久边界。 */
    @Autowired private BrokerHandoffDispatcher handoffDispatcher;
    /** 独立组观察raw认证事实，避免把生产消费者内存对象当持久证据。 */
    @Autowired private AuthenticatedRawProbe authenticatedRawProbe;

    private String projectKey, deviceKey, deviceSecret, productKey, productSecret;
    private UUID tenantId, projectId, deviceId, accountId;

    /**
     * Broker 回调共享密钥（S3.5-1）。
     *
     * <p>从配置读而不是在测试里写死同一个字面量：写死的话，改了 application.yml 却
     * 忘了改测试时，测试会以「401」的形式红——但红的原因看起来像功能坏了，
     * 而不是「两处配置不一致」。从配置读则永远测的是真实生效的那个值。
     */
    @Value("${things-link.security.broker-callback.secret}")
    private String brokerCallbackSecret;

    /**
     * 携带共享密钥的请求头。
     *
     * <p><b>刻意写成字面量而不是引用 {@code BrokerCallbackAuthenticationFilter.CREDENTIAL_HEADER}。</b>
     * 这个头名是一份<b>跨进程的线上契约</b>：另一端是 {@code deploy/emqx/base.hocon}，
     * 一个 Java 常量对它毫无约束力。引用常量的话，改名后 Java 侧全绿、EMQX 侧全挂；
     * 写成字面量，改名会让本测试立刻红，提醒去改 hocon。
     *
     * <p>注意它<b>不是</b> {@code Authorization}：本服务同时是 OAuth2 资源服务器，
     * 共用那个头会让请求先被 JWT 解析拦下，症状是「密钥明明是对的却 401」。
     */
    private static final String BROKER_HEADER = "X-Broker-Callback-Token";

    /** S3-11D 生产 listener 在 bootstrap 测试上下文启动前必须存在的真实 Kafka 主题。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaTopicTestConfiguration {

        /** @return 与生产 raw 主题对应的三分区测试主题 */
        @Bean
        NewTopic rawUplinkTopic() {
            return TopicBuilder.name(RAW_UPLINK_TOPIC).partitions(3).replicas(1).build();
        }

        /** @return 与生产 normalized 主题对应的三分区测试主题 */
        @Bean
        NewTopic normalizedUplinkTopic() {
            return TopicBuilder.name(NORMALIZED_UPLINK_TOPIC).partitions(3).replicas(1).build();
        }

        /** @return S4-3 下行 listener 启动所需的三分区真实测试主题 */
        @Bean
        NewTopic commandDownlinkTopic() {
            return TopicBuilder.name("tc.device.downlink").partitions(3).replicas(1).build();
        }

        /** 实时消费者启动前必须显式创建主题，测试环境禁止依赖 broker 自动建主题。 */
        @Bean
        NewTopic realtimeTopic() {
            return TopicBuilder.name("tc.device.realtime").partitions(3).replicas(1).build();
        }

        /** S6-3 通知消费者启动前必须显式创建主题，禁止测试依赖 broker 自动创建。 */
        @Bean
        NewTopic notificationTopic() {
            return TopicBuilder.name("tc.notification").partitions(3).replicas(1).build();
        }

        /** S8-2C processed 续接是物模型与遥测的唯一入口，processed 消费者启动前必须显式建主题。 */
        @Bean
        NewTopic processedUplinkTopic() {
            return TopicBuilder.name(PROCESSED_UPLINK_TOPIC).partitions(3).replicas(1).build();
        }

        /** S8-2B 规则重试消费者启动前必须显式创建两个退避主题，禁止测试依赖 broker 自动创建。 */
        @Bean
        NewTopic ruleRetryOneMinuteTopic() {
            return TopicBuilder.name(RETRY_ONE_MINUTE_TOPIC).partitions(1).replicas(1).build();
        }

        /** @return 5 分钟退避重试主题的单分区测试主题 */
        @Bean
        NewTopic ruleRetryFiveMinutesTopic() {
            return TopicBuilder.name(RETRY_FIVE_MINUTES_TOPIC).partitions(1).replicas(1).build();
        }

        /** @return 与生产统一死信主题对应的单分区测试主题 */
        @Bean
        NewTopic deadLetterTopic() {
            return TopicBuilder.name(DEAD_LETTER_TOPIC).partitions(1).replicas(1).build();
        }

        /** @return 规则通知投递主题；S9-1 通知消费者随全上下文启动，missing-topics-fatal 要求显式建主题 */
        @Bean
        NewTopic ruleNotificationTopic() {
            return TopicBuilder.name("tc.rule.notification").partitions(3).replicas(1).build();
        }

        /** @return S9-2 设备操作终态消费者启动所需的测试主题 */
        @Bean
        NewTopic deviceCommandTerminalTopic() {
            return TopicBuilder.name("tc.device.command.terminal").partitions(3).replicas(1).build();
        }

        /** 建立独立raw身份探针，不参与业务消费组。 */
        @Bean
        AuthenticatedRawProbe authenticatedRawProbe() {
            return new AuthenticatedRawProbe();
        }

        /** @return 用独立消费组观察 HTTP → raw consumer → DLQ 的跨层结果 */
        @Bean
        DeadLetterProbe deadLetterProbe() {
            return new DeadLetterProbe();
        }
    }

    /** 测试专用 DLQ 探针，不参与生产消费组，也不会改变被测 offset。 */
    static final class DeadLetterProbe {

        /** 收集原始信封及 Spring Kafka 恢复头。 */
        private final LinkedBlockingQueue<ConsumerRecord<String, Object>> records =
                new LinkedBlockingQueue<>();

        /**
         * 接收统一死信主题记录。
         *
         * @param record 未支持或永久失败的原始上行记录
         */
        @KafkaListener(topics = DEAD_LETTER_TOPIC, groupId = "emqx-callback-dlq-test-probe")
        void receive(ConsumerRecord<String, Object> record) {
            records.add(record);
        }
    }

    /** 真实Kafka反序列化后的身份探针，仅保留本专项的带身份消息。 */
    static final class AuthenticatedRawProbe {
        /** 有界专项消息队列。 */
        private final LinkedBlockingQueue<RawUplinkMessage> records = new LinkedBlockingQueue<>(32);

        /** 从独立组接收已持久信封。 */
        @KafkaListener(topics = RAW_UPLINK_TOPIC, groupId = "emqx-authenticated-raw-test-probe")
        void receive(ConsumerRecord<String, Object> record) {
            if (record.value() instanceof RawUplinkMessage raw && raw.authenticatedIdentity() != null) {
                records.offer(raw);
            }
        }
    }

    @BeforeEach void seed() throws Exception {
        rateLimiter.clear();
        jdbcTemplate.update("DELETE FROM sys_project_member");
        clearRawPropertyPointsBeforeAllProjectFixtureReset();
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");

        // 注册登录
        String email = "emqx-test@example.com";
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        JsonNode loginBody = JSON.readTree(login.getResponse().getContentAsString());
        String token = loginBody.get("accessToken").asString();
        tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_tenant_member WHERE account_id = (SELECT id FROM sys_account WHERE email = ?)",
                UUID.class, email);
        accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
        String refresh = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(v -> v.startsWith("tc_refresh=")).map(v -> v.substring("tc_refresh=".length(), v.indexOf(';')))
                .findFirst().orElseThrow();

        // 创建项目
        MvcResult proj = mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"EMQX测试项目\",\"region\":\"sh-1\"}")).andReturn();
        projectId = UUID.fromString(JSON.readTree(proj.getResponse().getContentAsString()).get("id").asString());
        projectKey = jdbcTemplate.queryForObject("SELECT project_key FROM sys_project WHERE id = ?", String.class, projectId);

        // 切换项目 + 创建设备
        MvcResult sw = mockMvc.perform(post("/api/v1/auth/switch-project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .cookie(new Cookie("tc_refresh", refresh)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"%s\"}".formatted(projectId))).andReturn();
        token = JSON.readTree(sw.getResponse().getContentAsString()).get("accessToken").asString();

        // 一型一密只能绑定已发布类型，产品密钥通过管理端点生成且只返回一次。
        MvcResult type = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeKey\":\"register_type\",\"name\":\"注册类型\","
                                + "\"deviceKind\":\"DIRECT\",\"payloadProtocol\":\"STANDARD\","
                                + "\"networkType\":\"WIFI\"}"))
                .andReturn();
        UUID typeId = UUID.fromString(JSON.readTree(type.getResponse().getContentAsString()).get("id").asString());
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types/" + typeId + "/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyKey\":\"temperature\",\"name\":\"温度\","
                                + "\"accessType\":\"REPORT\",\"dataType\":\"NUMBER\","
                                + "\"minimumValue\":-40,\"maximumValue\":125,\"sortOrder\":0}"))
                .andReturn();
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types/" + typeId + "/publish")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        MvcResult productCredential = mockMvc.perform(post("/api/v1/projects/" + projectId
                        + "/device-types/" + typeId + "/product-credential")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        assertThat(productCredential.getResponse().getStatus())
                .as(productCredential.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode productBody = JSON.readTree(productCredential.getResponse().getContentAsString());
        productKey = productBody.get("productKey").asString();
        productSecret = productBody.get("productSecret").asString();

        MvcResult dev = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"mqtt_sensor\",\"name\":\"MQTT传感器\","
                                + "\"deviceTypeId\":\"%s\"}".formatted(typeId))).andReturn();
        deviceId = UUID.fromString(JSON.readTree(dev.getResponse().getContentAsString()).get("id").asString());
        deviceKey = "mqtt_sensor";
        // 版本发布属控制面（gap 2.3）；数据面夹具直接建立 1.0.0 与 INITIAL 绑定，接通 versioned 摄入链。
        seedThingModelVersion(tenantId, projectId, typeId, deviceId, TEMPERATURE_SNAPSHOT);

        // 生成凭据
        MvcResult cred = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices/" + deviceId + "/credentials")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        deviceSecret = JSON.readTree(cred.getResponse().getContentAsString()).get("plainSecret").asString();
    }

    /** 正确产品凭据原子创建设备与一机一密，响应使用稳定的有类型契约。 */
    @Test
    void productCredentialRegistersDevice() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/emqx/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"projectKey\":\"%s\",\"productKey\":\"%s\","
                                + "\"productSecret\":\"%s\",\"deviceKey\":\"dynamic_01\"}")
                                .formatted(projectKey, productKey, productSecret)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("deviceKey").asString()).isEqualTo("dynamic_01");
        assertThat(body.get("accessToken").asString()).hasSize(64);
        MvcResult authentication = mockMvc.perform(post("/api/v1/emqx/auth").header(BROKER_HEADER, brokerCallbackSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s/dynamic_01\",\"password\":\"%s\","
                                .formatted(projectKey, body.get("accessToken").asString())
                                + "\"clientid\":\"registered-device\"}"))
                .andReturn();
        assertThat(JSON.readTree(authentication.getResponse().getContentAsString()).get("result").asString())
                .isEqualTo("allow");
    }

    /** 错误产品密钥与不存在产品使用同一 30026，公开端点不能成为产品枚举器。 */
    @Test
    void invalidProductCredentialsUseUniformError() throws Exception {
        MvcResult wrongSecret = mockMvc.perform(post("/api/v1/emqx/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"projectKey\":\"%s\",\"productKey\":\"%s\","
                                + "\"productSecret\":\"%s\",\"deviceKey\":\"dynamic_02\"}")
                                .formatted(projectKey, productKey, "0".repeat(64))))
                .andReturn();
        MvcResult missingProduct = mockMvc.perform(post("/api/v1/emqx/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"projectKey\":\"%s\",\"productKey\":\"missing\","
                                + "\"productSecret\":\"%s\",\"deviceKey\":\"dynamic_03\"}")
                                .formatted(projectKey, "0".repeat(64))))
                .andReturn();

        assertThat(JSON.readTree(wrongSecret.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(30026);
        assertThat(JSON.readTree(missingProduct.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(30026);
        assertThat(wrongSecret.getResponse().getStatus()).isEqualTo(403);
        assertThat(missingProduct.getResponse().getStatus()).isEqualTo(403);
    }

    @Test void correctCredentialsAllowAuth() throws Exception {
        assertThat(projectKey).isNotEmpty();
        assertThat(deviceSecret).hasSize(64);
        String username = projectKey + "/" + deviceKey;
        MvcResult r = mockMvc.perform(post("/api/v1/emqx/auth").header(BROKER_HEADER, brokerCallbackSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\",\"clientid\":\"mqttx_001\"}"
                                .formatted(username, deviceSecret)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(r.getResponse().getContentAsString()).get("result").asString()).isEqualTo("allow");
    }

    /** 凭据轮换提交后，与 EMQX 共用的协议无关端口必须立即拒绝旧密钥，不得等缓存 TTL 到期。 */
    @Test
    void sharedAuthenticationPortRevokesRotatedCredentialImmediately() throws Exception {
        assertThat(authenticationPort.authenticate(projectKey, deviceKey, deviceSecret))
                .as("轮换前的有效密钥应通过共享端口认证").isPresent();

        long nextVersion;
        // 夹具直写必须走 owner 连接：应用角色在未设置 RLS 范围时看不到也改不到设备凭据行。
        try (Connection connection = fixtureOwnerConnection()) {
            try (PreparedStatement revoke = connection.prepareStatement(
                    "UPDATE dev_credential SET deleted_at = now() WHERE project_id = ? AND device_id = ?"
                            + " AND deleted_at IS NULL")) {
                revoke.setObject(1, projectId);
                revoke.setObject(2, deviceId);
                assertThat(revoke.executeUpdate()).as("真实作废旧凭据行").isEqualTo(1);
            }
            try (PreparedStatement bump = connection.prepareStatement(
                    "UPDATE dev_device SET credential_version = credential_version + 1 WHERE id = ?"
                            + " RETURNING credential_version")) {
                bump.setObject(1, deviceId);
                try (ResultSet rows = bump.executeQuery()) {
                    assertThat(rows.next()).as("真实递增设备凭据代际").isTrue();
                    nextVersion = rows.getLong(1);
                }
            }
        }
        cacheInvalidationPublisher.publish(new CacheInvalidationEvent(Uuid7.generate(),
                CacheResource.DEVICE_CREDENTIAL, CacheInvalidationOperation.REVOKE, deviceId, null,
                nextVersion, 0L, Instant.now()));

        assertThat(authenticationPort.authenticate(projectKey, deviceKey, deviceSecret))
                .as("旧密钥必须立即失效而不是继续命中成功缓存").isEmpty();
        assertThat(emqxAuthService.authenticate(projectKey + "/" + deviceKey, deviceSecret, "device-client"))
                .as("MQTT 回调共用同一实现，撤销后同样拒绝").isEqualTo(EmqxAuthService.EmqxAuthResult.DENY);
    }

    /** 缓存及真实Kafka均保存签发时身份，数据库后续代际变化不能重标旧消息。 */
    @Test
    void authenticatedGenerationSurvivesDurableRawKafkaWithoutUpgrade() throws Exception {
        String authRequest = "{\"username\":\"%s/%s\",\"password\":\"%s\",\"clientid\":\"identity-client\"}"
                .formatted(projectKey, deviceKey, deviceSecret);
        MvcResult result = mockMvc.perform(post("/api/v1/emqx/auth")
                .header(BROKER_HEADER, brokerCallbackSecret).contentType(MediaType.APPLICATION_JSON)
                .content(authRequest)).andReturn();
        JsonNode attributes = JSON.readTree(result.getResponse().getContentAsString()).get("client_attrs");
        assertThat(attributes.get("tc_auth_tenant_id").asString()).isEqualTo(tenantId.toString());
        assertThat(attributes.get("tc_auth_project_id").asString()).isEqualTo(projectId.toString());
        assertThat(attributes.get("tc_auth_device_id").asString()).isEqualTo(deviceId.toString());
        long generation = Long.parseLong(attributes.get("tc_auth_credential_version").asString());
        MvcResult cached = mockMvc.perform(post("/api/v1/emqx/auth")
                .header(BROKER_HEADER, brokerCallbackSecret).contentType(MediaType.APPLICATION_JSON)
                .content(authRequest)).andReturn();
        var cachedAttributes = JSON.readTree(cached.getResponse().getContentAsString()).get("client_attrs");
        for(String key:List.of("tc_auth_tenant_id","tc_auth_project_id","tc_auth_device_id","tc_auth_credential_version","tc_auth_config_version"))
            assertThat(cachedAttributes.get(key)).isEqualTo(attributes.get(key));
        assertThat(cachedAttributes.get("tc_auth_connection_id")).isNotEqualTo(attributes.get("tc_auth_connection_id"));
        // 仅模拟认证签发后的当前代际变化，旧信封仍是历史事实，不声称其满足OTA消费资格。
        // 直写必须走 owner 连接：应用角色在未设置 RLS 范围时对 dev_device 的 UPDATE 会静默影响 0 行。
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement bump = connection.prepareStatement(
                     "UPDATE dev_device SET credential_version = credential_version + 1 WHERE id = ?")) {
            bump.setObject(1, deviceId);
            assertThat(bump.executeUpdate()).as("真实递增设备凭据代际").isEqualTo(1);
        }
        authenticatedRawProbe.records.clear();
        UUID messageId = Uuid7.generate();
        String payload = "{\"messageId\":\"%s\",\"occurredAt\":\"2026-08-05T08:00:00Z\","
                .formatted(messageId) + "\"payload\":{\"temperature\":26.5}}";
        String encoded = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String envelope = """
                {"schemaVersion":2,"handoffId":"%s","username":"%s/%s",
                 "topic":"tc/v1/%s/%s/up/property/report","payloadBase64":"%s","qos":1,
                 "retained":false,"clientId":"identity-client","publishedAtMs":1785916800000,
                 "brokerNode":"fixture-node","authenticatedIdentity":{"tenantId":"%s",
                 "projectId":"%s","deviceId":"%s","credentialVersion":"%s"}}
                """.formatted(messageId, projectKey, deviceKey, projectKey, deviceKey, encoded,
                        tenantId, projectId, deviceId, generation);
        assertThat(handoffDispatcher.dispatch(envelope.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(HandoffDisposition.ACCEPTED);
        RawUplinkMessage stored = authenticatedRawProbe.records.poll(10, TimeUnit.SECONDS);
        assertThat(stored).isNotNull();
        assertThat(stored.authenticatedIdentity())
                .isEqualTo(new AuthenticatedDeviceIdentity(tenantId, projectId, deviceId, generation));
        assertThat(stored.payload()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
        // 独立 raw 探针先收到记录不代表业务消费者已经提交；必须等真实落库后才允许下一用例清场。
        // 否则异步写入可能落在原始点清理与项目删除之间，触发 MODEL_RAW_REFERENCE_REMAINS。
        awaitMessageIngestion(messageId, Duration.ofSeconds(10));
    }

    @Test void wrongPasswordDeniesAuth() throws Exception {
        String username = projectKey + "/" + deviceKey;
        MvcResult r = mockMvc.perform(post("/api/v1/emqx/auth").header(BROKER_HEADER, brokerCallbackSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"wrong\",\"clientid\":\"mqttx_001\"}"
                                .formatted(username)))
                .andReturn();
        assertThat(JSON.readTree(r.getResponse().getContentAsString()).get("result").asString()).isEqualTo("deny");
    }

    @Test void aclAllowsPubOnUpAndSubOnDown() throws Exception {
        String username = projectKey + "/" + deviceKey;
        // pub on up/property/report → allow
        MvcResult pub = mockMvc.perform(post("/api/v1/emqx/acl").header(BROKER_HEADER, brokerCallbackSecret).contentType(MediaType.APPLICATION_JSON)
                        .content(withMqttIdentity("{\"username\":\"%s\",\"topic\":\"tc/v1/%s/%s/up/property/report\",\"access\":\"2\"}"
                                .formatted(username, projectKey, deviceKey))))
                .andReturn();
        assertThat(JSON.readTree(pub.getResponse().getContentAsString()).get("result").asString()).isEqualTo("allow");

        // sub on down/command/+ → allow
        MvcResult sub = mockMvc.perform(post("/api/v1/emqx/acl").header(BROKER_HEADER, brokerCallbackSecret).contentType(MediaType.APPLICATION_JSON)
                        .content(withMqttIdentity("{\"username\":\"%s\",\"topic\":\"tc/v1/%s/%s/down/command/abc\",\"access\":\"1\"}"
                                .formatted(username, projectKey, deviceKey))))
                .andReturn();
        assertThat(JSON.readTree(sub.getResponse().getContentAsString()).get("result").asString()).isEqualTo("allow");
    }

    @Test void aclDeniesPubOnDown() throws Exception {
        String username = projectKey + "/" + deviceKey;
        MvcResult r = mockMvc.perform(post("/api/v1/emqx/acl").header(BROKER_HEADER, brokerCallbackSecret).contentType(MediaType.APPLICATION_JSON)
                        .content(withMqttIdentity("{\"username\":\"%s\",\"topic\":\"tc/v1/%s/%s/down/config\",\"access\":\"2\"}"
                                .formatted(username, projectKey, deviceKey))))
                .andReturn();
        assertThat(JSON.readTree(r.getResponse().getContentAsString()).get("result").asString()).isEqualTo("deny");
    }

    @Test void aclDeniesCrossDeviceAccess() throws Exception {
        String username = projectKey + "/" + deviceKey;
        MvcResult r = mockMvc.perform(post("/api/v1/emqx/acl").header(BROKER_HEADER, brokerCallbackSecret).contentType(MediaType.APPLICATION_JSON)
                        .content(withMqttIdentity("{\"username\":\"%s\",\"topic\":\"tc/v1/%s/other_device/up/property/report\",\"access\":\"2\"}"
                                .formatted(username, projectKey))))
                .andReturn();
        assertThat(JSON.readTree(r.getResponse().getContentAsString()).get("result").asString()).isEqualTo("deny");
    }

    /** 已认证 Topic 的属性上报回调应解析真实设备范围并由 Kafka 确认。 */
    @Test
    void publishedMessageEntersRawKafkaTopic() throws Exception {
        UUID messageId = Uuid7.generate();
        String payload = "{\"messageId\":\"%s\",\"occurredAt\":\"2026-08-05T08:00:00Z\","
                .formatted(messageId) + "\"payload\":{\"temperature\":26.5}}";
        String encoded = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(post("/api/v1/emqx/events/message-published").header(BROKER_HEADER, brokerCallbackSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s/%s","topic":"tc/v1/%s/%s/up/property/report",
                                 "payload_base64":"%s","qos":1,"retained":false,
                                 "clientid":"mqttx_001","published_at_ms":1785916800000}
                                """.formatted(projectKey, deviceKey, projectKey, deviceKey, encoded)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("accepted").asBoolean()).isTrue();

        // HTTP 仅等待 raw broker ack；轮询真实数据库证明两个 Kafka consumer 与业务事务已经异步闭环。
        awaitMessageIngestion(messageId, Duration.ofSeconds(10));
        transactionTemplate.executeWithoutResult(status -> {
            setProjectScope();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ts_property_point WHERE message_id = ?", Integer.class, messageId))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ts_device_message_log WHERE message_id = ?", Integer.class, messageId))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT reported ->> 'temperature' FROM dev_shadow WHERE device_id = ?", String.class, deviceId))
                    .isEqualTo("26.5");
        });
    }

    /**
     * 命令回复必须走独立且受 Broker 密钥保护的入口，不能依赖 EMQX Webhook 不会转发的查询串。
     *
     * <p>用协议上不完整的空对象作载荷：专用入口会明确返回 {@code accepted=false}，而通用 raw
     * 入口只保留字节并返回 true。该差异同时锁住 Controller 路由，防止将来又静默落回通用入口。</p>
     */
    @Test
    void commandReplyUsesDedicatedProtectedEndpoint() throws Exception {
        String encoded = Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
        UUID commandId = Uuid7.generate();

        MvcResult result = mockMvc.perform(post("/api/v1/emqx/events/command-reply")
                        .header(BROKER_HEADER, brokerCallbackSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s/%s","topic":"tc/v1/%s/%s/up/command/%s/reply",
                                 "payload_base64":"%s","qos":1,"retained":false,
                                 "clientid":"mqttx_command_reply","published_at_ms":1785916800000}
                                """.formatted(projectKey, deviceKey, projectKey, deviceKey, commandId, encoded)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("accepted").asBoolean()).isFalse();
    }

    /**
     * S6-4 以真实 Broker HTTP 回调驱动两级 Kafka 消费者，证明上行事实会在同一业务事务中激活告警。
     *
     * <p>这条用例刻意不直接调用 {@code AlarmEvaluationService}：那样只能证明状态机，无法发现
     * ingestion 丢失 messageId、traceId 或 telemetry 忘记调用告警公开端口的断链。重复回调必须仍经过
     * raw 与 normalized 主题，最终由 inbox 幂等键吸收，不能为同一 source message 新增告警事件。</p>
     */
    @Test
    void publishedMessageActivatesAlarmWithStableMessageAndTraceWithoutDuplicateEvents() throws Exception {
        TenantContext.set(new TenantScope(tenantId, projectId, accountId));
        try {
            alarmRuleService.create(projectId, new AlarmRuleCommand(
                    "S6 上行温度告警", "HIGH_TEMPERATURE", deviceId, "temperature",
                    AlarmRule.ComparisonOperator.GT, 30D, 0,
                    AlarmRule.ComparisonOperator.LTE, 25D, 0,
                    AlarmRule.Severity.MAJOR, true, null));
        } finally {
            // 规则配置服务依赖控制台租户上下文；Kafka 消费线程则由标准信封设置事务级 RLS 范围。
            TenantContext.clear();
        }

        UUID messageId = Uuid7.generate();
        // traceId 必须满足 HTTP/Kafka 共同的十六进制外部输入约束，否则入口会安全地重生成而无法验收透传。
        String traceId = messageId.toString().replace("-", "");
        String payload = "{\"messageId\":\"%s\",\"occurredAt\":\"2026-08-09T16:00:00Z\","
                .formatted(messageId) + "\"payload\":{\"temperature\":31.0}}";
        String encoded = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        publishPropertyReport(encoded, traceId).andExpect(status().isOk());
        awaitAlarmActivation(messageId, Duration.ofSeconds(10));
        transactionTemplate.executeWithoutResult(status -> {
            setProjectScope();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM alarm_event
                     WHERE project_id=? AND source_message_id=? AND event_type='ACTIVATED' AND trace_id=?
                    """, Integer.class, projectId, messageId, traceId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT condition_state FROM alarm_instance
                     WHERE project_id=? AND originator_id=? AND alarm_type='HIGH_TEMPERATURE'
                    """, String.class, projectId, deviceId)).isEqualTo("ACTIVE");
        });

        // 重发相同设备报文时仍走 HTTP→raw→normalized；S3 inbox 是阻止 S6 重复告警的唯一事实闸门。
        publishPropertyReport(encoded, traceId).andExpect(status().isOk());
        awaitMessageIngestion(messageId, Duration.ofSeconds(10));
        transactionTemplate.executeWithoutResult(status -> {
            setProjectScope();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM alarm_event
                     WHERE project_id=? AND source_message_id=?
                    """, Integer.class, projectId, messageId)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM ts_property_point WHERE message_id=?
                    """, Integer.class, messageId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT trace_id FROM ts_device_message_log WHERE message_id=?
                    """, String.class, messageId)).isEqualTo(traceId);
        });
    }

    /** 构造并发送一条已认证的 EMQX 属性回调，调用者只提供实际报文和预期透传的 traceId。 */
    private org.springframework.test.web.servlet.ResultActions publishPropertyReport(
            String encodedPayload, String traceId) throws Exception {
        return mockMvc.perform(post("/api/v1/emqx/events/message-published")
                .header(BROKER_HEADER, brokerCallbackSecret)
                .header(TraceContext.TRACE_ID_HEADER, traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s/%s","topic":"tc/v1/%s/%s/up/property/report",
                         "payload_base64":"%s","qos":1,"retained":false,
                         "clientid":"mqttx_s6_alarm","published_at_ms":1786291200000}
                        """.formatted(projectKey, deviceKey, projectKey, deviceKey, encodedPayload)));
    }

    /** 在有限时间内等待异步告警事务提交，避免通过固定睡眠掩盖 Kafka 消费失败。 */
    private void awaitAlarmActivation(UUID messageId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Integer count = transactionTemplate.execute(status -> {
                setProjectScope();
                return jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM alarm_event
                         WHERE project_id=? AND source_message_id=? AND event_type='ACTIVATED'
                        """, Integer.class, projectId, messageId);
            });
            if (count != null && count == 1) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("等待 EMQX 上行激活告警超时: " + messageId);
    }

    /** 通配回调必须接收未支持类型，并由 raw 消费者携带类型诊断送入统一 DLQ。 */
    @Test
    void unsupportedPublishedMessageEntersDeadLetterWithTypeDiagnostic() throws Exception {
        String encoded = Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(post("/api/v1/emqx/events/message-published")
                        .header(BROKER_HEADER, brokerCallbackSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s/%s","topic":"tc/v1/%s/%s/up/event/alarm",
                                 "payload_base64":"%s","qos":1,"retained":false,
                                 "clientid":"mqttx_unsupported","published_at_ms":1785916800000}
                                """.formatted(projectKey, deviceKey, projectKey, deviceKey, encoded)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("accepted").asBoolean()).isTrue();

        ConsumerRecord<String, Object> deadLetter = awaitRawDeadLetter(Duration.ofSeconds(10));
        assertThat(deadLetter).isNotNull();
        assertThat(((RawUplinkMessage) deadLetter.value()).topic()).endsWith("/up/event/alarm");
        assertThat(deadLetter.headers()).anySatisfy(header -> {
            assertThat(header.key()).containsIgnoringCase("exception-message");
            assertThat(new String(header.value(), StandardCharsets.UTF_8))
                    .contains("暂不支持的消息类型: event/alarm");
        });
        awaitMetricAtLeast("thingslink.ingestion.dlq.messages", 1D, Duration.ofSeconds(2));
    }

    /**
     * 从统一 DLQ 中筛出本用例需要的原始上行记录。
     *
     * <p>同一 Spring 上下文还运行 normalized、命令和通知消费者；前一测试退出时异步失败记录可能晚于断言到达。
     * 探针因此不能假设队首一定是 {@link RawUplinkMessage}，否则全量套件会被合法的异构 DLQ 记录污染。</p>
     */
    private ConsumerRecord<String, Object> awaitRawDeadLetter(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecord<String, Object> candidate = deadLetterProbe.records.poll(100, TimeUnit.MILLISECONDS);
            if (candidate != null && candidate.value() instanceof RawUplinkMessage raw
                    && raw.topic().endsWith("/up/event/alarm")) {
                return candidate;
            }
        }
        return null;
    }

    /** 在限定时间内等待异步标准消费者提交 inbox，不用固定长睡眠放大测试耗时。 */
    private void awaitMessageIngestion(UUID messageId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Integer count = transactionTemplate.execute(status -> {
                setProjectScope();
                return jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM sys_inbox_message WHERE message_id = ?", Integer.class, messageId);
            });
            if (count != null && count == 1) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("等待标准消息业务落库超时: " + messageId);
    }

    /** 在限定时间内等待异步指标达到下限，避免 Kafka 消费线程与测试探针之间的竞态。 */
    private void awaitMetricAtLeast(String name, double minimum, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            double value = meterRegistry.find(name).counters().stream().mapToDouble(counter -> counter.count()).sum();
            if (value >= minimum) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("等待指标达到下限超时: " + name);
    }

    /** 五项最小指标必须能被无 JWT 的 Prometheus 抓取，并保留消费组等诊断标签。 */
    @Test
    void prometheusEndpointExposesMinimumDataPlaneMetrics() throws Exception {
        dataPlaneMetrics.recordDeadLetter("tc.observability.probe");
        dataPlaneMetrics.recordRateLimited();
        dataPlaneMetrics.recordUplinkVisible(Instant.now().minusMillis(200));
        dataPlaneMetrics.recordBrokerCallback("/api/v1/emqx/auth", 503, Duration.ofMillis(20));

        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("thingslink_kafka_consumer_lag")
                .contains("group=\"things-link-ingestion-raw\"")
                .contains("thingslink_ingestion_dlq_messages_total")
                .contains("thingslink_ingestion_uplink_rate_limited_total")
                .contains("thingslink_ingestion_uplink_end_to_end_seconds")
                .contains("thingslink_emqx_callback_seconds");
    }

    @Test void brokerEventsOwnConnectionLifecycleAndOnlineStatus() throws Exception {
        String username = projectKey + "/" + deviceKey;
        mockMvc.perform(post("/api/v1/emqx/events/connected").header(BROKER_HEADER, brokerCallbackSecret).contentType(MediaType.APPLICATION_JSON)
                .content(withMqttIdentity("""
                        {"username":"%s","clientid":"mqttx_001","peerhost":"10.0.0.8","node":"emqx@node1"}
                        """.formatted(username)))).andReturn();

        transactionTemplate.executeWithoutResult(status -> {
            setProjectScope();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM dev_device WHERE id = ?", String.class, deviceId))
                    .isEqualTo("ONLINE");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT client_ip FROM dev_connection WHERE device_id = ? AND disconnected_at IS NULL",
                    String.class, deviceId)).isEqualTo("10.0.0.8");
        });

        mockMvc.perform(post("/api/v1/emqx/events/disconnected").header(BROKER_HEADER, brokerCallbackSecret).contentType(MediaType.APPLICATION_JSON)
                .content(withMqttIdentity("""
                        {"username":"%s","clientid":"mqttx_001","reason":"client_disconnect"}
                        """.formatted(username)))).andReturn();

        transactionTemplate.executeWithoutResult(status -> {
            setProjectScope();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM dev_device WHERE id = ?", String.class, deviceId))
                    .isEqualTo("OFFLINE");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT disconnect_reason FROM dev_connection WHERE device_id = ?",
                    String.class, deviceId)).isEqualTo("client_disconnect");
        });
    }

    /** 同一设备存在多条 Broker 会话时，只有最后一条断开才能把设备置为离线。 */
    @Test void deviceStaysOnlineUntilItsLastBrokerSessionDisconnects() throws Exception {
        String username = projectKey + "/" + deviceKey;
        for (String clientId : List.of("mqttx_primary", "mqttx_backup")) {
            mockMvc.perform(post("/api/v1/emqx/events/connected").header(BROKER_HEADER, brokerCallbackSecret).contentType(MediaType.APPLICATION_JSON)
                    .content(withMqttIdentity("""
                            {"username":"%s","clientid":"%s","peerhost":"10.0.0.9","node":"emqx@node1"}
                            """.formatted(username, clientId))))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/emqx/events/disconnected").header(BROKER_HEADER, brokerCallbackSecret).contentType(MediaType.APPLICATION_JSON)
                .content(withMqttIdentity("""
                        {"username":"%s","clientid":"mqttx_primary","reason":"normal"}
                        """.formatted(username))))
                .andExpect(status().isOk());

        transactionTemplate.executeWithoutResult(status -> {
            setProjectScope();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM dev_device WHERE id = ?", String.class, deviceId))
                    .isEqualTo("ONLINE");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM dev_connection
                     WHERE device_id = ? AND disconnected_at IS NULL
                    """, Integer.class, deviceId)).isEqualTo(1);
        });

        mockMvc.perform(post("/api/v1/emqx/events/disconnected").header(BROKER_HEADER, brokerCallbackSecret).contentType(MediaType.APPLICATION_JSON)
                .content(withMqttIdentity("""
                        {"username":"%s","clientid":"mqttx_backup","reason":"normal"}
                        """.formatted(username))))
                .andExpect(status().isOk());

        transactionTemplate.executeWithoutResult(status -> {
            setProjectScope();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM dev_device WHERE id = ?", String.class, deviceId))
                    .isEqualTo("OFFLINE");
        });
    }

    /**
     * S3.5-1：六个 Broker 回调端点在没有共享密钥时一律 401，且不产生任何副作用。
     *
     * <p>这条用例守的是架构缺口 G-01。在它之前，这些端点是真正的无鉴权入口：
     * 只要知道 projectKey/deviceKey 就能伪造设备上行、把设备打成离线。
     *
     * <p>断言里特意包含 {@code /events/disconnected}：它是「无副作用」最容易被
     * 破坏的一个 —— 只要过滤器漏了它，一个匿名请求就能让线上设备批量离线。
     */
    @Test
    @DisplayName("S3.5-1：无共享密钥的 Broker 回调一律 401 且无副作用")
    void brokerCallbacksRejectRequestsWithoutSharedSecret() throws Exception {
        String username = projectKey + "/" + deviceKey;

        // 先让设备上线，后面用它证明被拒的 disconnected 回调没有真的改状态
        mockMvc.perform(post("/api/v1/emqx/events/connected")
                        .header(BROKER_HEADER, brokerCallbackSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withMqttIdentity("""
                                {"username":"%s","clientid":"mqttx_guard","peerhost":"10.0.0.8","node":"emqx@node1"}
                                """.formatted(username))))
                .andExpect(status().isOk());

        for (String path : List.of(
                "/api/v1/emqx/auth",
                "/api/v1/emqx/acl",
                "/api/v1/emqx/events/connected",
                "/api/v1/emqx/events/disconnected",
                "/api/v1/emqx/events/message-published",
                "/api/v1/emqx/events/command-reply")) {
            // 完全不带 Authorization
            MvcResult missing = mockMvc.perform(post(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"%s\",\"clientid\":\"mqttx_guard\",\"reason\":\"normal\"}"
                                    .formatted(username)))
                    .andReturn();
            assertThat(missing.getResponse().getStatus()).as(path + " 缺少凭据").isEqualTo(401);
            assertThat(JSON.readTree(missing.getResponse().getContentAsString()).get("code").asInt())
                    .as(path + " 错误码").isEqualTo(20030);

            // 带了但不对。响应必须与上面完全一致 —— 区分开会告诉探测者这里确实有共享密钥
            MvcResult wrong = mockMvc.perform(post(path)
                            .header(BROKER_HEADER, "x".repeat(64))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"%s\",\"clientid\":\"mqttx_guard\",\"reason\":\"normal\"}"
                                    .formatted(username)))
                    .andReturn();
            assertThat(wrong.getResponse().getStatus()).as(path + " 凭据错误").isEqualTo(401);
            assertThat(JSON.readTree(wrong.getResponse().getContentAsString()).get("code").asInt())
                    .as(path + " 错误码").isEqualTo(20030);
        }

        // 被拒的 disconnected 没有改动任何状态：设备仍在线，会话仍活跃
        transactionTemplate.executeWithoutResult(status -> {
            setProjectScope();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM dev_device WHERE id = ?", String.class, deviceId)).isEqualTo("ONLINE");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM dev_connection
                     WHERE device_id = ? AND disconnected_at IS NULL
                    """, Integer.class, deviceId)).isEqualTo(1);
        });
        double failures = meterRegistry.get("thingslink.emqx.callback")
                .tag("result", "failure").timers().stream().mapToDouble(timer -> timer.count()).sum();
        assertThat(failures).isGreaterThanOrEqualTo(10D);
    }

    /**
     * S3.5-1：加矩阵参数不能绕过回调鉴权。
     *
     * <p>{@code /api/v1/emqx/auth;x=1} 在 {@code getRequestURI()} 里原样保留，而
     * Spring MVC 的 PathPattern 会剥掉 {@code ;} 之后的内容照样路由到 Controller。
     * 过滤器若用未归一化的路径做精确匹配，这一个分号就是完整的鉴权绕过。
     *
     * <p><b>当前实际拦下它的是 Spring Security 的 {@code StrictHttpFirewall}</b>：
     * 它默认拒绝 URL 里含分号的请求，返回 400，请求根本走不到本应用的过滤器。
     * 所以这里断言「被拒绝」而不是断言某个具体码 —— 写死 401 会让这条用例
     * 实际上在测防火墙，而不是测我们自己的归一化。
     *
     * <p>那为什么过滤器还要做归一化？因为防火墙是<b>可配置的</b>：将来任何一次
     * 「某个客户端要用矩阵参数，把 allowSemicolon 打开」的改动，都会让这一层消失。
     * 两道防线各自独立成立，才不会因为一次配置调整就同时失效。
     */
    @Test
    @DisplayName("S3.5-1：矩阵参数不能绕过回调鉴权")
    void matrixParametersCannotBypassCallbackAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/emqx/auth;bypass=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s/%s\",\"password\":\"%s\",\"clientid\":\"bypass\"}"
                                .formatted(projectKey, deviceKey, deviceSecret)))
                .andReturn();

        // 400 = StrictHttpFirewall 挡下；401 = 防火墙放宽后由本应用的过滤器挡下。
        // 唯一不可接受的是 200：那意味着一个分号就完成了鉴权绕过
        assertThat(result.getResponse().getStatus()).isIn(400, 401);
    }

    /**
     * S3.5-1：动态注册端点<b>不能</b>被共享密钥挡住。
     *
     * <p>它的路径也在 {@code /api/v1/emqx/} 下，但调用方是设备而不是 Broker（ADR 0003）。
     * 要求设备带共享密钥等于把密钥预置进每一台出厂设备，它也就不再是秘密。
     *
     * <p>用「错误的产品密钥」而不是正确的来断言：正确的会真的建出设备，让这条用例
     * 依赖前面的清库顺序。这里只要证明它<b>没有被 401 挡在门外</b>，
     * 走到了业务判定（30026）就够了。
     */
    @Test
    @DisplayName("S3.5-1：设备动态注册端点不受 Broker 共享密钥约束")
    void deviceRegistrationEndpointStaysOpenToDevices() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/emqx/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"projectKey\":\"%s\",\"productKey\":\"%s\","
                                + "\"productSecret\":\"%s\",\"deviceKey\":\"no_broker_secret\"}")
                                .formatted(projectKey, productKey, "0".repeat(64))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(30026);
    }

    /** 测试线程没有 JWT 过滤器，读取 RLS 表前显式设置当前项目。 */
    private void setProjectScope() {
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, false)", String.class, tenantId.toString());
        jdbcTemplate.queryForObject("SELECT set_config('app.project_id', ?, false)", String.class, projectId.toString());
    }
    /** 每个实际Client ID保留同一次认证身份，断连不得重新认证换票据。 */
    private final java.util.Map<String, JsonNode> originalConnectionAuthentications = new java.util.HashMap<>();
    /** 从真实认证响应冻结服务器属性，保持回调测试与当前Broker合同一致。 */
    private String withMqttIdentity(String body) throws Exception {
        var payload=(tools.jackson.databind.node.ObjectNode)JSON.readTree(body);
        String raw=payload.path("clientid").asString("fixture-auth");
        var original=originalConnectionAuthentications.get(raw);
        if(original==null){
            var authentication = mockMvc.perform(post("/api/v1/emqx/auth").header(BROKER_HEADER,brokerCallbackSecret)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(java.util.Map.of(
                    "username",projectKey+"/"+deviceKey,"password",deviceSecret,"clientid",raw)))).andReturn();
            assertThat(authentication.getResponse().getStatus()).isEqualTo(200);
            original=JSON.readTree(authentication.getResponse().getContentAsString());
            assertThat(original.path("result").asString()).isEqualTo("allow");
            originalConnectionAuthentications.put(raw,original);
        }
        var attrs=original.get("client_attrs");
        for(String key:List.of("tc_auth_tenant_id","tc_auth_project_id","tc_auth_device_id","tc_auth_credential_version","tc_auth_config_version","tc_auth_connection_id"))
            payload.put(key,attrs.get(key).asString());
        payload.put("clientid",original.path("clientid_override").asString());
        return JSON.writeValueAsString(payload);
    }

}
