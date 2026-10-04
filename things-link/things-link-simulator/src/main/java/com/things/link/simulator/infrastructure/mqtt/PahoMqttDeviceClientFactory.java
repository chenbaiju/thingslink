package com.things.link.simulator.infrastructure.mqtt;

import com.things.link.simulator.application.MqttDeviceClient;
import com.things.link.simulator.application.MqttDeviceClientFactory;
import com.things.link.simulator.application.MqttDeviceMessageHandler;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 基于 Eclipse Paho 的 MQTT 3.1.1 设备连接工厂。
 *
 * <p>架构文档 13.4 要求模拟器不仅制造稳定吞吐，还必须能复现断线恢复。这里关闭 Paho 无抖动的自动重连，改用共享调度器执行
 * 指数退避和等抖动，避免 Broker 恢复时上千设备在同一秒形成重连风暴。内存持久化则避免压测进程为每台设备创建本地状态文件。
 */
@Component
public class PahoMqttDeviceClientFactory implements MqttDeviceClientFactory {

    /** MQTT 建连最长等待秒数，避免 Broker 不可达时批量启动永久挂住。 */
    private static final int CONNECTION_TIMEOUT_SECONDS = 5;

    /** 心跳周期兼顾断线发现速度和千连接场景的心跳开销。 */
    private static final int KEEP_ALIVE_SECONDS = 30;

    /** 所有模拟设备共用少量调度线程；重连任务只做一次网络调用，不为每设备常驻线程。 */
    private final ScheduledExecutorService reconnectScheduler = new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread thread = new Thread(runnable, "tc-simulator-mqtt-reconnect");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 入站业务处理不能占用 Paho 的 {@code messageArrived} 回调线程：命令处理会同步发布 QoS 1 回执，
     * 若在回调内等待 PUBACK，客户端无法及时完成当前入站消息的确认与后续网络事件，真实 Broker 链路会停在
     * {@code PUBLISHED}。共享虚拟线程执行器解除重入等待；每个客户端再用 {@link SerialExecutor} 保留 MQTT
     * 到达顺序，不能为了异步化破坏架构文档 5.1 的同连接设备命令顺序。
     */
    private final ExecutorService messageHandlerExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("tc-simulator-mqtt-message-", 0).factory());

    /** 生产使用线程本地随机数；测试可注入确定序列以稳定证明重连时间确实分散。 */
    private final LongSupplier reconnectJitter;

    /** Spring 生产装配入口。 */
    public PahoMqttDeviceClientFactory() {
        this(() -> ThreadLocalRandom.current().nextLong());
    }

    /**
     * 故障验收构造器；只替换抖动样本，网络连接、调度与 Paho 行为仍走生产实现。
     *
     * @param reconnectJitter 每次退避使用的原始抖动样本
     */
    PahoMqttDeviceClientFactory(LongSupplier reconnectJitter) {
        this.reconnectJitter = reconnectJitter;
    }

    /** {@inheritDoc} */
    @Override
    public MqttDeviceClient connect(String brokerUri, String projectKey, String deviceKey, String accessToken) {
        try {
            // 随机后缀允许同一设备在不同模拟器进程中演练连接抢占，而不会复用本地 Paho 状态。
            String clientId = "tc-sim-" + deviceKey + "-" + UUID.randomUUID().toString().substring(0, 8);
            MqttAsyncClient client = new MqttAsyncClient(brokerUri, clientId, new MemoryPersistence());
            MqttConnectOptions options = connectOptions(projectKey, deviceKey, accessToken);
            PahoDeviceClient adapter = new PahoDeviceClient(
                    client, options, reconnectScheduler, messageHandlerExecutor, reconnectJitter);
            client.setCallback(adapter);
            // 异步建连仍需等首次连接完成，保持「批量启动要么全连要么回滚」的原子语义不变。
            client.connect(options).waitForCompletion(CONNECTION_TIMEOUT_SECONDS * 1000L);
            return adapter;
        } catch (MqttException exception) {
            throw new IllegalStateException("MQTT 设备连接失败: " + deviceKey, exception);
        }
    }

    /**
     * 创建每个连接私有的认证选项；关闭库内自动重连，确保退避与抖动完全由本工厂控制。
     */
    private static MqttConnectOptions connectOptions(
            String projectKey, String deviceKey, String accessToken) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setUserName(projectKey + "/" + deviceKey);
        options.setPassword(accessToken.toCharArray());
        options.setAutomaticReconnect(false);
        options.setCleanSession(true);
        options.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS);
        options.setKeepAliveInterval(KEEP_ALIVE_SECONDS);
        return options;
    }

    /** 应用停止时终止尚未执行的重连任务，避免测试或进程关闭后设备连接被重新拉起。 */
    @PreDestroy
    public void shutdown() {
        reconnectScheduler.shutdownNow();
        messageHandlerExecutor.shutdownNow();
    }

    /** Paho 连接适配到模拟器应用端口，并负责断线恢复与订阅重建。 */
    static final class PahoDeviceClient implements MqttDeviceClient, MqttCallbackExtended {

        /** Paho 异步网络客户端。 */
        private final MqttAsyncClient client;
        /** 首次连接和重连共用的认证配置。 */
        private final MqttConnectOptions options;
        /** 工厂级共享重连调度器。 */
        private final ScheduledExecutorService scheduler;
        /** 与 Paho 网络回调隔离的业务处理执行器，允许处理器安全发布 QoS 回执。 */
        private final Executor messageHandlerExecutor;
        /** 每次退避的抖动样本；生产随机、故障验收确定。 */
        private final LongSupplier reconnectJitter;
        /** cleanSession=true 时连接恢复后必须重建的订阅。 */
        private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
        /** 连续失败次数决定指数退避阶梯。 */
        private final AtomicInteger reconnectAttempt = new AtomicInteger();
        /** 首次连接为 1，之后每次真实恢复单调递增。 */
        private final AtomicLong connectionGeneration = new AtomicLong();
        /** 主动关闭后禁止任何在途任务复活连接。 */
        private final AtomicBoolean closed = new AtomicBoolean();
        /** 同一连接最多保留一个待执行重连任务。 */
        private volatile ScheduledFuture<?> reconnectFuture;
        /** 最近一次真实连接完成时间，用于量化风暴是否被抖动摊开。 */
        private volatile Instant lastConnectedAt = Instant.EPOCH;

        /** QoS 1 发布交付收据；登记先于 publish，PUBACK/断线/关闭都不会错过，避免「先回调后登记」悬挂。 */
        private final Set<CompletableFuture<Void>> pendingDeliveries = ConcurrentHashMap.newKeySet();

        /**
         * @param client Paho 客户端
         * @param options 固定认证选项
         * @param scheduler 共享调度器
         * @param messageHandlerExecutor 入站业务处理执行器
         * @param reconnectJitter 重连退避抖动源
         */
        PahoDeviceClient(
                MqttAsyncClient client,
                MqttConnectOptions options,
                ScheduledExecutorService scheduler,
                Executor messageHandlerExecutor,
                LongSupplier reconnectJitter) {
            this.client = client;
            this.options = options;
            this.scheduler = scheduler;
            this.messageHandlerExecutor = new SerialExecutor(messageHandlerExecutor);
            this.reconnectJitter = reconnectJitter;
        }

        /** {@inheritDoc} */
        @Override
        public void subscribe(String topic, int qos, MqttDeviceMessageHandler handler) {
            Subscription subscription = new Subscription(qos, handler);
            subscriptions.put(topic, subscription);
            subscribeNow(topic, subscription);
        }

        /** {@inheritDoc} */
        @Override
        public CompletableFuture<Void> publish(String topic, byte[] payload, int qos, boolean retained) {
            // 已关闭即失败完成：关闭后任何迟到 publish 都不能再往在途集合登记，否则 close 的结算循环可能已退出，
            // 这条交付将永远等不到 PUBACK 也不会被结算。
            CompletableFuture<Void> delivered = new CompletableFuture<>();
            if (closed.get()) {
                delivered.completeExceptionally(new IllegalStateException("MQTT 连接已关闭"));
                return delivered;
            }
            // 先在集合登记、再发起 publish：Paho 网络回调可能在 publish 返回前就触发 onSuccess/onFailure，
            // 用 userContext+IMqttActionListener 直接关联本 Future，不依赖外部的 token→Future 映射，
            // 从根本上消除「先回调、后登记」导致 Future 永久悬挂的竞态。
            pendingDeliveries.add(delivered);
            // close 可能发生在第一次 closed 检查与集合登记之间；登记后必须二次检查，封住该窗口。
            if (closed.get()) {
                delivered.completeExceptionally(new IllegalStateException("MQTT 连接已关闭"));
                pendingDeliveries.remove(delivered);
                return delivered;
            }
            try {
                MqttMessage message = new MqttMessage(payload);
                message.setQos(qos);
                message.setRetained(retained);
                client.publish(topic, message, delivered, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        // 先 complete 再 remove：whenComplete 在 complete 内同步执行完（计数/落盘），
                        // 因此「不在集合中」等价于「回调已收敛」，close 的 failPendingDeliveries 才不会漏掉
                        // 一个「已移除但尚未执行成功回调」的交付——那是写盘发生在 manifest 关闭之后的竞态根源。
                        delivered.complete(null);
                        pendingDeliveries.remove(delivered);
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        Throwable failure = exception == null
                                ? new IllegalStateException("MQTT 发布失败但 Paho 未提供异常")
                                : exception;
                        delivered.completeExceptionally(failure);
                        pendingDeliveries.remove(delivered);
                    }
                });
                return delivered;
            } catch (MqttException exception) {
                // 同步发起失败（非法参数/客户端已关闭等）：回收登记并以失败 Future 返回，而非抛异常，
                // 使调用方的「发起」计数仍成立，失败由 whenComplete 统一记账，避免「失败 > 发起」的计数裂口。
                delivered.completeExceptionally(exception);
                pendingDeliveries.remove(delivered);
                return delivered;
            }
        }

        /**
         * 连接恢复即清零退避阶梯并重建订阅；cleanSession=true 不保留 Broker 侧订阅。
         */
        @Override
        public void connectComplete(boolean reconnect, String serverUri) {
            reconnectAttempt.set(0);
            long generation = connectionGeneration.incrementAndGet();
            lastConnectedAt = Instant.now();
            // 本实现用显式 connect 执行退避恢复，Paho 的 reconnect 标志可能仍为 false；连接代次才是可靠判断。
            if (generation > 1) {
                subscriptions.forEach(this::subscribeNow);
            }
        }

        /** 网络断开只登记一次延迟任务，不在 Paho 回调线程里同步重试；断线前的在途发布一并标记为未确认。 */
        @Override
        public void connectionLost(Throwable cause) {
            failPendingDeliveries();
            scheduleReconnect();
        }

        /** 消息由每个 Topic 的订阅回调处理，本方法不会被 Paho 直接用于业务分发。 */
        @Override
        public void messageArrived(String topic, MqttMessage message) {
            // subscribeNow 已安装逐 Topic 监听器，保留空实现只是满足 Paho 回调契约。
        }

        /**
         * QoS 1 发布确认回调。
         *
         * <p>成功路径已由 {@code publish(..., userContext, IMqttActionListener)} 的 onSuccess/onFailure 处理，
         * 本回调不再参与收据完成，只保留以满足 {@link MqttCallback} 契约。A4-0 的成功集合口径不变：
         * 只有 PUBACK（onSuccess）才算发布成功。</p>
         */
        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // 成功路径由 IMqttActionListener.onSuccess 完成；此处不再需要 token→Future 映射。
        }

        /**
         * 断线时把全部未确认发布以异常完成，使「发起数 = 确认数 + 未确认损失」可对账，而不是让 future 永久悬挂。
         *
         * <p>{@link ConcurrentHashMap} 的 keySet 迭代是弱一致的，可能漏掉并发加入/移除的元素；循环直到集合为空，
         * 确保每一条在途交付都被结算。与 onSuccess/onFailure 的「先 complete 后 remove」配合，
         * 集合为空时所有交付回调（成功记账或失败记账）都已收敛。</p>
         */
        private void failPendingDeliveries() {
            while (!pendingDeliveries.isEmpty()) {
                for (CompletableFuture<Void> delivered : pendingDeliveries) {
                    delivered.completeExceptionally(new IllegalStateException("连接在 PUBACK 前断开，发布未确认"));
                    pendingDeliveries.remove(delivered);
                }
            }
        }

        /** {@inheritDoc} */
        @Override
        public void forceConnectionLoss() {
            if (closed.get()) {
                throw new IllegalStateException("MQTT 连接已关闭");
            }
            try {
                if (client.isConnected()) {
                    // sendDisconnectPacket=false 让 Broker 观察到真实 TCP 异常断开，而不是正常下线。
                    client.disconnectForcibly(0, 0, false);
                }
                // 强制掉线让所有在途 QoS1 发布失去收到 PUBACK 的可能；主动结算它们，
                // 不能依赖 connectionLost 回调兜底——disconnectForcibly 未必触发该回调，否则 future 永久悬挂。
                failPendingDeliveries();
                scheduleReconnect();
            } catch (MqttException exception) {
                throw new IllegalStateException("MQTT 异常断连注入失败", exception);
            }
        }

        /** {@inheritDoc} */
        @Override
        public boolean isConnected() {
            return client.isConnected();
        }

        /** {@inheritDoc} */
        @Override
        public long connectionGeneration() {
            return connectionGeneration.get();
        }

        /** {@inheritDoc} */
        @Override
        public Instant lastConnectedAt() {
            return lastConnectedAt;
        }

        /** 计算带等抖动的指数退避，阶梯上限为 60 秒。 */
        static Duration reconnectDelay(int attempt, long randomValue) {
            int safeAttempt = Math.max(1, Math.min(attempt, 7));
            long ceilingMillis = Math.min(60_000L, 1_000L << (safeAttempt - 1));
            long floorMillis = ceilingMillis / 2L;
            long jitterRange = Math.max(1L, ceilingMillis - floorMillis + 1L);
            return Duration.ofMillis(floorMillis + Math.floorMod(randomValue, jitterRange));
        }

        /** 安排一次重连；前一任务尚未执行时不叠加第二个任务。 */
        private synchronized void scheduleReconnect() {
            if (closed.get() || (reconnectFuture != null && !reconnectFuture.isDone())) {
                return;
            }
            int attempt = reconnectAttempt.incrementAndGet();
            Duration delay = reconnectDelay(attempt, reconnectJitter.getAsLong());
            reconnectFuture = scheduler.schedule(this::reconnect, delay.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** 单次重连失败后进入下一退避阶梯，绝不在同一调度任务中忙循环。 */
        private void reconnect() {
            if (closed.get()) {
                return;
            }
            try {
                client.connect(options).waitForCompletion(CONNECTION_TIMEOUT_SECONDS * 1000L);
            } catch (MqttException exception) {
                synchronized (this) {
                    reconnectFuture = null;
                }
                scheduleReconnect();
            }
        }

        /** 使用 Paho 的逐订阅监听器保留原 Topic，恢复后仍走相同业务处理器。 */
        private void subscribeNow(String topic, Subscription subscription) {
            try {
                IMqttToken token = client.subscribe(topic, subscription.qos(), (receivedTopic, message) -> {
                    try {
                        messageHandlerExecutor.execute(() -> {
                            if (!closed.get()) {
                                subscription.handler().onMessage(receivedTopic, message.getPayload());
                            }
                        });
                    } catch (RejectedExecutionException exception) {
                        // 正常关闭会先停止执行器并关闭连接，二者存在极短竞态；关闭后的消息无需处理。
                        if (!closed.get()) {
                            throw exception;
                        }
                    }
                });
                // 等 SUBACK：订阅失败必须回滚已建连接，保持「订阅是认证生命周期一部分」的原子语义。
                token.waitForCompletion(CONNECTION_TIMEOUT_SECONDS * 1000L);
            } catch (MqttException exception) {
                throw new IllegalStateException("MQTT 订阅失败", exception);
            }
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> pending = reconnectFuture;
            if (pending != null) {
                pending.cancel(false);
            }
            // 主动关闭时兜底完成所有未决交付，避免调用方等待一个永远不完成的 future。
            // failPendingDeliveries 循环到集合为空即「回调收敛」：配合「先 complete 后 remove」，
            // 返回时所有成功/失败记账都已执行完，之后 DeviceSimulator 才能安全关闭 manifest。
            failPendingDeliveries();
            try {
                if (client.isConnected()) {
                    try {
                        client.disconnect(2_000).waitForCompletion(2_000);
                    } catch (MqttException gracefulFailure) {
                        // G1 nightly #14 在 1,000 台并发收尾时证明少量正常 DISCONNECT 可能超过两秒；
                        // 这不能让 close 在本地资源释放前直接退出。强制关闭只作为正常停机的有界兜底，
                        // 发布已由 failPendingDeliveries 结算，故不会把运行期丢消息静默改成成功。
                        client.disconnectForcibly(0L, 1_000L, true);
                    }
                }
                client.close();
            } catch (MqttException exception) {
                throw new IllegalStateException("MQTT 连接关闭失败", exception);
            }
        }

        /** @param qos MQTT 服务质量等级 @param handler 业务消息处理器 */
        private record Subscription(int qos, MqttDeviceMessageHandler handler) {
        }
    }

    /**
     * 在共享执行器之上为单个设备连接提供 FIFO 串行语义。
     *
     * <p>任务不在 Paho 回调线程执行，因此处理器仍可同步等待 QoS 1 PUBACK；同一适配器最多只有一个活动任务，
     * 又不会让后到命令越过先到命令。不同设备各有独立实例，仍能并发使用共享虚拟线程。</p>
     */
    static final class SerialExecutor implements Executor {

        /** 真正启动任务的共享虚拟线程执行器。 */
        private final Executor delegate;

        /** 尚未启动的设备消息，按 MQTT 回调到达顺序排列。 */
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        /** 当前已交给底层执行器的任务；非空时后续消息只能排队。 */
        private Runnable active;

        /**
         * @param delegate 真正启动任务的共享执行器
         */
        SerialExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        /**
         * 将任务加入单设备 FIFO；前一任务结束后才调度下一任务。
         *
         * @param command 设备消息处理任务
         */
        @Override
        public synchronized void execute(Runnable command) {
            tasks.addLast(() -> {
                try {
                    command.run();
                } finally {
                    scheduleNext();
                }
            });
            if (active == null) {
                scheduleNext();
            }
        }

        /** 启动队首任务；底层关闭拒绝任务时清空队列，避免留下永远无法执行的活动标记。 */
        private synchronized void scheduleNext() {
            active = tasks.pollFirst();
            if (active == null) {
                return;
            }
            try {
                delegate.execute(active);
            } catch (RuntimeException exception) {
                active = null;
                tasks.clear();
                throw exception;
            }
        }
    }
}
