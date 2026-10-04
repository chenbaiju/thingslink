package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.integration.domain.*;
import com.things.link.integration.infrastructure.EmqxRealtimePublisher;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.realtime.enabled=true","things-link.security.broker-callback.secret=realtime-broker-test-only-secret"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class RealtimeBrokerFixture extends RealtimeEventFixture {
    static final String API_KEY="public-realtime-test",API_SECRET="public-realtime-test-secret";
    GenericContainer<?> broker;EmqxRealtimePublisher realPublisher;com.things.link.integration.infrastructure.EmqxRealtimeSessions realSessions;
    @MockitoBean RealtimeMqttPublisher publisher;
    @MockitoBean RealtimeMqttSessions sessions;
    @Autowired RealtimeDeliveryDispatcher dispatcher;
    @Autowired RealtimeDeliveryState state;
    @BeforeAll void startBroker()throws Exception{
        org.testcontainers.Testcontainers.exposeHostPorts(port);
        String config=Files.readString(Path.of("../../deploy/emqx-app/base.hocon"))
            .replace("host.docker.internal:8080","host.testcontainers.internal:"+port)
            .replace("dev-only-broker-callback-secret-do-not-use-in-production","realtime-broker-test-only-secret")
            .replace("/opt/emqx/etc/dev-api-keys","/opt/emqx/etc/test-api-keys");
        broker=new GenericContainer<>("emqx/emqx:6.3.1@sha256:5ecbf93d04e34aaf4096af074e93b773d0838c8ea668cfabdec7976b272d9606")
            .withExposedPorts(1883,18083)
            .withCopyToContainer(Transferable.of(config.getBytes(StandardCharsets.UTF_8),0444),"/opt/emqx/etc/base.hocon")
            .withCopyToContainer(Transferable.of((API_KEY+":"+API_SECRET+":administrator\n"+Files.readString(Path.of("../../deploy/emqx-app/dev-api-keys"))).getBytes(StandardCharsets.UTF_8),0444),"/opt/emqx/etc/test-api-keys")
            .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200).withStartupTimeout(Duration.ofSeconds(90)));
        broker.start();realPublisher=new EmqxRealtimePublisher(apiBase(),"tc-app-dev-publisher","dev-only-app-publisher-secret-do-not-use-in-production");realSessions=new com.things.link.integration.infrastructure.EmqxRealtimeSessions(apiBase(),"tc-app-dev-sessions","dev-only-app-session-secret-do-not-use-in-production");
    }
    @AfterAll void stopBroker(){if(realSessions!=null)realSessions.close();if(realPublisher!=null)realPublisher.close();if(broker!=null)broker.stop();}
    @BeforeEach void delegatePublisher(){when(sessions.configured()).thenReturn(true);when(sessions.active()).thenAnswer(i->realSessions.active());when(sessions.disconnect(any())).thenAnswer(i->realSessions.disconnect(i.getArgument(0)));when(publisher.configured()).thenReturn(true);when(publisher.publish(any(),any(),any())).thenAnswer(i->realPublisher.publish(i.getArgument(0),i.getArgument(1),i.getArgument(2)));}
    String apiBase(){return "http://"+broker.getHost()+":"+broker.getMappedPort(18083);}
    HttpResponse<String> brokerApi(String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create(apiBase()+"/api/v5"+path)).header("Authorization","Basic "+Base64.getEncoder().encodeToString((API_KEY+":"+API_SECRET).getBytes(StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    RealtimeTicketService.Issued issue(int seconds)throws Exception{
        var scope=new RealtimeTicketParser().parse(json.writeValueAsBytes(Map.of("protocol","MQTT","eventTypes",List.of("device.property.report"),"devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value"))))));
        return tickets.issue(new RealtimeIdentity(RealtimeIdentity.Kind.CONSOLE,tenant,project,0,account,account,null,Instant.now().plusSeconds(seconds)),scope,"127.0.0.1");
    }
    PublicMqttWire wire(RealtimeTicketService.Issued t,int version,boolean clean)throws Exception{return new PublicMqttWire(broker.getHost(),broker.getMappedPort(1883),version,"tc-app-v1-"+t.ticketId(),"tc-app-v1:"+t.ticketId(),t.credential(),clean);}
    void connected(PublicMqttWire w){assertThat(w.connack.header()).isEqualTo(0x20);assertThat(w.connack.body()[1]).isZero();}
    void subscribed(PublicMqttWire w,String topic)throws Exception{var ack=w.subscribe(topic,1);assertThat(ack.header()).isEqualTo(0x90);assertThat(ack.body()[ack.body().length-1]).isEqualTo((byte)1);}
    void dispatchAll(){for(var c:state.candidates())if(c.project().equals(project))dispatcher.dispatch(c);}
}
