package com.things.link.integration.application;
import com.things.link.integration.domain.IntegrationErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Master keys never leave this object; only a purpose-separated subscription key is revealed once. */
@Component
public class WebhookSigningKeys {
    private final Map<String,byte[]> keys;private final String current;
    public WebhookSigningKeys(ObjectMapper json,@Value("${things-link.integration.webhook.enabled:false}") boolean enabled,
        @Value("${things-link.integration.webhook.signing-keys-json:}") String configured,@Value("${things-link.integration.webhook.current-signing-key-id:}") String current){
        this.current=current;var parsed=new HashMap<String,byte[]>();
        try{if(!configured.isBlank()){var root=json.readTree(configured);if(!root.isObject()||root.isEmpty()||root.size()>4)throw new IllegalArgumentException();
            for(var e:root.properties()){if(!e.getKey().matches("[A-Za-z0-9_-]{1,32}")||!e.getValue().isString())throw new IllegalArgumentException();byte[] bytes=Base64.getDecoder().decode(e.getValue().asString());if(bytes.length<32||bytes.length>64)throw new IllegalArgumentException();parsed.put(e.getKey(),bytes);}}
            if(enabled&&!parsed.containsKey(current))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new IllegalArgumentException("公开Webhook签名配置无效");}this.keys=Map.copyOf(parsed);
    }
    public String currentId(){if(!keys.containsKey(current))throw new BusinessException(IntegrationErrorCode.NOT_ENABLED);return current;}
    public boolean available(String id){return keys.containsKey(id);}
    public byte[] derive(String id,UUID tenant,UUID project,UUID subscription,UUID generation){byte[] master=keys.get(id);if(master==null)throw new IllegalStateException("Webhook signing key unavailable");return hmac(master,"thingslink:public-webhook:v1:"+tenant+":"+project+":"+subscription+":"+generation);}
    public static byte[] hmac(byte[] key,String body){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));}catch(java.security.GeneralSecurityException impossible){throw new IllegalStateException("Webhook HMAC unavailable");}}
    @Override public String toString(){return "WebhookSigningKeys[REDACTED]";}
}
