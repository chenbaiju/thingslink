package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.BrokerHandoffDispatcher;
import com.things.link.ingestion.application.HandoffDisposition;
import com.things.link.support.mqtt.BrokerIngressProperties;
import com.things.link.support.mqtt.BrokerIngressReadiness;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 固定单活 MQTT 3.1.1 ingress，在下游持久事实形成后才 manual ACK。
 *
 * <p>连接必须等到 {@link ApplicationReadyEvent}：消息代理 HTTP 认证器 回调本应用，若在 Web 服务就绪前同步 CONNECT
 * 会形成自依赖死锁。回调线程同步处理一条消息，禁止把 ACK 所有权转交给无持久 executor。</p>
 */
@Component
public class DurableUplinkMqttIngress implements MqttCallbackExtended {

    /** 只记录固定状态，不把 URI、用户名、Topic payload 或异常凭据写入日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DurableUplinkMqttIngress.class);
    /** 首次重连等待，避免应用/数据库恢复期形成认证风暴。 */
    private static final long INITIAL_RECONNECT_SECONDS = 1L;
    /** 重连退避上限，保持恢复有界同时避免忙循环。 */
    private static final long MAX_RECONNECT_SECONDS = 30L;
    /** ADR 0047 的首次原地重试间隔；不能在依赖故障时形成忙循环。 */
    private static final long INITIAL_LOCAL_RETRY_SECONDS = 1L;
    /** ADR 0047 只限制单次等待，不虚构依赖恢复总时限。 */
    private static final long MAX_LOCAL_RETRY_SECONDS = 30L;
    /** CONNECT/SUBSCRIBE 控制包完成上限。 */
    private static final long CONTROL_TIMEOUT_MILLIS = 10_000L;
    /** ADR 0046 冻结的单活 inflight 上限。 */
    private static final int MAX_INFLIGHT = 32;
    /** ingress 配置与唯一服务身份。 */
    private final BrokerIngressProperties properties;
    /** 同步 durable handoff 分派器。 */
    private final BrokerHandoffDispatcher dispatcher;
    /** 低基数连接、ACK 与重试指标。 */
    private final BrokerHandoffMetrics metrics;
    /** 与设备认证共享的订阅就绪闸门。 */
    private final BrokerIngressReadiness readiness;
    /** 资格环境在 manual ACK 前建立可强杀屏障；生产关闭时为空操作。 */
    private final BrokerHandoffQualificationRecorder qualificationRecorder;
    /** 唯一重连调度器；daemon 防止测试 JVM 因关闭遗漏悬挂。 */
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "broker-handoff-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    /** 防止 connectionLost 与连接失败重复排队。 */
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    /** 连续连接失败次数，用于指数退避。 */
    private final AtomicInteger failures = new AtomicInteger();
    /** 应用关闭标志；关闭后不得重新建连。 */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 关闭时唤醒正在退避的 callback，避免最长 30 秒等待拖住 Spring 停机。 */
    private final CountDownLatch shutdownSignal = new CountDownLatch(1);
    /** Paho 客户端在 ready 后创建，disabled 环境不占线程或网络。 */
    private volatile MqttAsyncClient client;

    /**
     * @param properties ingress 服务身份与 Broker 参数
     * @param dispatcher 同步持久交接分派器
     * @param metrics handoff 指标
     * @param readiness 设备认证共享的订阅就绪闸门
     * @param qualificationRecorder 默认关闭的资格证据记录器
     */
    @Autowired
    public DurableUplinkMqttIngress(BrokerIngressProperties properties,
                                    BrokerHandoffDispatcher dispatcher,
                                    BrokerHandoffMetrics metrics,
                                    BrokerIngressReadiness readiness,
                                    BrokerHandoffQualificationRecorder qualificationRecorder) {
        this.properties = properties;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
        this.readiness = readiness;
        this.qualificationRecorder = qualificationRecorder;
    }

    /**
     * 隔离测试入口，用受控 Paho 替身验证 ACK 先后关系，不建立真实网络连接。
     *
     * @param properties ingress 属性
     * @param dispatcher 分派器
     * @param metrics 指标
     * @param readiness 订阅就绪闸门
     * @param qualificationRecorder 资格证据记录器
     * @param client Paho 客户端替身
     */
    DurableUplinkMqttIngress(BrokerIngressProperties properties,
                             BrokerHandoffDispatcher dispatcher,
                             BrokerHandoffMetrics metrics,
                             BrokerIngressReadiness readiness,
                             BrokerHandoffQualificationRecorder qualificationRecorder,
                             MqttAsyncClient client) {
        this(properties, dispatcher, metrics, readiness, qualificationRecorder);
        this.client = client;
    }

    /** Web Server 已可接受 Broker auth/ACL 回调后，异步建立固定 durable session。 */
    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() {
        if (!properties.enabled()) return;
        properties.requireValidWhenEnabled();
        scheduleConnect(0L);
    }

    /**
     * Paho 在首次连接和恢复连接后调用；订阅是幂等的，始终重申 QoS 1 以封闭会话状态漂移。
     *
     * @param reconnect 是否由 Paho 内部重连；本实现关闭自动重连，因此正常为 false
     * @param serverURI Broker URI，仅由 Paho 提供，不写日志
     */
    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        // 实际订阅在 connect token 读取 sessionPresent 后同步完成；此回调只满足 Paho 生命周期接口。
    }

    /** 连接丢失意味着当前未 ACK 消息继续留在 durable session；有界退避后用同一 clientId 恢复。 */
    @Override
    public void connectionLost(Throwable cause) {
        readiness.markUnavailable();
        metrics.disconnected();
        metrics.deliveryFinished();
        if (!closed.get()) scheduleConnect(reconnectDelaySeconds());
    }

    /**
     * 同步完成下游接管再调用 Paho 手动确认；暂时故障在当前 callback 原地重试，不主动制造重连。
     *
     * @param topic 必须是固定内部 Topic
     * @param message 消息代理持久会话 投递
     * @throws Exception 暂时故障或 ACK 失败，禁止吞掉
     */
    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        if (!BrokerIngressProperties.INTERNAL_TOPIC.equals(topic) || message.getQos() != 1 || message.isRetained()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION);
        }
        metrics.deliveryStarted();
        try {
            HandoffDisposition disposition = dispatchUntilDurable(message.getPayload());
            qualificationRecorder.beforeManualAck(disposition);
            MqttAsyncClient current = client;
            if (current == null || !current.isConnected()) {
                throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
            }
            current.messageArrivedComplete(message.getId(), message.getQos());
            metrics.record(disposition);
        } catch (Exception exception) {
            // 分派暂时失败由 dispatchUntilDurable 逐 attempt 计数；这里只补记 ACK/连接阶段失败。
            if (!(exception instanceof DispatchRetryTerminatedException)) metrics.transientRetry();
            throw exception;
        } finally {
            metrics.deliveryFinished();
        }
    }

    /**
     * 保持当前 MQTT callback 与未 ACK 消息所有权，直到下游持久事实形成或应用关闭。
     *
     * <p>ADR 0047 取代“异常冒泡后依赖 EMQX 5.8.4 durable cursor 重放”的分支；重试间隔有界，
     * 总等待时间由真实依赖恢复决定。这里没有建立进程内队列，进程停止后消息仍由 Broker 会话持有。</p>
     *
     * @param payload Broker v1 信封字节
     * @return 可安全 manual ACK 的持久结果
     */
    private HandoffDisposition dispatchUntilDurable(byte[] payload) {
        int failedAttempts = 0;
        while (!closed.get()) {
            try {
                return dispatcher.dispatch(payload);
            } catch (RuntimeException exception) {
                failedAttempts++;
                metrics.transientRetry();
                long delaySeconds = localRetryDelaySeconds(failedAttempts);
                try {
                    if (shutdownSignal.await(delaySeconds, TimeUnit.SECONDS)) {
                        throw new DispatchRetryTerminatedException(exception);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DispatchRetryTerminatedException(interrupted);
                }
            }
        }
        throw new DispatchRetryTerminatedException(new IllegalStateException("应用已关闭"));
    }

    /**
     * 计算 1、2、4、8、16、30 秒退避；attempt 很大时先钳制指数，避免左移溢出。
     *
     * @param failedAttempts 已失败次数，从 1 开始
     * @return 本次等待秒数
     */
    private static long localRetryDelaySeconds(int failedAttempts) {
        int exponent = Math.min(Math.max(failedAttempts - 1, 0), 5);
        return Math.min(MAX_LOCAL_RETRY_SECONDS, INITIAL_LOCAL_RETRY_SECONDS << exponent);
    }

    /** 本 ingress 只订阅，不发布；Paho 要求实现的交付回调保持空操作。 */
    @Override
    public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
    }

    /** 创建客户端、读取 CONNACK sessionPresent 并同步订阅；任一步失败都进入有界退避。 */
    private void connectOnce() {
        reconnectScheduled.set(false);
        if (closed.get() || !properties.enabled()) return;
        try {
            MqttAsyncClient current = client;
            if (current == null) {
                current = new MqttAsyncClient(properties.brokerUri(), properties.clientId(),
                        new MemoryPersistence());
                current.setManualAcks(true);
                current.setCallback(this);
                client = current;
            }
            if (current.isConnected()) return;
            IMqttToken connectToken = current.connect(connectOptions());
            connectToken.waitForCompletion(CONTROL_TIMEOUT_MILLIS);
            current.subscribe(BrokerIngressProperties.INTERNAL_TOPIC, 1).waitForCompletion(CONTROL_TIMEOUT_MILLIS);
            // 只有 CONNACK 与 SUBACK 都成功，设备认证才可开始放行。
            readiness.markReady();
            failures.set(0);
            metrics.connected(connectToken.getSessionPresent());
            LOGGER.info("Broker durable handoff ingress 已连接 sessionPresent={}", connectToken.getSessionPresent());
        } catch (Exception exception) {
            readiness.markUnavailable();
            metrics.disconnected();
            LOGGER.warn("Broker durable handoff ingress 连接失败，将有界退避重试 cause={}",
                    exception.getClass().getSimpleName());
            scheduleConnect(reconnectDelaySeconds());
        }
    }

    /** 构造冻结的 MQTT 3.1.1 非 clean 会话参数；自动重连关闭以便每次读取 sessionPresent 证据。 */
    private MqttConnectOptions connectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCleanSession(false);
        options.setAutomaticReconnect(false);
        options.setMaxInflight(MAX_INFLIGHT);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        options.setUserName(properties.username());
        options.setPassword(properties.password().toCharArray());
        return options;
    }

    /** 只允许一个待执行连接任务，避免断线回调与失败 catch 叠加。 */
    private void scheduleConnect(long delaySeconds) {
        if (closed.get() || !reconnectScheduled.compareAndSet(false, true)) return;
        metrics.reconnectScheduled();
        reconnectExecutor.schedule(this::connectOnce, delaySeconds, TimeUnit.SECONDS);
    }

    /** 指数退避按连续失败次数增长并钳制在 30 秒。 */
    private long reconnectDelaySeconds() {
        int exponent = Math.min(failures.getAndIncrement(), 5);
        return Math.min(MAX_RECONNECT_SECONDS, INITIAL_RECONNECT_SECONDS << exponent);
    }

    /** 应用关闭时发送正常 DISCONNECT；未 ACK 消息因 cleanSession=false 仍保留到 48 小时会话窗口。 */
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        shutdownSignal.countDown();
        readiness.markUnavailable();
        reconnectExecutor.shutdownNow();
        MqttAsyncClient current = client;
        if (current == null) return;
        try {
            if (current.isConnected()) current.disconnect().waitForCompletion(CONTROL_TIMEOUT_MILLIS);
            current.close();
        } catch (MqttException exception) {
            LOGGER.debug("关闭 Broker durable handoff ingress 时连接已不可用");
        } finally {
            metrics.disconnected();
        }
    }

    /**
     * 标识“分派重试因应用关闭或线程中断而终止”，避免 messageArrived 把最后一次失败重复计入指标。
     */
    private static final class DispatchRetryTerminatedException extends RuntimeException {

        /**
         * @param cause 最后一项暂时故障或关闭等待中断
         */
        private DispatchRetryTerminatedException(Throwable cause) {
            super("Broker handoff 原地重试已终止", cause);
        }
    }
}
