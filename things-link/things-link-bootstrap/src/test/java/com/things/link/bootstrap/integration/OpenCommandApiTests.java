package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.telemetry.application.PublicCommandIdentity;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 公开Key命令真实HTTP/PG原事务与持久恢复，不把202认作设备执行成功。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="things-link.integration.api-key.enabled=true")
class OpenCommandApiTests extends OpenCommandHttpFixture {
    @Autowired com.things.link.telemetry.application.DeviceCommandClaimPort claims;
    @Test void httpAcceptanceRecoveryAndAnotherKeyRemainSeparate()throws Exception{
        var first=submit(secret,"same","{}");assertThat(first.statusCode()).isEqualTo(202);
        String id=json.readTree(first.body()).path("commandId").asString();assertThat(id).isNotBlank();
        assertThat(count("integ_command_receipt")).isEqualTo(1);assertThat(count("ts_device_command_attempt")).isEqualTo(1);assertThat(count("sys_outbox_event")).isEqualTo(1);
        var replay=submit(secret,"same","{}");assertThat(replay.statusCode()).isEqualTo(409);assertThat(json.readTree(replay.body()).path("code").asInt()).isEqualTo(10014);
        var recovered=get("/devices/"+device+"/commands/by-key?idempotencyKey=same",secret);assertThat(recovered.statusCode()).isEqualTo(200);assertThat(recovered.body()).contains(id);
        String other=key();assertThat(get("/devices/"+device+"/commands/"+id,other).statusCode()).isEqualTo(404);
        var second=submit(other,"same","{}");assertThat(second.statusCode()).isEqualTo(202);assertThat(json.readTree(second.body()).path("commandId").asString()).isNotEqualTo(id);
        assertThat(first.body()).doesNotContain("requestedBy","idempotencyKey","connectionDeviceId",secret);
    }
    @Test void exactDecimalDomainReplayAndChangedInputAreDistinguished(){
        var parser=new OpenCommandRequestParser();var input=parser.parse("{\"commandKey\":\"start\",\"input\":{\"x\":1.12345678901234567890123456789}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var first=service.submit(principal,device,"precise",input.commandKey(),input.input());
        assertThat(service.submit(principal,device,"precise",input.commandKey(),input.input()).commandId()).isEqualTo(first.commandId());
        assertThatThrownBy(()->service.submit(principal,device,"precise","start",json.readTree("{\"x\":2}"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode().code()).isEqualTo(10009));
        assertThat(count("integ_command_receipt")).isEqualTo(1);
    }
    @Test void receiptFailureRollsBackCommandAttemptAndOutbox(){
        String fn="test_public_receipt_"+project.toString().replace("-","");
        owner.execute("CREATE FUNCTION "+fn+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+project+"'::uuid THEN RAISE EXCEPTION 'injected receipt failure'; END IF; RETURN NEW; END $$");
        owner.execute("CREATE TRIGGER "+fn+" BEFORE INSERT ON integ_command_receipt FOR EACH ROW EXECUTE FUNCTION "+fn+"()");
        try{assertThatThrownBy(()->service.submit(principal,device,"rollback","start",json.readTree("{}"))).isInstanceOf(DataAccessException.class);}
        finally{owner.execute("DROP TRIGGER "+fn+" ON integ_command_receipt");owner.execute("DROP FUNCTION "+fn+"()");}
        for(String table:List.of("integ_command_receipt","ts_device_command","ts_device_command_attempt","sys_outbox_event"))assertThat(count(table)).isZero();
    }
    @Test void oldPrefixCannotBeClaimedAndOriginCannotBeRewritten(){
        String internal=PublicCommandIdentity.internalKey(principal.keyId(),"legacy");
        owner.update("""
            INSERT INTO ts_device_command(id,tenant_id,project_id,target_device_id,connection_device_id,command_definition_id,
                command_key,input_schema,output_schema,request_payload,status,idempotency_key,requested_by,
                timeout_seconds,attempt_count,max_attempts,next_attempt_at,trace_id,accepted_at)
            VALUES (?,?,?,?,?,?,'start','{}','{}','{}','ACCEPTED',?,?,30,0,3,now(),'legacy',now())
            """,Uuid7.generate(),tenant,project,device,device,definition,internal,account);
        assertThatThrownBy(()->service.submit(principal,device,"legacy","start",json.readTree("{}"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode().code()).isEqualTo(10009));
        assertThat(count("integ_command_receipt")).isZero();
        var result=service.submit(principal,device,"real","start",json.readTree("{}"));
        assertThatThrownBy(()->owner.update("UPDATE ts_device_command SET integration_key_id=NULL WHERE id=?",result.commandId())).isInstanceOf(DataAccessException.class);
    }
    @Test void appCannotDeleteReceiptAndLostCommandLeavesTombstone(){
        var result=service.submit(principal,device,"tombstone","start",json.readTree("{}"));
        assertThat(tx.<Integer>execute(s->{scope.establish(tenant,project);return jdbc.queryForObject("SELECT count(*) FROM integ_command_receipt",Integer.class);})).isEqualTo(1);
        assertThat(tx.<Integer>execute(s->{scope.establish(UUID.randomUUID(),project);return jdbc.queryForObject("SELECT count(*) FROM integ_command_receipt",Integer.class);})).isZero();
        assertThat(tx.<Integer>execute(s->{scope.establish(tenant,UUID.randomUUID());return jdbc.queryForObject("SELECT count(*) FROM integ_command_receipt",Integer.class);})).isZero();
        assertThatThrownBy(()->tx.execute(s->{scope.establish(tenant,project);return jdbc.update("DELETE FROM integ_command_receipt WHERE project_id=?",project);})).isInstanceOf(DataAccessException.class);
        owner.update("DELETE FROM ts_device_command_attempt WHERE command_id=?",result.commandId());owner.update("DELETE FROM ts_device_command WHERE id=?",result.commandId());
        assertThatThrownBy(()->service.submit(principal,device,"tombstone","start",json.readTree("{}"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode().code()).isEqualTo(10014));
        assertThatThrownBy(()->service.recover(principal,device,"tombstone")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode().code()).isEqualTo(10014));
        assertThat(count("ts_device_command")).isZero();assertThat(count("integ_command_receipt")).isEqualTo(1);
    }
    @Test void httpAndCoapHaveNoPushAttemptAtAcceptance(){
        for(String protocol:List.of("HTTP","COAP")){
            owner.update("DELETE FROM dev_access_binding WHERE device_id=?",device);
            owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES (?,?,?,?)",device,tenant,project,protocol);
            var result=service.submit(principal,device,protocol,"start",json.readTree("{}"));assertThat(result.attemptCount()).isZero();
            assertThat(count("sys_outbox_event")).isZero();
            var claimed=claims.claim(new com.things.link.telemetry.application.DeviceCommandClaimPort.ClaimRequest(tenant,project,device,java.time.Duration.ofSeconds(60),10));
            assertThat(claimed.stream().map(com.things.link.telemetry.application.DeviceCommandClaimPort.Claimed::commandId)).contains(result.commandId());
        }
        assertThat(count("ts_device_command_attempt")).isZero();assertThat(count("sys_outbox_event")).isZero();assertThat(count("integ_command_receipt")).isEqualTo(2);
        assertThat(count("ts_device_command_claim")).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT sum(attempt_count) FROM ts_device_command WHERE project_id=?",Integer.class,project)).isEqualTo(2);
    }
    @Test void stalePrincipalCannotWriteAfterRevocationOrRoleLoss(){
        keys.revoke(tenant,project,account,Uuid7.generate(),principal.keyId());
        assertThatThrownBy(()->service.submit(principal,device,"revoked","start",json.readTree("{}"))).isInstanceOf(BusinessException.class);
        var other=authentication.authenticate(key(),"127.0.0.1");
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",project,account);
        assertThatThrownBy(()->service.submit(other,device,"viewer","start",json.readTree("{}"))).isInstanceOf(BusinessException.class);
        assertThat(count("ts_device_command")).isZero();
    }
}
