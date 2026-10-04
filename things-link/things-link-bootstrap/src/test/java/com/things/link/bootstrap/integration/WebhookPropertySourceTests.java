package com.things.link.bootstrap.integration;

import com.things.link.device.application.*;
import com.things.link.telemetry.application.PropertyIngestionService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real shadow CAS and full standard ingestion; source is read back from the committed Outbox. */
class WebhookPropertySourceTests extends WebhookFixture {
    /** 本夹具只验原事务；旧AFTER_COMMIT实时网络由独立真实Kafka用例验证，禁止误连开发Broker。 */
    @org.springframework.test.context.bean.override.mockito.MockitoBean(enforceOverride=true)
    private com.things.link.ingestion.application.RealtimeKafkaPublisher isolatedLegacyRealtimePublisher;
    @Autowired DeviceIngestionService devices;
    @Autowired PropertyIngestionService ingestion;
    @Autowired ObjectMapper mapper;
    @Override String modelSnapshot(){return "{\"properties\":{\"value\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"},\"empty\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"}},\"events\":{},\"commands\":{}}";}
    @AfterEach void cleanupPropertyFacts(){
        for(String table:List.of("sys_outbox_event","ts_property_point_internal","ts_device_message_log","ts_property_aggregate_backfill","sys_message_log_inbox","sys_inbox_message"))owner.update("DELETE FROM "+table+" WHERE project_id=?",project);
    }
    int sources(){return owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'",Integer.class,project);}
    PublicWebhookSource source(){return mapper.readValue(owner.queryForObject("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'",String.class,project),PublicWebhookSource.class);}
    JsonNode payload(){return mapper.readTree(source().eventText()).get("payload");}
    void merge(UUID message,Instant at,Map<String,Object> values,boolean rollback){tx.execute(s->{rls.establish(tenant,project);var context=devices.validateReportedProperties(tenant,project,device,"1.0.0",Instant.now(),values);devices.mergeReportedProperties(context,project,device,values,at,message,"property-source");if(rollback)s.setRollbackOnly();return null;});}
    StandardUplinkMessage standard(UUID id){var now=Instant.now();return new StandardUplinkMessage(id,tenant,project,device,null,TransportProtocol.MQTT,StandardUplinkMessage.Direction.UP,StandardUplinkMessage.Type.PROPERTY_REPORT,"1.0.0",now,now,"property-source",100,Map.of("value",new BigDecimal("0.12345678901234567890123456789")));}
    @Test void sourceRetainsAcceptedPrecisionIdentityAndDatabaseRevision(){UUID original=Uuid7.generate();Instant at=Instant.now();merge(original,at,Map.of("value",new BigDecimal("0.12345678901234567890123456789")),false);var fact=source();var body=payload();assertThat(fact.event().eventId()).isNotEqualTo(original);assertThat(fact.event().eventType()).isEqualTo("device.property.report");assertThat(fact.event().occurredAt()).isEqualTo(at.truncatedTo(java.time.temporal.ChronoUnit.MICROS));assertThat(body.path("sourceMessageId").asString()).isEqualTo(original.toString());assertThat(body.path("modelVersionId").asString()).isEqualTo(model.toString());assertThat(body.path("modelVersion").asString()).isEqualTo("1.0.0");assertThat(body.path("properties").path("value").path("valueJson").asString()).isEqualTo("0.12345678901234567890123456789");assertThat(body.path("properties").path("value").path("reportedRevision").asString()).isEqualTo(owner.queryForObject("SELECT reported_revisions->>'value' FROM dev_shadow WHERE device_id=?",String.class,device));assertThat(body.path("properties").path("value").path("dataType").asString()).isEqualTo("NUMBER");assertThat(fact.event().recordedAt()).isNotNull();}
    @Test void partiallyLateReportContainsOnlyAcceptedKeys(){owner.update("UPDATE dev_shadow SET reported_at=jsonb_set(reported_at,'{value}',to_jsonb(?::text)) WHERE device_id=?",Instant.now().plusSeconds(60).toString(),device);merge(Uuid7.generate(),Instant.now(),Map.of("value",1,"empty",2),false);assertThat(payload().path("properties").has("value")).isFalse();assertThat(payload().path("properties").path("empty").path("valueJson").asString()).isEqualTo("2");}
    @Test void whollyLateReportHasNoSource(){merge(Uuid7.generate(),Instant.parse("2026-09-19T00:00:00Z"),Map.of("value",1),false);assertThat(sources()).isZero();}
    @Test void originalRollbackRestoresShadowAndRemovesSource(){String before=owner.queryForObject("SELECT reported::text FROM dev_shadow WHERE device_id=?",String.class,device);merge(Uuid7.generate(),Instant.now(),Map.of("value",1),true);assertThat(sources()).isZero();assertThat(owner.queryForObject("SELECT reported::text FROM dev_shadow WHERE device_id=?",String.class,device)).isEqualTo(before);}
    @Test void fullStandardReplayDoesNotCreateAnotherSource(){var message=standard(Uuid7.generate());assertThat(ingestion.ingest(message)).isTrue();String first=source().eventText();assertThat(ingestion.ingest(message)).isFalse();assertThat(sources()).isEqualTo(1);assertThat(source().eventText()).isEqualTo(first);assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='AUTOMATION_PROPERTY_ACCEPTED'",Integer.class,project)).isEqualTo(1);}
    @Test void outboxFailureRollsBackInboxHistoryShadowAndLog(){var message=standard(Uuid7.generate());String before=owner.queryForObject("SELECT reported::text FROM dev_shadow WHERE device_id=?",String.class,device);owner.execute("REVOKE INSERT ON sys_outbox_event FROM thingslink_app");try{assertThatThrownBy(()->ingestion.ingest(message)).isInstanceOf(org.springframework.dao.DataAccessException.class);}finally{owner.execute("GRANT INSERT ON sys_outbox_event TO thingslink_app");}assertThat(sources()).isZero();for(String table:List.of("sys_inbox_message","ts_property_point_internal","ts_device_message_log"))assertThat(owner.queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,project)).isZero();assertThat(owner.queryForObject("SELECT reported::text FROM dev_shadow WHERE device_id=?",String.class,device)).isEqualTo(before);}
    @Test void sourceFreezesCurrentProjectGenerationBeforeShadowWrite(){owner.update("UPDATE sys_project SET lifecycle_generation=3 WHERE id=?",project);merge(Uuid7.generate(),Instant.now(),Map.of("value",1),false);assertThat(source().event().projectGeneration()).isEqualTo(3);}
    @Test void archivedProjectCannotEmitAnAcceptedShadowSource(){owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",project);assertThatThrownBy(()->merge(Uuid7.generate(),Instant.now(),Map.of("value",1),false)).isInstanceOf(com.things.link.shared.error.BusinessException.class);assertThat(sources()).isZero();}
    @Test void historyOnlyContextCannotAdvanceShadowOrSource(){tx.execute(s->{rls.establish(tenant,project);var context=devices.validateReportedProperties(tenant,project,device,"1.0.0",Instant.now(),Map.of("value",1));var old=new DeviceIngestionContext(context.tenantId(),context.thingModelVersionId(),context.versionNumber(),context.schemaDigest(),context.digestAlgorithm(),context.modelSnapshot(),DeviceIngestionContext.Eligibility.HISTORY_ONLY,context.propertyDataTypes(),false);assertThatThrownBy(()->devices.mergeReportedProperties(old,project,device,Map.of("value",1),Instant.now(),Uuid7.generate(),"old")).isInstanceOf(IllegalArgumentException.class);s.setRollbackOnly();return null;});assertThat(sources()).isZero();}
}
