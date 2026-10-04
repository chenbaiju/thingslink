package com.things.link.integration.api;
import com.things.link.integration.application.ApiKeyPrincipal;
import com.things.link.integration.domain.IntegrationErrorCode;
import com.things.link.shared.error.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.Principal;
import java.util.*;

/** ADR0173闭集参数与实际字节预算；不从客户端读取授权项目。 */
public final class OpenApiRequestSupport {
    private OpenApiRequestSupport(){}
    public static ApiKeyPrincipal identity(Principal raw){
        if(raw instanceof Authentication auth && auth.getPrincipal() instanceof ApiKeyPrincipal key)return key;
        throw new BusinessException(IntegrationErrorCode.ACCESS_INVALID);
    }
    public static void query(HttpServletRequest request,Set<String> allowed){
        request.getParameterMap().forEach((name,values)->{if(!allowed.contains(name)||values.length!=1||values[0].isEmpty())throw invalid();});
    }
    public static UUID uuid(String raw){try{UUID value=UUID.fromString(raw);if(!value.toString().equals(raw))throw invalid();return value;}
        catch(IllegalArgumentException e){throw invalid();}}
    public static int limit(String raw){if(raw==null)return 20;if(!raw.matches("[1-9][0-9]?"))throw invalid();int n=Integer.parseInt(raw);if(n>50)throw invalid();return n;}
    public static byte[] body(HttpServletRequest request)throws IOException{
        byte[] bytes=request.getInputStream().readNBytes(256*1024+1);if(bytes.length>256*1024)throw invalid();return bytes;
    }
    public static <T>T bounded(T value,ObjectMapper json){
        // 与HTTP使用同一ObjectMapper计实际JSON字节，不截断模型或值。
        if(json.writeValueAsBytes(value).length>4*1024*1024)throw invalid();return value;
    }
    public static BusinessException invalid(){return new BusinessException(CommonErrorCode.INVALID_PARAMETER);}
}
