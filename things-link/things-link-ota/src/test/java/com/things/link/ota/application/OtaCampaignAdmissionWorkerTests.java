package com.things.link.ota.application;

import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 仅验证worker物理生命周期和单轮预算，不用服务替身冒充设备资格。 */
class OtaCampaignAdmissionWorkerTests {
    /** 关闭配置或停止状态都不允许新领取。 */
    @Test
    void doesNotClaimWhenDisabledOrStopped() {
        var service=mock(OtaCampaignAdmissionService.class);
        var disabled=new OtaCampaignAdmissionWorker(service,false);disabled.start();disabled.tick();
        var enabled=new OtaCampaignAdmissionWorker(service,true);enabled.tick();enabled.start();enabled.stop();enabled.tick();
        verifyNoInteractions(service);assertThat(enabled.isRunning()).isFalse();
    }

    /** 领取期间停止后不再准入，已持久租约交由到期恢复。 */
    @Test
    void stopBetweenClaimAndAdmissionLeavesLeaseForRecovery() {
        var service=mock(OtaCampaignAdmissionService.class);var worker=new OtaCampaignAdmissionWorker(service,true);
        when(service.claimOne()).thenAnswer(invocation->{worker.stop();return Optional.of(claim());});
        worker.start();worker.tick();verify(service).claimOne();verify(service,never()).admit(any(),any());
        assertThat(worker.isRunning()).isFalse();
    }

    /** 每轮最多一条，领取与准入顺序明确，不内部扫描或循环清空队列。 */
    @Test
    void admitsAtMostOneClaimPerTick() {
        var service=mock(OtaCampaignAdmissionService.class);var claim=claim();
        when(service.claimOne()).thenReturn(Optional.of(claim));
        var worker=new OtaCampaignAdmissionWorker(service,true);worker.start();worker.tick();
        var order=inOrder(service);order.verify(service).claimOne();order.verify(service).admit(claim.jobId(),claim.token());
        verifyNoMoreInteractions(service);
    }

    /** 事务失败本轮不重试、不更改业务结论；停止回调只终止后续领取。 */
    @Test
    void preservesFailedTransactionForLaterLeaseRecovery() {
        var service=mock(OtaCampaignAdmissionService.class);var claim=claim();
        when(service.claimOne()).thenReturn(Optional.of(claim));
        when(service.admit(claim.jobId(),claim.token())).thenThrow(new IllegalStateException("受控数据库失败"));
        var worker=new OtaCampaignAdmissionWorker(service,true);worker.start();worker.tick();
        verify(service).claimOne();verify(service).admit(claim.jobId(),claim.token());verifyNoMoreInteractions(service);
        var stopped=new java.util.concurrent.atomic.AtomicBoolean();worker.stop(()->stopped.set(true));
        assertThat(stopped).isTrue();worker.tick();verifyNoMoreInteractions(service);
    }

    /** 仅调度参数替身，不用于PG资格或授权证明。 */
    private static OtaCampaignRuntimeRepository.Claim claim() {
        return new OtaCampaignRuntimeRepository.Claim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),1,UUID.randomUUID(),0,UUID.randomUUID(),Instant.now().plusSeconds(15));
    }
}
