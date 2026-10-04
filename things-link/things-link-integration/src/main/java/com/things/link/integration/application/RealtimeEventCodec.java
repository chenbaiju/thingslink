package com.things.link.integration.application;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/** JSON值保持完整词法字符串，reportedRevision保持十进制字符串，禁止double中转。 */
@Component
public class RealtimeEventCodec {
    private final ObjectMapper json;
    public RealtimeEventCodec(ObjectMapper json){this.json=json;}
    public String sourceHash(DeviceRealtimeUpdate u){
        var root=base(u);root.put("tenantId",u.tenantId().toString());root.put("modelVersion",u.modelVersion());root.put("shadowVersion",u.shadowVersion());
        root.set("properties",properties(u,new TreeSet<>(u.propertiesJson().keySet())));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(root)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public Optional<String> envelope(DeviceRealtimeUpdate u,RuntimeDeviceQuery scope){
        var keys=new TreeSet<>(scope.propertyKeys());keys.retainAll(u.propertiesJson().keySet());if(keys.isEmpty())return Optional.empty();
        var root=base(u);root.set("properties",properties(u,keys));return Optional.of(json.writeValueAsString(root));
    }
    public boolean oversized(String envelope){return envelope.getBytes(StandardCharsets.UTF_8).length>32768;}
    private ObjectNode base(DeviceRealtimeUpdate u){var root=json.createObjectNode();
        root.put("eventId",u.messageId().toString());root.put("eventType","device.property.report");root.put("schemaVersion",1);
        root.put("occurredAt",u.occurredAt().toString());root.put("projectId",u.projectId().toString());root.put("deviceId",u.deviceId().toString());
        root.put("thingModelVersionId",u.thingModelVersionId().toString());root.put("traceId",u.traceId());return root;
    }
    private ObjectNode properties(DeviceRealtimeUpdate u,Set<String> keys){var values=json.createObjectNode();
        for(String key:keys){var property=json.createObjectNode();property.put("dataType",u.propertyDataTypes().get(key));property.put("valueJson",u.propertiesJson().get(key));property.put("reportedRevision",u.reportedRevisions().get(key));values.set(key,property);}return values;
    }
}
