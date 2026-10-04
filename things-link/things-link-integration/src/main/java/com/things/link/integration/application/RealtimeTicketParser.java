package com.things.link.integration.application;
import com.things.link.device.application.RuntimeDeviceQueryParser;
import com.things.link.shared.error.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
/** 与公开资源相同的严格UTF-8、重复键及尾随内容拒绝；设备范围复用中性解析器。 */
public final class RealtimeTicketParser {
    private final JsonMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public RealtimeTicketRequest parse(byte[] bytes) {
        try {
            if(bytes==null||bytes.length==0||bytes.length>256*1024)throw invalid();
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(text.charAt(0)=='\ufeff')throw invalid();
            var root=json.readTree(text);
            if(root==null||!root.isObject()||root.size()!=3||!root.has("protocol")||!root.has("eventTypes")||!root.has("devices")||!root.path("protocol").isString())throw invalid();
            var protocol=RealtimeTicketRequest.Protocol.valueOf(root.path("protocol").asString());
            var events=root.path("eventTypes");
            if(!events.isArray()||events.size()!=1||!events.get(0).isString()||!"device.property.report".equals(events.get(0).asString()))throw invalid();
            var deviceRoot=json.createObjectNode().set("devices",root.path("devices"));
            var devices=new RuntimeDeviceQueryParser().parseCurrentValues(json.writeValueAsBytes(deviceRoot));
            if(devices.isEmpty()||devices.size()>20||devices.stream().mapToInt(d->d.propertyKeys().size()).sum()>200)throw invalid();
            return new RealtimeTicketRequest(protocol,List.of("device.property.report"),devices);
        } catch(BusinessException e){throw e;} catch(Exception e){throw invalid();}
    }
    private static BusinessException invalid(){return new BusinessException(CommonErrorCode.INVALID_PARAMETER);}
}
