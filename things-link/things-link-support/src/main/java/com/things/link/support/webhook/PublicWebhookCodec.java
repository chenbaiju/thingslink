package com.things.link.support.webhook;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.support.json.AutomationCanonicalJson;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
public class PublicWebhookCodec {
    public static final int MAX_BODY_BYTES=262144;
    private final ObjectMapper json;
    public PublicWebhookCodec(ObjectMapper json){this.json=json.rebuild().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();}
    public Encoded encode(PublicWebhookEvent e){
        var root=json.createObjectNode();root.put("eventId",e.eventId().toString());root.put("eventType",e.eventType());root.put("schemaVersion",1);root.put("tenantId",e.tenantId().toString());root.put("projectId",e.projectId().toString());root.put("resourceType",e.resourceType());root.put("resourceId",e.resourceId().toString());root.put("deviceId",e.deviceId()==null?null:e.deviceId().toString());root.put("occurredAt",e.occurredAt().toString());root.put("recordedAt",e.recordedAt().toString());root.put("traceId",e.traceId());
        // Oversized trusted input is digested without allocating a second parsed tree or truncating a fake body.
        if(e.payloadJson().length()>MAX_BODY_BYTES){try{var hash=MessageDigest.getInstance("SHA-256");hash.update((e.projectGeneration()+"\nOVERSIZE\n"+AutomationCanonicalJson.canonical(root)+"\n").getBytes(StandardCharsets.UTF_8));for(int start=0;start<e.payloadJson().length();){int end=Math.min(start+4096,e.payloadJson().length());if(end<e.payloadJson().length()&&Character.isHighSurrogate(e.payloadJson().charAt(end-1)))end--;hash.update(e.payloadJson().substring(start,end).getBytes(StandardCharsets.UTF_8));start=end;}return new Encoded(HexFormat.of().formatHex(hash.digest()),null);}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
        var payload=json.readTree(e.payloadJson());if(payload==null||!payload.isObject())throw new IllegalArgumentException("Webhook source payload must be object");root.set("payload",payload);String event=AutomationCanonicalJson.canonical(root);String digest=AutomationCanonicalJson.digestBytes((e.projectGeneration()+"\n"+event).getBytes(StandardCharsets.UTF_8));return new Encoded(digest,body(new UUID(0,0),event).getBytes(StandardCharsets.UTF_8).length<=MAX_BODY_BYTES?event:null);
    }
    public static String body(UUID deliveryId,String eventText){return "{\"deliveryId\":\""+deliveryId+"\",\"event\":"+eventText+"}";}
    /** Validate the transported canonical body against its frozen source identity. */
    public void validate(com.things.link.shared.message.PublicWebhookSource source){
        if(source.eventText()==null)return; // Trusted original encoder retained only the full oversize digest.
        var root=json.readTree(source.eventText());
        if(root==null||!root.isObject()||!root.has("payload"))throw new IllegalArgumentException("Invalid Webhook source body");
        var e=source.event();
        var full=new PublicWebhookEvent(e.eventId(),e.eventType(),e.tenantId(),e.projectId(),e.projectGeneration(),
            e.resourceType(),e.resourceId(),e.deviceId(),e.occurredAt(),e.recordedAt(),e.traceId(),root.get("payload").toString());
        var encoded=encode(full);
        if(!source.hash().equals(encoded.hash())||!source.eventText().equals(encoded.eventText()))
            throw new IllegalArgumentException("Webhook source identity or digest mismatch");
    }
    public com.things.link.shared.message.PublicWebhookSource prepare(PublicWebhookEvent e){
        var encoded=encode(e);
        var metadata=new PublicWebhookEvent(e.eventId(),e.eventType(),e.tenantId(),e.projectId(),e.projectGeneration(),
            e.resourceType(),e.resourceId(),e.deviceId(),e.occurredAt(),e.recordedAt(),e.traceId(),"{}");
        return new com.things.link.shared.message.PublicWebhookSource(1,metadata,encoded.hash(),encoded.eventText());
    }
    public record Encoded(String hash,String eventText){}
}
