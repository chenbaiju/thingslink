package com.things.link.enduser.api.support;

import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** App JWT项目生命周期代次的滚动升级兼容与严格数值解析单测。 */
class AppJwtIdentityTests {

    /** 旧JWT缺少pgv时保持ADR0073冻结的初始代次0。 */
    @Test
    void treatsMissingProjectGenerationAsZero() {
        assertThat(AppJwtIdentity.projectGeneration(jwt(null))).isZero();
    }

    /** Long.MAX_VALUE仍是合法代次，不能经double中间值发生精度截断。 */
    @Test
    void preservesLongMaximumProjectGeneration() {
        assertThat(AppJwtIdentity.projectGeneration(jwt(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
    }

    /** 非数值、负数、小数、非有限值与2^63均精确按60009拒绝，不能因浮点舍入伪装Long。 */
    @Test
    void rejectsInvalidProjectGenerationClaims() {
        assertInvalidGeneration("1");
        assertInvalidGeneration(-1L);
        assertInvalidGeneration(1.5D);
        assertInvalidGeneration(Double.NaN);
        assertInvalidGeneration(Math.scalb(1.0D, 63));
    }

    /** 创建仅供身份解析的已验签JWT形状。 */
    private static Jwt jwt(Object projectGeneration) {
        Jwt.Builder builder = Jwt.withTokenValue("unit-token").header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim(AppTokenIssuer.CLAIM_TENANT_ID, UUID.randomUUID().toString())
                .claim(AppTokenIssuer.CLAIM_PROJECT_ID, UUID.randomUUID().toString());
        if (projectGeneration != null) {
            builder.claim(AppTokenIssuer.CLAIM_PROJECT_GENERATION, projectGeneration);
        }
        return builder.build();
    }

    /** 断言错误声明稳定映射为App身份失效60009。 */
    private static void assertInvalidGeneration(Object value) {
        assertThatThrownBy(() -> AppJwtIdentity.projectGeneration(jwt(value)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(EndUserErrorCode.END_USER_ACCESS_INVALID));
    }
}
