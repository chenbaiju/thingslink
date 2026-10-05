package com.things.link.support.outbox;

import com.things.link.support.fault.FaultInjectionCheckpoint;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.AutomationPropertyAccepted;
import com.things.link.shared.message.DeviceCommandTerminalEvent;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.shared.message.ModbusRequest;
import com.things.link.shared.message.NotificationDeliveryRequest;
import com.things.link.shared.message.RuleNotificationDeliveryRequest;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TopologyReplyMessage;
import com.things.link.support.observability.OutboxMetrics;
import com.things.link.support.trace.TraceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.DeserializationFeature;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 将已提交的 Outbox 事件可靠投递到 Kafka。
 *
 * <p>该发布器是 at-least-once：Kafka 已确认、进程在数据库确认前崩溃时会重投，消费者必须按其业务
 * 幂等键吸收重复。反过来，业务事务提交后发布器崩溃不会丢失事件，因为记录仍会在租约过期后被领取。
 */
@Component
@ConditionalOnProperty(
        name = "things-link.outbox.publisher.enabled",
        havingValue = "true",
        matchIfMissing = true)
@DataPlaneDatabase
public class KafkaTransactionalOutboxPublisher implements DisposableBean {

    /** 仅输出事件标识和主题，禁止把可能包含设备参数的 JSON 载荷写入日志。 */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(KafkaTransactionalOutboxPublisher.class);

    /** 等待 Kafka broker 确认上限；必须短于默认 30 秒租约，防止旧领取者长期占用记录。 */
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    /** S4-3 冻结下行主题；部署脚本已以 12 分区创建，不能由业务输入决定。 */
    public static final String DEVICE_DOWNLINK_TOPIC = "tc.device.downlink";

    /** S6-2 冻结通知请求主题；deploy 已显式创建六分区，禁止业务侧自动建主题。 */
    public static final String NOTIFICATION_TOPIC = "tc.notification";

    /** S9-1 冻结规则通知投递主题；deploy 已显式创建六分区，禁止业务侧自动建主题。 */
    public static final String RULE_NOTIFICATION_TOPIC = "tc.rule.notification";
    /** S9-2 设备操作终态回写主题。 */
    public static final String DEVICE_COMMAND_TERMINAL_TOPIC = "tc.device.command.terminal";

    /** S10-3 网关拓扑上报回执主题，key 为网关 ID。 */
    public static final String DEVICE_TOPOLOGY_REPLY_TOPIC = "tc.device.topo.reply";

    /** S10-4b 网关配置下发主题，key 为网关 ID。 */
    public static final String DEVICE_CONFIG_TOPIC = "tc.device.config";

    /** S10-4c Modbus 读请求下发主题，key 为网关 ID。 */
    public static final String DEVICE_MODBUS_REQUEST_TOPIC = "tc.device.modbus.request";

    /** 命令派发事件的稳定类型；非白名单类型绝不发送到 Kafka。 */
    public static final String DEVICE_COMMAND_DISPATCH_EVENT = "DEVICE_COMMAND_DISPATCH";
    /** 设备命令/属性设置终态事件。 */
    public static final String DEVICE_COMMAND_TERMINAL_EVENT = DeviceCommandTerminalEvent.EVENT_TYPE;
    /** 网关拓扑上报回执事件类型。 */
    public static final String DEVICE_TOPOLOGY_REPLY_EVENT = TopologyReplyMessage.EVENT_TYPE;
    /** 网关配置下发事件类型。 */
    public static final String DEVICE_CONFIG_PUSH_EVENT = DeviceConfigPush.EVENT_TYPE;
    /** Modbus 读请求事件类型。 */
    public static final String DEVICE_MODBUS_REQUEST_EVENT = ModbusRequest.EVENT_TYPE;

    /** 告警投递请求的稳定 Outbox 事件类型。 */
    public static final String NOTIFICATION_DELIVERY_REQUEST_EVENT =
            NotificationDeliveryRequest.EVENT_TYPE;

    /** 规则通知投递请求的稳定 Outbox 事件类型。 */
    public static final String RULE_NOTIFICATION_DELIVERY_REQUEST_EVENT =
            RuleNotificationDeliveryRequest.EVENT_TYPE;

    /** 失败退避的最小间隔，防止 broker 故障期间形成无间隔忙循环。 */
    private static final Duration MIN_RETRY_DELAY = Duration.ofSeconds(1);

    /** 冻结四条单线程 stripe；同 lane 固定落入同一条，跨 lane 并行等待 broker。 */
    private static final int STRIPE_COUNT = 4;

    /** 每条 stripe 只允许一个运行项和一个等待项，四条总容量等于单轮领取上限八。 */
    private static final int STRIPE_QUEUE_CAPACITY = 1;

    /** 优雅停机等待必须小于 30 秒租约，未完成项随后由其他实例接管。 */
    private static final int SHUTDOWN_AWAIT_SECONDS = 25;

    /** Outbox 存储端口。 */
    private final TransactionalOutboxRepository outboxRepository;

    /** 继承全局幂等 producer 与 trace interceptor 的 Kafka 模板。 */
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** 低基数投递观测门面。 */
    private final OutboxMetrics metrics;

    /** Outbox 文本载荷到共享消息契约的反序列化器。 */
    private final ObjectMapper objectMapper;

    /** 只为已冻结Modbus标准信封恢复精确数值，不改变其他Outbox路由或全局mapper。 */
    private final ObjectReader modbusNormalizedReader;

    /** 每轮最多领取数，由数据库再次强制为 1 到 8。 */
    private final int batchSize;

    /** 数据库租约时长；必须长于单条 broker 等待上限。 */
    private final Duration leaseDuration;

    /** 固定 stripe worker；拒绝必须回写重试，不能 CallerRuns 阻塞 trigger。 */
    private final List<ThreadPoolExecutor> stripeExecutors;
    /** G1-C4b 默认禁用的一次性强杀屏障；没有网络入口且只保存稳定身份摘要。 */
    private final FaultInjectionCheckpoint faultCheckpoint;

    /**
     * 创建后台发布器。
     *
     * @param outboxRepository Outbox 存储端口
     * @param kafkaTemplate Kafka 发布模板
     * @param metrics Outbox 指标门面
     * @param objectMapper JSON 映射器
     * @param batchSize 单轮批次大小
     * @param leaseSeconds Outbox 领取租约秒数
     */
    public KafkaTransactionalOutboxPublisher(
            TransactionalOutboxRepository outboxRepository,
            KafkaTemplate<String, Object> kafkaTemplate,
            OutboxMetrics metrics,
            ObjectMapper objectMapper,
            @Value("${things-link.outbox.publisher.batch-size:8}") int batchSize,
            @Value("${things-link.outbox.publisher.lease-seconds:30}") long leaseSeconds) {
        this(outboxRepository, kafkaTemplate, metrics, objectMapper, batchSize, leaseSeconds,
                FaultInjectionCheckpoint.disabled());
    }

    /**
     * 创建带 C4b 屏障的 Spring 生产发布器；屏障默认禁用，不改变正常发布时序。
     *
     * @param outboxRepository Outbox 存储端口
     * @param kafkaTemplate Kafka 发布模板
     * @param metrics Outbox 指标门面
     * @param objectMapper JSON 映射器
     * @param batchSize 单轮批次大小
     * @param leaseSeconds Outbox 领取租约秒数
     * @param faultCheckpoint 默认禁用的 G1-C4b 一次性屏障
     */
    @Autowired
    public KafkaTransactionalOutboxPublisher(
            TransactionalOutboxRepository outboxRepository,
            KafkaTemplate<String, Object> kafkaTemplate,
            OutboxMetrics metrics,
            ObjectMapper objectMapper,
            @Value("${things-link.outbox.publisher.batch-size:8}") int batchSize,
            @Value("${things-link.outbox.publisher.lease-seconds:30}") long leaseSeconds,
            FaultInjectionCheckpoint faultCheckpoint) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.modbusNormalizedReader = objectMapper.readerFor(StandardUplinkMessage.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.faultCheckpoint = Objects.requireNonNull(faultCheckpoint, "faultCheckpoint");
        if (batchSize < 1 || batchSize > STRIPE_COUNT * (STRIPE_QUEUE_CAPACITY + 1)) {
            throw new IllegalArgumentException("Outbox 发布批次必须在 1 到 8 之间");
        }
        if (leaseSeconds <= SEND_TIMEOUT_SECONDS || leaseSeconds > 300) {
            throw new IllegalArgumentException("Outbox 发布租约必须大于 10 秒且不超过 300 秒");
        }
        this.batchSize = batchSize;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
        this.stripeExecutors = createStripeExecutors();
        this.metrics.registerStripes(this.stripeExecutors);
    }

    /**
     * 领取并逐条等待 Kafka broker 确认。
     *
     * <p>不能把整个批次放在一个数据库事务中：网络等待会持有行锁，且一个慢 broker 会阻塞后续事件。 领取函数已提交租约，逐条确认或释放各自使用独立短事务。
     */
    @Scheduled(fixedDelayString = "${things-link.outbox.publisher.fixed-delay-millis:1000}",
            scheduler = "outboxTriggerScheduler")
    public void publishReadyEvents() {
        OutboxClaim claim;
        try {
            metrics.recordOldestHeadAge(outboxRepository.oldestUnpublishedAge(Instant.now()));
            faultCheckpoint.reach(FaultInjectionCheckpoint.Checkpoint.OUTBOX_BEFORE_CLAIM,
                    "outbox-current-head");
            claim = outboxRepository.claimReady(batchSize, leaseDuration);
            if (!claim.events().isEmpty()) {
                faultCheckpoint.reach(FaultInjectionCheckpoint.Checkpoint.OUTBOX_AFTER_CLAIM_COMMIT,
                        claim.events().getFirst().id().toString());
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Outbox 领取待发布事件失败", exception);
            return;
        }
        for (OutboxEvent event : claim.events()) {
            int stripe = stripeIndex(event);
            ThreadPoolExecutor executor = stripeExecutors.get(stripe);
            try {
                // stripe 线程不继承调度切面的 ThreadLocal；在真实数据库工作入口重新声明数据面。
                executor.execute(() -> {
                    try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
                        publishOne(claim, event);
                    }
                });
            } catch (RejectedExecutionException exception) {
                // 容量快照与提交存在竞争时必须释放数据库租约；CallerRuns 会重新阻塞 trigger，静默丢弃则永久等待租约。
                metrics.recordStripeRejected(stripe);
                releaseForRetry(claim, event, "OutboxStripeRejected");
            }
        }
    }

    /** @return 基于 Kafka 真实顺序 lane 的稳定 stripe 下标 */
    private static int stripeIndex(OutboxEvent event) {
        return Math.floorMod(Objects.hash(event.destinationTopic(), event.partitionKey()), STRIPE_COUNT);
    }

    /** @return 四条固定单线程、有界队列、Abort 拒绝的发布 stripe */
    private static List<ThreadPoolExecutor> createStripeExecutors() {
        List<ThreadPoolExecutor> executors = new ArrayList<>(STRIPE_COUNT);
        AtomicInteger threadSequence = new AtomicInteger();
        for (int stripe = 0; stripe < STRIPE_COUNT; stripe++) {
            int stripeNumber = stripe;
            executors.add(new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(STRIPE_QUEUE_CAPACITY),
                    runnable -> {
                        Thread thread = new Thread(runnable,
                                "tc-outbox-stripe-" + stripeNumber + "-" + threadSequence.incrementAndGet());
                        // 可靠性来自数据库租约，不来自阻止 JVM 退出；daemon 可避免非 Spring 测试/工具构造实例后悬挂进程。
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()));
        }
        return List.copyOf(executors);
    }

    /**
     * 发布单个事件并推进或释放其租约。
     *
     * @param claim 本轮领取批次
     * @param event 待投递事件
     */
    private void publishOne(OutboxClaim claim, OutboxEvent event) {
        try {
            TraceContext.set(TraceContext.resolve(event.traceId()));
            RoutedMessage routed = route(event);
            if (!routed.topic().equals(event.destinationTopic())) {
                throw new IllegalArgumentException("Outbox 持久目标 Topic 与事件路由不一致");
            }
            long sendStarted = System.nanoTime();
            kafkaTemplate
                    .send(routed.topic(), event.partitionKey(), routed.payload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            metrics.recordPublishWait(routed.topic(), System.nanoTime() - sendStarted);
            faultCheckpoint.reach(FaultInjectionCheckpoint.Checkpoint.OUTBOX_AFTER_KAFKA_ACK,
                    event.id().toString());
            if (outboxRepository.markPublished(event.id(), claim.leaseToken())) {
                faultCheckpoint.reach(
                        FaultInjectionCheckpoint.Checkpoint.OUTBOX_AFTER_MARK_PUBLISHED_COMMIT,
                        event.id().toString());
                metrics.recordPublished(routed.topic());
                LOGGER.debug(
                        "Outbox 事件已发布 id={} topic={} aggregateType={} aggregateId={}",
                        event.id(),
                        routed.topic(),
                        event.aggregateType(),
                        event.aggregateId());
            } else {
                // 租约已被接管时不能再写状态；重复发布交由下游幂等处理，避免旧实例覆盖新实例结论。
                metrics.recordLeaseExpired(routed.topic());
                LOGGER.warn(
                        "Outbox 事件 Kafka 已确认但租约已失效，等待幂等重投 id={} topic={}",
                        event.id(),
                        routed.topic());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            releaseForRetry(claim, event, "Kafka 发布线程被中断");
        } catch (Exception exception) {
            releaseForRetry(claim, event, exception.getClass().getSimpleName());
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * 以固定短退避登记故障；异常消息可能含 broker 地址或载荷，因此只持久化异常类型。
     *
     * @param claim 本轮领取批次
     * @param event 投递失败事件
     * @param failureMessage 已脱敏失败类型
     */
    private void releaseForRetry(OutboxClaim claim, OutboxEvent event, String failureMessage) {
        Instant nextAvailableAt = Instant.now().plus(retryDelay());
        try {
            if (outboxRepository.markRetry(
                    event.id(), claim.leaseToken(), nextAvailableAt, failureMessage)) {
                metrics.recordRetry(topicForMetric(event));
                LOGGER.warn(
                        "Outbox 事件发布失败，已登记重试 id={} topic={} aggregateType={} failureType={}",
                        event.id(),
                        topicForMetric(event),
                        event.aggregateType(),
                        failureMessage);
            }
        } catch (RuntimeException exception) {
            // 更新失败时保留原租约，过期后仍会重试；不能向调度器外抛而中断同批其他事件。
            LOGGER.error(
                    "Outbox 事件登记重试失败，等待租约过期接管 id={} topic={}",
                    event.id(),
                    topicForMetric(event),
                    exception);
        }
    }

    /**
     * 返回当前冻结的一秒重试间隔。
     *
     * <p>Outbox 表会累积失败次数供后续运维查询；命令终态与有限重试由业务状态机决定，不能由 通用发布器擅自把事件长期延后或标记失败。这里仅避免 broker 故障时无间隔忙循环。
     *
     * @return 下一次允许领取前的等待时长
     */
    private static Duration retryDelay() {
        return MIN_RETRY_DELAY;
    }

    /**
     * 只允许路由目录中的冻结消息进入 Kafka，避免通用 Outbox 成为任意对象反序列化入口。
     *
     * @param event 已领取 Outbox 事件
     * @return 经过白名单反序列化的下行命令信封
     */
    private RoutedMessage route(OutboxEvent event) {
        return switch (event.eventType()) {
            case com.things.link.shared.message.PublicWebhookSource.EVENT_TYPE -> routePublicWebhook(event);
            case AutomationPropertyAccepted.EVENT_TYPE -> routeAutomationProperty(event);
            case DEVICE_COMMAND_DISPATCH_EVENT ->
                    new RoutedMessage(
                            DEVICE_DOWNLINK_TOPIC,
                            objectMapper.readValue(event.payload(), DeviceCommandDispatch.class));
            case DEVICE_COMMAND_TERMINAL_EVENT ->
                    new RoutedMessage(DEVICE_COMMAND_TERMINAL_TOPIC,
                            objectMapper.readValue(event.payload(), DeviceCommandTerminalEvent.class));
            case DEVICE_TOPOLOGY_REPLY_EVENT ->
                    new RoutedMessage(DEVICE_TOPOLOGY_REPLY_TOPIC,
                            objectMapper.readValue(event.payload(), TopologyReplyMessage.class));
            case DEVICE_CONFIG_PUSH_EVENT ->
                    new RoutedMessage(DEVICE_CONFIG_TOPIC,
                            objectMapper.readValue(event.payload(), DeviceConfigPush.class));
            case DEVICE_MODBUS_REQUEST_EVENT ->
                    new RoutedMessage(DEVICE_MODBUS_REQUEST_TOPIC,
                            objectMapper.readValue(event.payload(), ModbusRequest.class));
            case OutboxRouteCatalog.MODBUS_NORMALIZED_EVENT_TYPE -> routeModbusNormalized(event);
            case NOTIFICATION_DELIVERY_REQUEST_EVENT ->
                    new RoutedMessage(
                            NOTIFICATION_TOPIC,
                            objectMapper.readValue(
                                    event.payload(), NotificationDeliveryRequest.class));
            case RULE_NOTIFICATION_DELIVERY_REQUEST_EVENT ->
                    new RoutedMessage(
                            RULE_NOTIFICATION_TOPIC,
                            objectMapper.readValue(
                                    event.payload(), RuleNotificationDeliveryRequest.class));
            default -> throw new IllegalArgumentException("不支持的 Outbox 事件类型: " + event.eventType());
        };
    }

    /** 发送前同时校验已持久化的路由元数据与规范身份。 */
    private RoutedMessage routePublicWebhook(OutboxEvent event) {
        var message=objectMapper.readValue(event.payload(),com.things.link.shared.message.PublicWebhookSource.class);
        new com.things.link.support.webhook.PublicWebhookCodec(objectMapper).validate(message);
        if(!event.tenantId().equals(message.event().tenantId())||!event.projectId().equals(message.event().projectId())
            ||!com.things.link.shared.message.PublicWebhookSource.AGGREGATE_TYPE.equals(event.aggregateType())
            ||!event.aggregateId().equals(message.aggregateId())||!event.partitionKey().equals(message.aggregateId().toString())
            ||!event.traceId().equals(message.transportTrace()))throw new IllegalArgumentException("Webhook source route mismatch");
        return new RoutedMessage(com.things.link.shared.message.PublicWebhookSource.TOPIC,message);
    }

    /** 第三方不能借持久事件元数据与载荷不一致改变归属或分区。 */
    private RoutedMessage routeAutomationProperty(OutboxEvent event) {
        AutomationPropertyAccepted message = objectMapper.readerFor(AutomationPropertyAccepted.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(event.payload());
        if (!event.tenantId().equals(message.tenantId()) || !event.projectId().equals(message.projectId())
                || !AutomationPropertyAccepted.AGGREGATE_TYPE.equals(event.aggregateType())
                || !event.aggregateId().equals(message.deviceId())
                || !event.partitionKey().equals(message.deviceId().toString())
                || !event.traceId().equals(message.traceId())) {
            throw new IllegalArgumentException("自动化属性Outbox归属或路由不一致");
        }
        return new RoutedMessage(AutomationPropertyAccepted.TOPIC, message);
    }

    /** ADR0063：发送前核验持久元数据与完整信封，避免错误key或归属破坏下游隔离和幂等。 */
    private RoutedMessage routeModbusNormalized(OutboxEvent event) {
        // D-146b仅防止持久原文恢复时再次舍入；不恢复Modbus寄存器解码阶段已损失的精度。
        StandardUplinkMessage message = modbusNormalizedReader.readValue(event.payload());
        if (!event.tenantId().equals(message.tenantId()) || !event.projectId().equals(message.projectId())
                || !OutboxRouteCatalog.MODBUS_RESULT_AGGREGATE_TYPE.equals(event.aggregateType())
                || !event.aggregateId().equals(message.deviceId())
                || !event.partitionKey().equals(message.deviceId().toString())
                || message.gatewayId() == null || message.modelVersion() == null) {
            // 保留原失败重试策略；不标记已发布或静默跳过持久lane head，也不打印原始载荷。
            throw new IllegalArgumentException("Modbus normalized Outbox归属、路由或版本不完整");
        }
        return new RoutedMessage(OutboxRouteCatalog.MODBUS_NORMALIZED_TOPIC, message);
    }

    /** 失败指标也必须保持固定主题集合，未知类型不把业务输入变成 Prometheus 标签。 */
    private static String topicForMetric(OutboxEvent event) {
        return event.destinationTopic();
    }

    /** 停止 trigger 后等待在途 broker 确认；超时项不强行确认，数据库租约到期后安全接管。 */
    @Override
    public void destroy() throws InterruptedException {
        for (ThreadPoolExecutor executor : stripeExecutors) {
            executor.shutdown();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_AWAIT_SECONDS);
        for (ThreadPoolExecutor executor : stripeExecutors) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
            }
        }
    }

    /** 已由固定白名单完成反序列化的 Kafka 主题及其消息对象。 */
    private record RoutedMessage(String topic, Object payload) {}
}
