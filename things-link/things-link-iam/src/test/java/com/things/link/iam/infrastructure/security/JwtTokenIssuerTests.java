package com.things.link.iam.infrastructure.security;

import com.things.link.iam.application.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR0073控制台访问令牌项目代次声明形状的直接验收。 */
class JwtTokenIssuerTests {

    /** 选中项目时pid与pgv必须共同签发，避免恢复后仅凭稳定项目ID复活旧JWT。 */
    @Test
    void selectedProjectWritesProjectIdAndGeneration() {
        JwtEncoder encoder = encoderReturning("selected-token");
        JwtTokenIssuer issuer = issuer(encoder);
        UUID accountId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        issuer.issue(new AuthenticatedPrincipal(accountId, tenantId, projectId, 7L));

        JwtClaimsSet claims = capturedClaims(encoder);
        assertThat(claims.getClaimAsString(JwtTokenIssuer.CLAIM_PROJECT_ID))
                .isEqualTo(projectId.toString());
        assertThat(((Number) claims.getClaim(JwtTokenIssuer.CLAIM_PROJECT_GENERATION)).longValue())
                .isEqualTo(7L);
    }

    /** 未选项目时pid和pgv都缺席，不能把账号级会话误绑定到零代虚假项目。 */
    @Test
    void accountOnlyTokenOmitsProjectIdAndGeneration() {
        JwtEncoder encoder = encoderReturning("account-token");
        JwtTokenIssuer issuer = issuer(encoder);

        issuer.issue(new AuthenticatedPrincipal(UUID.randomUUID(), UUID.randomUUID(), null, 0L));

        JwtClaimsSet claims = capturedClaims(encoder);
        assertThat(claims.hasClaim(JwtTokenIssuer.CLAIM_PROJECT_ID)).isFalse();
        assertThat(claims.hasClaim(JwtTokenIssuer.CLAIM_PROJECT_GENERATION)).isFalse();
    }

    /** 使用固定测试配置构造生产签发器，JWT正文只通过encoder捕获观察。 */
    private static JwtTokenIssuer issuer(JwtEncoder encoder) {
        return new JwtTokenIssuer(encoder, new JwtProperties(
                "test-console-secret-at-least-32-bytes", Duration.ofMinutes(15), "things-link"));
    }

    /** 返回固定密文，测试不依赖真实签名算法或系统时间。 */
    private static JwtEncoder encoderReturning(String value) {
        JwtEncoder encoder = mock(JwtEncoder.class);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getTokenValue()).thenReturn(value);
        when(encoder.encode(any(JwtEncoderParameters.class))).thenReturn(jwt);
        return encoder;
    }

    /** 捕获生产提交给encoder的声明集，不解析测试自己构造的JWT。 */
    private static JwtClaimsSet capturedClaims(JwtEncoder encoder) {
        ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(encoder).encode(captor.capture());
        return captor.getValue().getClaims();
    }
}
