package com.things.link.assistant.application;
import com.things.link.assistant.domain.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static com.things.link.assistant.application.AnalysisReleaseReviewTests.*;
import static org.assertj.core.api.Assertions.*;
class AnalysisResultIssuerTests {
    final String input="1".repeat(64), request="2".repeat(64);
    final AnalysisContent content=new AnalysisContent("private-text",List.of(new AnalysisContent.Finding(AnalysisContent.Kind.FACT,"private-finding",List.of("e-device"))),List.of());
    final AnalysisUsage usage=new AnalysisUsage(1,1,2,0,1);
    AnalysisCall call(){return new AnalysisCall(UUID.fromString("019c1234-5678-7890-8123-456789abcdef"),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"k".repeat(64),"r".repeat(64),2,AnalysisCall.Status.DISPATCHED,NOW,NOW.plusSeconds(60),NOW.plusSeconds(86400),NOW,null);}
    AnalysisResultIssuer.Binding binding(){return new AnalysisResultIssuer.Binding(input,request,COUNTER,CANDIDATE,Set.of("e-device","e-alarm"));}
    AnalysisResultIssuer issuer(AnalysisCall c,AnalysisReleaseReview.Approval r,Clock clock){return new AnalysisResultIssuer(c,binding(),r,clock);}
    AnalysisResultIssuer.Receipt receipt(AnalysisCall c,AnalysisReleaseReview.Approval r){return new AnalysisResultIssuer.Receipt(c.id(),2,input,request,COUNTER,CANDIDATE,r.reviewSha256,"REVIEWED_EXECUTION","VALIDATED","f".repeat(64),content,usage);}
    AnalysisExecutionContext context(AnalysisCall c){return new AnalysisExecutionContext(c,new ModelCredential(UUID.randomUUID(),c.tenantId(),c.projectId(),2,1,true,new byte[]{1},new byte[]{2},"synthetic",c.createdBy(),NOW));}
    void denied(Runnable r){assertThatThrownBy(r::run).hasMessage("ANALYSIS_RELEASE_NOT_ADMITTED").hasNoCause();}
    @Test void syntheticApprovalIssuesOnceAndReceiverStillOwnsFinalAuthorization() throws Exception {
        var c=call();var r=approval();var issuer=issuer(c,r,Clock.fixed(NOW,ZoneOffset.UTC));var permit=issuer.issue(receipt(c,r));
        denied(()->issuer.issue(receipt(c,r)));assertThatThrownBy(()->permit.consume(context(call()),NOW)).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
        permit.consume(context(c),NOW);permit.verifyReturn(context(c),NOW);assertThatThrownBy(()->permit.verifyReturn(context(c),NOW.plusSeconds(60))).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
        assertThat(issuer.toString()+receipt(c,r)+binding()+permit).doesNotContain("private",input,request);
    }
    @Test void wrongReceiptConsumesAttemptAndCannotRetry() throws Exception {
        var c=call();var review=approval();var source=receipt(c,review);
        List<UnaryOperator<AnalysisResultIssuer.Receipt>> edits=List.of(r->patch(r,0,UUID.randomUUID()),r->patch(r,1,3L),r->patch(r,2,"0".repeat(64)),r->patch(r,3,"0".repeat(64)),r->patch(r,4,"0".repeat(64)),r->patch(r,5,"0".repeat(64)),r->patch(r,6,"0".repeat(64)),r->patch(r,7,"OFFLINE_UNQUALIFIED"),r->patch(r,7,"APPROVED"),r->patch(r,8,"REJECTED"),r->patch(r,9,"0".repeat(64)),r->patch(r,10,null),r->patch(r,11,null),r->patch(r,10,new AnalysisContent("x",List.of(new AnalysisContent.Finding(AnalysisContent.Kind.FACT,"x",List.of("foreign"))),List.of())),r->patch(r,10,new AnalysisContent("中".repeat(6000),List.of(),List.of())),r->null);
        for(var edit:edits){var issuer=issuer(c,review,Clock.fixed(NOW,ZoneOffset.UTC));denied(()->issuer.issue(edit.apply(source)));denied(()->issuer.issue(source));}
    }
    @Test void missingReviewOrWrongOriginalBindingsReject() throws Exception {
        var c=call();var r=approval();var clock=Clock.fixed(NOW,ZoneOffset.UTC);
        denied(()->issuer(c,null,clock));denied(()->issuer(null,r,clock));
        denied(()->new AnalysisResultIssuer(c,new AnalysisResultIssuer.Binding(input,request,"0".repeat(64),CANDIDATE,Set.of("e-device","e-alarm")),r,clock));
        denied(()->new AnalysisResultIssuer(c,new AnalysisResultIssuer.Binding(input,request,COUNTER,"0".repeat(64),Set.of("e-device","e-alarm")),r,clock));
        denied(()->new AnalysisResultIssuer.Binding(input,request,COUNTER,CANDIDATE,Set.of("e-device","e-alarm","foreign")));
        denied(()->new AnalysisResultIssuer.Binding(input,request,COUNTER,CANDIDATE,Set.of("e-device")));
        denied(()->new AnalysisResultIssuer.Binding(input,"bad",COUNTER,CANDIDATE,Set.of("e-device","e-alarm")));
    }
    @Test void earliestReviewDeadlineWinsAndClockCannotExtendIt() throws Exception {
        var c=call();var document=approvedDocument();document.put("expiresAt",NOW.plusSeconds(10).toString());
        var r=verify(JSON.writeValueAsBytes(document),NOW).orElseThrow();var permit=issuer(c,r,Clock.fixed(NOW,ZoneOffset.UTC)).issue(receipt(c,r));
        permit.consume(context(c),NOW);permit.verifyReturn(context(c),NOW.plusSeconds(9));assertThatThrownBy(()->permit.verifyReturn(context(c),NOW.plusSeconds(10))).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
        for(Instant changed:List.of(NOW.minusSeconds(1),NOW.plusSeconds(10),NOW.plusSeconds(60))){var clock=new MutableClock(NOW);var issuer=issuer(c,r,clock);clock.now=changed;denied(()->issuer.issue(receipt(c,r)));}
    }
    @Test void completionCostCannotCrossOriginalDeadline() throws Exception {
        var c=call();var r=approval();
        var clock=new Clock(){int reads;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return ++reads<3?NOW:NOW.plusSeconds(60);}};
        var issuer=issuer(c,r,clock);denied(()->issuer.issue(receipt(c,r)));
    }
    @Test void wrongCallLifecycleCannotCreateIssuerAndEvidenceScopeIsCopied() throws Exception {
        var c=call();var r=approval();var clock=Clock.fixed(NOW,ZoneOffset.UTC);
        for(var status:List.of(AnalysisCall.Status.RESERVED,AnalysisCall.Status.UNKNOWN,AnalysisCall.Status.SUCCEEDED,AnalysisCall.Status.FAILED)) {
            var changed=new AnalysisCall(c.id(),c.tenantId(),c.projectId(),c.createdBy(),c.deviceId(),c.modelVersionId(),c.keyHash(),c.requestHash(),2,status,c.createdAt(),c.deadline(),c.expiresAt(),c.dispatchedAt(),null);
            denied(()->issuer(changed,r,clock));
        }
        var ids=new HashSet<>(Set.of("e-device","e-alarm"));
        var binding=new AnalysisResultIssuer.Binding(input,request,COUNTER,CANDIDATE,ids);ids.add("foreign");
        assertThat(binding.evidenceIds()).doesNotContain("foreign");
        assertThatThrownBy(()->binding.evidenceIds().add("foreign")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void wallClockRollbackCannotRenewMonotonicBudget() throws Exception {
        var c=call();var r=approval();var wall=new MutableClock(NOW.plusSeconds(30));
        var nanos=new java.util.concurrent.atomic.AtomicLong(100);
        var issuer=new AnalysisResultIssuer(c,binding(),r,wall,nanos::get);
        wall.now=NOW.plusSeconds(1);nanos.addAndGet(Duration.ofSeconds(30).toNanos());
        denied(()->issuer.issue(receipt(c,r)));
    }
    @Test void concurrentIssuanceHasOnlyOneWinner() throws Exception {
        var c=call();var r=approval();var issuer=issuer(c,r,Clock.fixed(NOW,ZoneOffset.UTC));
        try(var pool=Executors.newFixedThreadPool(2)){var results=pool.invokeAll(List.<Callable<Boolean>>of(()->attempt(issuer,receipt(c,r)),()->attempt(issuer,receipt(c,r))));assertThat(results.stream().filter(f->{try{return f.get();}catch(Exception e){throw new AssertionError(e);}}).count()).isEqualTo(1);}
    }
    boolean attempt(AnalysisResultIssuer i,AnalysisResultIssuer.Receipt r){try{i.issue(r);return true;}catch(IllegalArgumentException e){return false;}}
    AnalysisResultIssuer.Receipt patch(AnalysisResultIssuer.Receipt r,int index,Object value){Object[] f={r.callId(),r.configurationRevision(),r.inputSha256(),r.requestSha256(),r.counterSha256(),r.executionCandidateSha256(),r.reviewSha256(),r.qualification(),r.status(),r.providerFingerprintSha256(),r.content(),r.usage()};f[index]=value;return new AnalysisResultIssuer.Receipt((UUID)f[0],(Long)f[1],(String)f[2],(String)f[3],(String)f[4],(String)f[5],(String)f[6],(String)f[7],(String)f[8],(String)f[9],(AnalysisContent)f[10],(AnalysisUsage)f[11]);}
    static class MutableClock extends Clock {Instant now;MutableClock(Instant n){now=n;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
}
