package com.things.link.integration.infrastructure;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
class EmqxRealtimeSessionsTests {
    @Test void boundedCanonicalApplicationIdentitiesOnlyAndFixedPath()throws Exception{
        UUID id=UUID.randomUUID();var path=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v5/clients",e->{path.set(e.getRequestURI().toString());byte[] response=("{\"data\":[{\"clientid\":\"tc-app-v1-"+id+"\",\"username\":\"tc-app-v1:"+id+"\",\"ip_address\":\"192.0.2.5\"},{\"clientid\":\"device\",\"username\":\"device\"},{\"clientid\":\"tc-app-v1-"+id+"\",\"username\":\"foreign\"}],\"meta\":{\"hasnext\":false}}").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);e.close();});server.start();
        try(var sessions=client(server)){assertThat(sessions.active()).containsExactly(new com.things.link.integration.application.RealtimeMqttSessions.Session(id,"192.0.2.5"));assertThat(path.get()).isEqualTo("/api/v5/clients?page=1&limit=1000");}finally{server.stop(0);}
    }
    @Test void failedDisconnectDoesNotLieAboutSuccessOrFollowRedirectAnd404IsIdempotent()throws Exception{
        var calls=new AtomicInteger();var redirected=new AtomicInteger();var method=new AtomicReference<String>();var path=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v5/clients",e->{method.set(e.getRequestMethod());path.set(e.getRequestURI().getPath());int n=calls.incrementAndGet();e.getResponseHeaders().add("Retry-After","0");e.getResponseHeaders().add("Location","/redirect");e.sendResponseHeaders(n==1?503:n==2?307:n==3?404:204,-1);e.close();});server.createContext("/redirect",e->{redirected.incrementAndGet();e.close();});server.start();
        try(var sessions=client(server)){UUID id=UUID.randomUUID();assertThat(sessions.disconnect(id)).isFalse();assertThat(calls).hasValue(1);assertThat(sessions.disconnect(id)).isFalse();assertThat(redirected).hasValue(0);assertThat(sessions.disconnect(id)).isTrue();assertThat(sessions.disconnect(id)).isTrue();assertThat(method.get()).isEqualTo("DELETE");assertThat(path.get()).isEqualTo("/api/v5/clients/tc-app-v1-"+id);}finally{server.stop(0);}
    }
    @Test void malformedOversizedAndTruncatedEnumerationNeverBecomesAnEmptySuccessfulScan()throws Exception{
        var response=new AtomicReference<>("{\"data\":[],\"meta\":{\"hasnext\":true}}");var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v5/clients",e->{byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);try{e.getResponseBody().write(bytes);}finally{e.close();}});server.start();
        try(var sessions=client(server)){for(String body:List.of(response.get(),"not-json","{\"data\":{}}","x".repeat(4*1024*1024+1))){response.set(body);assertThatThrownBy(sessions::active).hasMessage("REALTIME_BROKER_SESSION_UNAVAILABLE");}}finally{server.stop(0);}
    }
    private EmqxRealtimeSessions client(HttpServer s){return new EmqxRealtimeSessions("http://127.0.0.1:"+s.getAddress().getPort(),"test","test-secret");}
}
