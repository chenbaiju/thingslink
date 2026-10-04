package com.things.link.support.json;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AutomationCanonicalJsonTests {
    private final JsonMapper json=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    @Test void recursivelySortsObjectsAndNormalizesNumbers() {
        assertThat(AutomationCanonicalJson.digest(json.readTree("{\"z\":[1.0,{\"b\":2,\"a\":-0.0}],\"a\":1e3}")))
                .isEqualTo(AutomationCanonicalJson.digest(json.readTree("{\"a\":1000,\"z\":[1,{\"a\":0,\"b\":2.00}]}")));
    }
    @Test void preservesArrayOrderAndScalarTypes() {
        assertThat(AutomationCanonicalJson.digest(json.readTree("[1,2]"))).isNotEqualTo(AutomationCanonicalJson.digest(json.readTree("[2,1]")));
        assertThat(AutomationCanonicalJson.digest(json.readTree("1"))).isNotEqualTo(AutomationCanonicalJson.digest(json.readTree("\"1\"")));
        assertThat(AutomationCanonicalJson.canonical(json.readTree("[null,true,false,\"引号\\\"\"]")))
                .isEqualTo("[null,true,false,\"引号\\\"\"]");
    }
    @Test void preservesHighPrecisionAndDoesNotExpandHugeExponent() {
        assertThat(AutomationCanonicalJson.digest(json.readTree("0.123456789012345678901")))
                .isNotEqualTo(AutomationCanonicalJson.digest(json.readTree("0.123456789012345678902")));
        assertThat(AutomationCanonicalJson.canonical(json.readTree("1e999999"))).isEqualTo("1E+999999");
    }
    @Test void rejectsNonJsonValuesAndNullReference() {
        assertThatThrownBy(()->AutomationCanonicalJson.canonical(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AutomationCanonicalJson.canonical(json.getNodeFactory().numberNode(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
