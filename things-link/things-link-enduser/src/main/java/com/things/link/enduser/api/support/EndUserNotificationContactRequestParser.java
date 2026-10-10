package com.things.link.enduser.api.support;
import com.things.link.enduser.api.dto.request.UpdateEndUserNotificationContactRequest;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Set;

/** 封闭三字段原始解析，未知、重复、缺失或类型不符均拒绝且不回显号码。 */
@Component
public class EndUserNotificationContactRequestParser {
    private final JsonMapper mapper=JsonMapper.builder().build();
    /**
     * 解析当前项目接收号码请求，不接受前端替换租户或项目。
     * @param body 最大1024字节标准UTF-8 JSON
     * @return 号码与规范版本；号码业务格式由用例层复验
     */
    public UpdateEndUserNotificationContactRequest parse(byte[] body) {
        if(body==null||body.length==0||body.length>1024)throw invalid();
        try {
            String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            try(var parser=mapper.reader().createParser(json)) {
                if(parser.nextToken()!=JsonToken.START_OBJECT)throw invalid();
                var fields=Set.of("voiceNumber","smsNumber","expectedRevision");
                var values=new HashMap<String,String>();
                JsonToken token;
                while((token=parser.nextToken())!=JsonToken.END_OBJECT) {
                    if(token!=JsonToken.PROPERTY_NAME)throw invalid();
                    String name=parser.getString();
                    if(!fields.contains(name)||values.containsKey(name))throw invalid();
                    token=parser.nextToken();
                    if(token==JsonToken.VALUE_NULL&&!name.equals("expectedRevision")) values.put(name,null);
                    else if(token==JsonToken.VALUE_STRING) values.put(name,parser.getString());
                    else throw invalid();
                }
                if(!values.keySet().equals(fields)||parser.nextToken()!=null)throw invalid();
                String revision=values.get("expectedRevision");
                if(!revision.matches("0|[1-9][0-9]{0,18}"))throw invalid();
                Long.parseLong(revision);
                return new UpdateEndUserNotificationContactRequest(values.get("voiceNumber"),values.get("smsNumber"),revision);
            }
        } catch(BusinessException exception){throw exception;}
        catch(Exception exception){throw invalid();}
    }
    /** 不把请求中的号码放入诊断或响应。 */
    private static BusinessException invalid(){return new BusinessException(CommonErrorCode.INVALID_PARAMETER);}
}
