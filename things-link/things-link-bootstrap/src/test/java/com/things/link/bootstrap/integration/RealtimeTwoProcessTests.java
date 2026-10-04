package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.integration.domain.RealtimeDeliveryRepository;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
@Import(com.things.link.bootstrap.integration.fixture.RealtimeNodeProcess.Pause.class)
class RealtimeTwoProcessTests extends RealtimeBrokerFixture {
    record Node(Process process,Path directory){}
    final List<Node> nodes=new ArrayList<>();Path run;
    @AfterEach void stopProcesses()throws Exception{
        for(var node:nodes){if(node.process().isAlive()){node.process().getOutputStream().write("quit\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));node.process().getOutputStream().flush();if(!node.process().waitFor(15,TimeUnit.SECONDS)){node.process().destroyForcibly();node.process().waitFor(10,TimeUnit.SECONDS);}}
            var cp=node.directory().resolve("classpath");if(Files.exists(cp))try(var files=Files.walk(cp)){for(var f:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);}
            try(var files=Files.list(node.directory())){for(var f:files.filter(f->!f.getFileName().toString().equals("node.log")).toList())Files.deleteIfExists(f);}
        }
        nodes.clear();
    }
    @Test void twoRealProcessesFenceStaleTokenAndRecoverKilledClaimOwner()throws Exception{
        run=Files.createDirectories(Path.of("../../logs/verify/s14-r8c-2c-2/process-"+Uuid7.generate()).toAbsolutePath().normalize());
        var t=issue(240);String topic=RealtimeMqttPublisher.topic(t.ticketId());
        try(var socket=wire(t,4,true);var keeper=Executors.newSingleThreadScheduledExecutor()){
            connected(socket);subscribed(socket,topic);var peer=realSessions.active().stream().filter(v->v.ticket().equals(t.ticketId())).findFirst().orElseThrow().peerIp();
            keeper.scheduleWithFixedDelay(()->tickets.authorizeMqtt(t.ticketId(),peer),0,5,TimeUnit.SECONDS);
            var a=start("a");var b=start("b");assertThat(a.process().pid()).isNotEqualTo(b.process().pid());
            UUID event=Uuid7.generate();admission.accept(update(event,"1.123456789012345678901"));var candidate=candidate(event);
            assertThat(command(a,"claim",candidate)).isEqualTo("ok");assertThat(command(b,"claim",candidate)).isEqualTo("empty");
            owner.update("UPDATE integ_realtime_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",candidate.id());
            assertThat(command(b,"dispatch",candidate)).isEqualTo("ok");assertThat(json.readTree(socket.message(topic)).path("eventId").asString()).isEqualTo(event.toString());
            assertThat(command(a,"send",candidate)).isEqualTo("ok");socket.socket.setSoTimeout(300);assertThatThrownBy(()->socket.message(topic)).isInstanceOf(java.net.SocketTimeoutException.class);socket.socket.setSoTimeout(10000);
            UUID next=Uuid7.generate();admission.accept(update(next,"2.123456789012345678901"));var crashed=candidate(next);assertThat(command(a,"claim",crashed)).isEqualTo("ok");a.process().destroyForcibly();assertThat(a.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
            assertThat(command(b,"claim",crashed)).isEqualTo("empty");owner.update("UPDATE integ_realtime_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",crashed.id());assertThat(command(b,"dispatch",crashed)).isEqualTo("ok");
            assertThat(json.readTree(socket.message(topic)).path("eventId").asString()).isEqualTo(next.toString());assertThat(owner.queryForObject("SELECT count(*) FROM integ_realtime_delivery WHERE project_id=? AND status='DELIVERED' AND attempts=2",Integer.class,project)).isEqualTo(2);
            keeper.shutdownNow();
        }
    }
    RealtimeDeliveryRepository.Candidate candidate(UUID event){return new RealtimeDeliveryRepository.Candidate(tenant,project,owner.queryForObject("SELECT id FROM integ_realtime_delivery WHERE project_id=? AND event_id=?",UUID.class,project,event));}
    Node start(String name)throws Exception{
        Path dir=Files.createDirectories(run.resolve(name)),cpRoot=Files.createDirectories(dir.resolve("classpath")),target=Files.createDirectories(cpRoot.resolve("com/things/link/bootstrap/integration/fixture"));
        try(var files=Files.list(Path.of("target/test-classes/com/things/link/bootstrap/integration/fixture"))){for(var file:files.filter(f->f.getFileName().toString().startsWith("RealtimeNodeProcess")).toList())Files.copy(file,target.resolve(file.getFileName()));}
        Files.copy(Path.of("src/test/resources/application-test.yml"),cpRoot.resolve("application-test.yml"));
        var cp=new ArrayList<String>();cp.add(cpRoot.toString());for(var entry:System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).split(java.io.File.pathSeparator))if(!entry.contains("/target/test-classes"))cp.add(entry);
        var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx384m","-Drealtime.fixture.directory="+dir,"-cp",String.join(java.io.File.pathSeparator,cp),"com.things.link.bootstrap.integration.fixture.RealtimeNodeProcess",
            "--server.port=0","--spring.flyway.enabled=false","--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+APP_ROLE,"--spring.datasource.password="+APP_ROLE_PASSWORD,
            "--spring.data.redis.host="+REDIS.getHost(),"--spring.data.redis.port="+REDIS.getMappedPort(6379),"--spring.kafka.admin.auto-create=false","--spring.kafka.listener.auto-startup=false",
            "--things-link.integration.realtime.enabled=true","--things-link.integration.realtime.mqtt-api.base-url="+apiBase(),"--things-link.integration.realtime.mqtt-api.api-key=tc-app-dev-publisher","--things-link.integration.realtime.mqtt-api.api-secret=dev-only-app-publisher-secret-do-not-use-in-production"));
        var process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();var node=new Node(process,dir);nodes.add(node);await(node,"ready",60);return node;
    }
    String command(Node node,String action,RealtimeDeliveryRepository.Candidate c)throws Exception{String id=Uuid7.generate().toString();node.process().getOutputStream().write((action+" "+id+" "+c.tenant()+" "+c.project()+" "+c.id()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));node.process().getOutputStream().flush();await(node,"done-"+id,20);return Files.readString(node.directory().resolve("done-"+id));}
    void await(Node node,String marker,int seconds){org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(seconds)).until(()->{assertThat(node.process().isAlive()).as("child log %s",node.directory()).isTrue();return Files.exists(node.directory().resolve(marker));});}
}
