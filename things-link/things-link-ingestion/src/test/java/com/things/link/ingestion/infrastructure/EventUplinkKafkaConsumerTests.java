package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.EventUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.telemetry.application.EventIngestionService;
import com.things.link.telemetry.application.ProjectIngestionRejectedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.CannotAcquireLockException;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EventUplinkKafkaConsumerTests {
    @Test
    void callsOnlyDedicatedEventTransactionAndRejectsWrongKeyBeforeIt() {
        var service = mock(EventIngestionService.class);
        var message = message();
        var consumer = new EventUplinkKafkaConsumer(service);
        assertThatThrownBy(() -> consumer.consume(record(message, "wrong"))).isInstanceOf(InvalidUplinkMessageException.class);
        verifyNoInteractions(service);
        consumer.consume(record(message, message.deviceId().toString()));
        verify(service).ingest(message);
    }

    @Test
    void separatesPermanentBusinessAndWriteDenialFromRetryableSqlLocksAndAuthorityFailure() {
        var service = mock(EventIngestionService.class);
        var message = message();
        var consumer = new EventUplinkKafkaConsumer(service);
        var record = record(message, message.deviceId().toString());
        doThrow(new BusinessException(CommonErrorCode.INVALID_PARAMETER, "private-params")).when(service).ingest(message);
        assertThatThrownBy(() -> consumer.consume(record)).isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessage("EVENT_REJECTED_10001").hasNoCause();
        doThrow(new ProjectIngestionRejectedException()).when(service).ingest(message);
        assertThatThrownBy(() -> consumer.consume(record)).isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessage("EVENT_PROJECT_WRITE_REJECTED");
        for (RuntimeException failure : new RuntimeException[] {new DataAccessResourceFailureException("sql-unavailable"),
                new CannotAcquireLockException("lock-unavailable"), new IllegalStateException("quota-authority-unavailable")}) {
            doThrow(failure).when(service).ingest(message);
            assertThatThrownBy(() -> consumer.consume(record)).isSameAs(failure);
        }
    }

    private static ConsumerRecord<String, EventUplinkMessage> record(EventUplinkMessage message, String key) {
        return new ConsumerRecord<>(RawUplinkKafkaConsumer.EVENT_NORMALIZED_TOPIC, 0, 0L, key, message);
    }
    private static EventUplinkMessage message() {
        return new EventUplinkMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                TransportProtocol.MQTT, "alarm", "1.0.0", Instant.now(), Instant.now(), "trace-event", 100, Map.of());
    }
}
