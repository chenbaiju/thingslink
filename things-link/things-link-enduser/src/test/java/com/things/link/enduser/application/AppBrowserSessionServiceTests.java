package com.things.link.enduser.application;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 编码失败保留安全类型与失败位置，禁止诊断展开局部令牌或底层异常凭据。 */
class AppBrowserSessionServiceTests {
    /** 测试只检查内部本地签发结果的诊断，事务回滚由真实PG集成类单独证明。 */
    @Test void encodingFailurePreservesLocationWithoutCredentialText() {
        var sessions = mock(AppSessionService.class);
        var codec = mock(AppBrowserRefreshCookieCodec.class);
        String jwt = new PlainJWT(new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString())
                .claim("pid", UUID.randomUUID().toString()).build()).serialize();
        when(sessions.rotate("refresh-secret")).thenReturn(new AppIssuedSession(
                new AppAccessToken(jwt, Instant.now().plusSeconds(60)), "refresh-secret", Instant.now().plusSeconds(600)));
        var original = new IllegalArgumentException("cookie-secret", new RuntimeException("nested-secret"));
        when(codec.encode(any(), any(), any())).thenThrow(original);
        var service = new AppBrowserSessionService(mock(AppAuthenticationService.class), sessions, codec);
        Throwable failed = catchThrowable(() -> service.refresh("epoch", "refresh-secret"));
        assertThat(failed).isInstanceOf(IllegalStateException.class);
        assertThat(failed.getCause().getMessage()).contains(IllegalArgumentException.class.getName());
        assertThat(failed.getCause().getStackTrace()).containsExactly(original.getStackTrace());
        var rendered = new StringWriter();
        failed.printStackTrace(new PrintWriter(rendered));
        assertThat(rendered.toString()).doesNotContain("cookie-secret", "nested-secret", "refresh-secret", jwt);
    }
}
