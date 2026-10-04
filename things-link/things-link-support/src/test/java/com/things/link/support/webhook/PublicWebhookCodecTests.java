package com.things.link.support.webhook;
import com.things.link.shared.message.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class PublicWebhookCodecTests {
    final PublicWebhookCodec codec=new PublicWebhookCodec(JsonMapper.builder().build());
    PublicWebhookEvent event(String payload){UUID id=UUID.randomUUID();return new PublicWebhookEvent(id,"device.property.report",id,id,2,"device",id,id,Instant.now(),Instant.now(),null,payload);}
    @Test void preservesPrecisionAndCanonicalBytesAcrossTransport(){
        var source=codec.prepare(event("{\"z\":9007199254740993,\"a\":0.12345678901234567890123456789}"));
        var mapper=JsonMapper.builder().build();var restored=mapper.readValue(mapper.writeValueAsBytes(source),PublicWebhookSource.class);
        codec.validate(restored);assertThat(restored).isEqualTo(source);assertThat(restored.eventText()).contains("9007199254740993","0.12345678901234567890123456789");
        assertThat(restored.event().payloadJson()).isEqualTo("{}");assertThat(restored.toString()).doesNotContain("9007199254740993");
    }
    @Test void oversizedSourceKeepsOnlyStableFullDigest(){var e=event("{\"secret\":\""+"x".repeat(300000)+"\"}");var source=codec.prepare(e);codec.validate(source);assertThat(source.eventText()).isNull();assertThat(source.hash()).hasSize(64);assertThat(codec.prepare(e)).isEqualTo(source);assertThat(JsonMapper.builder().build().writeValueAsString(source)).doesNotContain("secret");}
    @Test void utf8WireLimitIncludesEnvelope(){var source=codec.prepare(event("{\"text\":\""+"中".repeat(90000)+"\"}"));assertThat(source.eventText()).isNull();}
    @Test void changedIdentityHashAndUnknownBodyFieldsFailClosed(){var source=codec.prepare(event("{\"x\":1}"));assertThatThrownBy(()->codec.validate(new PublicWebhookSource(1,source.event(),"a".repeat(64),source.eventText()))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->codec.validate(new PublicWebhookSource(1,source.event(),source.hash(),source.eventText().replace("\"x\":1","\"x\":2")))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->codec.validate(new PublicWebhookSource(1,source.event(),source.hash(),source.eventText().replace("\"schemaVersion\":1","\"schemaVersion\":2")))).isInstanceOf(IllegalArgumentException.class);}
    @Test void rejectsWrongVersionAndPayloadSmuggling(){var e=event("{\"x\":1}");assertThatThrownBy(()->new PublicWebhookSource(1,e,"a".repeat(64),null)).isInstanceOf(IllegalArgumentException.class);var source=codec.prepare(e);assertThatThrownBy(()->new PublicWebhookSource(2,source.event(),source.hash(),source.eventText())).isInstanceOf(IllegalArgumentException.class);}
}
