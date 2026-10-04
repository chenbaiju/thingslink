package com.things.link.bootstrap.integration;

import com.things.link.ingestion.infrastructure.protocol.coap.DeviceAccessCoapServer;
import com.things.link.shared.message.PublicWebhookSource;
import org.eclipse.californium.core.*;
import org.eclipse.californium.core.coap.*;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.*;
import org.eclipse.californium.scandium.dtls.CertificateType;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.californium.scandium.dtls.x509.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Actual HTTP, DTLS and production scheduling; Kafka/TLS delivery remains its own acceptance slice. */
@TestPropertySource(properties={"things-link.access.coap.enabled=true","things-link.access.coap.port=0"})
class WebhookActivityProtocolTests extends WebhookFixture {
    static final String SECRET="a1".repeat(32);
    static final String PATH="/device-access/v1/command/claim";
    static final Path TLS_FIXTURE_DIRECTORY=Path.of("..","things-link-ingestion","src","test","resources","tls").toAbsolutePath().normalize();
    @Autowired DeviceAccessCoapServer server;
    @Autowired com.things.link.device.application.DeviceCredentialService deviceCredentials;
    @Autowired com.things.link.device.application.DeviceService deviceService;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.things.link.device.application.DeviceAuthenticationPort authentication;
    @DynamicPropertySource static void tlsFixtureProperties(DynamicPropertyRegistry registry){
        registry.add("things-link.access.tls.certificate",()->"file:"+TLS_FIXTURE_DIRECTORY.resolve("device-access-test-cert.pem"));
        registry.add("things-link.access.tls.private-key",()->"file:"+TLS_FIXTURE_DIRECTORY.resolve("device-access-test-key.pem"));
    }
    @BeforeEach void credentials(){
        for(UUID id:List.of(device,unbound))owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(gen_random_uuid(),?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'activity-wire')",tenant,project,id,SECRET);
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'HTTP'),(?,?,?,'COAP')",device,tenant,project,unbound,tenant,project);
    }
    @AfterEach void cleanupNative(){
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource())).execute(s->{
            owner.queryForList("SELECT id FROM sys_project WHERE id=? FOR UPDATE",project);
            for(String table:List.of("dev_credential","dev_access_binding","sys_outbox_event"))owner.update("DELETE FROM "+table+" WHERE project_id=?",project);return null;});
    }
    String identity(UUID id){return owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?",String.class,project)+"/"+owner.queryForObject("SELECT device_key FROM dev_device WHERE id=?",String.class,id);}
    java.net.http.HttpResponse<String> pollHttp(String secret)throws Exception{return request("POST",PATH,"{}",null,Map.of("X-TC-Device-Key",identity(device),"X-TC-Device-Secret",secret));}
    List<PublicWebhookSource> sources(UUID id){return owner.queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND aggregate_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE' ORDER BY created_at,id",String.class,project,id).stream().map(s->json.readValue(s,PublicWebhookSource.class)).toList();}
    void due(UUID id){owner.update("UPDATE dev_access_binding SET last_activity_at=clock_timestamp()-interval '301 seconds' WHERE device_id=?",id);}
    void awaitOffline(UUID id){await().atMost(Duration.ofSeconds(10)).untilAsserted(()->{assertThat(sources(id)).hasSize(2);assertThat(sources(id).getLast().event().eventType()).isEqualTo("device.offline");assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?",Boolean.class,id)).isFalse();});}
    @Test void realHttpPollingAndProductionTimerGenerateOneWindow()throws Exception{
        assertThat(pollHttp(SECRET).statusCode()).isEqualTo(204);assertThat(pollHttp(SECRET).statusCode()).isEqualTo(204);assertThat(sources(device)).hasSize(1);
        due(device);awaitOffline(device);
        assertThat(pollHttp(SECRET).statusCode()).isEqualTo(204);assertThat(sources(device)).hasSize(3);
    }
    @Test void realDtlsPollingAndProductionTimerGenerateCoapWindow()throws Exception{
        var first=send(PATH,"{}",identity(unbound),SECRET);assertThat(first.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);assertThat(first.advanced().getSourceContext().getPeerIdentity()).isNotNull();
        assertThat(send(PATH,"{}",identity(unbound),SECRET).getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);assertThat(sources(unbound)).hasSize(1);
        assertThat(json.readTree(sources(unbound).getFirst().eventText()).path("payload").path("source").asString()).isEqualTo("COAP");
        due(unbound);awaitOffline(unbound);
    }
    @Test void badSecretsDoNotCreateActivityAndKeepAuthenticationBackoff()throws Exception{
        assertThat(pollHttp("wrong").statusCode()).isEqualTo(401);assertThat(sources(device)).isEmpty();
        assertThat(pollHttp(SECRET).statusCode()).isEqualTo(429);
        var denied=send(PATH,"{}",identity(unbound),"wrong");assertThat(denied.getCode()).isEqualTo(CoAP.ResponseCode.UNAUTHORIZED);assertThat(sources(unbound)).isEmpty();
        assertThat(send(PATH,"{}",identity(unbound),SECRET).getCode()).isEqualTo(CoAP.ResponseCode.TOO_MANY_REQUESTS);
    }
    @Test void realProtocolsRejectWrongPlaneWithoutCreatingActivity()throws Exception{
        assertThat(send(PATH,"{}",identity(device),SECRET).getCode()).isEqualTo(CoAP.ResponseCode.FORBIDDEN);
        var denied=request("POST",PATH,"{}",null,Map.of("X-TC-Device-Key",identity(unbound),"X-TC-Device-Secret",SECRET));assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(sources(device)).isEmpty();assertThat(sources(unbound)).isEmpty();
    }
    @Test void archivedProjectRejectsNewPollButTimerClosesOldWindow()throws Exception{
        assertThat(pollHttp(SECRET).statusCode()).isEqualTo(204);owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",project);
        assertThat(pollHttp(SECRET).statusCode()).isEqualTo(403);due(device);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?",Boolean.class,device)).isFalse());assertThat(sources(device)).hasSize(1);
    }
    @Test void timerContinuesPastOneFailedSourceAndRecoversWithoutManualTick()throws Exception{
        assertThat(pollHttp(SECRET).statusCode()).isEqualTo(204);
        assertThat(send(PATH,"{}",identity(unbound),SECRET).getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
        String name="test_activity_"+device.toString().replace("-","");
        owner.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.id='"+device+"'::uuid THEN RAISE EXCEPTION 'TEST_ACTIVITY_SOURCE_FAILURE'; END IF; RETURN NEW; END $$");
        try {
            owner.execute("CREATE TRIGGER "+name+" BEFORE UPDATE OF webhook_presence_revision ON dev_device FOR EACH ROW EXECUTE FUNCTION "+name+"()");
            due(device);due(unbound);awaitOffline(unbound);
            assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?",Boolean.class,device)).isTrue();assertThat(sources(device)).hasSize(1);
        } finally {
            owner.execute("DROP TRIGGER IF EXISTS "+name+" ON dev_device");owner.execute("DROP FUNCTION "+name+"()");
        }
        awaitOffline(device);
    }
    /** 实际控制事务后通过原生HTTP/DTLS验证拒绝及重新认证，不直接翻配置字段。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"HTTP,ROUND_TRIP","COAP,ROUND_TRIP","HTTP,DISABLE","COAP,DISABLE", "HTTP,CREDENTIAL","COAP,CREDENTIAL","HTTP,DELETE","COAP,DELETE"})
    void realControlChangesCloseNativeWindowAndOnlyCurrentAccessCanReopen(String protocol,String operation)throws Exception {
        UUID target=protocol.equals("HTTP")?device:unbound;
        int accepted=protocol.equals("HTTP")?204:CoAP.ResponseCode.CHANGED.value;
        assertThat(nativePoll(protocol,target,SECRET)).isEqualTo(accepted);
        assertThat(sources(target)).hasSize(1);
        String currentSecret=SECRET;
        if(operation.equals("ROUND_TRIP"))changeConfiguration(target,"MQTT",true,"1");
        else if(operation.equals("DISABLE"))changeConfiguration(target,protocol,false,"1");
        else if(operation.equals("CREDENTIAL"))currentSecret=asConsole(()->deviceCredentials.generate(project,target).plainSecret());
        else asConsole(()->{deviceService.delete(project,target);return true;});
        assertThat(sources(target)).hasSize(2);
        String reason=switch(operation){case "ROUND_TRIP"->"CONFIG_CHANGED";case "DISABLE"->"CONFIG_DISABLED";case "CREDENTIAL"->"CREDENTIAL_CHANGED";default->"DEVICE_DELETED";};
        assertThat(json.readTree(sources(target).getLast().eventText()).path("payload").path("reason").asString()).isEqualTo(reason);
        boolean credentialsRejected=operation.equals("CREDENTIAL"); // 热凭据缓存仍由当前平面/删除门禁拒绝为403。
        int denied=protocol.equals("HTTP")?(credentialsRejected?401:403):(credentialsRejected?CoAP.ResponseCode.UNAUTHORIZED.value:CoAP.ResponseCode.FORBIDDEN.value);
        assertThat(nativePoll(protocol,target,SECRET)).isEqualTo(denied);
        assertThat(sources(target)).hasSize(2);
        assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?",Boolean.class,target)).isFalse();
        if(!operation.equals("DELETE")) {
            if(!operation.equals("CREDENTIAL"))changeConfiguration(target,protocol,true,"2");
            String validSecret=currentSecret;
            // 保留真实认证失败退避；恢复后不通过清缓存或绕过预算制造成功。
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(250)).untilAsserted(()->assertThat(nativePoll(protocol,target,validSecret)).isEqualTo(accepted));
            assertThat(sources(target)).hasSize(3);
            assertThat(sources(target).getLast().event().eventType()).isEqualTo("device.online");
            assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?",Boolean.class,target)).isTrue();
        }
    }

    /** 原生请求的真实状态码；所有请求仍经过安全链与认证预算。 */
    int nativePoll(String protocol,UUID target,String credential)throws Exception {
        return protocol.equals("HTTP")?request("POST",PATH,"{}",null,Map.of("X-TC-Device-Key",identity(target),"X-TC-Device-Secret",credential)).statusCode()
            :send(PATH,"{}",identity(target),credential).getCode().value;
    }
    /** 已冻结Console HTTP管理路径的真实CAS。 */
    void changeConfiguration(UUID target,String protocol,boolean enabled,String version)throws Exception {
        var result=request("PUT","/api/v1/projects/"+project+"/devices/"+target+"/access-config",
            json.writeValueAsString(Map.of("protocol",protocol,"enabled",enabled,"expectedConfigVersion",version)),token(),Map.of());
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
    }
    /** 控制服务使用真实操作者上下文，事务仍由生产服务开启。 */
    <T>T asConsole(java.util.function.Supplier<T> action) {
        com.things.link.shared.tenant.TenantContext.set(new com.things.link.shared.tenant.TenantScope(tenant,project,account));
        try{return action.get();}finally{com.things.link.shared.tenant.TenantContext.clear();}
    }

    /** 原凭据认证已成功但返回在途，轮换提交后不能重新打开旧活动窗。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"HTTP","COAP"})
    void credentialChangeWhileAuthenticationInFlightCannotReopenActivity(String protocol)throws Exception {
        UUID target=protocol.equals("HTTP")?device:unbound;
        if(protocol.equals("HTTP"))assertThat(pollHttp(SECRET).statusCode()).isEqualTo(204);
        else assertThat(send(PATH,"{}",identity(target),SECRET).getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
        var authenticated=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        String[] keys=identity(target).split("/");
        org.mockito.Mockito.doAnswer(call->{
            Object result=call.callRealMethod();
            if(once.compareAndSet(false,true)) {
                authenticated.countDown();
                if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("认证在途夹具超时");
            }
            return result;
        }).when(authentication).authenticate(keys[0],keys[1],SECRET);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=executor.submit(()->protocol.equals("HTTP")?pollHttp(SECRET).statusCode():send(PATH,"{}",identity(target),SECRET).getCode().value);
            try {
                assertThat(authenticated.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                com.things.link.shared.tenant.TenantContext.set(new com.things.link.shared.tenant.TenantScope(tenant,project,account));
                try {deviceCredentials.generate(project,target);}
                finally {com.things.link.shared.tenant.TenantContext.clear();}
                assertThat(sources(target)).hasSize(2);
                release.countDown();
                assertThat(pending.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(protocol.equals("HTTP")?401:CoAP.ResponseCode.UNAUTHORIZED.value);
                assertThat(sources(target)).hasSize(2);
                assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?",Boolean.class,target)).isFalse();
            } finally {release.countDown();}
        }
    }

    private CoapResponse send(String path, String body, String deviceKey, String credential) throws Exception {
        Configuration configuration = Configuration.createStandardWithoutFile();
        DtlsConnectorConfig clientConfig = new DtlsConnectorConfig.Builder(configuration)
                // 客户端也只启用冻结套件：握手成功即证明服务端协商出的就是它（而不是被降级到别的套件）。
                .setAsList(DtlsConfig.DTLS_CIPHER_SUITES, CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                // Scandium 要求证书套件必须配一份身份材料，客户端也不例外；服务端把客户端证书模式设为 NONE，
                // 既不索要也不校验它——因此"用一份服务端并不信任的自签客户端证书仍能握手成功"恰好证明没有 mTLS 要求。
                .setCertificateIdentityProvider(new KeyManagerCertificateProvider(clientKeyManager(),
                        CertificateType.X_509))
                .setAdvancedCertificateVerifier(new StaticNewAdvancedCertificateVerifier.Builder()
                        // 只测试使用：信任仓库内的自签测试证书，生产按部署信任链校验。
                        .setTrustedCertificates(testCertificate())
                        .setSupportedCertificateTypes(List.of(CertificateType.X_509))
                        .build())
                .build();
        CoapEndpoint endpoint = new CoapEndpoint.Builder()
                .setConfiguration(configuration)
                // 客户端也必须认识 65001／65002，否则 65001 作为关键选项无法出现在请求里。
                .setOptionRegistry(new org.eclipse.californium.core.coap.option.MapBasedOptionRegistry(
                        org.eclipse.californium.core.coap.option.StandardOptionRegistry.getDefaultOptionRegistry(),
                        new org.eclipse.californium.core.coap.option.OpaqueOptionDefinition(
                                DeviceAccessCoapServer.DEVICE_KEY_OPTION, "TC-Device-Key"),
                        new org.eclipse.californium.core.coap.option.OpaqueOptionDefinition(
                                DeviceAccessCoapServer.CREDENTIAL_OPTION, "TC-Credential")))
                .setConnector(new DTLSConnector(clientConfig))
                .build();
        // 不用 CoapClient.Builder：它的可选字段（query）未设置时 create() 会 NPE；直接用 URI 构造更稳。
        CoapClient client = new CoapClient("coaps://127.0.0.1:" + server.boundPort() + path)
                .setEndpoint(endpoint);
        Request request = Request.newPost();
        request.setURI("coaps://127.0.0.1:" + server.boundPort() + path);
        request.setPayload(body.getBytes(StandardCharsets.UTF_8));
        request.getOptions().setContentFormat(MediaTypeRegistry.APPLICATION_JSON);
        if (deviceKey != null) {
            request.getOptions().addOption(new Option(DeviceAccessCoapServer.DEVICE_KEY_OPTION, deviceKey));
        }
        if (credential != null) {
            request.getOptions().addOption(new Option(DeviceAccessCoapServer.CREDENTIAL_OPTION, credential));
        }
        try {
            return client.advanced(request);
        } finally {
            endpoint.destroy();
            client.shutdown();
        }
    }

    /** @return 测试客户端身份；服务端不索要客户端证书，这里只为满足 Scandium 的配置校验 */
    private static javax.net.ssl.X509KeyManager clientKeyManager() {
        return new com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTlsContextFactory()
                .serverKeyManager(new org.springframework.core.io.FileSystemResource(
                                TLS_FIXTURE_DIRECTORY.resolve("device-access-test-cert.pem").toFile()),
                        new org.springframework.core.io.FileSystemResource(
                                TLS_FIXTURE_DIRECTORY.resolve("device-access-test-key.pem").toFile()), null);
    }

    /** @return 仓库内的仅测试用自签服务端证书 */
    private static X509Certificate testCertificate() throws Exception {
        try (var input = java.nio.file.Files.newInputStream(
                TLS_FIXTURE_DIRECTORY.resolve("device-access-test-cert.pem"))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

}
