package com.things.link.ota.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;

/** 调度器单次工作和停机边界，不证明数据库授权。 */
class OtaCampaignAdvancementWorkerTests {
    /** 禁用或停止都不触发候选查询。 */
    @Test void disabledAndStoppedNeverClaim() {
        var service = mock(OtaCampaignAdvancementService.class);
        var worker = new OtaCampaignAdvancementWorker(service, false);
        worker.start(); worker.tick(); worker.stop(); worker.tick();
        verifyNoInteractions(service);
    }
    /** 即使前次有可推进项，一次调度也不会循环扫描所有活动。 */
    @Test void oneTransactionPerTickAndStopPreventsFurtherWork() {
        var service = mock(OtaCampaignAdvancementService.class);
        when(service.advanceOne()).thenReturn(true);
        var worker = new OtaCampaignAdvancementWorker(service, true);
        worker.start(); worker.tick(); worker.stop(); worker.tick();
        verify(service, times(1)).advanceOne();
    }
}
