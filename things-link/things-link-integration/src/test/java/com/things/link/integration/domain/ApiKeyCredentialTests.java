package com.things.link.integration.domain;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 凭据词法、摘要和秘密边界；不把凭据解析当成认证。 */
class ApiKeyCredentialTests {
    @Test void generatedCredentialRoundTripsAndHasIndependentRandomSecret() {
        UUID id=UUID.randomUUID();
        var a=ApiKeyCredential.generate(id);
        var b=ApiKeyCredential.generate(id);
        assertThat(a.reveal()).hasSize(86).isNotEqualTo(b.reveal());
        var parsed=ApiKeyCredential.parse(a.reveal()).orElseThrow();
        assertThat(parsed.keyId()).isEqualTo(id);
        assertThat(parsed.matchesDigest(a.digest())).isTrue();
        assertThat(parsed.matchesDigest(b.digest())).isFalse();
        assertThat(a.digest()).matches("[0-9a-f]{64}").doesNotContain(a.reveal());
    }
    @Test void rejectsMalformedAndNonCanonicalSecretsWithoutThrowing() {
        String value=ApiKeyCredential.generate(UUID.randomUUID()).reveal();
        for (String invalid: new String[]{"", " "+value,value+" ",value+"=",value+".x",
                value.replace("tcak1", "tcak2"),value.replace("-", "_"),"x".repeat(10000)}) {
            assertThat(ApiKeyCredential.parse(invalid)).isEmpty();
        }
        assertThat(ApiKeyCredential.parse(null)).isEmpty();
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int last=alphabet.indexOf(value.charAt(85));
        assertThat(ApiKeyCredential.parse(value.substring(0,85)+alphabet.charAt(last+1))).isEmpty();
    }
    @Test void diagnosticsAndInvalidDigestsDoNotExposeCredential() {
        var value=ApiKeyCredential.generate(UUID.randomUUID());
        assertThat(value.toString()).isEqualTo("ApiKeyCredential[REDACTED]");
        assertThat(value.matchesDigest(null)).isFalse();
        assertThat(value.matchesDigest("x".repeat(64))).isFalse();
        assertThat(value.matchesDigest(value.digest().toUpperCase())).isFalse();
    }
}
