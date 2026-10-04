package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.integration.infrastructure.websocket.PublicRealtimeWsHandler;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.realtime.enabled=true","things-link.integration.realtime.ws.allowed-origins=https://console.example.com"})
class PublicRealtimeWsTests extends RealtimeEventFixture {
    @Autowired PublicRealtimeWsHandler handler;
    final List<Socket> sockets=new CopyOnWriteArrayList<>();
    static class Socket implements WebSocket.Listener,AutoCloseable {
        WebSocket socket;final BlockingQueue<String> frames=new LinkedBlockingQueue<>();final CompletableFuture<Integer> closed=new CompletableFuture<>();final StringBuilder partial=new StringBuilder();
        public void onOpen(WebSocket socket){this.socket=socket;socket.request(1);}
        public CompletionStage<?> onText(WebSocket socket,CharSequence value,boolean last){partial.append(value);if(last){frames.add(partial.toString());partial.setLength(0);}socket.request(1);return null;}
        public CompletionStage<?> onClose(WebSocket socket,int code,String reason){closed.complete(code);return null;}
        public void onError(WebSocket socket,Throwable error){closed.completeExceptionally(error);}
        String next()throws Exception{String value=frames.poll(12,TimeUnit.SECONDS);assertThat(value).isNotNull();return value;}
        public void close(){if(socket!=null)socket.sendClose(1000,"");}
    }
    @AfterEach void closeSockets()throws Exception{for(var s:sockets)s.close();handler.tick();org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(12)).until(()->{handler.tick();return owner.queryForObject("SELECT count(*) FROM integ_realtime_ticket WHERE project_id=? AND status='CONNECTED'",Integer.class,project)==0;});}
    RealtimeTicketService.Issued issue()throws Exception{return tickets.issue(new RealtimeIdentity(RealtimeIdentity.Kind.CONSOLE,tenant,project,0,account,account,null,Instant.now().plusSeconds(120)),new RealtimeTicketParser().parse(json.writeValueAsBytes(Map.of("protocol","WS","eventTypes",List.of("device.property.report"),"devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value")))))),"127.0.0.1");}
    Socket open(RealtimeTicketService.Issued ticket,String query,Map<String,String> headers)throws Exception{var s=new Socket();sockets.add(s);var builder=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).subprotocols("tc-realtime-v1",ticket.credential());headers.forEach(builder::header);builder.buildAsync(URI.create("ws://127.0.0.1:"+port+"/api/open/v1/realtime/ws"+query),s).get(15,TimeUnit.SECONDS);return s;}
    @Test void actualHandshakeReadyPrecisionAndPong()throws Exception{
        var ticket=issue();var s=open(ticket,"",Map.of("Origin","https://console.example.com"));assertThat(s.socket.getSubprotocol()).isEqualTo("tc-realtime-v1");var ready=s.next();assertThat(ready).contains("READY",ticket.ticketId().toString()).doesNotContain(ticket.credential());
        UUID event=Uuid7.generate();ingress.accept(update(event,"123456789.123456789123456789"));handler.tick();String frame=s.next();assertThat(frame).contains(event.toString(),"123456789.123456789123456789","9007199254740993");
        s.socket.sendText("{\"type\":\"PING\"}",true).join();assertThat(s.next()).isEqualTo("{\"type\":\"PONG\"}");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->owner.queryForObject("SELECT status FROM integ_realtime_delivery WHERE ticket_id=?",String.class,ticket.ticketId()).equals("DELIVERED"));
    }
    @Test void mixedCredentialsQueryAndOriginFailWithoutConsumingTicket()throws Exception{
        var ticket=issue();for(var headers:List.of(Map.of("Authorization","Bearer "+token()),Map.of("X-Api-Key",secret),Map.of("Cookie","tc_refresh=secret"),Map.of("Origin","https://evil.example.com"),Map.of("Origin","null")))assertThatThrownBy(()->open(ticket,"",headers)).hasCauseInstanceOf(WebSocketHandshakeException.class);
        assertThatThrownBy(()->open(ticket,"?ticket=ignored",Map.of())).hasCauseInstanceOf(WebSocketHandshakeException.class);
        assertThat(owner.queryForObject("SELECT status FROM integ_realtime_ticket WHERE id=?",String.class,ticket.ticketId())).isEqualTo("RESERVED");
        assertThat(open(ticket,"",Map.of()).next()).contains("READY");
    }
    @Test void usedTicketCannotReplaceLiveOrClosedConnection()throws Exception{
        var ticket=issue();var s=open(ticket,"",Map.of());s.next();
        assertThatThrownBy(()->owner.update("UPDATE integ_realtime_ticket SET instance_id='other' WHERE id=?",ticket.ticketId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->owner.update("UPDATE integ_realtime_ticket SET peer_ip='192.0.2.1' WHERE id=?",ticket.ticketId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->open(ticket,"",Map.of())).hasCauseInstanceOf(WebSocketHandshakeException.class);
        s.socket.sendText("{\"type\":\"PING\"}",true).join();assertThat(s.next()).contains("PONG");s.close();s.closed.get(10,TimeUnit.SECONDS);handler.tick();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(()->owner.queryForObject("SELECT status FROM integ_realtime_ticket WHERE id=?",String.class,ticket.ticketId()).equals("CLOSED"));
        assertThatThrownBy(()->open(ticket,"",Map.of())).hasCauseInstanceOf(WebSocketHandshakeException.class);
    }
    @Test void twelveConcurrentUpgradesHaveExactlyOneReadyOwner()throws Exception{
        var ticket=issue();var start=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            for(int i=0;i<12;i++)results.add(pool.submit(()->{start.await();try{var s=open(ticket,"",Map.of());String frame=s.frames.poll(4,TimeUnit.SECONDS);if(frame!=null){assertThat(frame).contains("READY");return true;}assertThat(s.closed.get(10,TimeUnit.SECONDS)).isEqualTo(1008);return false;}catch(ExecutionException rejected){assertThat(rejected.getCause()).isInstanceOf(WebSocketHandshakeException.class);return false;}}));
            start.countDown();int ready=0;for(var future:results)if(future.get(25,TimeUnit.SECONDS))ready++;assertThat(ready).isEqualTo(1);
        }
        assertThat(owner.queryForObject("SELECT status FROM integ_realtime_ticket WHERE id=?",String.class,ticket.ticketId())).isEqualTo("CONNECTED");
    }
    @Test void idleCurrentMemberLossCloses1008()throws Exception{
        var s=open(issue(),"",Map.of());s.next();owner.update("DELETE FROM sys_project_member WHERE project_id=?",project);assertThat(s.closed.get(12,TimeUnit.SECONDS)).isEqualTo(1008);
    }
    @Test void lostRedisLeaseCloses1011()throws Exception{
        var s=open(issue(),"",Map.of());s.next();redis.delete("quota:websocket:connections:{"+tenant+"}");assertThat(s.closed.get(12,TimeUnit.SECONDS)).isEqualTo(1011);
    }
    @Test void unknownAndBinaryFramesClose1008()throws Exception{
        var s=open(issue(),"",Map.of());s.next();s.socket.sendText("{\"type\":\"SUBSCRIBE\"}",true).join();assertThat(s.closed.get(10,TimeUnit.SECONDS)).isEqualTo(1008);
        var other=open(issue(),"",Map.of());other.next();other.socket.sendBinary(java.nio.ByteBuffer.wrap(new byte[]{1}),true).join();assertThat(other.closed.get(10,TimeUnit.SECONDS)).isEqualTo(1008);
    }
    @Test void sourceOverflowCloses1013()throws Exception{
        var ticket=issue();var s=open(ticket,"",Map.of());s.next();ingress.accept(update(Uuid7.generate(),"1".repeat(34000)));assertThat(s.closed.get(12,TimeUnit.SECONDS)).isEqualTo(1013);
    }
}
