package com.things.link.support.kafka;
import com.things.link.shared.error.RealtimeAdmissionUnavailableException;
import org.apache.kafka.clients.consumer.*;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.MessageListenerContainer;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RealtimeIngressKafkaErrorHandlerTests {
    @Test void persistenceFailureNeverExhaustsIntoAutomaticOffsetRecovery(){
        var recovered=new AtomicInteger();var handler=KafkaTraceConfiguration.realtimeIngressErrorHandler((r,e)->recovered.incrementAndGet(),0);
        var record=new ConsumerRecord<>("tc.device.realtime",0,10,"device","payload");
        var failure=new RealtimeAdmissionUnavailableException(new org.springframework.jdbc.CannotGetJdbcConnectionException("unavailable"));
        for(int i=0;i<100;i++)assertThat(handler.handleOne(failure,record,mock(Consumer.class),mock(MessageListenerContainer.class))).isFalse();
        assertThat(recovered).hasValue(0);
    }
    @Test void deterministicConflictUsesConfirmedRecovery(){
        var recovered=new AtomicInteger();var handler=KafkaTraceConfiguration.realtimeIngressErrorHandler((r,e)->recovered.incrementAndGet(),0);
        assertThat(handler.handleOne(new IllegalArgumentException("REALTIME_EVENT_ID_CONFLICT"),new ConsumerRecord<>("tc.device.realtime",0,10,"device","payload"),mock(Consumer.class),mock(MessageListenerContainer.class))).isTrue();
        assertThat(recovered).hasValue(1);
    }
}
