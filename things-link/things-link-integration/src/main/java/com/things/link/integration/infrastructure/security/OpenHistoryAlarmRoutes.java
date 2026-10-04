package com.things.link.integration.infrastructure.security;
import com.things.link.integration.application.OpenApiRoute;
import org.springframework.context.annotation.*;
import java.util.regex.Pattern;
/** 历史及告警均消耗共享读取配额，POST不代表业务写。 */
@Configuration(proxyBeanMethods=false)
public class OpenHistoryAlarmRoutes {
    @Bean OpenApiRoute openHistory(){return new OpenApiRoute("GET",Pattern.compile("^/api/open/v1/devices/[^/]+/history$"),"device:read",false);}
    @Bean OpenApiRoute openAlarms(){return new OpenApiRoute("POST",Pattern.compile("^/api/open/v1/alarms/query$"),"alarm:read",false);}
}
