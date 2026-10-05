package com.things.link.integration.infrastructure.websocket;
import com.things.link.integration.application.*;
import com.things.link.integration.domain.*;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** 每条连接仅一个写入者；持久数据预算为 256，独立控制预算为 16。 */
@Component
@DataPlaneDatabase
public class PublicRealtimeWsHandler extends AbstractWebSocketHandler implements DisposableBean {
    private final RealtimeTicketService tickets;private final RealtimeWsDelivery delivery;private final ObjectMapper json;
    private final Semaphore slots=new Semaphore(1000);
    private final ConcurrentMap<String,Connection> connections=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1000),r->{var t=new Thread(r,"tc-public-ws");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public PublicRealtimeWsHandler(RealtimeTicketService tickets,RealtimeWsDelivery delivery,ObjectMapper json){this.tickets=tickets;this.delivery=delivery;this.json=json;}
    private static final class Connection {
        final WebSocketSession socket;final RealtimeTicketRepository.Candidate ticket;final ArrayBlockingQueue<String> control=new ArrayBlockingQueue<>(16);final AtomicBoolean scheduled=new AtomicBoolean();volatile boolean closing;boolean ready;long guard;
        Connection(WebSocketSession socket,RealtimeTicket t){this.socket=socket;ticket=new RealtimeTicketRepository.Candidate(t.identity().tenant(),t.identity().project(),t.id());}
    }
    @Override public void afterConnectionEstablished(WebSocketSession socket){
        String credential=(String)socket.getAttributes().remove("public-ticket"),peer=(String)socket.getAttributes().remove("public-peer");
        if(!slots.tryAcquire()){closeSocket(socket,1013);return;}
        boolean registered=false;
        try(var ignored=DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)){
            var ticket=tickets.connectWs(credential,peer);var connection=new Connection(socket,ticket);connections.put(socket.getId(),connection);registered=true;
            socket.setTextMessageSizeLimit(32768);socket.setBinaryMessageSizeLimit(32768);
            connection.control.add(json.writeValueAsString(Map.of("type","READY","ticketId",ticket.id(),"expiresAt",ticket.expiresAt().toString())));schedule(connection);
        }catch(RuntimeException failure){if(!registered)slots.release();var connection=connections.get(socket.getId());if(connection!=null)connection.closing=true;closeSocket(socket,code(failure));}
    }
    @Override protected void handleTextMessage(WebSocketSession socket,TextMessage message){var c=connections.get(socket.getId());if(c==null)return;
        try{var n=json.readTree(message.getPayload());if(!n.isObject()||n.size()!=1||!n.path("type").isString())throw new IllegalArgumentException();String type=n.path("type").asString();
            if(type.equals("PING")){if(!c.control.offer("{\"type\":\"PONG\"}")){close(c,1013);return;}schedule(c);}else if(!type.equals("PONG"))close(c,1008);
        }catch(RuntimeException malformed){close(c,1008);}
    }
    @Override protected void handleBinaryMessage(WebSocketSession socket,BinaryMessage message){var c=connections.get(socket.getId());if(c!=null)close(c,1008);}
    @Override public void handleTransportError(WebSocketSession socket,Throwable failure){var c=connections.get(socket.getId());if(c!=null)close(c,1011);}
    @Override public void afterConnectionClosed(WebSocketSession socket,CloseStatus status){var c=connections.get(socket.getId());if(c!=null){c.closing=true;schedule(c);}}
    @Scheduled(scheduler="maintenanceScheduler",fixedDelay=1000,initialDelay=1000) public void tick(){connections.values().forEach(this::schedule);}
    private void schedule(Connection c){if(!c.scheduled.compareAndSet(false,true))return;try{workers.execute(()->pump(c));}catch(RejectedExecutionException full){c.scheduled.set(false);close(c,1013);}}
    private void pump(Connection c){try(var ignored=DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)){
        if(c.closing||!c.socket.isOpen()){c.closing=true;tickets.closeWs(c.ticket);if(connections.remove(c.socket.getId(),c))slots.release();return;}
        int controls=0;String value;while(controls++<16&&(value=c.control.poll())!=null){final String payload=value;delivery.frame(c.ticket,t->send(c,payload));c.ready=true;}
        if(!c.ready)return;
        if(System.nanoTime()-c.guard>=TimeUnit.SECONDS.toNanos(5)){delivery.frame(c.ticket,t->{});c.guard=System.nanoTime();}
        int sent=0;for(var pending:delivery.pending(c.ticket)){if(c.closing||sent++>=16)break;delivery.send(c.ticket,pending,payload->send(c,payload));}
    }catch(RuntimeException failure){int code=code(failure);if(code==1008)try{if("RESYNC_REQUIRED".equals(tickets.wsClosedReason(c.ticket)))code=1013;}catch(RuntimeException unavailable){code=1011;}close(c,code);
    }finally{c.scheduled.set(false);}}
    private void send(Connection c,String payload){if(c.closing||!c.socket.isOpen())throw new IllegalStateException("WS closed");if(payload.getBytes(StandardCharsets.UTF_8).length>32768){close(c,1013);throw new IllegalStateException("WS frame oversized");}
        if(!(c.socket instanceof NativeWebSocketSession nativeSession))throw new IllegalStateException("Native WS required");
        var nativeSocket=nativeSession.getNativeSession(jakarta.websocket.Session.class);if(nativeSocket==null)throw new IllegalStateException("Native WS required");
        nativeSocket.getAsyncRemote().setSendTimeout(5000);Future<Void> result=nativeSocket.getAsyncRemote().sendText(payload);
        try{result.get(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();result.cancel(true);throw new IllegalStateException("WS interrupted");}catch(ExecutionException|TimeoutException failure){result.cancel(true);throw new IllegalStateException("WS send unavailable");}
    }
    private void close(Connection c,int code){c.closing=true;closeSocket(c.socket,code);}
    private static void closeSocket(WebSocketSession socket,int code){try{socket.close(new CloseStatus(code));}catch(Exception ignored){}}
    private static int code(RuntimeException failure){if(failure instanceof BusinessException b){if(b.errorCode()==IntegrationErrorCode.REALTIME_CAPACITY)return 1013;if(b.errorCode()!=IntegrationErrorCode.REALTIME_UNAVAILABLE)return 1008;}return 1011;}
    @Override public void destroy()throws InterruptedException{connections.values().forEach(c->{close(c,1011);schedule(c);});workers.shutdown();if(!workers.awaitTermination(10,TimeUnit.SECONDS)){workers.shutdownNow();workers.awaitTermination(5,TimeUnit.SECONDS);}}
}
