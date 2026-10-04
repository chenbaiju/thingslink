package com.things.link.integration.application;
import com.things.link.shared.error.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.*;
import java.nio.charset.*;
import java.util.Set;
/** 命令封闭信封与精确数值解析；HTTP适配先执行256KiB实际字节预算。 */
public final class OpenCommandRequestParser {
    private final ObjectMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public Request parse(byte[] bytes){
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(text.isEmpty()||text.charAt(0)=='\uFEFF')throw invalid();
            JsonNode value=json.readTree(text);
            if(value==null||!value.isObject()||!Set.copyOf(value.propertyNames()).equals(Set.of("commandKey","input"))
                ||!value.path("commandKey").isString()||!value.path("commandKey").asString().matches("[A-Za-z0-9_-]{1,64}")||!value.path("input").isObject())throw invalid();
            return new Request(value.path("commandKey").asString(),value.path("input"));
        }catch(CharacterCodingException|tools.jackson.core.JacksonException|NullPointerException e){throw invalid();}
    }
    private static BusinessException invalid(){return new BusinessException(CommonErrorCode.INVALID_PARAMETER);}
    public record Request(String commandKey,JsonNode input){}
}
