package com.things.link.integration.infrastructure.websocket;
import com.things.link.integration.application.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import java.util.*;
@Configuration(proxyBeanMethods=false)
@EnableWebSocket
public class PublicRealtimeWsConfiguration implements WebSocketConfigurer {
    public static final String PATH="/api/open/v1/realtime/ws";
    private final PublicRealtimeWsHandler handler;private final RealtimeTicketService tickets;private final Set<String> origins;
    public PublicRealtimeWsConfiguration(PublicRealtimeWsHandler handler,RealtimeTicketService tickets,@Value("${things-link.integration.realtime.ws.allowed-origins:}") String origins){this.handler=handler;this.tickets=tickets;
        var list=new HashSet<String>();for(String value:origins.split(",")){String origin=value.trim();if(origin.isEmpty())continue;var uri=java.net.URI.create(origin);if(!Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!(uri.getRawPath()==null||uri.getRawPath().isEmpty()))throw new IllegalArgumentException("Invalid public WS origin");list.add(origin);}this.origins=Set.copyOf(list);}
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain publicRealtimeWsSecurity(HttpSecurity http)throws Exception{return http.securityMatcher(r->PATH.equals(r.getRequestURI().substring(r.getContextPath().length())))
        .csrf(c->c.disable()).cors(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable()).logout(c->c.disable()).requestCache(c->c.disable()).securityContext(c->c.disable())
        .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(c->c.requestMatchers(r->"GET".equals(r.getMethod())).permitAll().anyRequest().denyAll()).build();}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(handler,PATH).setAllowedOrigins(origins.toArray(String[]::new)).addInterceptors(new HandshakeInterceptor(){
        @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler ws,Map<String,Object> attrs){
            response.getHeaders().setCacheControl("no-store");
            try{
                var h=request.getHeaders();if(request.getMethod()!=HttpMethod.GET||request.getURI().getRawQuery()!=null||h.containsHeader("Authorization")||h.containsHeader("X-Api-Key")||h.containsHeader("Cookie"))return deny(response,HttpStatus.UNAUTHORIZED);
                var origin=h.get("Origin");if(origin!=null&&(origin.size()!=1||!origins.contains(origin.getFirst())))return deny(response,HttpStatus.FORBIDDEN);
                var protocols=h.get("Sec-WebSocket-Protocol");if(protocols==null)return deny(response,HttpStatus.UNAUTHORIZED);
                var tokens=Arrays.stream(String.join(",",protocols).split(",",-1)).map(String::trim).toList();if(tokens.size()!=2||!tokens.getFirst().equals("tc-realtime-v1"))return deny(response,HttpStatus.UNAUTHORIZED);
                String peer=request.getRemoteAddress().getAddress().getHostAddress();var ticket=tickets.authenticate(tokens.get(1),RealtimeTicketRequest.Protocol.WS,peer);
                if(!ticket.status().equals("RESERVED"))return deny(response,HttpStatus.UNAUTHORIZED);
                attrs.put("public-ticket",tokens.get(1));attrs.put("public-peer",peer);return true;
            }catch(com.things.link.shared.error.BusinessException failure){return deny(response,HttpStatus.UNAUTHORIZED);}catch(RuntimeException failure){return deny(response,HttpStatus.SERVICE_UNAVAILABLE);}
        }
        @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception failure){}
    }).setHandshakeHandler(new DefaultHandshakeHandler(){@Override protected String selectProtocol(List<String> protocols,WebSocketHandler handler){return "tc-realtime-v1";}});}
    private static boolean deny(ServerHttpResponse response,HttpStatus status){response.setStatusCode(status);return false;}
}
