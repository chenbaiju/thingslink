package com.things.link.integration.api;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
/** 预算按真实UTF-8字节检查，而非字符数或Content-Length声明。 */
class OpenApiRequestSupportTests {
    @Test void requestBudgetUsesActualBytes()throws Exception{
        var request=new MockHttpServletRequest();request.setContent(new byte[256*1024]);
        assertThat(OpenApiRequestSupport.body(request)).hasSize(256*1024);
        var oversized=new MockHttpServletRequest();oversized.setContent(new byte[256*1024+1]);
        assertThatThrownBy(()->OpenApiRequestSupport.body(oversized)).isInstanceOf(BusinessException.class);
    }
    @Test void responseBudgetCountsMultibyteJsonAndDoesNotTruncate(){
        var json=JsonMapper.builder().build();String small="中".repeat(1024);
        assertThat(OpenApiRequestSupport.bounded(small,json)).isEqualTo(small);
        assertThatThrownBy(()->OpenApiRequestSupport.bounded("中".repeat(1_400_000),json)).isInstanceOf(BusinessException.class);
    }
    @Test void duplicateOrUnknownQueryAndNoncanonicalUuidFail(){
        var request=new MockHttpServletRequest();request.addParameter("limit","1","2");
        assertThatThrownBy(()->OpenApiRequestSupport.query(request,Set.of("limit"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->OpenApiRequestSupport.uuid("1-1-1-1-1")).isInstanceOf(BusinessException.class);
    }
}
