package com.things.link.integration.infrastructure.security;
import com.things.link.integration.application.OpenApiRoute;
import org.springframework.context.annotation.*;
import java.util.regex.Pattern;
@Configuration(proxyBeanMethods=false)
public class RealtimeTicketRoutes {
    @Bean OpenApiRoute openRealtimeTicket(){return new OpenApiRoute("POST",Pattern.compile("^/api/open/v1/realtime/tickets$"),"device:read",false);}
}
