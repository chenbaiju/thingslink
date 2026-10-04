package com.things.link.testing;

import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 需要真实 Kafka 的集成测试基类。
 *
 * <p>Kafka 不放进通用数据库基类：Maven 每个模块使用独立 Surefire JVM，无条件启动会让不涉及消息的
 * 测试也反复创建 broker，既拖慢构建又放大 Docker Desktop 下原生 Kafka 快速启停的不稳定性。</p>
 */
public abstract class AbstractKafkaIntegrationTest extends AbstractIntegrationTest {

    /**
     * Kafka 镜像与部署基线保持同一 4.1 协议代际，避免内存替身掩盖真实客户端行为。
     *
     * <p>用 JVM 版 {@code apache/kafka} 而非 {@code apache/kafka-native}：native 版（GraalVM）
     * 在 CI 的受限容器里常出现 KRaft broker 启动后迟迟不打印 "Transitioning from RECOVERY to
     * RUNNING"，容器日志停在 "===> Launching ..." 就再无输出，导致 Testcontainers 默认的日志
     * 等待策略 60s 超时。二者都是 Kafka 4.1.0，协议代际一致；JVM 版启动日志稳定，是
     * {@link KafkaContainer} 的首选镜像。</p>
     */
    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.1.0");
    /** 真实 Kafka Broker，在当前 Surefire JVM 内由所有消息测试复用。 */
    protected static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE)
            // CI runner 上 JVM Kafka 冷启动 + KRaft 首次选举可能逼近默认 60s，留足裕量避免偶发超时。
            .withStartupTimeout(Duration.ofMinutes(2));

    static {
        KAFKA.start();
    }

    /**
     * 将随机映射的 Kafka 地址注入 Spring Boot。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
