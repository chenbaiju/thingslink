package com.things.link.enduser.application;

import com.things.link.enduser.infrastructure.security.AppJwtProperties;
import com.things.link.enduser.infrastructure.security.AppJwtTokenIssuer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** App 访问令牌签发器的单元测试（S11-2a）。 */
class AppJwtTokenIssuerTests {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID appUserId = UUID.randomUUID();

    /** subject=appUserId，tid/pid 声明齐全，issuer 与控制台不同。 */
    @Test
    void issueWritesAppClaims() {
        JwtEncoder encoder = mock(JwtEncoder.class);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getTokenValue()).thenReturn("signed-token");
        when(encoder.encode(any(JwtEncoderParameters.class))).thenReturn(jwt);

        AppJwtTokenIssuer issuer = new AppJwtTokenIssuer(encoder, new AppJwtProperties(
                "test-app-secret-key-at-least-32-bytes", Duration.ofMinutes(15), "things-link-app"));

        AppAccessToken result = issuer.issue(
                new AppAuthenticatedPrincipal(tenantId, projectId, appUserId, 7L));

        assertThat(result.value()).isEqualTo("signed-token");
        assertThat(result.expiresAt()).isAfter(Instant.now());

        ArgumentCaptor<JwtEncoderParameters> captor =
                ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(encoder).encode(captor.capture());
        JwtClaimsSet claims = captor.getValue().getClaims();

        assertThat(claims.getAudience()).containsExactly(AppTokenIssuer.AUDIENCE);
        assertThat(claims.getSubject()).isEqualTo(appUserId.toString());
        assertThat(claims.getClaimAsString(AppTokenIssuer.CLAIM_TENANT_ID))
                .isEqualTo(tenantId.toString());
        assertThat(claims.getClaimAsString(AppTokenIssuer.CLAIM_PROJECT_ID))
                .isEqualTo(projectId.toString());
        assertThat(((Number) claims.getClaim(AppTokenIssuer.CLAIM_PROJECT_GENERATION)).longValue())
                .isEqualTo(7L);
        assertThat(claims.getClaimAsString("iss")).isEqualTo("things-link-app");
    }

}
