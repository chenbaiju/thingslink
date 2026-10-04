package com.things.link.rule.api.dto.request;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
/** 仅自动化配置启用精确数值解析，不改变其他API的既有JSON行为。 */
public final class AutomationJsonDeserializer extends ValueDeserializer<JsonNode> {
    private static final ObjectReader READER=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build().readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    @Override public JsonNode deserialize(JsonParser parser,DeserializationContext context){return READER.readValue(parser);}
}
