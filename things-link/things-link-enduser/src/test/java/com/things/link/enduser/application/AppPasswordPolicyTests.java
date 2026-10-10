package com.things.link.enduser.application;

import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppPasswordPolicyTests {
    @Test void byteBoundaryMatchesActualEncoderWithoutTruncation() {
        var encoder=new BCryptPasswordEncoder(4);
        for (String valid:new String[]{"a".repeat(72),"密".repeat(24),"password8"}) {
            AppPasswordPolicy.validate(valid);
            assertThat(encoder.matches(valid,encoder.encode(valid))).isTrue();
        }
        for (String invalid:new String[]{"a".repeat(73),"密".repeat(25),"1234567","        "})
            assertThatThrownBy(()->AppPasswordPolicy.validate(invalid)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->encoder.encode("密".repeat(25))).isInstanceOf(IllegalArgumentException.class);
    }
}
