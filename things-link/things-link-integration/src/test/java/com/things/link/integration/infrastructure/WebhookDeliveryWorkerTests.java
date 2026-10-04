package com.things.link.integration.infrastructure;
import com.things.link.integration.application.*;
import com.things.link.integration.domain.WebhookDeliveryRepository;
import com.things.link.support.tenant.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class WebhookDeliveryWorkerTests {
    @Test void saturationDoesNotClaimQueuedWorkOrRunNetworkOnScheduler()throws Exception{
        var state=mock(WebhookDeliveryState.class);var dispatcher=mock(WebhookDeliveryDispatcher.class);
        var candidates=new ArrayList<WebhookDeliveryRepository.Candidate>();for(int i=0;i<100;i++)candidates.add(new WebhookDeliveryRepository.Candidate(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
        when(state.candidates()).thenReturn(candidates);var entered=new CountDownLatch(4);var release=new CountDownLatch(1);var calls=new AtomicInteger();var data=new AtomicInteger();
        doAnswer(inv->{calls.incrementAndGet();if(DatabaseWorkloadContext.current()==DatabaseWorkload.DATA)data.incrementAndGet();entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");return null;}).when(dispatcher).dispatch(any());
        var worker=new WebhookDeliveryWorker(state,dispatcher);
        try{
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),worker::tick);
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();worker.tick();assertThat(calls).hasValue(4);assertThat(data).hasValue(4);
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);
        }finally{release.countDown();worker.destroy();}
    }
    @Test void scanFailureDoesNotEscapeMaintenanceScheduler()throws Exception{
        var state=mock(WebhookDeliveryState.class);when(state.candidates()).thenThrow(new IllegalStateException("test"));var dispatcher=mock(WebhookDeliveryDispatcher.class);var worker=new WebhookDeliveryWorker(state,dispatcher);
        try{worker.tick();verifyNoInteractions(dispatcher);}finally{worker.destroy();}
    }
}
