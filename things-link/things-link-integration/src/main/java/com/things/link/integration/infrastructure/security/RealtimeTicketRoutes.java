package com.things.link.integration.infrastructure.security;
import com.things.link.integration.application.OpenApiRoute;
import org.springframework.context.annotation.*;
import java.util.regex.Pattern;
/** 注册公开实时票据签发路由及设备读取权限要求，不直接签发票据。 */
@Configuration(proxyBeanMethods=false)
public class RealtimeTicketRoutes {
    @Bean OpenApiRoute openRealtimeTicket(){return new OpenApiRoute("POST",Pattern.compile("^/api/open/v1/realtime/tickets$"),"device:read",false);}
}
