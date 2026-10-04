package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.RealtimeKafkaPublisher;
import com.things.link.ingestion.application.RealtimeProjectPublisher;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 单消费组实时 topic 到 Redis 项目频道的桥接器。
 *
 * <p>Kafka topic 只允许一个 ThingsLink 实时 consumer group 消费，再由 Redis 向所有拥有
 * 连接的实例扇出；每实例独立消费 Kafka 会让全部实例承受完整遥测流量，违反 ADR 0016。</p>
 */
public class RealtimeKafkaConsumer {

    /** 固定组名，滚动重启后继续同一偏移，不得用随机实例名创建重复消费组。 */
    public static final String REALTIME_CONSUMER_GROUP = "things-link-ingestion-realtime";

    /** Redis 项目频道发布端口。 */
    private final RealtimeProjectPublisher projectPublisher;
    private final com.things.link.integration.application.PublicRealtimeIngress publicIngress;

    /**
     * @param projectPublisher Redis Pub/Sub 项目扇出端口
     */
    public RealtimeKafkaConsumer(RealtimeProjectPublisher projectPublisher) {
        this(projectPublisher,update->{});
    }

    public RealtimeKafkaConsumer(RealtimeProjectPublisher projectPublisher,com.things.link.integration.application.PublicRealtimeIngress publicIngress){this.projectPublisher=projectPublisher;this.publicIngress=publicIngress;}

    /**
     * 验证冻结的分区键并发布 Redis 在线增量。
     *
     * @param record 已由 Kafka 反序列化的实时增量
     */
    @KafkaListener(topics = RealtimeKafkaPublisher.REALTIME_TOPIC,
            groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-realtime",
            containerFactory = "realtimeIngressKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-realtime:2}")
    public void consume(ConsumerRecord<String, DeviceRealtimeUpdate> record) {
        DeviceRealtimeUpdate update = record.value();
        if (update == null || !update.deviceId().toString().equals(record.key())) {
            // key 不一致会破坏同设备的属性时间顺序；这是生产者契约错误，应进入公共 DLQ。
            throw new IllegalArgumentException("实时 Kafka key 必须等于更新信封的 deviceId");
        }
        // 公开接收先完成PG持久交接；其失败不得被旧Redis的best-effort语义掩盖。
        publicIngress.accept(update);
        // Redis提示仍为best-effort，不改变公开受理的结果。
        projectPublisher.publish(update);
    }
}
