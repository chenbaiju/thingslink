package com.things.link.integration.application;
import java.util.Set;
import java.util.regex.Pattern;
/** 显式公开允许集；资源完成领域/额度验收后逐项注册，未知方法/路径不继承权限。 */
public record OpenApiRoute(String method,Pattern path,String scope,boolean write) {
    public OpenApiRoute {
        if(!Set.of("GET","POST","PUT","DELETE","PATCH").contains(method)
                ||path==null||!path.pattern().startsWith("^/api/open/v1/")||!path.pattern().endsWith("$")
                ||!Set.of("device:read","device:control","alarm:read").contains(scope))
            throw new IllegalArgumentException("公开路由必须明确方法、完整路径与既有scope");
    }
    public boolean matches(String method,String path){return this.method.equals(method)&&this.path.matcher(path).matches();}
}
