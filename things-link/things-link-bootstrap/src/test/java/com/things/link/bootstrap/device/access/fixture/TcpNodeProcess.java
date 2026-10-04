package com.things.link.bootstrap.device.access.fixture;

import com.things.link.ThingsLinkApplication;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import tools.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 独立JVM测试入口：只控制监听生命周期，不替代生产路由、网络、状态机或SQL。 */
public final class TcpNodeProcess {
    /** 禁止实例化工具入口。 */ private TcpNodeProcess() { }
    /** 输出状态文件而非从日志猜测就绪；stdin EOF触发正常有界关闭。 */
    public static void main(String[] args) throws Exception {
        System.setProperty("socksProxyHost", "");
        System.setProperty("http.proxyHost", "");
        Path control = Path.of(System.getProperty("tcp.fixture.directory"));
        ObjectMapper json = new ObjectMapper();
        try (var context = new SpringApplicationBuilder(ThingsLinkApplication.class, Faults.class).profiles("test").run(args)) {
            var listeners = context.getBean(KafkaListenerEndpointRegistry.class);
            if (Boolean.getBoolean("tcp.fixture.shared")) {
                listeners.getListenerContainers().stream()
                        .filter(c -> "things-link-ingestion-downlink".equals(c.getGroupId())).forEach(c -> c.start());
            }
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("pid", ProcessHandle.current().pid());
            status.put("port", context.getBean(DeviceAccessTcpServer.class).boundPort());
            if (context.containsBean("deviceAccessTcpRuntime")) {
                Object runtime = context.getBean("deviceAccessTcpRuntime");
                status.put("runtime", runtime.getClass().getMethod("id").invoke(runtime));
                status.put("group", runtime.getClass().getMethod("groupId").invoke(runtime));
            } else { status.put("runtime", "legacy-node"); status.put("group", "legacy"); }
            write(control.resolve("ready.json"), json.writeValueAsString(status));
            try (var input = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
                for (String line; (line = input.readLine()) != null;) {
                    if (line.equals("quit")) break;
                    String[] command = line.split(" ", 2);
                    if (!command[0].equals("status")) Files.writeString(control.resolve("fault-controls.jsonl"),
                            json.writeValueAsString(Map.of("at",java.time.Instant.now().toString(),"action",command[0],"id",command[1])) + "\n",
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    switch (command[0]) {
                        case "arm-barrier" -> { barrier = new java.util.concurrent.CountDownLatch(1); armBarrier.set(true); Files.deleteIfExists(control.resolve("barrier-hit")); }
                        case "release-barrier" -> barrier.countDown();
                        case "arm-mark-failure" -> failMark.set(true);
                        case "arm-mark-barrier" -> { barrier = new java.util.concurrent.CountDownLatch(1); markBarrier.set(true); }
                        case "arm-read-failure" -> failRead.set(true);
                        case "pause-scanner" -> pauseScanner = true;
                        case "resume-scanner" -> pauseScanner = false;
                        case "pause-outbox" -> pauseOutbox = true;
                        case "resume-outbox" -> pauseOutbox = false;
                        default -> { }
                    }
                    if (command[0].equals("stop-tcp-consumer")) listeners.getListenerContainer("tcpCommandDownlink").stop();
                    if (command[0].equals("start-tcp-consumer")) listeners.getListenerContainer("tcpCommandDownlink").start();
                    Object gate = context.containsBean("tcpDownlinkReadiness") ? context.getBean("tcpDownlinkReadiness") : null;
                    write(control.resolve("done-" + command[1]), json.writeValueAsString(Map.of("ready",
                            gate != null && Boolean.TRUE.equals(gate.getClass().getMethod("ready").invoke(gate)))));
                }
            }
        }
    }
    /** 仅独立测试进程持有的故障开关，不进入生产构件。 */
    private static final java.util.concurrent.atomic.AtomicBoolean armBarrier = new java.util.concurrent.atomic.AtomicBoolean();
    /** 回执先于派发确认的真实竞争屏障。 */
    private static final java.util.concurrent.atomic.AtomicBoolean markBarrier = new java.util.concurrent.atomic.AtomicBoolean();
    /** 下一次真实SQL写入后回滚。 */
    private static final java.util.concurrent.atomic.AtomicBoolean failMark = new java.util.concurrent.atomic.AtomicBoolean();
    /** 下一次配置读取模拟可重试数据库异常。 */
    private static final java.util.concurrent.atomic.AtomicBoolean failRead = new java.util.concurrent.atomic.AtomicBoolean();
    /** 写前时序屏障。 */ private static volatile java.util.concurrent.CountDownLatch barrier;
    /** 暂停独立调度入口，绝不替换SQL结果。 */ private static volatile boolean pauseScanner;
    /** 暂停真实Outbox发布入口，用于负向记录输入。 */ private static volatile boolean pauseOutbox;
    /** 显式导入测试配置，所有注入均在原方法周围执行。 */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    public static class Faults {
        /** 只拦截四个有界时序点。 */
        @org.springframework.context.annotation.Bean
        static org.springframework.beans.factory.config.BeanPostProcessor faultProcessor() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                /** 原SQL/网络始终由生产实例执行，故障通过异常触发真实事务回滚。 */
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    String type = org.springframework.aop.support.AopUtils.getTargetClass(bean).getSimpleName();
                    if (!java.util.Set.of("DownlinkPreprocessingChain", "JdbcDeviceCommandRepository", "DeviceAccessSessionService", "DeviceCommandTimeoutScanner", "KafkaTransactionalOutboxPublisher", "TcpCommandDownlinkKafkaConsumer", "DeviceCommandDownlinkKafkaConsumer", "DeviceCommandService").contains(type)) return bean;
                    org.aopalliance.intercept.MethodInterceptor hook = invocation -> {
                        String method = invocation.getMethod().getName();
                        if (type.equals("DeviceCommandTimeoutScanner") && method.equals("scan") && pauseScanner) return null;
                        if (type.equals("KafkaTransactionalOutboxPublisher") && method.equals("publishReadyEvents") && pauseOutbox) return null;
                        if (type.equals("DeviceAccessSessionService") && method.equals("config") && Thread.currentThread().getName().contains("tcpCommandDownlink") && failRead.compareAndSet(true, false)) {
                            write(Path.of(System.getProperty("tcp.fixture.directory"), "read-failed"), "injected");
                            throw new org.springframework.dao.TransientDataAccessResourceException("TEST_ONLY_READ_FAILURE");
                        }
                        if (type.equals("DeviceCommandService") && method.equals("markDispatched") && markBarrier.compareAndSet(true, false)) {
                            write(Path.of(System.getProperty("tcp.fixture.directory"), "mark-barrier-hit"), "before SQL; reply may commit first");
                            if (!barrier.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("TEST_MARK_BARRIER_TIMEOUT");
                        }
                        Object result = invocation.proceed();
                        if (method.equals("consume") && invocation.getArguments()[0] instanceof org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record
                                && record.value() instanceof com.things.link.shared.message.DeviceCommandDispatch event) {
                            appendAudit(new ObjectMapper().writeValueAsString(Map.of("eventId",event.eventId(),"commandId",event.commandId(),"partition",record.partition(),"offset",record.offset(),"role",type)));
                        }
                        if (type.equals("DownlinkPreprocessingChain") && method.equals("apply") && armBarrier.compareAndSet(true, false)) {
                            write(Path.of(System.getProperty("tcp.fixture.directory"), "barrier-hit"), "after admission before write");
                            if (!barrier.await(60, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("TEST_BARRIER_TIMEOUT");
                        }
                        if (type.equals("JdbcDeviceCommandRepository") && method.equals("markDispatched") && failMark.compareAndSet(true, false)) {
                            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("FAULT_REQUIRES_REAL_TRANSACTION");
                            write(Path.of(System.getProperty("tcp.fixture.directory"), "mark-failed"), "SQL executed; throwing before commit");
                            throw new org.springframework.dao.TransientDataAccessResourceException("TEST_ONLY_MARK_ROLLBACK");
                        }
                        return result;
                    };
                    if (bean instanceof org.springframework.aop.framework.Advised advised) { advised.addAdvice(hook); return bean; }
                    var proxy = new org.springframework.aop.framework.ProxyFactory(bean); proxy.setProxyTargetClass(true); proxy.addAdvice(hook); return proxy.getProxy();
                }
            };
        }
    }
    /** 同进程两个消费者线程串行附加脱敏offset证据。 */
    private static synchronized void appendAudit(String line) throws Exception {
        Files.writeString(Path.of(System.getProperty("tcp.fixture.directory"), "consumed.jsonl"), line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
    /** 原子发布回执，文件存在即代表完整JSON可读。 */
    private static void write(Path target, String value) throws Exception {
        Path pending = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(pending, value);
        Files.move(pending, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
