package com.things.link.integration.application;
import org.junit.jupiter.api.Test;
import com.things.link.shared.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
/** 命令输入在持久化前保持精度并拒绝多义结构。 */
class OpenCommandRequestParserTests {
    private final OpenCommandRequestParser parser=new OpenCommandRequestParser();
    @Test void preservesDecimalAndRejectsAmbiguousBodies(){
        var parsed=parser.parse("{\"commandKey\":\"start\",\"input\":{\"n\":1.12345678901234567890123456789}}".getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.input().toString()).contains("1.12345678901234567890123456789");
        for(String bad:List.of("{\"commandKey\":\"a\",\"commandKey\":\"b\",\"input\":{}}","{\"commandKey\":\"a\",\"input\":{},\"owner\":1}","{\"commandKey\":\"a\",\"input\":[]} ","{} {}"))
            assertThatThrownBy(()->parser.parse(bad.getBytes(StandardCharsets.UTF_8))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->parser.parse(new byte[]{(byte)0xc3,0x28})).isInstanceOf(BusinessException.class);
    }
}
