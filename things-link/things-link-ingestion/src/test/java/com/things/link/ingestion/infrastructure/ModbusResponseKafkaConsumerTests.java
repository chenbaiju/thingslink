package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.ModbusResponseAcceptanceService;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.shared.message.ModbusResponse;
import com.things.link.shared.id.Uuid7;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** ADR0063消费适配合同：只委托持久接管，信封拒绝及事务故障不得被吞掉。 */
class ModbusResponseKafkaConsumerTests {

    /** 原始信封只接管一次，避免在适配层重建消息号、版本或时间。 */
    @Test
    void delegatesOriginalEnvelopeExactlyOnce() {
        ModbusResponseAcceptanceService acceptance = mock(ModbusResponseAcceptanceService.class);
        ModbusResponse response = response();
        new ModbusResponseKafkaConsumer(acceptance).consume(record(response.gatewayId().toString(), response));
        verify(acceptance).accept(response);
        verifyNoMoreInteractions(acceptance);
    }

    /** 无效信封没有授权依据，必须在进入业务事务前拒绝。 */
    @Test
    void rejectsNullEnvelopeBeforeAcceptance() {
        ModbusResponseAcceptanceService acceptance = mock(ModbusResponseAcceptanceService.class);
        ModbusResponseKafkaConsumer consumer = new ModbusResponseKafkaConsumer(acceptance);
        assertThatThrownBy(() -> consumer.consume(record("gateway", null)))
                .isInstanceOf(InvalidUplinkMessageException.class);
        verifyNoInteractions(acceptance);
    }

    /** Kafka key必须等于网关，不能把错误分区归属下放到接纳服务。 */
    @Test
    void rejectsMismatchedKeyBeforeAcceptance() {
        ModbusResponseAcceptanceService acceptance = mock(ModbusResponseAcceptanceService.class);
        assertThatThrownBy(() -> new ModbusResponseKafkaConsumer(acceptance).consume(record("wrong", response())))
                .isInstanceOf(InvalidUplinkMessageException.class);
        verifyNoInteractions(acceptance);
    }

    /** 接纳异常原样传播，原消费链才能执行既有重试策略。 */
    @Test
    void propagatesAcceptanceFailureWithoutAcknowledgingSuccess() {
        ModbusResponseAcceptanceService acceptance = mock(ModbusResponseAcceptanceService.class);
        ModbusResponse response = response();
        RuntimeException failure = new IllegalStateException("接纳事务失败");
        doThrow(failure).when(acceptance).accept(response);
        assertThatThrownBy(() -> new ModbusResponseKafkaConsumer(acceptance)
                .consume(record(response.gatewayId().toString(), response))).isSameAs(failure);
        verify(acceptance).accept(response);
        verifyNoMoreInteractions(acceptance);
    }

    /** 合法传输信封；归属与解码由独立真实业务验收覆盖。 */
    private ModbusResponse response() {
        return new ModbusResponse(Uuid7.generate(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ModbusResponse.Status.SUCCESS, List.of(0, 0x3f80), null, Instant.now(), "0123456789abcdef");
    }

    /** 构造真实消费记录，不模拟consumer内部执行步骤。 */
    private ConsumerRecord<String, ModbusResponse> record(String key, ModbusResponse response) {
        return new ConsumerRecord<>(ModbusResponseKafkaConsumer.MODBUS_RESPONSE_TOPIC, 0, 0L, key, response);
    }
}
