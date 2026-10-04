package com.things.link.ingestion;

import com.things.link.dashboard.application.DashboardShareRealtimeAccessService;
import com.things.link.dashboard.application.DashboardShareRuntimeService;
import com.things.link.dashboard.application.sharing.DashboardShareProtectionService;
import com.things.link.dashboard.application.sharing.DashboardShareRuntimeProperties;
import com.things.link.dashboard.application.sharing.DashboardShareSecurityEvents;
import com.things.link.device.application.DeviceAccessScopeService;
import com.things.link.device.application.DeviceBatchIngestionService;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.device.application.DeviceTopologyIngestionService;
import com.things.link.device.application.ModbusConfigService;
import com.things.link.device.application.ModbusResponseAcceptanceService;
import com.things.link.project.application.ProjectService;
import com.things.link.ota.application.OtaDeviceReportIngestionService;
import com.things.link.ota.application.OtaDownloadRequestIngestionService;
import com.things.link.ota.application.OtaJobProgressIngestionService;
import com.things.link.ota.application.OtaConfirmationIngestionService;
import com.things.link.ota.application.OtaCommitReconciliationIngestionService;
import com.things.link.ota.application.OtaRollbackPreflightIngestionService;
import com.things.link.ota.application.OtaRollbackIngestionService;
import com.things.link.ota.application.OtaInstallStopIngestionService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.QuotaRuntimeMetrics;
import com.things.link.support.kafka.KafkaTraceConfiguration;
import com.things.link.support.observability.DataPlaneMetrics;
import com.things.link.telemetry.application.PropertyIngestionService;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.rule.application.queue.PublishedRuleCatalog;
import com.things.link.rule.application.queue.RuleExecutionCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.BATCH_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.CONFIG_REPLY_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.MODBUS_RESPONSE_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.TOPO_TOPIC;
import static com.things.link.ingestion.infrastructure.ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.UplinkKafkaConfiguration.DEAD_LETTER_TOPIC;
import static com.things.link.ingestion.infrastructure.DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.TopologyReplyKafkaConsumer.TOPOLOGY_REPLY_TOPIC;
import static com.things.link.ingestion.infrastructure.DeviceConfigKafkaConsumer.CONFIG_TOPIC;
import static com.things.link.ingestion.infrastructure.ModbusRequestKafkaConsumer.MODBUS_REQUEST_TOPIC;
import static com.things.link.ingestion.application.RealtimeKafkaPublisher.REALTIME_TOPIC;
import static org.mockito.Mockito.mock;

/**
 * ingestion 模块集成测试的最小启动入口。
 *
 * <p>生产运行仍由 bootstrap 唯一启动；本类只存在于测试 classpath。</p>
 */
@SpringBootApplication
@Import({KafkaTraceConfiguration.class, DataPlaneMetrics.class, QuotaRuntimeMetrics.class})
public class IngestionTestApplication {

    /** 独立模块不扫描device生产适配器；默认无路由，不冒充真实MQTT下行资格。 */
    @Bean
    com.things.link.device.application.DeviceMqttDownlinkRoutePort testMqttRoutePort() {
        return mock(com.things.link.device.application.DeviceMqttDownlinkRoutePort.class);
    }

    /** 配置持久准入由bootstrap真库专项验证；模块夹具只补齐明确的跨域依赖。 */
    @Bean
    com.things.link.device.application.DeviceConfigDeliveryAdmissionService testConfigAdmission() {
        return mock(com.things.link.device.application.DeviceConfigDeliveryAdmissionService.class);
    }

    /** 独立模块只验证实时分流；公开集成原事务与送达由bootstrap真库专项验证。 */
    @Bean
    com.things.link.integration.application.PublicRealtimeIngress publicRealtimeIngress() {
        return mock(com.things.link.integration.application.PublicRealtimeIngress.class);
    }


    /** 模块仅验证Kafka分流；真实OTA报告事务由bootstrap整合专项验证。 */
    @Bean
    OtaDeviceReportIngestionService otaReports() { return mock(OtaDeviceReportIngestionService.class); }

    /** 模块不扫描跨域生产服务；实际下载申请资格另由bootstrap真实PG/Kafka验证。 */
    @Bean
    OtaDownloadRequestIngestionService otaDownloadRequests() { return mock(OtaDownloadRequestIngestionService.class); }

    /** 模块仅验证进度分流；真实认证、来源和阶段事务由bootstrap专项验证。 */
    @Bean
    OtaJobProgressIngestionService otaJobProgress() { return mock(OtaJobProgressIngestionService.class); }

    /** 独立模块只验证分流；完整健康提交事务在bootstrap验证。 */
    @Bean
    OtaConfirmationIngestionService otaConfirmation() { return mock(OtaConfirmationIngestionService.class); }

    /** 独立模块验证对账分流，真实范围与采用由bootstrap验证。 */
    @Bean
    OtaCommitReconciliationIngestionService otaReconciliation() { return mock(OtaCommitReconciliationIngestionService.class); }

    /** 本模块只验证预检路由，完整资格由bootstrap真实事务验收。 */
    @Bean
    OtaRollbackPreflightIngestionService otaPreflight() { return mock(OtaRollbackPreflightIngestionService.class); }

    /** 独立接入测试仅隔离OTA事务，不为生产提供默认成功。 */
    @org.springframework.context.annotation.Bean
    OtaRollbackIngestionService otaRollback() { return mock(OtaRollbackIngestionService.class); }

    /**
     * 模块测试不加载 bootstrap Actuator，使用内存注册表承载与生产相同的指标 Bean。
     *
     * @return 测试进程内指标注册表
     */
    @Bean
    MeterRegistry testMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    /**
     * 显式创建生产 listener 依赖的原始主题；测试 broker 禁止借自动建主题掩盖分区基线错误。
     *
     * @return 三分区测试主题，生产环境由 deploy 脚本创建 12 分区
     */
    @Bean
    NewTopic rawUplinkTopic() {
        return TopicBuilder.name(RAW_UPLINK_TOPIC).partitions(3).replicas(1).build();
    }

    /**
     * 显式创建标准消息主题。
     *
     * @return 三分区测试主题
     */
    @Bean
    NewTopic normalizedUplinkTopic() {
        return TopicBuilder.name(NORMALIZED_UPLINK_TOPIC).partitions(3).replicas(1).build();
    }

    /**
     * 显式创建规则成功后的可靠续接主题。
     *
     * @return 与 normalized 相同分区数的处理后测试主题
     */
    @Bean
    NewTopic processedUplinkTopic() {
        return TopicBuilder.name(PROCESSED_UPLINK_TOPIC).partitions(3).replicas(1).build();
    }

    /**
     * 显式创建连接设备有序的下行测试主题。
     *
     * @return 三分区测试主题；生产环境固定 12 分区
     */
    @Bean
    NewTopic commandDownlinkTopic() {
        return TopicBuilder.name(DOWNLINK_TOPIC).partitions(3).replicas(1).build();
    }

    /**
     * 显式创建实时增量主题，禁止测试 broker 的自动建主题掩盖生产分区基线。
     *
     * @return 三分区实时主题
     */
    @Bean
    NewTopic realtimeTopic() {
        return TopicBuilder.name(REALTIME_TOPIC).partitions(3).replicas(1).build();
    }

    /**
     * 显式创建统一死信主题。
     *
     * @return 单分区测试 DLQ；生产环境按 deploy 基线为三分区
     */
    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER_TOPIC).partitions(1).replicas(1).build();
    }

    /** S10-2a 拓扑消息主题。 @return 三分区测试主题 */
    @Bean
    NewTopic topoTopic() {
        return TopicBuilder.name(TOPO_TOPIC).partitions(3).replicas(1).build();
    }

    /** S10-2b 批量属性上报主题。 @return 三分区测试主题 */
    @Bean
    NewTopic batchTopic() {
        return TopicBuilder.name(BATCH_TOPIC).partitions(3).replicas(1).build();
    }

    /** S10-3 拓扑回执主题。 @return 三分区测试主题 */
    @Bean
    NewTopic topologyReplyTopic() {
        return TopicBuilder.name(TOPOLOGY_REPLY_TOPIC).partitions(3).replicas(1).build();
    }

    /** S10-4b 配置下发主题。 @return 三分区测试主题 */
    @Bean
    NewTopic configTopic() {
        return TopicBuilder.name(CONFIG_TOPIC).partitions(3).replicas(1).build();
    }

    /** S10-4b 配置回执主题。 @return 三分区测试主题 */
    @Bean
    NewTopic configReplyTopic() {
        return TopicBuilder.name(CONFIG_REPLY_TOPIC).partitions(3).replicas(1).build();
    }

    /** S10-4c Modbus 读请求主题。 @return 三分区测试主题 */
    @Bean
    NewTopic modbusRequestTopic() {
        return TopicBuilder.name(MODBUS_REQUEST_TOPIC).partitions(3).replicas(1).build();
    }

    /** S10-4c Modbus 响应主题。 @return 三分区测试主题 */
    @Bean
    NewTopic modbusResponseTopic() {
        return TopicBuilder.name(MODBUS_RESPONSE_TOPIC).partitions(3).replicas(1).build();
    }

    /**
     * ingestion 模块测试只验证消息适配，不加载 telemetry 业务迁移；使用首选替身隔离标准消费者副作用。
     *
     * @return telemetry 批量摄入应用端口替身
     */
    @Bean
    @Primary
    PropertyIngestionService testPropertyIngestionService() {
        return mock(PropertyIngestionService.class);
    }

    /**
     * ingestion 模块 Kafka 测试不加载 rule 数据库目录，由专项用例自行声明活动计划。
     *
     * @return 已发布规则目录替身
     */
    @Bean
    @Primary
    PublishedRuleCatalog testPublishedRuleCatalog() {
        return mock(PublishedRuleCatalog.class);
    }

    /**
     * ingestion 模块测试不启动真实公平 Worker；S8 整合验收在 bootstrap 覆盖完整链路。
     *
     * @return 规则队列协调器替身
     */
    @Bean
    @Primary
    RuleExecutionCoordinator testRuleExecutionCoordinator() {
        return mock(RuleExecutionCoordinator.class);
    }

    /**
     * ingestion 模块测试不加载 telemetry 命令迁移，使用替身隔离下行与回复适配器副作用。
     *
     * @return telemetry 命令状态机 application 替身
     */
    @Bean
    @Primary
    DeviceCommandService testDeviceCommandService() {
        return mock(DeviceCommandService.class);
    }

    /**
     * HTTP 上行入口的设备确权端口替身；本模块集成测试不加载 device 模块的数据库实现。
     *
     * @return 设备确权应用端口替身
     */
    @Bean
    @Primary
    DeviceAccessScopeService testDeviceAccessScopeService() {
        return mock(DeviceAccessScopeService.class);
    }

    /**
     * 接入幂等受理事实由 device 域拥有并随其迁移建表；本模块集成测试不加载设备迁移，
     * 用主选替身承接判定与写入，真实 PostgreSQL 语义由 bootstrap 真库用例覆盖。
     *
     * @return 接入幂等受理端口替身
     */
    @Bean
    @Primary
    com.things.link.device.application.DeviceAccessAcceptancePort testDeviceAccessAcceptancePort() {
        return mock(com.things.link.device.application.DeviceAccessAcceptancePort.class);
    }

    /**
     * 设备面认证用例依赖 device 域的凭据校验与会话端口；本模块集成测试只扫描 ingestion 包，
     * 因此用主选替身满足装配，真实凭据、配置与活动语义由 bootstrap 真库用例覆盖。
     *
     * @return 协议无关凭据校验端口替身
     */
    @Bean
    @Primary
    com.things.link.device.application.DeviceAuthenticationPort testDeviceAuthenticationPort() {
        return mock(com.things.link.device.application.DeviceAuthenticationPort.class);
    }

    /**
     * 接入配置与会话事实由 device 域拥有；本模块不加载设备迁移，用主选替身满足认证用例装配。
     *
     * @return 接入会话端口替身
     */
    @Bean
    @Primary
    com.things.link.device.application.DeviceAccessSessionPort testDeviceAccessSessionPort() {
        return mock(com.things.link.device.application.DeviceAccessSessionPort.class);
    }

    /**
     * 命令事实由 telemetry 域拥有；设备面命令端点在本模块装配，但命令状态机与仓储不在扫描范围，
     * 因此用主选替身满足装配，真实租约与终态语义由 bootstrap 真库用例覆盖。
     *
     * @return 命令领取端口替身
     */
    @Bean
    @Primary
    com.things.link.telemetry.application.DeviceCommandClaimPort testDeviceCommandClaimPort() {
        return mock(com.things.link.telemetry.application.DeviceCommandClaimPort.class);
    }

    /**
     * 统一业务回复入口同样属 telemetry 域；本模块只验证端点装配。
     *
     * @return 业务回复端口替身
     */
    @Bean
    @Primary
    com.things.link.telemetry.application.DeviceCommandAccessReplyPort testDeviceCommandAccessReplyPort() {
        return mock(com.things.link.telemetry.application.DeviceCommandAccessReplyPort.class);
    }

    /**
     * WebSocket 订阅仅需项目成员确权端口；模块测试不加载 project 的数据库实现。
     *
     * @return 项目成员资格校验替身
     */
    @Bean
    @Primary
    ProjectService testProjectService() {
        return mock(ProjectService.class);
    }

    /**
     * 实时代次复核新增项目生命周期端口；模块测试不加载项目仓储，故提供主选替身满足上下文。
     *
     * @return 项目生命周期及凭据代次快照替身
     */
    @Bean
    @Primary
    ProjectLifecycleAccessService testProjectLifecycleAccessService() {
        return mock(ProjectLifecycleAccessService.class);
    }

    /**
     * HTTP 上行入口现经 project 公共策略接口取 owner tenant 套餐；模块隔离测试不加载 project 持久层，
     * 因而提供替身并由各测试明确声明期望策略。
     *
     * @return 有效配额策略公开接口替身
     */
    @Bean
    @Primary
    EffectiveQuotaPolicyProvider testEffectiveQuotaPolicyProvider() {
        return mock(EffectiveQuotaPolicyProvider.class);
    }

    /**
     * WebSocket 订阅逐设备调用接入公开服务，避免测试启动完整 device 领域。
     *
     * @return 设备归属校验替身
     */
    @Bean
    @Primary
    DeviceIngestionService testDeviceIngestionService() {
        return mock(DeviceIngestionService.class);
    }

    /** S10-2a 拓扑在线状态机替身；ingestion 只做信封校验与转发，不加载 device 拓扑状态机。 */
    @Bean
    @Primary
    DeviceTopologyIngestionService testDeviceTopologyIngestionService() {
        return mock(DeviceTopologyIngestionService.class);
    }

    /** S10-2b 批量上报归属复核替身；ingestion 只做协议解析与转发。 */
    @Bean
    @Primary
    DeviceBatchIngestionService testDeviceBatchIngestionService() {
        return mock(DeviceBatchIngestionService.class);
    }

    /** S10-4b Modbus 配置下发/诊断替身；ingestion 只做配置信封发布与回执转发。 */
    @Bean
    @Primary
    ModbusConfigService testModbusConfigService() {
        return mock(ModbusConfigService.class);
    }

    /** ADR0063响应持久接管替身；本模块只验证适配，真实数据库接纳由bootstrap集成覆盖。 */
    @Bean
    @Primary
    ModbusResponseAcceptanceService testModbusResponseAcceptanceService() {
        return mock(ModbusResponseAcceptanceService.class);
    }

    /**
     * 握手拦截器要求 JWT 解码器；Kafka 模块测试不会执行 HTTP 握手，故用替身满足装配。
     *
     * @return JWT 解码器替身
     */
    @Bean(name = {"testJwtDecoder", "jwtDecoder"})
    @Primary
    JwtDecoder testJwtDecoder() {
        return mock(JwtDecoder.class);
    }
    /** 消息模块隔离测试不加载App持久层；真实实时授权由Bootstrap网络旅程验证。 */
    @Bean
    com.things.link.enduser.application.AppRealtimeAccessService testAppRealtimeAccessService() {
        return mock(com.things.link.enduser.application.AppRealtimeAccessService.class);
    }

    /** Console身份出口留在其拥有模块，Kafka测试只满足依赖装配。 */
    @Bean
    com.things.link.project.application.AccountDirectory testAccountDirectory() {
        return mock(com.things.link.project.application.AccountDirectory.class);
    }

    /** 指定设备批量端口替身，不在消息模块初始化设备迁移。 */
    @Bean
    com.things.link.device.application.AppDeviceDataPlaneService testAppDeviceDataPlaneService() {
        return mock(com.things.link.device.application.AppDeviceDataPlaneService.class);
    }

    /** 本测试不执行新WebSocket确权；事务RLS由Bootstrap普通账号实证。 */
    @Bean
    com.things.link.support.tenant.TransactionLocalRlsScope testTransactionLocalRlsScope() {
        return mock(com.things.link.support.tenant.TransactionLocalRlsScope.class);
    }

    /** 分享订阅授权由dashboard拥有；Kafka模块测试不装配其数据库和发布资格。 */
    @Bean
    DashboardShareRealtimeAccessService testDashboardShareRealtimeAccessService() {
        return mock(DashboardShareRealtimeAccessService.class);
    }

    /** 独立分享握手的能力定位替身；真实PG/RLS授权仍由Bootstrap网络旅程证明。 */
    @Bean
    DashboardShareRuntimeService testDashboardShareRuntimeService() {
        return mock(DashboardShareRuntimeService.class);
    }

    /** 消息适配用例不发起匿名握手，来源限流的真实Redis测试使用独立专项装配。 */
    @Bean
    DashboardShareProtectionService testDashboardShareProtectionService() {
        return mock(DashboardShareProtectionService.class);
    }

    /** 模块隔离配置明确关闭匿名入口，不凭mock领域端口自动开放分享。 */
    @Bean
    DashboardShareRuntimeProperties testDashboardShareRuntimeProperties() {
        return new DashboardShareRuntimeProperties(false, null, false, null);
    }

    /** 安全事件出口由dashboard拥有；不因消息测试启动真实滚动日志工作线程。 */
    @Bean
    DashboardShareSecurityEvents testDashboardShareSecurityEvents() {
        return mock(DashboardShareSecurityEvents.class);
    }

    /** 精确App decoder名字与Console分离，不能由Primary兜底误配。 */
    @Bean
    JwtDecoder appJwtDecoder() { return mock(JwtDecoder.class); }
    /** 独立停止事务服务由bootstrap真实集成验证，此处仅装配分流入口。 */
    @Bean
    OtaInstallStopIngestionService otaInstallStop() { return mock(OtaInstallStopIngestionService.class); }

}
