package com.things.link.integration.infrastructure.security;
import com.things.link.integration.application.OpenApiRoute;
import org.springframework.context.annotation.*;
import java.util.regex.Pattern;
/** 只为已实施的四项资源登记只读准入；新管理/控制路径不自动放行。 */
@Configuration(proxyBeanMethods=false)
public class OpenDeviceRoutes {
    @Bean OpenApiRoute openDeviceCatalog(){return read("GET","/devices");}
    @Bean OpenApiRoute openDeviceDetail(){return read("GET","/devices/[^/]+");}
    @Bean OpenApiRoute openModel(){return read("GET","/models/[^/]+");}
    @Bean OpenApiRoute openCurrent(){return read("POST","/devices/current-values/query");}
    private static OpenApiRoute read(String method,String path){return new OpenApiRoute(method,Pattern.compile("^/api/open/v1"+path+"$"),"device:read",false);}
}
