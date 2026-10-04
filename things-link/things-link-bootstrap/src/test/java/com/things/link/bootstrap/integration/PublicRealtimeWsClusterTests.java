package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.ingestion.infrastructure.RealtimeKafkaConsumer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.kafka.KafkaContainer;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.realtime.enabled=true","spring.kafka.listener.auto-startup=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PublicRealtimeWsClusterTests extends RealtimeEventFixture {
    static final KafkaContainer KAFKA=new KafkaContainer("apache/kafka:4.1.0").withStartupTimeout(Duration.ofSeconds(90));static{KAFKA.start();}
    @DynamicPropertySource static void kafkaProperties(DynamicPropertyRegistry p){p.add("spring.kafka.bootstrap-servers",KAFKA::getBootstrapServers);}
    @Autowired @Qualifier("realtimeIngressKafkaListenerContainerFactory") ConcurrentKafkaListenerContainerFactory<Object,Object> factory;
    @Autowired RealtimeKafkaConsumer consumer;
    @Autowired DeviceIngestionService devices;
    record Node(Process process,Path directory,int port){}
    final List<Node> nodes=new ArrayList<>();final List<PublicRealtimeWsTests.Socket> sockets=new ArrayList<>();Path run;
    @Override String modelSnapshot(){return "{\"properties\":{\"value\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"},\"empty\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"}},\"events\":{},\"commands\":{}}";}
    @AfterEach void stopProcesses()throws Exception{
        for(var socket:sockets)try{socket.close();}catch(RuntimeException ignored){}
        for(var node:nodes){if(node.process().isAlive()){node.process().getOutputStream().write("quit\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));node.process().getOutputStream().flush();if(!node.process().waitFor(15,TimeUnit.SECONDS)){node.process().destroyForcibly();node.process().waitFor(10,TimeUnit.SECONDS);}}
            var cp=node.directory().resolve("classpath");if(Files.exists(cp))try(var files=Files.walk(cp)){for(var f:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);}
            Files.deleteIfExists(node.directory().resolve("ready"));
        }
    }
    RealtimeTicketService.Issued issue()throws Exception{return tickets.issue(new RealtimeIdentity(RealtimeIdentity.Kind.CONSOLE,tenant,project,0,account,account,null,Instant.now().plusSeconds(240)),new RealtimeTicketParser().parse(json.writeValueAsBytes(Map.of("protocol","WS","eventTypes",List.of("device.property.report"),"devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value")))))),"127.0.0.1");}
    PublicRealtimeWsTests.Socket open(Node node,RealtimeTicketService.Issued ticket)throws Exception{var socket=new PublicRealtimeWsTests.Socket();sockets.add(socket);client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).subprotocols("tc-realtime-v1",ticket.credential()).buildAsync(URI.create("ws://127.0.0.1:"+node.port()+"/api/open/v1/realtime/ws"),socket).get(15,TimeUnit.SECONDS);return socket;}
    @Test @SuppressWarnings("unchecked") void realKafkaSourceRoutesToTwoOwnersAndSurvivesOwnerDeathWithFreshTicketAndRest()throws Exception{
        run=Files.createDirectories(Path.of("../../logs/verify/s14-r8c-3b/process-"+Uuid7.generate()).toAbsolutePath().normalize());
        var a=start("a");var b=start("b");assertThat(a.process().pid()).isNotEqualTo(b.process().pid());
        var ta=issue();var tb=issue();var sa=open(a,ta);var sb=open(b,tb);assertThat(sa.next()).contains("READY");assertThat(sb.next()).contains("READY");
        String ownerA=owner.queryForObject("SELECT instance_id FROM integ_realtime_ticket WHERE id=?",String.class,ta.ticketId()),ownerB=owner.queryForObject("SELECT instance_id FROM integ_realtime_ticket WHERE id=?",String.class,tb.ticketId());assertThat(ownerA).isNotEqualTo(ownerB).isNotEqualTo(tickets.instanceId());
        String topic="tc.device.realtime";try(var admin=AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers()))){admin.createTopics(List.of(new NewTopic(topic,1,(short)1),new NewTopic("tc.dlq",1,(short)1))).all().get(20,TimeUnit.SECONDS);}
        var container=factory.createContainer(topic);container.getContainerProperties().setGroupId(RealtimeKafkaConsumer.REALTIME_CONSUMER_GROUP);container.getContainerProperties().setMessageListener((MessageListener<Object,Object>)record->consumer.consume((ConsumerRecord<String,DeviceRealtimeUpdate>)(Object)record));container.start();
        try{
            UUID first=Uuid7.generate();String precise="1.12345678901234567890123456789";source(first,precise);
            for(var socket:List.of(sa,sb))assertThat(socket.next()).contains(first.toString(),precise);
            a.process().destroyForcibly();assertThat(a.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->open(b,ta)).hasCauseInstanceOf(WebSocketHandshakeException.class);
            var fresh=issue();var recovered=open(b,fresh);assertThat(recovered.next()).contains("READY");assertThat(recovered.frames.poll(400,TimeUnit.MILLISECONDS)).isNull();
            String body=json.writeValueAsString(Map.of("devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value")))));
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+b.port()+"/api/open/v1/devices/current-values/query")).timeout(Duration.ofSeconds(10)).header("X-Api-Key",secret).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(response.body()).contains(precise);
            UUID second=Uuid7.generate();source(second,"2.9876543210987654321");assertThat(sb.next()).contains(second.toString());assertThat(recovered.next()).contains(second.toString());
            owner.update("DELETE FROM sys_project_member WHERE project_id=?",project);assertThat(sb.closed.get(15,TimeUnit.SECONDS)).isEqualTo(1008);assertThat(recovered.closed.get(15,TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(owner.queryForObject("SELECT instance_id FROM integ_realtime_ticket WHERE id=?",String.class,ta.ticketId())).isEqualTo(ownerA);
        }finally{container.stop();}
    }
    void source(UUID id,String value){tx.execute(s->{rls.establish(tenant,project);var values=Map.<String,Object>of("value",new BigDecimal(value));Instant now=Instant.now();var context=devices.validateReportedProperties(tenant,project,device,"1.0.0",now,values);devices.mergeReportedProperties(context,project,device,values,now,id,"ws-cluster-source");return null;});}
    Node start(String name)throws Exception{
        Path dir=Files.createDirectories(run.resolve(name)),cpRoot=Files.createDirectories(dir.resolve("classpath")),target=Files.createDirectories(cpRoot.resolve("com/things/link/bootstrap/integration/fixture"));
        try(var files=Files.list(Path.of("target/test-classes/com/things/link/bootstrap/integration/fixture"))){for(var file:files.filter(f->f.getFileName().toString().startsWith("RealtimeWsNodeProcess")).toList())Files.copy(file,target.resolve(file.getFileName()));}
        Files.copy(Path.of("src/test/resources/application-test.yml"),cpRoot.resolve("application-test.yml"));var cp=new ArrayList<String>();cp.add(cpRoot.toString());for(var entry:System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).split(java.io.File.pathSeparator))if(!entry.contains("/target/test-classes"))cp.add(entry);
        var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx384m","-Drealtime.fixture.directory="+dir,"-cp",String.join(java.io.File.pathSeparator,cp),"com.things.link.bootstrap.integration.fixture.RealtimeWsNodeProcess","--server.port=0","--spring.flyway.enabled=false","--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+APP_ROLE,"--spring.datasource.password="+APP_ROLE_PASSWORD,"--spring.data.redis.host="+REDIS.getHost(),"--spring.data.redis.port="+REDIS.getMappedPort(6379),"--spring.kafka.bootstrap-servers="+KAFKA.getBootstrapServers(),"--spring.kafka.admin.auto-create=false","--spring.kafka.listener.auto-startup=false","--things-link.integration.api-key.enabled=true","--things-link.integration.realtime.enabled=true"));
        var process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();var initial=new Node(process,dir,0);nodes.add(initial);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(60)).until(()->{assertThat(process.isAlive()).as("child log %s",dir).isTrue();return Files.exists(dir.resolve("ready"));});var node=new Node(process,dir,Integer.parseInt(Files.readString(dir.resolve("ready"))));nodes.set(nodes.indexOf(initial),node);return node;
    }
}
