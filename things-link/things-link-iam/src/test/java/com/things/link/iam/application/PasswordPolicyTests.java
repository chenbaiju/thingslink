package com.things.link.iam.application;

import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PasswordPolicyTests {
    @ParameterizedTest
    @ValueSource(strings={"1234567890", "ThingsLink123!", "  PASSWORD123!  ", "ｐａｓｓｗｏｒｄ１２３", "qwertyuiop"})
    void rejectsKnownDefaultsIncludingComparisonVariants(String value) {
        assertThatThrownBy(() -> PasswordPolicy.assertAcceptable(value)).isInstanceOf(BusinessException.class);
    }
    @ParameterizedTest
    @ValueSource(strings={"violet-lake-79-amber", "my-thingslink-is-private", "雨后湖边散步再读一本书", "  personal long phrase  "})
    void permitsPhrasesWithoutSubstringOrCompositionRules(String value) {
        assertThatCode(() -> PasswordPolicy.assertAcceptable(value)).doesNotThrowAnyException();
    }
}
