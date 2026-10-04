package com.things.link.bootstrap.integration;
import com.things.link.support.webhook.PublicWebhookSourceWriter;
import com.things.link.shared.message.*;
import com.things.link.shared.id.Uuid7;
import com.things.link.integration.application.*;
import com.things.link.integration.infrastructure.WebhookSourceKafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class WebhookSourceTests extends WebhookFixture {
    @Autowired PublicWebhookSourceWriter source;
    @Autowired WebhookEventAdmission admission;
    @Autowired WebhookSourceKafkaConsumer consumer;
    @Autowired ObjectMapper mapper;
    @AfterEach void cleanupSources(){owner.update("DELETE FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'",project);}
    PublicWebhookEvent event(String payload){var now=source.recordedAt();return new PublicWebhookEvent(Uuid7.generate(),"device.property.report",tenant,project,0,"device",device,device,now,now,null,payload);}
    PublicWebhookSource read(){return mapper.readValue(owner.queryForObject("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'",String.class,project),PublicWebhookSource.class);}
    @Test void originalRollbackRemovesSourceAndRequiresTransaction(){assertThatThrownBy(()->source.append(null)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);tx.execute(s->{source.append(event("{}"));s.setRollbackOnly();return null;});assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,project)).isZero();}
    @Test void committedDescriptorFreezesTimePrecisionAndDuplicateIdentity(){create(Uuid7.generate());var original=tx.execute(s->{var e=event("{\"value\":0.12345678901234567890123456789}");source.append(e);return e;});var descriptor=read();assertThat(descriptor.event().recordedAt()).isEqualTo(original.recordedAt());assertThat(admission.acceptSource(descriptor)).isEqualTo("ACCEPTED");assertThat(admission.acceptSource(read())).isEqualTo("DUPLICATE");assertThat(rows("integ_webhook_delivery")).isEqualTo(1);assertThat(owner.queryForObject("SELECT event_text FROM integ_webhook_event WHERE project_id=?",String.class,project)).contains("0.12345678901234567890123456789");}
    @Test void oversizedOriginalProducesDurableRejectionWithoutBody(){create(Uuid7.generate());tx.execute(s->{source.append(event("{\"private\":\""+"x".repeat(300000)+"\"}"));return null;});var descriptor=read();assertThat(descriptor.eventText()).isNull();assertThat(admission.acceptSource(descriptor)).isEqualTo("OVERSIZE");assertThat(admission.acceptSource(descriptor)).isEqualTo("DUPLICATE");assertThat(rows("integ_webhook_delivery")).isZero();}
    @Test void mismatchedKeyAndInvalidBodyAreRedactedWithoutAdmission(){tx.execute(s->{source.append(event("{\"secret\":1}"));return null;});var bytes=mapper.writeValueAsBytes(read());assertThatThrownBy(()->consumer.consume(new ConsumerRecord<>(PublicWebhookSource.TOPIC,0,0,"wrong".getBytes(StandardCharsets.UTF_8),bytes))).hasMessage("WEBHOOK_SOURCE_NOT_ADMITTED").hasNoCause();assertThat(rows("integ_webhook_event")).isZero();assertThatThrownBy(()->consumer.consume(new ConsumerRecord<>(PublicWebhookSource.TOPIC,0,0,device.toString().getBytes(StandardCharsets.UTF_8),"secret invalid".getBytes(StandardCharsets.UTF_8)))).hasMessage("WEBHOOK_SOURCE_NOT_ADMITTED").hasNoCause();}
    @Test void sourceWriteFailureRollsBackCallerBusinessWrite(){owner.execute("REVOKE INSERT ON sys_outbox_event FROM thingslink_app");try{assertThatThrownBy(()->tx.execute(s->{rls.establish(tenant,project);jdbc.update("UPDATE dev_device SET name='rollback-source' WHERE id=?",device);source.append(event("{}"));return null;})).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(owner.queryForObject("SELECT name FROM dev_device WHERE id=?",String.class,device)).isNotEqualTo("rollback-source");}finally{owner.execute("GRANT INSERT ON sys_outbox_event TO thingslink_app");}}
}
