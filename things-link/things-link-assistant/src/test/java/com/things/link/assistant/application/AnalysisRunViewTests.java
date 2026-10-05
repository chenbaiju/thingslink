package com.things.link.assistant.application;

import com.things.link.assistant.domain.AnalysisCall;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnalysisRunViewTests {
    final AnalysisCallView success=new AnalysisCallView(UUID.randomUUID(),AnalysisCall.Status.SUCCEEDED,Instant.EPOCH,
            Instant.EPOCH.plusSeconds(60),Instant.EPOCH.plusSeconds(86400),Instant.EPOCH,Instant.EPOCH.plusSeconds(1));
    final AnalysisResultView body=new AnalysisResultView("deepseek-flash","thingslink-agent-single-analysis-v1","private-summary",
            List.of(new AnalysisResultView.Finding("FACT","private-statement",List.of("e-device"))),List.of(),
            new AnalysisResultView.Usage(1,1,2,0,1));
    @Test void categoryCallAndContentCannotContradictEachOther() {
        var invalid=List.<Runnable>of(
                () -> new AnalysisRunService.View(null,AnalysisRunService.Category.SUCCEEDED,body),
                () -> new AnalysisRunService.View(success,AnalysisRunService.Category.UNAVAILABLE),
                () -> new AnalysisRunService.View(success,AnalysisRunService.Category.SUCCEEDED),
                () -> new AnalysisRunService.View(success,AnalysisRunService.Category.REPLAY,body),
                () -> new AnalysisRunService.View(success,AnalysisRunService.Category.UNQUALIFIED,body),
                () -> new AnalysisRunService.View(success,AnalysisRunService.Category.TRANSPORT_UNKNOWN,body));
        invalid.forEach(run -> assertThatThrownBy(run::run).hasMessage("INVALID_ANALYSIS_VIEW"));
        assertThat(new AnalysisRunService.View(success,AnalysisRunService.Category.REPLAY).result()).isNull();
        assertThat(new AnalysisRunService.View(null,AnalysisRunService.Category.UNAVAILABLE).result()).isNull();
    }
    @Test void publicResponsePrintingNeverIncludesTransientText() {
        var view=new AnalysisRunService.View(success,AnalysisRunService.Category.SUCCEEDED,body);
        assertThat(view.toString()).doesNotContain("private");
        assertThat(body.toString()).doesNotContain("private");
        assertThat(body.findings().getFirst().toString()).doesNotContain("private");
        assertThatThrownBy(() -> body.findings().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> body.findings().getFirst().evidenceIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void availabilityHasExactlyOnePositivePair() {
        for(var reason:AnalysisRunService.UnavailableReason.values()) {
            boolean accepted=reason==AnalysisRunService.UnavailableReason.REVIEWED_CONFIGURATION_AVAILABLE;
            assertThat(new AnalysisRunService.Availability(accepted,reason).businessAvailable()).isEqualTo(accepted);
            assertThatThrownBy(()->new AnalysisRunService.Availability(!accepted,reason)).hasMessage("INVALID_ANALYSIS_AVAILABILITY");
        }
        assertThatThrownBy(()->new AnalysisRunService.Availability(false,null)).hasMessage("INVALID_ANALYSIS_AVAILABILITY");
    }
}
