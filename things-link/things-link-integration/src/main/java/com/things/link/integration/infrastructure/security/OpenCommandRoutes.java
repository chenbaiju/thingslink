package com.things.link.integration.infrastructure.security;
import com.things.link.integration.application.OpenApiRoute;
import org.springframework.context.annotation.*;
import java.util.regex.Pattern;
/** 三个命令入口使用control权限；GET恢复仅按读计量。 */
@Configuration(proxyBeanMethods=false)
public class OpenCommandRoutes {
    @Bean OpenApiRoute openCommandSubmit(){return new OpenApiRoute("POST",Pattern.compile("^/api/open/v1/devices/[^/]+/commands$"),"device:control",true);}
    @Bean OpenApiRoute openCommandRead(){return new OpenApiRoute("GET",Pattern.compile("^/api/open/v1/devices/[^/]+/commands/[^/]+$"),"device:control",false);}
}
