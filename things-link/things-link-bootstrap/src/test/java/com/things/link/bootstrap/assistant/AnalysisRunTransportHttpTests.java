package com.things.link.bootstrap.assistant;

import com.sun.net.httpserver.*;
import com.things.link.assistant.application.*;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.iam.application.*;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.*;
import com.things.link.testing.AbstractIntegrationTest;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 真实平台HTTP、数据库与生产网络装配；内部对端只是本地合成拒绝端，不运行供应商或Python。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class AnalysisRunTransportHttpTests extends AbstractIntegrationTest {
    static final String MASTER=ModelConfigurationApiTests.master(), SECRET="synthetic-joined-analysis-only";
    static Peer peer;
    static final JsonMapper JSON=JsonMapper.builder().build();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        peer=new Peer();
        r.add("things-link.assistant.credentials.active-key-id",()->"analysis-joined");
        r.add("things-link.assistant.credentials.keys.analysis-joined",()->MASTER);
        String prefix="things-link.assistant.analysis.transport.";
        r.add(prefix+"origin",()->"https://localhost:"+peer.server.getAddress().getPort());
        r.add(prefix+"identity-file",()->peer.client.toString());
        r.add(prefix+"trust-file",()->peer.trust.toString());
        r.add(prefix+"identity-password",()->Peer.PASSWORD);
        r.add(prefix+"trust-password",()->Peer.PASSWORD);
        r.add(prefix+"counter-sha256",()->"a".repeat(64));
        r.add(prefix+"execution-candidate-sha256",()->"a".repeat(64));
    }
    @AfterAll static void closePeer() throws Exception { if(peer!=null)peer.close(); }
    @Value("${local.server.port}") int port;
    @Autowired TokenIssuer tokens;
    @Autowired ModelConfigurationService models;
    JdbcTemplate owner;
    WebAppDataRuntimeFixture.DataFixture data;
    String token,path,body;
    HttpClient client;
    @BeforeEach void setup() throws Exception {
        peer.reset();
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        data=WebAppDataRuntimeFixture.seed(owner);var f=data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'ADMIN')",UUID.randomUUID(),f.projectId(),f.actorId());
        asActor(()->models.replaceAndEnable(f.projectId(),new ModelCredentialInput("0",SECRET)));
        token=tokens.issue(new AuthenticatedPrincipal(f.actorId(),f.tenantId(),f.projectId())).value();
        path="/api/v1/projects/"+f.projectId()+"/assistant/analysis-runs";
        body=JSON.writeValueAsString(new AnalysisRequest(data.first(),data.model(),List.of("temperature"),PreparedModelEvidence.Template.STATUS_SUMMARY));
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();
    }
    @AfterEach void finish() { if(peer.gate!=null)peer.gate.countDown();if(client!=null)client.close();TenantContext.clear(); }
    void asActor(Runnable action) {
        var f=data.runtime();TenantContext.set(new TenantScope(f.tenantId(),f.projectId(),f.actorId()));
        try { action.run(); }finally { TenantContext.clear(); }
    }
    HttpRequest request(String suffix,String key) {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path+suffix))
                .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token);
        if(key==null)return builder.GET().build();
        return builder.header("Content-Type","application/json").header("Idempotency-Key",key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> send(String suffix,String key,int status) throws Exception {
        var response=client.send(request(suffix,key),HttpResponse.BodyHandlers.ofString());
        check(response,status);return response;
    }
    void check(HttpResponse<String> response,int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).doesNotContain(SECRET,MASTER,"private-joined-rejection");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
    @Test void realHttpUsesConfiguredMtlsOnceAndPersistsUnknownWithoutPrivateData(CapturedOutput output) throws Exception {
        var availability=JSON.readTree(send("/status",null,200).body());
        assertThat(availability.path("businessAvailable").asBoolean()).isFalse();
        assertThat(availability.path("reason").asString()).isEqualTo("MODEL_ADMISSION_PENDING");
        assertThat(peer.visits).hasValue(0);
        String key=Uuid7.generate().toString();var result=JSON.readTree(send("",key,200).body());
        assertThat(result.path("category").asString()).isEqualTo("TRANSPORT_UNKNOWN");
        assertThat(result.path("call").path("status").asString()).isEqualTo("UNKNOWN");
        UUID id=UUID.fromString(result.path("call").path("id").asString());
        assertThat(peer.visits).hasValue(1);assertThat(peer.credentialMatched).isTrue();
        assertThat(peer.consoleIdentityAbsent).isTrue();assertThat(peer.clientIdentity).isEqualTo("CN=client");
        assertThat(peer.path).isEqualTo("/internal/analysis");assertThat(peer.method).isEqualTo("POST");
        JsonNode wire=JSON.readTree(peer.received);
        assertThat(wire.path("callId").asString()).isEqualTo(id.toString());
        assertThat(wire.path("configurationRevision").asLong()).isEqualTo(2);
        var input=new String(Base64.getDecoder().decode(wire.path("inputBase64").asString()),StandardCharsets.UTF_8);
        assertThat(JSON.readTree(input).path("deviceAlias").asString()).isEqualTo("device-1");
        assertThat(input).doesNotContain(data.first().toString(),data.runtime().projectId().toString(),SECRET,"运行测试模型");
        assertThat(Instant.ofEpochMilli(wire.path("deadlineEpochMillis").asLong()))
                .isEqualTo(Instant.parse(result.path("call").path("deadline").asString()).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(owner.queryForObject("SELECT claimed_at IS NOT NULL FROM assistant_analysis_slot WHERE call_id=?",Boolean.class,id)).isTrue();
        assertThat(owner.queryForObject("SELECT status FROM assistant_analysis_call WHERE id=?",String.class,id)).isEqualTo("UNKNOWN");
        assertThat(JSON.readTree(send("",key,200).body()).path("category").asString()).isEqualTo("REPLAY");
        assertThat(JSON.readTree(send("/"+id,null,200).body()).path("status").asString()).isEqualTo("UNKNOWN");
        assertThat(peer.visits).hasValue(1);assertThat(output.getAll()).doesNotContain(SECRET,MASTER,"private-joined-rejection");
    }
    @Test void roleAndConfigurationRejectionNeverReachInternalPeer() throws Exception {
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId());
        send("/status",null,403);send("",Uuid7.generate().toString(),403);
        owner.update("UPDATE sys_project_member SET role='ADMIN' WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId());
        asActor(()->models.enable(data.runtime().projectId(),"2",false));
        send("",Uuid7.generate().toString(),409);
        assertThat(peer.visits).hasValue(0);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_analysis_call WHERE project_id=?",Integer.class,data.runtime().projectId())).isZero();
    }
    @Test void inFlightDuplicateAndRevocationCannotResendOrExposeMetadata(CapturedOutput output) throws Exception {
        peer.gate=new CountDownLatch(1);
        String key=Uuid7.generate().toString();
        var running=client.sendAsync(request("",key),HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(peer.entered.await(10,TimeUnit.SECONDS)).isTrue();
            var replay=JSON.readTree(send("",key,200).body());
            assertThat(replay.path("category").asString()).isEqualTo("REPLAY");
            assertThat(replay.path("call").path("status").asString()).isEqualTo("DISPATCHED");
            UUID id=UUID.fromString(replay.path("call").path("id").asString());
            owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId());
            peer.gate.countDown();var denied=running.get(10,TimeUnit.SECONDS);check(denied,403);
            assertThat(denied.body()).doesNotContain(id.toString(),"configurationRevision","call");
            send("",key,403);assertThat(peer.visits).hasValue(1);
            assertThat(owner.queryForObject("SELECT claimed_at IS NOT NULL FROM assistant_analysis_slot WHERE call_id=?",Boolean.class,id)).isTrue();
            assertThat(output.getAll()).doesNotContain(SECRET,MASTER,"private-joined-rejection");
        } finally { peer.gate.countDown(); }
    }

    /** 专属临时PKI和固定503端点，不含供应商或业务放行分支。 */
    static final class Peer implements AutoCloseable {
        static final String PASSWORD="synthetic-joined-store-password";
        final Path directory=Files.createTempDirectory("agent-joined-pki-");
        final Path client,trust;
        final HttpsServer server;
        final AtomicInteger visits=new AtomicInteger();
        volatile CountDownLatch gate,entered=new CountDownLatch(1);
        volatile boolean credentialMatched,consoleIdentityAbsent;
        volatile String clientIdentity,path,method;
        volatile byte[] received;
        Peer() throws Exception {
            keytool("-genkeypair","-alias","ca","-keyalg","EC","-groupname","secp256r1","-validity","7","-dname","CN=Joined CA",
                    "-ext","BC=ca:true,pathlen:0","-ext","KU=keyCertSign,cRLSign","-storetype","PKCS12","-keystore",directory.resolve("ca.p12").toString());
            keytool("-exportcert","-alias","ca","-keystore",directory.resolve("ca.p12").toString(),"-rfc","-file",directory.resolve("ca.pem").toString());
            Path service=identity("server","serverAuth");client=identity("client","clientAuth");trust=directory.resolve("trust.p12");
            var ca=KeyStore.getInstance("PKCS12");ca.load(null,null);ca.setCertificateEntry("root",load(directory.resolve("ca.p12")).getCertificate("ca"));
            try(var output=Files.newOutputStream(trust)){ca.store(output,PASSWORD.toCharArray());}
            var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(load(service),PASSWORD.toCharArray());
            var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(ca);
            var tls=SSLContext.getInstance("TLS");tls.init(keys.getKeyManagers(),managers.getTrustManagers(),new SecureRandom());
            server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls){@Override public void configure(HttpsParameters p){var ssl=tls.getDefaultSSLParameters();ssl.setNeedClientAuth(true);p.setSSLParameters(ssl);}});
            server.createContext("/",exchange->{
                try(exchange){
                    clientIdentity=((HttpsExchange)exchange).getSSLSession().getPeerPrincipal().getName();
                    if(!"CN=client".equals(clientIdentity)){exchange.sendResponseHeaders(403,-1);return;}
                    visits.incrementAndGet();path=exchange.getRequestURI().toString();method=exchange.getRequestMethod();
                    credentialMatched=SECRET.equals(exchange.getRequestHeaders().getFirst("X-Model-Credential"));
                    consoleIdentityAbsent=exchange.getRequestHeaders().getFirst("Authorization")==null && exchange.getRequestHeaders().getFirst("Cookie")==null;
                    received=exchange.getRequestBody().readNBytes(24577);entered.countDown();
                    if(gate!=null)try{gate.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                    byte[] response="private-joined-rejection".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Connection","close");
                    exchange.sendResponseHeaders(503,response.length);exchange.getResponseBody().write(response);
                }
            });server.start();
        }
        void reset(){visits.set(0);gate=null;entered=new CountDownLatch(1);received=null;credentialMatched=consoleIdentityAbsent=false;}
        void keytool(String... args) throws Exception {
            var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString());
            command.addAll(List.of(args));command.addAll(List.of("-storepass:env","TC_JOINED_TLS_PASSWORD","-noprompt"));
            var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile());builder.environment().put("TC_JOINED_TLS_PASSWORD",PASSWORD);
            var process=builder.start();try{assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();}finally{if(process.isAlive())process.destroyForcibly();}
        }
        Path identity(String name,String purpose) throws Exception {
            Path store=directory.resolve(name+".p12"),csr=directory.resolve(name+".csr"),certificate=directory.resolve(name+".pem");
            keytool("-genkeypair","-alias","identity","-keyalg","EC","-groupname","secp256r1","-validity","7","-dname","CN="+name,"-storetype","PKCS12","-keystore",store.toString());
            keytool("-certreq","-alias","identity","-keystore",store.toString(),"-file",csr.toString());
            keytool("-gencert","-alias","ca","-keystore",directory.resolve("ca.p12").toString(),"-infile",csr.toString(),"-outfile",certificate.toString(),"-rfc","-validity","7","-ext","BC=ca:false","-ext","KU=digitalSignature","-ext","EKU="+purpose,"-ext","SAN=dns:localhost");
            keytool("-importcert","-alias","root","-keystore",store.toString(),"-file",directory.resolve("ca.pem").toString());
            keytool("-importcert","-alias","identity","-keystore",store.toString(),"-file",certificate.toString());
            keytool("-delete","-alias","root","-keystore",store.toString());return store;
        }
        KeyStore load(Path path)throws Exception{var store=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(path)){store.load(input,PASSWORD.toCharArray());}return store;}
        @Override public void close()throws Exception{if(gate!=null)gate.countDown();server.stop(0);try(var paths=Files.walk(directory)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
