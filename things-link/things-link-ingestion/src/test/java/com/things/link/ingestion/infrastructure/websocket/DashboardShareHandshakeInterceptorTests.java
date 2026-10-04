package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.dashboard.application.DashboardSharePrincipal;
import com.things.link.dashboard.application.DashboardShareRuntimeService;
import com.things.link.dashboard.application.sharing.DashboardShareProtectionService;
import com.things.link.dashboard.application.sharing.DashboardShareRuntimeProperties;
import com.things.link.dashboard.application.sharing.DashboardShareSecurityEvents;
import com.things.link.ingestion.application.DashboardRealtimePrincipal;
import com.things.link.ingestion.application.DashboardShareConnectionLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 握手最低层验证源保护、严格传输和资源移交；不冒称mock为真实数据库资格。 */
class DashboardShareHandshakeInterceptorTests {
    /** 启用配置需要显式可写目录。 */
    @TempDir Path logs;
    /** 真实选择器的测试替身。 */
    private final UUID id=UUID.randomUUID();
    /** 能力定位端口由真实网络测试另行覆盖。 */
    private final DashboardShareRuntimeService runtime=mock(DashboardShareRuntimeService.class);
    /** 来源许可的调用顺序检查。 */
    private final DashboardShareProtectionService protection=mock(DashboardShareProtectionService.class);
    /** 两连接租约端口。 */
    private final DashboardShareConnectionLease leases=mock(DashboardShareConnectionLease.class);
    /** 固定事件不写实际安全日志文件。 */
    private final DashboardShareSecurityEvents events=mock(DashboardShareSecurityEvents.class);
    /** 禁用不接触Redis和数据库。 */
    @Test void disabledRejectsWithoutLookup() {
        var response=new MockHttpServletResponse();
        assertThat(before(interceptor(false),request(),response,new HashMap<>())).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        verifyNoInteractions(runtime,protection,leases);
    }
    /** 源Redis失败先于token查库，不能以本机额度继续匿名授权。 */
    @Test void sourceFailureStopsDatabaseAndLease() {
        doThrow(new IllegalStateException("private diagnostics")).when(protection).acquireSource(anyString());
        var response=new MockHttpServletResponse();
        assertThat(before(interceptor(true),request(),response,new HashMap<>())).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        verifyNoInteractions(runtime,leases);
    }
    /** 成功升级忽略Cookie/Authorization，仅保留分享身份及租约并移交给Registry。 */
    @Test void successfulUpgradeTransfersLeaseAndIgnoresOtherIdentity() {
        var source=authorize();
        var request=request();request.addHeader("Authorization","Bearer ignored");request.addHeader("Cookie","JSESSIONID=ignored");
        var response=new MockHttpServletResponse();var attributes=new HashMap<String,Object>();var interceptor=interceptor(true);
        assertThat(before(interceptor,request,response,attributes)).isTrue();
        assertThat(attributes.get(DashboardHandshakeInterceptor.PRINCIPAL_ATTRIBUTE)).isInstanceOfSatisfying(DashboardRealtimePrincipal.class,
                principal->{assertThat(principal.share()).isTrue();assertThat(principal.subjectId()).isNull();});
        verify(source,never()).close();
        response.setStatus(101);
        interceptor.afterHandshake(new ServletServerHttpRequest(request),new ServletServerHttpResponse(response),new TextWebSocketHandler(),null);
        verify(source).close();verify(leases,never()).release(any());
    }
    /** 容器Upgrade失败不能留下已分配的share名额，重复after回调不重复释放。 */
    @Test void upgradeFailureReleasesSourceAndLeaseExactlyOnce() {
        var source=authorize();var request=request();var response=new MockHttpServletResponse();var interceptor=interceptor(true);
        assertThat(before(interceptor,request,response,new HashMap<>())).isTrue();
        for(int index=0;index<2;index++)interceptor.afterHandshake(new ServletServerHttpRequest(request),new ServletServerHttpResponse(response),new TextWebSocketHandler(),new IllegalStateException());
        verify(source).close();verify(leases).release(any());
    }
    /** 缺失/附加Origin、query及重复凭据子协议不能触发能力查询。 */
    @Test void malformedOriginAndProtocolAreRejectedBeforeLookup() {
        when(protection.acquireSource(anyString())).thenReturn(mock(DashboardShareProtectionService.SourcePermit.class));
        for(int kind=0;kind<4;kind++) {
            var request=request();
            if(kind==0)request.removeHeader("Origin");
            if(kind==1)request.addHeader("Origin","https://attacker.invalid");
            if(kind==2)request.setQueryString("secret=forbidden");
            if(kind==3)request.addHeader("Sec-WebSocket-Protocol","tc.share.properties.v1");
            var response=new MockHttpServletResponse();
            assertThat(before(interceptor(true),request,response,new HashMap<>())).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
        }
        verifyNoInteractions(runtime,leases);
    }
    /** 薄包装让测试入口使用真实Servlet适配器。 */
    private boolean before(DashboardShareHandshakeInterceptor interceptor,MockHttpServletRequest request,MockHttpServletResponse response,HashMap<String,Object> attributes) {
        return interceptor.beforeHandshake(new ServletServerHttpRequest(request),new ServletServerHttpResponse(response),new TextWebSocketHandler(),attributes);
    }
    /** 规范secret只做词法输入，不断言它具有真实数据库能力。 */
    private MockHttpServletRequest request() {
        var request=new MockHttpServletRequest("GET","/ws/shares/"+id+"/properties");
        request.addHeader("Origin","https://share.example.test");
        request.addHeader("Sec-WebSocket-Protocol","tc.share.properties.v1, share.sh_"+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        return request;
    }
    /** 手工装配便于验证默认关闭。 */
    private DashboardShareHandshakeInterceptor interceptor(boolean enabled) {
        return new DashboardShareHandshakeInterceptor(runtime,protection,new DashboardShareRuntimeProperties(enabled,"https://share.example.test",false,logs.toString()),leases,events);
    }
    /** 仅构造成功资源，用真实integration证明PG与Redis行为。 */
    private DashboardShareProtectionService.SourcePermit authorize() {
        var source=mock(DashboardShareProtectionService.SourcePermit.class);
        when(protection.acquireSource(anyString())).thenReturn(source);
        when(runtime.authenticate(any(),any())).thenReturn(new DashboardSharePrincipal(id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),0,Instant.now().plusSeconds(60),"NONE","a".repeat(64)));
        when(leases.acquire(any(),any())).thenReturn(new DashboardShareConnectionLease.Lease(id,"member"));return source;
    }
}
