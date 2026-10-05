package com.things.link.assistant.application;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
class AnalysisReleaseReviewTests {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final Instant NOW=Instant.parse("2026-10-05T00:00:00Z");
    static final String CANDIDATE="abe35f14065feffd2b0d00d50223deacbd5cdefb5a07c9a348d6004ccad46445";
    static final String COUNTER="4419e0648eeff6c8bdd998e42eb0d8a6a58a62695cba4242d86bc50be3345c60";
    static final String MANIFEST="a1469ccb2412beda70e938852bb4eafd431f3efed91684be467a29b79361444a";
    static final byte[] ATTACHMENT="SYNTHETIC TEST ONLY; NOT PROVIDER EVIDENCE".getBytes(StandardCharsets.UTF_8);
    static byte[] resource() throws Exception {
        try(var s=AnalysisReleaseReviewTests.class.getResourceAsStream("/com/things/link/assistant/analysis-release-review.json")){return Objects.requireNonNull(s).readAllBytes();}
    }
    static String sha(byte[] raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}catch(Exception e){throw new AssertionError(e);}}
    @SuppressWarnings("unchecked") static Map<String,Object> approvedDocument() throws Exception {
        var v=JSON.readValue(resource(),Map.class);v.put("decision","APPROVED");v.put("providerFingerprintSha256","f".repeat(64));
        for(var c:(List<Map<String,Object>>)v.get("checks")){c.put("decision","ACCEPTED");c.put("evidenceSha256",sha(ATTACHMENT));}return v;
    }
    static AnalysisReleaseReview.Approval approval() throws Exception{return verify(JSON.writeValueAsBytes(approvedDocument()),NOW).orElseThrow();}
    static Optional<AnalysisReleaseReview.Approval> verify(byte[] raw,Instant now){return AnalysisReleaseReview.verify(raw,sha(raw),CANDIDATE,COUNTER,MANIFEST,now,id->ATTACHMENT.clone());}
    static void rejects(Runnable r){assertThatThrownBy(r::run).isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_ANALYSIS_RELEASE_REVIEW").hasNoCause();}
    @Test void packagedPendingDoesNotLoadMissingAttachmentsOrGrantApproval() throws Exception {
        assertThat(AnalysisReleaseReview.load(CANDIDATE,COUNTER,MANIFEST,NOW)).isEmpty();
        assertThat(AnalysisReleaseReview.verify(resource(),sha(resource()),CANDIDATE,COUNTER,MANIFEST,NOW,id->{throw new AssertionError("pending must not load attachments");})).isEmpty();
        assertThat(AnalysisReleaseReview.Approval.class.getConstructors()).isEmpty();
        try(var s=getClass().getResourceAsStream("/com/things/link/assistant/model-request-contract.json")){
            assertThat(JSON.readTree(resource()).get("requestContractSha256").asString()).isEqualTo(sha(s.readAllBytes()));}
    }
    @Test void explicitApprovalIsDistinctFromMaterialsCompleteAndHasOriginalWindow() throws Exception {
        var a=approval();assertThat(a.validAt(NOW)).isTrue();assertThat(a.validAt(Instant.parse("2026-10-11T00:00:00Z"))).isFalse();
        assertThat(a.toString()).doesNotContain("SYNTHETIC",CANDIDATE,MANIFEST);
        for(String state:List.of("PENDING","REJECTED")){var v=approvedDocument();v.put("decision",state);assertThat(verify(JSON.writeValueAsBytes(v),NOW)).isEmpty();}
    }
    @Test void wrongHashBindingsClockOrAttachmentsRejectWithoutPrivateCause() throws Exception {
        byte[] raw=JSON.writeValueAsBytes(approvedDocument());String hash=sha(raw);
        rejects(()->AnalysisReleaseReview.verify(raw,"0".repeat(64),CANDIDATE,COUNTER,MANIFEST,NOW,id->ATTACHMENT));
        rejects(()->AnalysisReleaseReview.verify(raw,hash,"0".repeat(64),COUNTER,MANIFEST,NOW,id->ATTACHMENT));
        rejects(()->AnalysisReleaseReview.verify(raw,hash,CANDIDATE,"0".repeat(64),MANIFEST,NOW,id->ATTACHMENT));
        rejects(()->AnalysisReleaseReview.verify(raw,hash,CANDIDATE,COUNTER,"0".repeat(64),NOW,id->ATTACHMENT));
        for(byte[] a:new byte[][]{null,new byte[0],new byte[65537],"private".getBytes()})rejects(()->AnalysisReleaseReview.verify(raw,hash,CANDIDATE,COUNTER,MANIFEST,NOW,id->a));
        for(Instant now:new Instant[]{null,Instant.parse("2026-10-03T23:59:59Z"),Instant.parse("2026-10-11T00:00:00Z")})rejects(()->verify(raw,now));
        assertThat(verify(raw,Instant.parse("2026-10-04T00:00:00Z"))).isPresent();
        rejects(()->AnalysisReleaseReview.verify(raw,hash,CANDIDATE,COUNTER,MANIFEST,NOW,id->{throw new IllegalStateException("private file");}));
    }
    @SuppressWarnings("unchecked") @Test void closedSchemaAndExplicitApprovalRequireAllChecks() throws Exception {
        List<Consumer<Map<String,Object>>> edits=List.of(v->v.put("version","other"),v->v.put("decision",true),v->v.put("decision","VERIFIED"),v->v.put("model","other"),v->v.put("modelVersion","other"),v->v.put("promptVersion","other"),v->v.put("requestContractSha256","0".repeat(64)),v->v.put("providerFingerprintSha256",null),v->v.put("providerFingerprintSha256","bad"),v->v.put("expiresAt","2026-10-12T00:00:00Z"),v->v.put("reviewedAt","2026-10-11T00:00:00Z"),v->v.put("reviewedAt","2026-10-04T00:00:00+00:00"),v->v.put("expiresAt","2026-09-31T00:00:00Z"),v->v.put("checks",List.of()),v->v.put("private",true),v->v.remove("decision"),
        v->((List<Map<String,Object>>)v.get("checks")).get(0).put("checkId","other"),
        v->{var c=(List<Map<String,Object>>)v.get("checks");c.set(1,c.get(0));},
        v->{var c=((List<Map<String,Object>>)v.get("checks")).get(0);c.put("decision","PENDING");c.put("evidenceSha256",null);},
        v->((List<Map<String,Object>>)v.get("checks")).get(0).put("decision","VERIFIED"),
        v->((List<Map<String,Object>>)v.get("checks")).get(0).put("evidenceSha256",null),
        v->((List<Map<String,Object>>)v.get("checks")).get(0).put("decision","REJECTED"));
        for(var edit:edits){var v=approvedDocument();edit.accept(v);byte[] raw=JSON.writeValueAsBytes(v);rejects(()->verify(raw,NOW));}
    }
    @Test void malformedDuplicateTrailingAndOversizeReject() throws Exception {
        String original=new String(JSON.writeValueAsBytes(approvedDocument()),StandardCharsets.UTF_8);
        for(byte[] raw:new byte[][]{new byte[0],new byte[16385],new byte[]{(byte)255},"null".getBytes(),"[]".getBytes(),(original+"{}").getBytes(),original.replace("\"decision\":\"APPROVED\"","\"decision\":\"APPROVED\",\"decision\":\"APPROVED\"").getBytes()})rejects(()->verify(raw,NOW));
        rejects(()->AnalysisReleaseReview.verify(null,"0".repeat(64),CANDIDATE,COUNTER,MANIFEST,NOW,id->ATTACHMENT));
    }
}
