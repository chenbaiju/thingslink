package com.things.link.bootstrap.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** ADR0192：真实设备Broker必须服从当前绑定，不能用HTTP回调通过替代缓存资格。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookMqttPlaneBrokerTests extends WebhookFixture {
    /** 回调指向本轮实际Spring HTTP端口。 */
    @Value("${things-link.security.broker-callback.secret}") String callbackSecret;
    /** 本类拥有的设备Broker，在应用仍可处理断连回调时先关闭。 */
    GenericContainer<?> broker;
    /** 仅供独占Broker指标只读查询的测试凭据。 */
    private static final String API_KEY="plane-test", API_SECRET="plane-test-only-secret";
    /** 设备测试凭据与公开API Key分离。 */
    private static final String DEVICE_SECRET="c4".repeat(32);
    /** 本轮合法设备路由，不接受载荷自报范围。 */
    String username;
    /** 当前控制、凭据、删除及路由均使用生产事务代理。 */
    @org.springframework.beans.factory.annotation.Autowired com.things.link.device.application.DeviceAccessControlService control;
    @org.springframework.beans.factory.annotation.Autowired com.things.link.device.application.DeviceCredentialService credentials;
    @org.springframework.beans.factory.annotation.Autowired com.things.link.device.application.DeviceService devices;
    @org.springframework.beans.factory.annotation.Autowired com.things.link.device.application.DeviceMqttDownlinkRoutePort routes;
    /** 只在专项中暂停真实认证方法已返回的不可变结果，不替换资格查询。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.things.link.device.infrastructure.emqx.EmqxAuthService authentication;
    /** 仓库测试证书仅用于独占节点，客户端仍验证CA及localhost主机名。 */
    private static final Path TLS=Path.of("../things-link-ingestion/src/test/resources/tls");
    /** 真实HTTP失败端点明确模拟踢出失败，不连接开发管理端口。 */
    private static final java.util.concurrent.atomic.AtomicInteger FAILED_KICKS=new java.util.concurrent.atomic.AtomicInteger();
    private static final com.sun.net.httpserver.HttpServer KICK_FAILURE=kickFailure();

    /** 独占HTTP服务只返回503，记录物理尝试但不暴露认证头。 */
    private static com.sun.net.httpserver.HttpServer kickFailure(){
        try{var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v5/clients/",exchange->{FAILED_KICKS.incrementAndGet();exchange.sendResponseHeaders(503,-1);exchange.close();});
            server.start();return server;
        }catch(java.io.IOException failure){throw new ExceptionInInitializerError(failure);}
    }
    /** 只将会话终止适配器指向独占失败端点，认证/ACL仍是真实Spring服务。 */
    @org.springframework.test.context.DynamicPropertySource
    static void failedKickConfiguration(org.springframework.test.context.DynamicPropertyRegistry registry){
        registry.add("things-link.device.emqx-session-api.base-url",()->"http://127.0.0.1:"+KICK_FAILURE.getAddress().getPort());
        registry.add("things-link.device.emqx-session-api.api-key",()->API_KEY);
        registry.add("things-link.device.emqx-session-api.api-secret",()->API_SECRET);
    }

    /** 仓库部署配置只替换回调地址和测试秘密；不得另造宽松认证/ACL。 */
    @BeforeAll void startBroker()throws Exception{
        org.testcontainers.Testcontainers.exposeHostPorts(port);
        String config=Files.readString(Path.of("../../deploy/emqx/base.hocon"))
                .replace("host.docker.internal:8080","host.testcontainers.internal:"+port)
                .replace("dev-only-broker-callback-secret-do-not-use-in-production",callbackSecret)
                +"\napi_key.bootstrap_file = \"/opt/emqx/etc/plane-test-api-keys\"\n";
        config += "\nlisteners.ssl.default.ssl_options.certfile = \"/opt/emqx/etc/route-cert.pem\"\n";
        config += "listeners.ssl.default.ssl_options.keyfile = \"/opt/emqx/etc/route-key.pem\"\n";
        broker=new GenericContainer<>("emqx/emqx:6.2.3").withExposedPorts(1883,8883,18083)
                .withCopyToContainer(Transferable.of(Files.readAllBytes(TLS.resolve("device-access-test-cert.pem")),0444),"/opt/emqx/etc/route-cert.pem")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(TLS.resolve("device-access-test-key.pem")),0444),"/opt/emqx/etc/route-key.pem")
                .withCopyToContainer(Transferable.of(config.getBytes(StandardCharsets.UTF_8),0444),"/opt/emqx/etc/base.hocon")
                .withCopyToContainer(Transferable.of((API_KEY+":"+API_SECRET+":administrator\n").getBytes(StandardCharsets.UTF_8),0444),"/opt/emqx/etc/plane-test-api-keys")
                .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200).withStartupTimeout(Duration.ofSeconds(90)));
        broker.start();
    }
    /** Broker产生回调依赖Spring存活，不能先关应用再发送停机断连。 */
    @AfterAll void stopBroker(){try{if(broker!=null)broker.stop();}finally{KICK_FAILURE.stop(0);}}
    /** 新设备隔离每例的凭据缓存与Broker会话。 */
    @BeforeEach void seedCredential(){
        owner.update("UPDATE dev_device SET status='OFFLINE' WHERE id=?",device);
        owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(gen_random_uuid(),?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'broker-plane')",tenant,project,device,DEVICE_SECRET);
        username=owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?",String.class,project)+"/"+owner.queryForObject("SELECT device_key FROM dev_device WHERE id=?",String.class,device);
    }
    /** 项目锁等待回调提交，仅清理本轮拥有的事实。 */
    @AfterEach void cleanup(){new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())).execute(s->{
        owner.queryForList("SELECT id FROM sys_project WHERE id=? FOR UPDATE",project);
        for(String table:List.of("dev_connection","dev_credential","dev_access_binding","sys_outbox_event"))owner.update("DELETE FROM "+table+" WHERE project_id=?",project);
        return null;
    });}
    /** 以运行中节点的配置为证据，不能只检查HOCON文本。 */
    @Test void actualAuthorizationAndAuthenticationCachesAreDisabled()throws Exception{
        for(String key:List.of("[authorization,cache,enable]","[authorization,node_cache,enable]","[authentication_settings,node_cache,enable]")){
            var result=broker.execInContainer("/opt/emqx/bin/emqx","eval","emqx:get_config("+key+").");
            assertThat(result.getExitCode()).as(key).isZero();assertThat(result.getStdout().trim()).as(key).isEqualTo("false");
        }
    }
    /** 热连接的合法Topic在切换后立即拒绝；新连接也不能沿用旧认证allow。 */
    @ParameterizedTest @ValueSource(ints={4,5})
    void warmSessionCannotResubscribePublishOrReconnectAfterNativeBinding(int version)throws Exception{
        String topic="tc/v1/"+username+"/down/command",up="tc/v1/"+username+"/up/property/report";
        long before=matched();
        try(var wire=wire(version,"warm")){
            assertThat(wire.connack.body()[1]).isZero();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND protocol='MQTT' AND disconnected_at IS NULL",Integer.class,device)).isEqualTo(1));
            assertThat(wire.subscribe(topic,1).body()[version==5?3:2]).isEqualTo((byte)1);
            wire.forbiddenPublish(up);assertThat(wire.read().header()).isEqualTo(0x40);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(matched()).isEqualTo(before+1));
            owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,heartbeat_seconds) VALUES(?,?,?,'TCP',30)",device,tenant,project);
            var denied=wire.subscribe(topic,1);assertThat(denied.header()).isEqualTo(0x90);assertThat(Byte.toUnsignedInt(denied.body()[denied.body().length-1])).isGreaterThanOrEqualTo(0x80);
            wire.forbiddenPublish(up);var ack=wire.read();assertThat(ack.header()).isEqualTo(0x40);
            if(version==5){assertThat(ack.body().length).isGreaterThanOrEqualTo(3);assertThat(Byte.toUnsignedInt(ack.body()[2])).isEqualTo(0x87);}
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(matched()).isEqualTo(before+1));
            try(var reconnect=wire(version,"fresh")){assertThat(reconnect.connack.body()[1]).isNotZero();}
        }
        // ADR0193拒绝旧身份回调；本用例直接构造绑定，不冒充6c-2c控制服务原子关闭。
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL",Integer.class,device)).isEqualTo(1));
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'",Integer.class,project)).isEqualTo(1);
        Path evidence=Files.createDirectories(Path.of("../../logs/verify/mqtt-plane-broker-"+device).toAbsolutePath().normalize());
        Files.writeString(evidence.resolve("result.json"),json.writeValueAsString(java.util.Map.of(
                "mqttVersion",version,"brokerContainer",broker.getContainerId(),"image",broker.getDockerImageName(),
                "matchedBefore",before,"matchedAfter",matched(),"nativeProtocol","TCP","oldLifecycleRejected",true,
                "newSubscriptionRejected",true,"newPublishRejected",true,"reconnectRejected",true)));
    }
    /** 原连接切回MQTT及禁用恢复后仍携带旧属性，只有重新认证的连接可获许可。 */
    @ParameterizedTest @ValueSource(ints={4,5})
    void originalConfigAttributesSurviveRoundTripAndDisableResume(int version)throws Exception{
        String down="tc/v1/"+username+"/down/command";
        try(var old=wire(version,"epoch-old")){
            assertThat(old.connack.body()[1]).isZero();
            assertThat(old.subscribe(down,1).body()[version==5?3:2]).isEqualTo((byte)1);
            owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'HTTP')",device,tenant,project);
            owner.update("UPDATE dev_access_binding SET protocol='MQTT',config_version=2 WHERE device_id=?",device);
            assertDeniedSubscription(old,down);
            try(var current=wire(version,"epoch-current")){
                assertThat(current.connack.body()[1]).isZero();
                assertThat(current.subscribe(down,1).body()[version==5?3:2]).isEqualTo((byte)1);
                await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(owner.queryForObject(
                    "SELECT count(*) FROM dev_connection WHERE device_id=? AND config_version=2 AND disconnected_at IS NULL",Integer.class,device)).isEqualTo(1));
                owner.update("UPDATE dev_access_binding SET enabled=false,config_version=3 WHERE device_id=?",device);
                owner.update("UPDATE dev_access_binding SET enabled=true,config_version=4 WHERE device_id=?",device);
                assertDeniedSubscription(old,down);assertDeniedSubscription(current,down);
                try(var fresh=wire(version,"epoch-fresh")){
                    assertThat(fresh.connack.body()[1]).isZero();
                    assertThat(fresh.subscribe(down,1).body()[version==5?3:2]).isEqualTo((byte)1);
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(owner.queryForObject(
                        "SELECT count(*) FROM dev_connection WHERE device_id=? AND config_version=4 AND disconnected_at IS NULL",Integer.class,device)).isEqualTo(1));
                }
                await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(owner.queryForObject(
                    "SELECT count(*) FROM dev_connection WHERE device_id=? AND config_version=4 AND disconnected_at IS NULL",Integer.class,device)).isZero());
                assertThat(owner.queryForObject("SELECT status FROM dev_device WHERE id=?",String.class,device))
                    .as("旧配置孤立行不能维持当前配置在线").isEqualTo("OFFLINE");
                try(var credentialOld=wire(version,"credential-old")){
                    assertThat(credentialOld.connack.body()[1]).isZero();
                    assertThat(credentialOld.subscribe(down,1).body()[version==5?3:2]).isEqualTo((byte)1);
                    owner.update("UPDATE dev_device SET credential_version=credential_version+1 WHERE id=?",device);
                    owner.update("UPDATE dev_access_binding SET config_version=5 WHERE device_id=?",device);
                    assertDeniedSubscription(credentialOld,down);
                }
            }
        }
        Path evidence=Files.createDirectories(Path.of("../../logs/verify/mqtt-config-broker-"+device).toAbsolutePath().normalize());
        Files.writeString(evidence.resolve("result.json"),json.writeValueAsString(java.util.Map.of(
            "mqttVersion",version,"brokerContainer",broker.getContainerId(),"image",broker.getDockerImageName(),
            "roundTripEpoch",2,"resumedEpoch",4,"oldAclRejected",true,"freshLifecycleClosed",true,"oldCredentialRejected",true)));
    }
    /** 四种实际传输与三类真实控制变更组成十二个独立场景。 */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> routeChanges(){
        return java.util.stream.Stream.of(4,5).flatMap(version->java.util.stream.Stream.of(false,true)
            .flatMap(tls->java.util.stream.Stream.of("ROUND_TRIP","DISABLE","CREDENTIAL")
                .map(change->org.junit.jupiter.params.provider.Arguments.of(version,tls,change))));
    }
    /** 删除也必须分别覆盖明文/TLS的MQTT3和MQTT5。 */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> transports(){
        return java.util.stream.Stream.of(4,5).flatMap(version->java.util.stream.Stream.of(false,true)
            .map(tls->org.junit.jupiter.params.provider.Arguments.of(version,tls)));
    }
    /** 真实控制事务关闭旧事实且踢出失败，旧活连接仍不能收到新配置路由。 */
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("routeChanges")
    void physicalDeliveryUsesCurrentConfigurationEvenWhenKickFails(int version,boolean tls,String change)throws Exception{
        String down="tc/v1/"+username+"/down/command",clientId="same-wire-"+device;
        try(var old=transport(version,tls,clientId,DEVICE_SECRET)){
            assertThat(old.connack.body()[1]).isZero();assertSubscribed(old,down);awaitActiveConnection();
            int attempts=FAILED_KICKS.get();String nextSecret=DEVICE_SECRET;
            if(change.equals("CREDENTIAL"))nextSecret=asConsole(()->credentials.generate(project,device)).plainSecret();
            else {
                var target=change.equals("ROUND_TRIP")?com.things.link.shared.message.TransportProtocol.HTTP
                    :com.things.link.shared.message.TransportProtocol.MQTT;
                asConsole(()->control.change(project,device,0,target,!change.equals("DISABLE")));
                assertThat(tx.execute(status->routes.lockCurrent(tenant,project,device)).isEmpty()).isTrue();
                try(var denied=transport(version,tls,clientId+"-denied",DEVICE_SECRET)){
                    assertThat(denied.connack.body()[1]).isNotZero();
                }
                asConsole(()->control.change(project,device,1,com.things.link.shared.message.TransportProtocol.MQTT,true));
            }
            assertThat(FAILED_KICKS.get()).as("实际HTTP踢出必须被503拒绝").isGreaterThan(attempts);
            assertDeniedSubscription(old,down);
            try(var current=transport(version,tls,clientId,nextSecret)){
                assertThat(current.connack.body()[1]).isZero();assertSubscribed(current,down);
                var route=tx.execute(status->routes.lockCurrent(tenant,project,device).orElseThrow());
                assertThat(route.configVersion()).isEqualTo(change.equals("CREDENTIAL")?1:2);
                byte[] payload=("current-route-"+device).getBytes(StandardCharsets.UTF_8);
                publishProbe(route.internalTopic(down),payload);
                assertThat(current.message(down)).containsExactly(payload);quietAndAlive(old);
                if(change.equals("CREDENTIAL"))try(var denied=transport(version,tls,clientId+"-revoked",DEVICE_SECRET)){
                    assertThat(denied.connack.body()[1]).isNotZero();
                }
            }
        }
    }
    /** 删除不产生新路由且旧凭据新连接被拒绝，物理踢出失败不恢复许可。 */
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("transports")
    void deletedDeviceHasNoNewRouteDespiteLiveOldSession(int version,boolean tls)throws Exception{
        try(var old=transport(version,tls,"delete-"+device,DEVICE_SECRET)){
            assertThat(old.connack.body()[1]).isZero();assertSubscribed(old,"tc/v1/"+username+"/down/command");
            awaitActiveConnection();int attempts=FAILED_KICKS.get();
            asConsole(()->{devices.delete(project,device);return null;});
            assertThat(FAILED_KICKS.get()).isGreaterThan(attempts);
            assertThat(tx.execute(status->routes.lockCurrent(tenant,project,device)).isEmpty()).isTrue();
            try(var denied=transport(version,tls,"delete-new-"+device,DEVICE_SECRET)){
                assertThat(denied.connack.body()[1]).isNotZero();
            }
            quietAndAlive(old);
        }
    }
    /** 四个启用设备监听器均在实际节点上读回服务器属性挂载点。 */
    @Test void everyEnabledListenerUsesServerAssignedMountpoint()throws Exception{
        for(String type:List.of("tcp","ssl","ws","wss")){
            var result=broker.execInContainer("/opt/emqx/bin/emqx","eval",
                "emqx:get_config([listeners,"+type+",default,mountpoint]).");
            assertThat(result.getExitCode()).isZero();
            assertThat(result.getStdout().trim()).isEqualTo("<<\"${client_attrs.tc_auth_mountpoint}\">>");
        }
    }
    /** 只在认证Console线程中设置范围，角色与写许可仍由生产查询验证。 */
    <T>T asConsole(java.util.function.Supplier<T> action){
        com.things.link.shared.tenant.TenantContext.set(new com.things.link.shared.tenant.TenantScope(tenant,project,account));
        try{return action.get();}finally{com.things.link.shared.tenant.TenantContext.clear();}
    }
    /** 等真实生命周期回调提交后才触发控制操作，保证踢出失败针对真实有效ID。 */
    void awaitActiveConnection(){await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(owner.queryForObject(
        "SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL",Integer.class,device)).isEqualTo(1));}
    /** SUBACK明确授予QoS1，不将连接成功当成订阅成功。 */
    void assertSubscribed(PublicMqttWire wire,String topic)throws Exception{
        var ack=wire.subscribe(topic,1);assertThat(ack.header()).isEqualTo(0x90);
        assertThat(ack.body()[ack.body().length-1]).isEqualTo((byte)1);
    }
    /** 空闲只观察第一个字节，避免部分报文超时被误判为完全无消息。 */
    void quietAndAlive(PublicMqttWire wire)throws Exception{
        wire.socket.setSoTimeout(700);
        org.assertj.core.api.Assertions.assertThatThrownBy(wire.in::readUnsignedByte).isInstanceOf(java.net.SocketTimeoutException.class);
        wire.socket.setSoTimeout(5000);wire.send(0xC0,new byte[0]);
        var pong=wire.read();assertThat(pong.header()).isEqualTo(0xD0);assertThat(pong.body()).isEmpty();
    }
    /** 本片观察已冻结路由的传输机制；业务发布器分别由2b/2c与最终组合负责。 */
    void publishProbe(String topic,byte[] payload)throws Exception{
        var response=client.send(HttpRequest.newBuilder(URI.create("http://"+broker.getHost()+":"+broker.getMappedPort(18083)+"/api/v5/publish"))
            .header("Authorization","Basic "+Base64.getEncoder().encodeToString((API_KEY+":"+API_SECRET).getBytes(StandardCharsets.UTF_8)))
            .version(java.net.http.HttpClient.Version.HTTP_1_1).header("Content-Type","application/json").timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of("topic",topic,"qos",1,"retain",false,
                "payload_encoding","base64","payload",Base64.getEncoder().encodeToString(payload))))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200,299);
    }
    /** TLS显式信任本轮测试证书并校验localhost，不使用信任全部的管理器。 */
    PublicMqttWire transport(int version,boolean tls,String clientId,String password)throws Exception{
        return transport(version,tls,clientId,username,password,true);
    }
    /** 持久恢复保留原用户名/Client ID和clean语义，不把不同设备身份藏在共享字段中。 */
    PublicMqttWire transport(int version,boolean tls,String clientId,String user,String password,boolean clean)throws Exception{
        if(!tls)return new PublicMqttWire(broker.getHost(),broker.getMappedPort(1883),version,clientId,user,password,clean);
        var trust=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());trust.load(null);
        try(var input=Files.newInputStream(TLS.resolve("device-access-test-cert.pem"))){
            trust.setCertificateEntry("owned",java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var managers=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());managers.init(trust);
        var context=javax.net.ssl.SSLContext.getInstance("TLS");context.init(null,managers.getTrustManagers(),null);
        var socket=(javax.net.ssl.SSLSocket)context.getSocketFactory().createSocket("localhost",broker.getMappedPort(8883));
        try{var parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);
            socket.setSoTimeout(5000);socket.startHandshake();
            return new PublicMqttWire(socket,version,clientId,user,password,clean,false);
        }catch(Exception|AssertionError failure){socket.close();throw failure;}
    }
    /** 同配置恢复订阅和离线队列，跨配置同Client ID必须是没有旧订阅的新会话。 */
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("transports")
    void persistentSessionResumesOnlyWithinItsConfiguration(int version,boolean tls)throws Exception{
        String clientId="persistent-"+device,down="tc/v1/"+username+"/down/command";
        var original=tx.execute(status->routes.lockCurrent(tenant,project,device).orElseThrow());
        try(var first=transport(version,tls,clientId,username,DEVICE_SECRET,false)){
            assertThat(first.connack.body()[0]).isZero();assertThat(first.connack.body()[1]).isZero();
            assertSubscribed(first,down);first.disconnectWithLongExpiry();
        }
        byte[] firstMessage=("same-config-"+device).getBytes(StandardCharsets.UTF_8);
        publishProbe(original.internalTopic(down),firstMessage);
        try(var restored=transport(version,tls,clientId,username,DEVICE_SECRET,false)){
            assertThat(restored.connack.body()[0]).as("同配置必须恢复会话").isEqualTo((byte)1);
            assertThat(restored.message(down)).containsExactly(firstMessage);
            restored.disconnectWithLongExpiry();
        }
        publishProbe(original.internalTopic(down),("old-queued-"+device).getBytes(StandardCharsets.UTF_8));
        asConsole(()->control.change(project,device,0,com.things.link.shared.message.TransportProtocol.HTTP,true));
        asConsole(()->control.change(project,device,1,com.things.link.shared.message.TransportProtocol.MQTT,true));
        try(var changed=transport(version,tls,clientId,username,DEVICE_SECRET,false)){
            assertThat(changed.connack.body()[0]).as("跨配置不能继承会话").isZero();
            assertThat(changed.connack.body()[1]).isZero();quietAndAlive(changed);
            var route=tx.execute(status->routes.lockCurrent(tenant,project,device).orElseThrow());
            assertThat(route.configVersion()).isEqualTo(2);
            publishProbe(route.internalTopic(down),"before-resubscribe".getBytes(StandardCharsets.UTF_8));
            quietAndAlive(changed);assertSubscribed(changed,down);
            byte[] current=("new-config-"+device).getBytes(StandardCharsets.UTF_8);
            publishProbe(route.internalTopic(down),current);
            assertThat(changed.message(down)).containsExactly(current);quietAndAlive(changed);
            changed.disconnectWithLongExpiry();
        }
    }
    /** 不同设备复用原Client ID不接管另一设备持久订阅，其原设备仍能恢复自己队列。 */
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("transports")
    void persistentSessionCannotBeInheritedByAnotherDevice(int version,boolean tls)throws Exception{
        String clientId="cross-device-"+device,down="tc/v1/"+username+"/down/command";
        var original=tx.execute(status->routes.lockCurrent(tenant,project,device).orElseThrow());
        try(var first=transport(version,tls,clientId,username,DEVICE_SECRET,false)){
            assertThat(first.connack.body()[1]).isZero();assertSubscribed(first,down);first.disconnectWithLongExpiry();
        }
        byte[] queued=("original-device-"+device).getBytes(StandardCharsets.UTF_8);
        publishProbe(original.internalTopic(down),queued);
        owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(gen_random_uuid(),?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'cross-device')",
            tenant,project,unbound,DEVICE_SECRET);
        var otherRoute=tx.execute(status->routes.lockCurrent(tenant,project,unbound).orElseThrow());
        String otherUser=otherRoute.projectKey()+"/"+otherRoute.deviceKey(),otherDown="tc/v1/"+otherUser+"/down/command";
        try(var other=transport(version,tls,clientId,otherUser,DEVICE_SECRET,false)){
            assertThat(other.connack.body()[0]).as("跨设备必须建立新会话").isZero();assertThat(other.connack.body()[1]).isZero();
            quietAndAlive(other);assertSubscribed(other,otherDown);
            byte[] current=("other-device-"+unbound).getBytes(StandardCharsets.UTF_8);
            publishProbe(otherRoute.internalTopic(otherDown),current);assertThat(other.message(otherDown)).containsExactly(current);
            try(var restored=transport(version,tls,clientId,username,DEVICE_SECRET,false)){
                assertThat(restored.connack.body()[0]).isEqualTo((byte)1);
                assertThat(restored.message(down)).containsExactly(queued);quietAndAlive(other);
                restored.disconnectWithLongExpiry();
            }
            other.disconnectWithLongExpiry();
        }
    }
    /** 已完成旧认证但响应在途时切换配置，恢复的旧持久订阅也不能收到新命名空间。 */
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("routeChanges")
    void inFlightAuthenticationCannotUpgradeOldSubscription(int version,boolean tls,String change)throws Exception{
        String id="inflight-"+device,down="tc/v1/"+username+"/down/command";
        try(var original=transport(version,tls,id,username,DEVICE_SECRET,false)){
            assertThat(original.connack.body()[1]).isZero();assertSubscribed(original,down);original.disconnectWithLongExpiry();
        }
        var captured=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation->{
            Object result=invocation.callRealMethod();
            if(once.compareAndSet(false,true)){
                captured.countDown();
                if(!release.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("在途认证测试未及时释放");
            }
            return result;
        }).when(authentication).authenticateIdentity(org.mockito.ArgumentMatchers.eq(username),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(id));
        var staleReference=new java.util.concurrent.atomic.AtomicReference<PublicMqttWire>();
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var pending=executor.submit(()->{var wire=transport(version,tls,id,username,DEVICE_SECRET,false);staleReference.set(wire);return wire;});
            String nextSecret=DEVICE_SECRET;
            try{
                assertThat(captured.await(2,java.util.concurrent.TimeUnit.SECONDS)).as("必须在真实资格读取完成后暂停响应").isTrue();
                if(change.equals("CREDENTIAL"))nextSecret=asConsole(()->credentials.generate(project,device)).plainSecret();
                else{
                    var target=change.equals("ROUND_TRIP")?com.things.link.shared.message.TransportProtocol.HTTP:com.things.link.shared.message.TransportProtocol.MQTT;
                    asConsole(()->control.change(project,device,0,target,!change.equals("DISABLE")));
                    asConsole(()->control.change(project,device,1,com.things.link.shared.message.TransportProtocol.MQTT,true));
                }
            }finally{release.countDown();}
            try(var stale=pending.get(8,java.util.concurrent.TimeUnit.SECONDS);
                var fresh=transport(version,tls,id,username,nextSecret,false)){
                assertThat(stale.connack.body()[1]).isZero();
                assertThat(stale.connack.body()[0]).as("延迟旧身份实际恢复旧订阅").isEqualTo((byte)1);
                assertThat(fresh.connack.body()[1]).isZero();assertThat(fresh.connack.body()[0]).isZero();assertSubscribed(fresh,down);
                var route=tx.execute(status->routes.lockCurrent(tenant,project,device).orElseThrow());
                assertThat(route.configVersion()).isEqualTo(change.equals("CREDENTIAL")?1:2);
                byte[] payload=("after-inflight-"+device).getBytes(StandardCharsets.UTF_8);
                publishProbe(route.internalTopic(down),payload);assertThat(fresh.message(down)).containsExactly(payload);quietAndAlive(stale);
            }
        }finally{release.countDown();if(staleReference.get()!=null)staleReference.get().close();}
    }
    /** 五个线协议载荷分支分别跨MQTT3/5、明文/TLS验证真实生产发布器。 */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> publisherTransports(){
        return java.util.stream.Stream.of(4,5).flatMap(version->java.util.stream.Stream.of(false,true)
            .flatMap(tls->java.util.stream.Stream.of("COMMAND","PROPERTY_SET","TOPOLOGY","CONFIG","MODBUS")
                .map(branch->org.junit.jupiter.params.provider.Arguments.of(version,tls,branch))));
    }
    /** 业务消息编码及物理发送真实执行，缺路由/持事务均拒绝且不产生裸Topic回退。 */
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("publisherTransports")
    void everyCommonPublisherBranchUsesFrozenRoute(int version,boolean tls,String branch)throws Exception{
        owner.update("UPDATE dev_type SET device_kind='GATEWAY',access_protocol=? WHERE id=?",
            branch.equals("MODBUS")||branch.equals("CONFIG")?"MODBUS_RTU_CLOUD_GATEWAY":"STANDARD_GATEWAY",type);
        var baseline=tx.execute(status->routes.lockCurrent(tenant,project,device).orElseThrow());
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try{
            var publisher=new com.things.link.ingestion.infrastructure.EmqxHttpCommandPublisher(json,
                "http://"+broker.getHost()+":"+broker.getMappedPort(18083),API_KEY,API_SECRET,Duration.ofSeconds(2),Duration.ofSeconds(5),
                new com.things.link.ingestion.infrastructure.EmqxDispatchMetrics(
                    new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("registry",registry))
                        .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)));
            var probe=publisherProbe(publisher,branch,baseline);
            String id="publisher-"+device;
            try(var old=transport(version,tls,id,DEVICE_SECRET)){
                assertThat(old.connack.body()[1]).isZero();assertSubscribed(old,probe.topic());awaitActiveConnection();
                asConsole(()->control.change(project,device,0,com.things.link.shared.message.TransportProtocol.MQTT,false));
                asConsole(()->control.change(project,device,1,com.things.link.shared.message.TransportProtocol.MQTT,true));
                try(var current=transport(version,tls,id,DEVICE_SECRET)){
                    assertThat(current.connack.body()[1]).isZero();assertSubscribed(current,probe.topic());
                    var route=tx.execute(status->routes.lockCurrent(tenant,project,device).orElseThrow());
                    assertThat(route.configVersion()).isEqualTo(2);
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->probe.send().accept(null))
                        .isInstanceOf(com.things.link.ingestion.application.InvalidDownlinkMessageException.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->tx.executeWithoutResult(status->probe.send().accept(route)))
                        .isInstanceOf(IllegalStateException.class);
                    quietAndAlive(current);quietAndAlive(old);
                    probe.send().accept(route);
                    assertThat(json.readTree(current.message(probe.topic()))).isEqualTo(json.readTree(probe.expected()));
                    quietAndAlive(old);
                }
            }
        }finally{registry.close();}
    }
    /** 专项只验证发布器与服务器路由组合；持久业务准入与失败回滚由2b/2c真实事务证据负责。 */
    PublisherProbe publisherProbe(com.things.link.ingestion.infrastructure.EmqxHttpCommandPublisher publisher,String branch,
            com.things.link.device.application.DeviceMqttDownlinkRoute route){
        var request=com.things.link.shared.id.Uuid7.generate();String prefix="tc/v1/"+username+"/down/";
        if(branch.equals("COMMAND")||branch.equals("PROPERTY_SET")){
            boolean property=branch.equals("PROPERTY_SET");
            var dispatch=new com.things.link.shared.message.DeviceCommandDispatch(com.things.link.shared.id.Uuid7.generate(),tenant,project,
                request,com.things.link.shared.id.Uuid7.generate(),2,device,route.deviceKey(),device,route.deviceKey(),route.projectKey(),
                property?com.things.link.shared.message.DeviceCommandDispatch.OperationType.PROPERTY_SET:com.things.link.shared.message.DeviceCommandDispatch.OperationType.COMMAND,
                property?null:"start","{\"value\":9007199254740993}",java.time.Instant.now().plusSeconds(30),"broker-qualification");
            var expected=json.createObjectNode().put("targetDeviceKey",route.deviceKey()).put("attempt",2);
            if(property){expected.put("requestId",request.toString());expected.set("properties",json.readTree(dispatch.inputJson()));}
            else{expected.put("commandKey","start");expected.set("input",json.readTree(dispatch.inputJson()));}
            return new PublisherProbe(prefix+(property?"property/set":"command/"+request),json.writeValueAsBytes(expected),r->publisher.publish(dispatch,r));
        }
        if(branch.equals("CONFIG")){
            var push=new com.things.link.shared.message.DeviceConfigPush(tenant,project,device,route.projectKey(),route.deviceKey(),
                com.things.link.shared.message.DeviceConfigPush.CONFIG_TYPE,3,List.of());
            return new PublisherProbe(prefix+"config",json.writeValueAsBytes(java.util.Map.of("configType",push.configType(),"version",3,"points",List.of())),r->publisher.publishConfig(push,r));
        }
        if(branch.equals("TOPOLOGY")){
            var reply=new com.things.link.shared.message.TopologyReplyMessage(request,tenant,project,device,route.projectKey(),route.deviceKey(),
                "child",com.things.link.shared.message.TopologyReplyMessage.Status.SUCCESS,null,null,java.time.Instant.now(),"broker-qualification");
            return new PublisherProbe(prefix+"topo/reply",json.writeValueAsBytes(java.util.Map.of("requestId",request.toString(),"subDeviceKey","child","status","SUCCESS")),r->publisher.publishTopologyReply(reply,r));
        }
        var modbus=new com.things.link.shared.message.ModbusRequest(request,tenant,project,device,route.projectKey(),route.deviceKey(),1,"03",10,2);
        return new PublisherProbe(prefix+"modbus/request",json.writeValueAsBytes(java.util.Map.of("requestId",request.toString(),"slaveAddress",1,
            "functionCode",modbus.functionCode(),"registerAddress",10,"quantity",2)),r->publisher.publishModbusRequest(modbus,r));
    }
    /** 固定原Topic及可观察正文，不承载数据库成功或设备执行成功断言。 */
    record PublisherProbe(String topic,byte[] expected,java.util.function.Consumer<com.things.link.device.application.DeviceMqttDownlinkRoute> send){}
    /** MQTT3/5均明确SUBACK拒绝，不能仅凭未收到业务数据判断授权结果。 */
    void assertDeniedSubscription(PublicMqttWire wire,String topic)throws Exception{
        var response=wire.subscribe(topic,1);assertThat(response.header()).isEqualTo(0x90);
        assertThat(Byte.toUnsignedInt(response.body()[response.body().length-1])).isGreaterThanOrEqualTo(0x80);
    }
    /** 与应用票据工具共用最小线协议，但使用真实设备用户名及凭据。 */
    PublicMqttWire wire(int version,String suffix)throws Exception{return new PublicMqttWire(broker.getHost(),broker.getMappedPort(1883),version,"plane-"+device+"-"+suffix,username,DEVICE_SECRET,true);}
    /** Broker规则匹配计数证明拒绝发布没有进入durable republish。 */
    long matched()throws Exception{
        var response=client.send(HttpRequest.newBuilder(URI.create("http://"+broker.getHost()+":"+broker.getMappedPort(18083)+"/api/v5/rules/tc_durable_uplink/metrics"))
                .header("Authorization","Basic "+Base64.getEncoder().encodeToString((API_KEY+":"+API_SECRET).getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var matched=json.readTree(response.body()).path("metrics").path("matched");assertThat(matched.isNumber()).as("rule metrics: %s",response.body()).isTrue();return matched.asLong();
    }
}
