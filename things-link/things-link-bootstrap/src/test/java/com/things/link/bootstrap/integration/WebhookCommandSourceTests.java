package com.things.link.bootstrap.integration;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.*;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.telemetry.application.*;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** ADR0203：真实受理与终态状态机生成来源，不构造公开事件。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.webhook.enabled=true","things-link.integration.webhook.current-signing-key-id=a","things-link.integration.webhook.signing-keys-json={\"a\":\"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=\"}"})
class WebhookCommandSourceTests extends OpenCommandHttpFixture {
    @Autowired DeviceCommandService commands;
    @Autowired DeviceCommandRepository repository;
    @Test void acceptanceAckAndDispatchAreNotCompletionAndReplayIsStable() {
        UUID id=submitCommand(); assertThat(sources()).isEmpty();
        assertThat(commands.markDispatched(dispatch(id),Instant.now())).isTrue();
        assertThat(commands.applyReply(reply(id,device,DeviceCommandReply.Status.ACK,"{}"))).isTrue();
        assertThat(sources()).isEmpty();
        var reply=reply(id,device,DeviceCommandReply.Status.SUCCESS,"{\"exact\":9007199254740993.12345678901234567890123456789}");
        assertThat(commands.applyReply(reply)).isTrue();
        var source=only(id,"SUCCEEDED"); var payload=json.readTree(source.eventText()).path("payload");
        assertThat(payload.path("responseJson").asString()).isEqualTo(owner.queryForObject("SELECT response_payload::text FROM ts_device_command WHERE id=?",String.class,id));
        assertThat(payload.path("responseJson").asString()).contains("9007199254740993.12345678901234567890123456789");
        assertThat(payload.path("operationType").asString()).isEqualTo("COMMAND");
        assertThat(payload.path("attemptCount").asString()).isEqualTo("1");
        assertThat(commands.applyReply(reply)).isFalse();
        assertThat(commands.applyReply(reply(id,device,DeviceCommandReply.Status.FAILED,"{}"))).isFalse();
        assertThat(sources()).containsExactly(source);
    }
    @Test void failedDeviceReplyAndInvalidOutputUsePersistedFailure() {
        UUID id=submitCommand(); assertThat(commands.applyReply(reply(id,device,DeviceCommandReply.Status.FAILED,"{}"))).isTrue();
        assertThat(json.readTree(only(id,"FAILED").eventText()).path("payload").path("failureCode").asString()).isEqualTo("DEVICE_REFUSED");
    }
    @Test void invalidSuccessSchemaIsFailedCompletion() {
        owner.update("UPDATE dev_command_definition SET output_schema='{\"type\":\"object\",\"required\":[\"required\"]}'::jsonb WHERE id=?",definition);
        UUID id=submitCommand(); assertThat(commands.applyReply(reply(id,device,DeviceCommandReply.Status.SUCCESS,"{}"))).isTrue();
        assertThat(json.readTree(only(id,"FAILED").eventText()).path("payload").path("failureCode").asString()).isEqualTo("OUTPUT_SCHEMA_INVALID");
    }
    @Test void wrongConnectionTenantAndProjectCannotCreateFacts() {
        UUID id=submitCommand();
        assertThat(commands.applyReply(reply(id,unbound,DeviceCommandReply.Status.SUCCESS,"{}"))).isFalse();
        var real=reply(id,device,DeviceCommandReply.Status.SUCCESS,"{}");
        assertThat(commands.applyReply(new DeviceCommandReply(real.messageId(),UUID.randomUUID(),project,device,id,real.occurredAt(),real.receivedAt(),real.status(),"{}",null,null,"scope"))).isFalse();
        assertThat(commands.applyReply(new DeviceCommandReply(real.messageId(),tenant,UUID.randomUUID(),device,id,real.occurredAt(),real.receivedAt(),real.status(),"{}",null,null,"scope"))).isFalse();
        assertThat(sources()).isEmpty(); assertThat(status(id)).isEqualTo("ACCEPTED");
    }
    @Test void sourceFailureRollsBackAttemptCommandAndInternalTerminalThenSameReplyRecovers() {
        UUID id=submitCommand(); var reply=reply(id,device,DeviceCommandReply.Status.SUCCESS,"{}");
        String fn="test_command_source_"+project.toString().replace("-","");
        owner.execute("CREATE FUNCTION "+fn+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+project+"'::uuid AND NEW.event_type='PUBLIC_WEBHOOK_SOURCE' THEN RAISE EXCEPTION 'source unavailable' USING ERRCODE='23505'; END IF; RETURN NEW; END $$");
        owner.execute("CREATE TRIGGER "+fn+" BEFORE INSERT ON sys_outbox_event FOR EACH ROW EXECUTE FUNCTION "+fn+"()");
        try { assertThatThrownBy(()->commands.applyReply(reply)).isInstanceOf(DataAccessException.class); }
        finally {owner.execute("DROP TRIGGER "+fn+" ON sys_outbox_event");owner.execute("DROP FUNCTION "+fn+"()");}
        assertThat(status(id)).isEqualTo("ACCEPTED"); assertThat(sources()).isEmpty();
        assertThat(owner.queryForObject("SELECT status FROM ts_device_command_attempt WHERE command_id=?",String.class,id)).isEqualTo("PENDING");
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='DEVICE_COMMAND_TERMINAL'",Integer.class,project)).isZero();
        assertThat(commands.applyReply(reply)).isTrue(); only(id,"SUCCEEDED");
    }
    @ParameterizedTest @ValueSource(strings={"ARCHIVED","DELETING"})
    void projectFreezeStillSettlesInternalCommandWithoutPublicSource(String state) {
        UUID id=submitCommand(); owner.update("UPDATE sys_project SET status=?,lifecycle_generation=lifecycle_generation+1 WHERE id=?",state,project);
        assertThat(commands.applyReply(reply(id,device,DeviceCommandReply.Status.SUCCESS,"{}"))).isTrue();
        assertThat(status(id)).isEqualTo("SUCCEEDED"); assertThat(sources()).isEmpty();
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='DEVICE_COMMAND_TERMINAL'",Integer.class,project)).isEqualTo(1);
    }
    @Test void sourceCapturesCurrentWritableGeneration() {
        UUID id=submitCommand();owner.update("UPDATE sys_project SET lifecycle_generation=lifecycle_generation+2 WHERE id=?",project);
        commands.applyReply(reply(id,device,DeviceCommandReply.Status.SUCCESS,"{}"));
        assertThat(only(id,"SUCCEEDED").event().projectGeneration()).isEqualTo(owner.queryForObject("SELECT lifecycle_generation FROM sys_project WHERE id=?",Long.class,project));
    }
    @Test void dispatchExhaustionProducesOneTimedOutSource() {
        UUID id=submitCommand();
        for(int attempt=1;attempt<=3;attempt++) {
            commands.recordDispatchFailure(dispatch(id),DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED,Instant.now());
            if(attempt<3){ assertThat(sources()).isEmpty();owner.update("UPDATE ts_device_command SET next_attempt_at=now()-interval '1 second' WHERE id=?",id);due(id); }
        }
        assertThat(json.readTree(only(id,"TIMED_OUT").eventText()).path("payload").path("failureCode").asString()).isEqualTo("DISPATCH_RETRY_EXHAUSTED");
        commands.recordDispatchFailure(dispatch(id),DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED,Instant.now()); assertThat(sources()).hasSize(1);
    }
    @Test void responseTimeoutRetriesThenPublishesOnlyFinalTimeout() {
        UUID id=submitCommand();
        for(int attempt=1;attempt<=3;attempt++) {
            commands.markDispatched(dispatch(id),Instant.now());
            owner.update("UPDATE ts_device_command SET deadline_at=now()-interval '1 second',next_attempt_at=now()-interval '1 second' WHERE id=?",id);
            due(id);if(attempt<3)assertThat(sources()).isEmpty();
        }
        assertThat(json.readTree(only(id,"TIMED_OUT").eventText()).path("payload").path("failureCode").asString()).isEqualTo("RESPONSE_TIMEOUT");
    }
    @Test void unavailableReceiverIsTerminal() {
        UUID id=submitCommand();owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?",device);
        assertThat(commands.admitDispatch(dispatch(id))).isFalse();
        assertThat(json.readTree(only(id,"FAILED").eventText()).path("payload").path("failureCode").asString()).isEqualTo("COMMAND_ROUTE_UNAVAILABLE");
    }
    @Test void propertySetIsExplicitlyTyped() {
        UUID id=propertySet(); commands.applyReply(reply(id,device,DeviceCommandReply.Status.SUCCESS,"{}"));
        var payload=json.readTree(only(id,"SUCCEEDED").eventText()).path("payload");
        assertThat(payload.path("operationType").asString()).isEqualTo("PROPERTY_SET");assertThat(payload.path("commandKey").isNull()).isTrue();
    }
    @Test void propertyCapabilityLossIsTerminal() {
        UUID id=propertySet();owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES (?,?,?,'HTTP')",device,tenant,project);
        assertThat(commands.admitDispatch(dispatch(id))).isFalse();
        assertThat(json.readTree(only(id,"FAILED").eventText()).path("payload").path("failureCode").asString()).isEqualTo("PROPERTY_SET_PROTOCOL_UNSUPPORTED");
    }
    @Test void frozenAdmissionPreservesInternalRejectionOnly() {
        UUID id=submitCommand();owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",project);
        assertThat(commands.admitDispatch(dispatch(id))).isFalse();assertThat(status(id)).isEqualTo("FAILED");assertThat(sources()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"HTTP","COAP"})
    void pullFinalTimeoutPublishesOnlyAfterActualClaim(String protocol) {
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES (?,?,?,?)",device,tenant,project,protocol);
        UUID id=submitCommand();assertThat(sources()).isEmpty();
        for(int attempt=1;attempt<=3;attempt++){
            assertThat(claims.claim(new DeviceCommandClaimPort.ClaimRequest(tenant,project,device,java.time.Duration.ofSeconds(60),10))).hasSize(1);
            owner.update("UPDATE ts_device_command SET deadline_at=now()-interval '1 second',next_attempt_at=now()-interval '1 second' WHERE id=?",id);
        }
        due(id);only(id,"TIMED_OUT");
    }
    @Autowired DeviceCommandClaimPort claims;
    UUID propertySet(){
        owner.update("INSERT INTO dev_property_definition(id,tenant_id,project_id,device_type_id,property_key,name,data_type,access_type) VALUES (?,?,?,?,'value','值','NUMBER','SHARED')",Uuid7.generate(),tenant,project,type);
        TenantContext.set(new TenantScope(tenant,project,account));
        try{return tx.execute(s->{scope.establish(tenant,project);return commands.submitRuleAction(new RuleDeviceActionRequest(Uuid7.generate(),project,account,device,DeviceCommandDispatch.OperationType.PROPERTY_SET,null,json.readTree("{\"value\":2}"))).commandId();});}
        finally {TenantContext.clear();}
    }
    @org.junit.jupiter.api.AfterEach void removeProperty(){owner.update("DELETE FROM dev_property_definition WHERE project_id=?",project);}
    UUID submitCommand(){return service.submit(principal,device,Uuid7.generate().toString(),"start",json.readTree("{}")).commandId();}
    DeviceCommandReply reply(UUID id,UUID receiver,DeviceCommandReply.Status status,String output){return new DeviceCommandReply(Uuid7.generate(),tenant,project,receiver,id,Instant.now(),Instant.now(),status,output,status==DeviceCommandReply.Status.FAILED?"DEVICE_REFUSED":null,null,"command-source");}
    DeviceCommandDispatch dispatch(UUID id){return json.readValue(owner.queryForObject("SELECT payload FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_DISPATCH' ORDER BY created_at DESC,id DESC LIMIT 1",String.class,id),DeviceCommandDispatch.class);}
    void due(UUID id){var due=repository.claimDue(100).stream().filter(v->v.commandId().equals(id)).findFirst().orElseThrow();commands.processDue(due);}
    String status(UUID id){return owner.queryForObject("SELECT status FROM ts_device_command WHERE id=?",String.class,id);}
    List<PublicWebhookSource> sources(){return owner.queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE' ORDER BY created_at,id",String.class,project).stream().map(v->json.readValue(v,PublicWebhookSource.class)).toList();}
    PublicWebhookSource only(UUID id,String status){
        assertThat(sources()).hasSize(1);var source=sources().getFirst();assertThat(source.event().resourceType()).isEqualTo("command");
        assertThat(source.event().resourceId()).isEqualTo(id);assertThat(source.event().deviceId()).isEqualTo(device);assertThat(source.event().eventType()).isEqualTo("command.completed");
        var event=json.readValue(owner.queryForObject("SELECT payload FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_TERMINAL'",String.class,id),DeviceCommandTerminalEvent.class);
        assertThat(source.event().eventId()).isEqualTo(event.eventId()); assertThat(json.readTree(source.eventText()).path("payload").path("status").asString()).isEqualTo(status);
        assertThat(source.event().occurredAt()).isEqualTo(owner.queryForObject("SELECT completed_at FROM ts_device_command WHERE id=?",java.sql.Timestamp.class,id).toInstant());return source;
    }
}
