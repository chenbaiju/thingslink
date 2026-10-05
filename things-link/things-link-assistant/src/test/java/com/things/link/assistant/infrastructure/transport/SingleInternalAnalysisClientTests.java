package com.things.link.assistant.infrastructure.transport;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.things.link.assistant.domain.AnalysisCall;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SingleInternalAnalysisClientTests {
    @TempDir static Path directory;
    static final String COUNTER = "a".repeat(64);
    static final String SECRET = "synthetic-analysis-key";
    final JsonMapper json = JsonMapper.builder().build();
    final InternalAnalysisRequestEncoderTests samples = new InternalAnalysisRequestEncoderTests();
    HttpsServer server;
    AtomicInteger visits = new AtomicInteger();
    volatile int status = 200;
    volatile boolean corruptBinding, corruptRequest, reviewedResponse, corruptReview, corruptProvider;
    volatile String receivedReview;
    volatile CountDownLatch gate;
    final CountDownLatch entered = new CountDownLatch(1);
    volatile byte[] received;
    volatile String key, authorization, cookie, path;

    @BeforeAll static void certificates() throws Exception {
        InternalAgentHealthClientTests.directory = directory;
        InternalAgentHealthClientTests.certificates();
    }
    @BeforeEach void start() throws Exception {
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(InternalAgentHealthClientTests.serverTLS) {
            @Override public void configure(HttpsParameters params) {
                var ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(true); params.setSSLParameters(ssl);
            }
        });
        server.createContext("/", exchange -> {
            visits.incrementAndGet();
            receivedReview=exchange.getRequestHeaders().getFirst("X-Analysis-Review");
            key=exchange.getRequestHeaders().getFirst("X-Model-Credential");
            authorization=exchange.getRequestHeaders().getFirst("Authorization");
            cookie=exchange.getRequestHeaders().getFirst("Cookie"); path=exchange.getRequestURI().toString();
            received=exchange.getRequestBody().readNBytes(24577);
            var request=json.readTree(received);
            var response=new InternalAnalysisResultDecoderTests().body();
            response.put("inputSha256",request.get("inputSha256").asString());
            response.put("requestSha256",corruptRequest?"b".repeat(64):ModelRequestFingerprint.fingerprint(
                    java.util.Base64.getDecoder().decode(request.get("inputBase64").asString())));
            response.put("callId",corruptBinding?UUID.randomUUID().toString():request.get("callId").asString());
            if (reviewedResponse) {
                response.put("version", "agent-internal-analysis-result-v3");
                response.put("qualification", "REVIEWED_EXECUTION");
                response.put("releaseReviewSha256", corruptReview ? "b".repeat(64) : receivedReview);
                if (corruptProvider) response.put("providerFingerprintSha256", "b".repeat(64));
            }
            byte[] body=status==200?json.writeValueAsBytes(response):"private-error-response".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            if(status==302)exchange.getResponseHeaders().set("Location","https://never-follow.invalid");
            exchange.sendResponseHeaders(status,body.length);
            entered.countDown();
            try(var output=exchange.getResponseBody()) {
                if(gate!=null)try { gate.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                output.write(body);
            } catch(java.io.IOException expectedDuringTimeout) { }
        });
        server.start();
    }
    @AfterEach void stop() {
        if(gate!=null)gate.countDown();
        server.stop(0);
    }
    URI origin(String host) { return URI.create("https://"+host+":"+server.getAddress().getPort()); }
    SingleInternalAnalysisClient client(String host, Path identity, Path trust) {
        char[] password=InternalAgentHealthClientTests.PASSWORD.toCharArray();
        return new SingleInternalAnalysisClient(origin(host),identity,password,trust,password);
    }
    SingleInternalAnalysisClient client() {
        return client("localhost",InternalAgentHealthClientTests.clientKeys,InternalAgentHealthClientTests.trust);
    }
    AnalysisCall call(Instant deadline) {
        var id=samples.id;
        return new AnalysisCall(id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),"private-key-hash","private-request-hash",2,AnalysisCall.Status.DISPATCHED,
            deadline.minusSeconds(60),deadline,deadline.plusSeconds(86400),deadline.minusSeconds(59),null);
    }
    AnalysisCall call() { return call(Instant.now().plusSeconds(59)); }
    byte[] secret() { return SECRET.getBytes(StandardCharsets.US_ASCII); }
    void failure(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalStateException.class)
            .hasMessage("INTERNAL_ANALYSIS_FAILED").hasNoCause();
    }

    @Test void fixedSingleMtlsPostPreservesWireAndClearsKey() {
        var client=client(); var call=call(); byte[] key=secret();
        byte[] expected=InternalAnalysisRequestEncoder.encode(call,samples.input());
        var result=client.execute(call,samples.input(),COUNTER,COUNTER,key);
        assertThat(result.qualification()).isEqualTo("OFFLINE_UNQUALIFIED");
        assertThat(result.status()).isEqualTo("VALIDATED");
        assertThat(received).isEqualTo(expected); assertThat(visits).hasValue(1);
        assertThat(path).isEqualTo("/internal/analysis"); assertThat(this.key).isEqualTo(SECRET);
        assertThat(authorization).isNull(); assertThat(cookie).isNull();
        assertThat(key).isEqualTo(new byte[key.length]); assertThat(client.toString()).doesNotContain(SECRET,"localhost");
        failure(()->client.execute(call,samples.input(),COUNTER,COUNTER,secret()));
        assertThat(visits).hasValue(1);
    }

    @Test void reviewedMtlsBindsIndependentApprovalBeforeIssuingOnePermit() {
        reviewedResponse=true;
        var approval=com.things.link.assistant.application.ReviewedTransportFixture.approval(COUNTER,COUNTER,COUNTER);
        var client=client();byte[] key=secret();var call=call();
        var receipt=client.executeReviewed(call,samples.input(),COUNTER,COUNTER,key,approval);
        assertThat(receipt).isInstanceOf(com.things.link.assistant.application.AnalysisTransport.Receipt.Releasable.class);
        assertThat(receivedReview).isEqualTo(approval.reviewSha256());
        assertThat(key).isEqualTo(new byte[key.length]);assertThat(visits).hasValue(1);
        assertThat(receipt.toString()).doesNotContain("private",SECRET,COUNTER);
        failure(()->client.executeReviewed(call,samples.input(),COUNTER,COUNTER,secret(),approval));
        assertThat(visits).hasValue(1);
    }
    @ParameterizedTest @ValueSource(strings={"legacy","review","provider"})
    void reviewedAttemptCannotReleaseOldOrDriftingResponse(String mode) {
        reviewedResponse=!mode.equals("legacy");corruptReview=mode.equals("review");corruptProvider=mode.equals("provider");
        var approval=com.things.link.assistant.application.ReviewedTransportFixture.approval(COUNTER,COUNTER,COUNTER);
        byte[] key=secret();failure(()->client().executeReviewed(call(),samples.input(),COUNTER,COUNTER,key,approval));
        assertThat(key).isEqualTo(new byte[key.length]);assertThat(visits).hasValue(1);
    }
    @Test void wrongOrMissingReviewCannotSendAndLegacyCannotSelfUpgrade() {
        var client=client();byte[] key=secret();
        failure(()->client.executeReviewed(call(),samples.input(),COUNTER,COUNTER,key,null));
        assertThat(key).isEqualTo(new byte[key.length]);
        var wrong=com.things.link.assistant.application.ReviewedTransportFixture.approval(COUNTER,"b".repeat(64),COUNTER);
        byte[] second=secret();failure(()->client().executeReviewed(call(),samples.input(),COUNTER,COUNTER,second,wrong));
        assertThat(second).isEqualTo(new byte[second.length]);assertThat(visits).hasValue(0);
        reviewedResponse=true;
        failure(()->client().execute(call(),samples.input(),COUNTER,COUNTER,secret()));
        assertThat(receivedReview).isNull();assertThat(visits).hasValue(1);
    }

    AnalysisTransportConfiguration.Properties properties() {
        var p=new AnalysisTransportConfiguration.Properties();
        p.setOrigin(origin("localhost"));p.setIdentityFile(InternalAgentHealthClientTests.clientKeys);
        p.setTrustFile(InternalAgentHealthClientTests.trust);
        p.setIdentityPassword(InternalAgentHealthClientTests.PASSWORD);p.setTrustPassword(InternalAgentHealthClientTests.PASSWORD);
        p.setCounterSha256(COUNTER);p.setExecutionCandidateSha256(COUNTER);return p;
    }
    @Test void configuredPortCreatesIndependentAttemptsAndFreezesConfiguration() {
        var p=properties();var port=new AnalysisTransportConfiguration().analysisTransport(p);
        assertThat(port.ready()).isTrue();assertThat(port.review()).isEmpty();assertThat(visits).hasValue(0);
        p.setOrigin(URI.create("https://changed.invalid:443"));p.setCounterSha256("b".repeat(64));p.setExecutionCandidateSha256("c".repeat(64));
        p.setIdentityFile(Path.of("missing-private-path"));p.setIdentityPassword("changed-private-password");
        for(int i=0;i<2;i++) {
            byte[] secret=secret();
            assertThat(port.execute(call(),samples.input(),secret)).isEqualTo(com.things.link.assistant.application.AnalysisTransport.Receipt.UNQUALIFIED);
            assertThat(secret).isEqualTo(new byte[secret.length]);
        }
        assertThat(visits).hasValue(2);
        assertThat(port.toString()+p).doesNotContain("localhost","changed",InternalAgentHealthClientTests.PASSWORD,COUNTER);
    }
    @Test void configuredPortPreservesFailureAndClearsCredentialWithoutRetry() {
        status=503;var port=new AnalysisTransportConfiguration().analysisTransport(properties());
        byte[] secret=secret();failure(()->port.execute(call(),samples.input(),secret));
        assertThat(secret).isEqualTo(new byte[secret.length]);assertThat(visits).hasValue(1);
    }
    @Test void springBindsDedicatedAnalysisPrefixAndRejectsInvalidOrPartialSettings() {
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(AnalysisTransportConfiguration.class);
        String prefix="things-link.assistant.analysis.transport.";
        for(String incomplete:List.of("origin=https://localhost:443","counter-sha256="+COUNTER,"execution-candidate-sha256="+COUNTER,
                "identity-password=private-synthetic-password")) {
            runner.withPropertyValues(prefix+incomplete).run(context->{
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseMessage("INVALID_ANALYSIS_TRANSPORT");
            });
        }
        var bound=runner.withPropertyValues(prefix+"origin="+origin("localhost"),
                prefix+"identity-file="+InternalAgentHealthClientTests.clientKeys,prefix+"trust-file="+InternalAgentHealthClientTests.trust,
                prefix+"identity-password="+InternalAgentHealthClientTests.PASSWORD,prefix+"trust-password="+InternalAgentHealthClientTests.PASSWORD,
                prefix+"counter-sha256="+COUNTER,prefix+"execution-candidate-sha256="+COUNTER);
        bound.run(context->{
            assertThat(context).hasNotFailed();
            var port=context.getBean(com.things.link.assistant.application.AnalysisTransport.class);
            assertThat(port.ready()).isTrue();byte[] secret=secret();
            assertThat(port.execute(call(),samples.input(),secret)).isEqualTo(com.things.link.assistant.application.AnalysisTransport.Receipt.UNQUALIFIED);
            assertThat(secret).isEqualTo(new byte[secret.length]);
        });
        for(String bad:List.of("origin=http://localhost:443","counter-sha256=invalid","execution-candidate-sha256=invalid","identity-password=wrong"))
            bound.withPropertyValues(prefix+bad).run(context->{
                assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasRootCauseMessage("INVALID_ANALYSIS_TRANSPORT");
            });
        bound.withPropertyValues(prefix+"unexpected=true").run(context->assertThat(context).hasFailed());
        assertThat(visits).hasValue(1);
    }
    @ParameterizedTest @ValueSource(ints={302,400,429,503})
    void errorsNeverRetryOrReturnPrivateBody(int rejectedStatus) {
        status=rejectedStatus; var client=client(); byte[] key=secret();
        failure(()->client.execute(call(),samples.input(),COUNTER,COUNTER,key));
        assertThat(key).isEqualTo(new byte[key.length]);
        failure(()->client.execute(call(),samples.input(),COUNTER,COUNTER,secret()));
        assertThat(visits).hasValue(1);
    }
    @Test void badBindingCannotReachBusinessResult() {
        corruptBinding=true; byte[] key=secret();
        failure(()->client().execute(call(),samples.input(),COUNTER,COUNTER,key));
        assertThat(key).isEqualTo(new byte[key.length]); assertThat(visits).hasValue(1);
    }
    @Test void validLookingWrongRequestDigestCannotPassIndependentExpectation() {
        corruptRequest=true;byte[] key=secret();var client=client();
        failure(()->client.execute(call(),samples.input(),COUNTER,COUNTER,key));
        assertThat(key).isEqualTo(new byte[key.length]);assertThat(visits).hasValue(1);
        failure(()->client.execute(call(),samples.input(),COUNTER,COUNTER,secret()));
        assertThat(visits).hasValue(1);
    }
    @Test void changedExecutionCandidateIsRejectedAfterOneMtlsExchangeAndClearsKey() {
        byte[] key=secret();
        failure(()->client().execute(call(),samples.input(),COUNTER,"b".repeat(64),key));
        assertThat(key).isEqualTo(new byte[key.length]);
        assertThat(visits).hasValue(1);
    }
    @Test void missingOrInvalidExpectedCandidateFailsBeforeNetworkAndConsumesAttempt() {
        for(String candidate:new String[]{null,"","bad"}) {
            var client=client();byte[] key=secret();
            failure(()->client.execute(call(),samples.input(),COUNTER,candidate,key));
            assertThat(key).isEqualTo(new byte[key.length]);
            failure(()->client.execute(call(),samples.input(),COUNTER,COUNTER,secret()));
        }
        var properties=properties();properties.setExecutionCandidateSha256(null);
        assertThatThrownBy(()->new AnalysisTransportConfiguration().analysisTransport(properties))
                .hasMessage("INVALID_ANALYSIS_TRANSPORT").hasNoCause();
        assertThat(visits).hasValue(0);
    }
    @Test void wrongClientCaAndHostnameFailBeforeHttp() {
        for(var c: new SingleInternalAnalysisClient[]{
            client("localhost",InternalAgentHealthClientTests.otherKeys,InternalAgentHealthClientTests.trust),
            client("localhost",InternalAgentHealthClientTests.clientKeys,InternalAgentHealthClientTests.wrongTrust),
            client("127.0.0.1",InternalAgentHealthClientTests.clientKeys,InternalAgentHealthClientTests.trust)}) {
            byte[] key=secret(); failure(()->c.execute(call(),samples.input(),COUNTER,COUNTER,key));
            assertThat(key).isEqualTo(new byte[key.length]);
        }
        assertThat(visits).hasValue(0);
    }
    @Test void invalidInputStillConsumesObjectAndClearsCredential() {
        for(byte[] key:new byte[][]{new byte[0],new byte[4097],"bad\r\nsecret".getBytes(StandardCharsets.US_ASCII)}) {
            var client=client(); failure(()->client.execute(call(),samples.input(),COUNTER,COUNTER,key));
            assertThat(key).isEqualTo(new byte[key.length]);
            failure(()->client.execute(call(),samples.input(),COUNTER,COUNTER,secret()));
        }
        var c=client(); byte[] key=secret();
        failure(()->c.execute(call(),samples.input(),"forged",COUNTER,key)); assertThat(key).isEqualTo(new byte[key.length]);
        failure(()->client().execute(call(Instant.now().minusSeconds(1)),samples.input(),COUNTER,COUNTER,secret()));
        failure(()->client().execute(call(Instant.now().plusSeconds(61)),samples.input(),COUNTER,COUNTER,secret()));
        assertThat(visits).hasValue(0);
    }
    @Test void concurrentDuplicateCannotSendOrEraseOwnedFirstCredential() throws Exception {
        gate=new CountDownLatch(1); var c=client(); byte[] first=secret();
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(()->c.execute(call(),samples.input(),COUNTER,COUNTER,first));
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(first).containsOnly((byte)0);
                failure(()->c.execute(call(),samples.input(),COUNTER,COUNTER,first));
                assertThat(visits).hasValue(1);
            } finally { gate.countDown(); }
            assertThat(pending.get(2,TimeUnit.SECONDS).qualification()).isEqualTo("OFFLINE_UNQUALIFIED");
        }
    }
    @Test void originalDeadlineActivelyClosesBlockedResponse() {
        gate=new CountDownLatch(1); byte[] key=secret(); var c=client();
        long started=System.nanoTime();
        failure(()->c.execute(call(Instant.now().plusMillis(1500)),samples.input(),COUNTER,COUNTER,key));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(3500);
        assertThat(key).isEqualTo(new byte[key.length]);
        assertThat(visits).hasValue(1);
    }
    @ParameterizedTest @ValueSource(strings={"http://localhost:8443","https://localhost","https://u@localhost:8443",
        "https://localhost:8443/path","https://localhost:8443?q=1","https://localhost:8443/#x"})
    void invalidOriginsFailBeforeNetwork(String origin) {
        failure(()->new SingleInternalAnalysisClient(URI.create(origin),null,null,null,null));
        assertThat(visits).hasValue(0);
    }
    @ParameterizedTest @ValueSource(strings={"HTTP/1.1 302 Found\r\nContent-Length: 2",
        "HTTP/1.1 200 OK\r\nContent-Length: 3","HTTP/1.1 200 OK\r\nContent-Length: 2\r\ncontent-length: 2",
        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked",
        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Encoding: gzip",
        "HTTP/1.1 200 OK\r\nContent-Length: +2","HTTP/1.1 200 OK\r\nContent-Length: 2\r\n Bad: value"})
    void invalidFramingIsRejected(String header) {
        byte[] raw=(header+"\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n\r\n{}").getBytes(StandardCharsets.US_ASCII);
        failure(()->SingleInternalAnalysisClient.responseBody(raw));
    }
    @Test void headerAndBodyBoundariesAreIndependent() {
        String base="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: ";
        byte[] maximum=(base+24576+"\r\n\r\n"+"a".repeat(24576)).getBytes(StandardCharsets.US_ASCII);
        assertThat(SingleInternalAnalysisClient.responseBody(maximum)).hasSize(24576);
        failure(()->SingleInternalAnalysisClient.responseBody((base+24577+"\r\n\r\n"+"a".repeat(24577)).getBytes(StandardCharsets.US_ASCII)));
        failure(()->SingleInternalAnalysisClient.responseBody((base+2+"\r\nX-Large: "+"a".repeat(4096)+"\r\n\r\n{}").getBytes(StandardCharsets.US_ASCII)));
    }
    @Test void dnsTimeoutKeepsSlotsUntilActualLookupExits() throws Exception {
        CountDownLatch release=new CountDownLatch(1), started=new CountDownLatch(2);
        AtomicInteger active=new AtomicInteger();
        var resolver=new BoundedAnalysisResolver(2,host->{active.incrementAndGet();started.countDown();
            try { release.await(3,TimeUnit.SECONDS); return InetAddress.getLoopbackAddress(); }
            finally { active.decrementAndGet(); }});
        try {
            for(int i=0;i<2;i++) assertThatThrownBy(()->resolver.resolve("synthetic",TimeUnit.MILLISECONDS.toNanos(100)))
                .isInstanceOf(TimeoutException.class);
            assertThat(started.await(1,TimeUnit.SECONDS)).isTrue(); assertThat(active).hasValue(2);
            assertThatThrownBy(()->resolver.resolve("synthetic",TimeUnit.SECONDS.toNanos(1)))
                .isInstanceOf(IllegalStateException.class).hasMessage("INTERNAL_ANALYSIS_RESOLUTION_FAILED");
        } finally { release.countDown(); }
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(active.get()!=0 && System.nanoTime()<end)Thread.sleep(5);
        assertThat(active).hasValue(0);
    }
}
