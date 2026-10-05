package com.things.link.assistant.application;

import com.things.link.assistant.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnalysisResultPermitTests {
    final Instant now=Instant.parse("2026-10-04T00:00:00Z");
    final AnalysisContent content=new AnalysisContent("private",List.of(new AnalysisContent.Finding(
            AnalysisContent.Kind.FACT,"private",List.of("e-device"))),List.of());
    final AnalysisUsage usage=new AnalysisUsage(1,1,2,0,1);
    AnalysisExecutionContext execution() {
        var call=new AnalysisCall(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID(),"key-hash","request-hash",2,AnalysisCall.Status.DISPATCHED,
                now,now.plusSeconds(60),now.plusSeconds(86400),now,null);
        var credential=new ModelCredential(UUID.randomUUID(),call.tenantId(),call.projectId(),2,1,true,
                new byte[]{1},new byte[]{2},"synthetic",call.createdBy(),now);
        return new AnalysisExecutionContext(call,credential);
    }
    AnalysisResultPermit issue(AnalysisExecutionContext execution) {
        return issue(execution.call(),content,usage,Set.of("e-device"),now.plusSeconds(30));
    }
    AnalysisResultPermit issue(AnalysisCall call, AnalysisContent value, AnalysisUsage counts, Set<String> ids, Instant expires) {
        return new AnalysisResultPermit(call,value,counts,ids,expires,"deepseek-flash","thingslink-agent-single-analysis-v1");
    }
    @Test void noPublicConstructionAndOneConsumptionBoundToExactCall() {
        assertThat(AnalysisResultPermit.class.getConstructors()).isEmpty();
        var context=execution();var permit=issue(context);
        assertThatThrownBy(() -> permit.consume(execution(),now)).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
        assertThatThrownBy(() -> permit.verifyReturn(context,now)).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
        permit.consume(context,now);permit.verifyReturn(context,now);
        assertThatThrownBy(() -> permit.consume(context,now)).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
        assertThatThrownBy(() -> permit.verifyReturn(context,now.plusSeconds(30))).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
        assertThat(permit.toString()).doesNotContain("private","key-hash","request-hash");
    }
    @Test void cannotIssueUnknownUsageUnscopedOrOversizedContentOrExtendedDeadline() {
        var context=execution();
        var tooLarge=new AnalysisContent("中".repeat(6000),List.of(),List.of());
        var bad=List.<Runnable>of(
                () -> issue(context.call(),content,null,Set.of("e-device"),now.plusSeconds(30)),
                () -> issue(context.call(),content,usage,Set.of("other"),now.plusSeconds(30)),
                () -> issue(context.call(),tooLarge,usage,Set.of("e-device"),now.plusSeconds(30)),
                () -> issue(context.call(),content,usage,Set.of("e-device"),now.plusSeconds(61)),
                () -> issue(context.call(),content,usage,Set.of("e-device"),now));
        bad.forEach(run -> assertThatThrownBy(run::run).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_ANALYSIS_RESULT_PERMIT").hasNoCause());
    }
    @Test void modelAndPromptMustMatchTheFixedReviewedProfile() {
        var context=execution();
        assertThatThrownBy(() -> new AnalysisResultPermit(context.call(),content,usage,Set.of("e-device"),now.plusSeconds(30),
                "unreviewed-model","thingslink-agent-single-analysis-v1")).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
        assertThatThrownBy(() -> new AnalysisResultPermit(context.call(),content,usage,Set.of("e-device"),now.plusSeconds(30),
                "deepseek-flash","unreviewed-prompt")).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
    }
}
