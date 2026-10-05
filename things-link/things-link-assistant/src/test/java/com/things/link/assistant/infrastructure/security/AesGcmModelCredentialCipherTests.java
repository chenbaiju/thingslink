package com.things.link.assistant.infrastructure.security;
import com.things.link.assistant.domain.ModelCredential;
import java.util.*;
import java.time.Instant;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AesGcmModelCredentialCipherTests {
    String key() { byte[] k=new byte[32]; new SecureRandom().nextBytes(k); return Base64.getEncoder().encodeToString(k); }
    AesGcmModelCredentialCipher cipher(String active,Map<String,String> keys) {
        var p=new ModelCredentialEncryptionProperties();p.setActiveKeyId(active);p.setKeys(keys);return new AesGcmModelCredentialCipher(p);
    }
    ModelCredential identity(UUID tenant,UUID project,long revision) {
        return new ModelCredential(UUID.randomUUID(),tenant,project,revision,revision,false,null,null,null,UUID.randomUUID(),Instant.now());
    }
    ModelCredential copy(ModelCredential c,UUID tenant,UUID project,UUID id,long revision,byte[] data,byte[] nonce,String keyId) {
        return new ModelCredential(id,tenant,project,c.revision(),revision,false,data,nonce,keyId,c.updatedBy(),c.updatedAt());
    }
    @Test void randomNonceRoundtripAndNoSecretToString() {
        var cipher=cipher("v1",Map.of("v1",key()));var identity=identity(UUID.randomUUID(),UUID.randomUUID(),1);
        var first=cipher.encrypt(identity,"synthetic-secret");var second=cipher.encrypt(identity,"synthetic-secret");
        cipher.verify(first);cipher.verify(second);
        assertThat(first.nonce()).isNotEqualTo(second.nonce()); assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.toString()).doesNotContain("synthetic-secret","v1");
        byte[] copy=first.ciphertext();copy[0]^=1;cipher.verify(first);
    }
    @Test void rejectsTamperTenantProjectIdentityVersionNonceAndKeyId() {
        var cipher=cipher("v1",Map.of("v1",key()));var c=cipher.encrypt(identity(UUID.randomUUID(),UUID.randomUUID(),2),"synthetic-secret");
        byte[] tampered=c.ciphertext();tampered[0]^=1;
        for (var bad:List.of(
            copy(c,UUID.randomUUID(),c.projectId(),c.id(),2,c.ciphertext(),c.nonce(),c.keyId()),
            copy(c,c.tenantId(),UUID.randomUUID(),c.id(),2,c.ciphertext(),c.nonce(),c.keyId()),
            copy(c,c.tenantId(),c.projectId(),UUID.randomUUID(),2,c.ciphertext(),c.nonce(),c.keyId()),
            copy(c,c.tenantId(),c.projectId(),c.id(),1,c.ciphertext(),c.nonce(),c.keyId()),
            copy(c,c.tenantId(),c.projectId(),c.id(),2,tampered,c.nonce(),c.keyId()),
            copy(c,c.tenantId(),c.projectId(),c.id(),2,c.ciphertext(),new byte[12],c.keyId()),
            copy(c,c.tenantId(),c.projectId(),c.id(),2,c.ciphertext(),c.nonce(),"absent")))
            assertThatThrownBy(()->cipher.verify(bad)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("synthetic-secret").hasNoCause();
    }
    @Test void rotationReadsRetainedKeysButRejectsRetiredOrWrongMaterial() {
        String old=key(),next=key();var c=cipher("v1",Map.of("v1",old)).encrypt(identity(UUID.randomUUID(),UUID.randomUUID(),1),"synthetic-secret");
        cipher("v2",Map.of("v1",old,"v2",next)).verify(c);
        assertThatThrownBy(()->cipher("v2",Map.of("v2",next)).verify(c)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->cipher("v1",Map.of("v1",next)).verify(c)).isInstanceOf(IllegalStateException.class);
    }
    @Test void missingConfigurationFailsAtUseAndMalformedConfigurationFailsWithoutEcho() {
        var absent=cipher(null,Map.of());
        assertThatThrownBy(()->absent.encrypt(identity(UUID.randomUUID(),UUID.randomUUID(),1),"synthetic-secret")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->cipher("v1",Map.of("v1","malformed-sensitive-value"))).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("malformed-sensitive-value").hasNoCause();
    }
    @Test void singleDeliveryWipesBytesOnSuccessAndCallbackFailure() {
        var cipher=cipher("v1",Map.of("v1",key()));var c=cipher.encrypt(identity(UUID.randomUUID(),UUID.randomUUID(),1),"synthetic-secret");
        var seen=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        int result=cipher.deliver(c,b->{seen.set(b);assertThat(new String(b,java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("synthetic-secret");return 7;});
        assertThat(result).isEqualTo(7);
        assertThat(seen.get()).containsOnly((byte)0);
        assertThatThrownBy(()->cipher.deliver(c,b->{seen.set(b);throw new IllegalStateException("fixed-test-failure");})).hasMessage("fixed-test-failure");
        assertThat(seen.get()).containsOnly((byte)0);
    }
}
