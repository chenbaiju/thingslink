package com.things.link.integration.application;
import com.things.link.integration.domain.RealtimeTicketCredential;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class RealtimeTicketParserTests {
    @Test void canonicalCredentialCannotBeAnotherAudienceOrLeakToString(){
        var value=RealtimeTicketCredential.generate(UUID.randomUUID());
        assertThat(value.reveal()).hasSize(86);assertThat(value.digest()).hasSize(64);
        assertThat(RealtimeTicketCredential.parse(value.reveal())).isPresent();
        assertThat(RealtimeTicketCredential.parse(value.reveal().replace("tcrt1","tcak1"))).isEmpty();
        assertThat(RealtimeTicketCredential.parse(value.reveal()+"=")).isEmpty();
        assertThat(value.toString()).doesNotContain(value.reveal(),value.digest());
    }
    @Test void closedProtocolEventsAndStrictJsonCannotExpandScope(){
        String device="{\"deviceId\":\""+UUID.randomUUID()+"\",\"expectedModelVersionId\":\""+UUID.randomUUID()+"\",\"propertyKeys\":[\"value\"]}";
        String valid="{\"protocol\":\"MQTT\",\"eventTypes\":[\"device.property.report\"],\"devices\":["+device+"]}";
        var parser=new RealtimeTicketParser();assertThat(parser.parse(valid.getBytes(StandardCharsets.UTF_8)).propertyCount()).isEqualTo(1);
        for(String bad:java.util.List.of(valid.replace("MQTT","HTTP"),valid.replace("device.property.report","*"),valid+"{}",valid.replace("\"MQTT\"","\"MQTT\",\"protocol\":\"WS\""),valid.replace("[\"value\"]","[\"value\",\"value\"]"),"\ufeff"+valid))
            assertThatThrownBy(()->parser.parse(bad.getBytes(StandardCharsets.UTF_8))).isInstanceOf(com.things.link.shared.error.BusinessException.class);
        assertThatThrownBy(()->parser.parse(new byte[]{(byte)0xff})).isInstanceOf(com.things.link.shared.error.BusinessException.class);
    }
}
