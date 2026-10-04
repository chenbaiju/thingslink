package com.things.link.bootstrap.integration;

import com.things.link.integration.application.*;
import com.things.link.integration.domain.WebhookDeliveryRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** ADR0211：实际Console安全链、管理原事务和墓碑恢复。 */
class WebhookHttpTests extends WebhookFixture {
    @Autowired WebhookEventAdmission admission; @Autowired WebhookDeliveryState state;
    @Autowired com.things.link.enduser.application.AppTokenIssuer appTokens;
    String base(){return "/api/v1/projects/"+project+"/webhooks";}
    String createBody(UUID op){return json.writeValueAsString(Map.of("operationId",op,"name","receiver","targetUrl","https://receiver.example.com/events","eventTypes",List.of("device.online"),"deviceIds",List.of(device)));}
    java.net.http.HttpResponse<String> write(String method,String path,String body)throws Exception{return request(method,path,body,token(),Map.of("Idempotency-Key",Uuid7.generate().toString()));}
    String createHttp()throws Exception{var response=write("POST",base(),createBody(Uuid7.generate()));assertThat(response.statusCode()).as(response.body()).isEqualTo(201);return json.readTree(response.body()).path("subscription").path("id").asText();}
    @Test void firstSecretTombstoneAndDomainReplayNeverRevealSecretAgain()throws Exception {
        UUID op=Uuid7.generate();String body=createBody(op);var headers=Map.of("Idempotency-Key",op.toString());
        var first=request("POST",base(),body,token(),headers);assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        var value=json.readTree(first.body());String secret=value.path("signingSecret").asText();assertThat(Base64.getDecoder().decode(secret)).hasSize(32);
        assertThat(first.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var second=request("POST",base(),body,token(),headers);assertThat(second.statusCode()).isEqualTo(409);assertThat(json.readTree(second.body()).path("code").asInt()).isEqualTo(10014);
        var replay=write("POST",base(),body);assertThat(replay.statusCode()).isEqualTo(201);assertThat(json.readTree(replay.body()).has("signingSecret")).isFalse();
        var recovered=request("GET",base()+"/operations/"+op,null,token(),Map.of());assertThat(recovered.statusCode()).isEqualTo(200);assertThat(recovered.body()).doesNotContain(secret,"signingSecret");
        assertThat(json.readTree(recovered.body()).path("resultRevision").asText()).isEqualTo("1");assertThat(rows("integ_webhook_subscription")).isEqualTo(1);
    }
    @Test void lifecycleAndTargetChangeUseExpectedRevisionAndFreshSecret()throws Exception {
        String id=createHttp();long revision=1;
        for(String action:List.of("pause","resume","rotate")) {
            var response=write("POST",base()+"/"+id+"/"+action,json.writeValueAsString(Map.of("operationId",Uuid7.generate(),"expectedRevision",Long.toString(revision++))));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(json.readTree(response.body()).has("signingSecret")).isEqualTo(action.equals("rotate"));
        }
        var body=(tools.jackson.databind.node.ObjectNode)json.readTree(createBody(Uuid7.generate()));body.put("expectedRevision","4");body.put("targetUrl","https://changed.example.com/events");
        var changed=write("PUT",base()+"/"+id,json.writeValueAsString(body));assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);assertThat(json.readTree(changed.body()).has("signingSecret")).isTrue();
        var stale=write("POST",base()+"/"+id+"/pause",json.writeValueAsString(Map.of("operationId",Uuid7.generate(),"expectedRevision","4")));assertThat(stale.statusCode()).isEqualTo(409);
        var revoke=write("POST",base()+"/"+id+"/revoke",json.writeValueAsString(Map.of("operationId",Uuid7.generate(),"expectedRevision","5")));assertThat(revoke.statusCode()).isEqualTo(200);
        assertThat(json.readTree(revoke.body()).path("subscription").path("status").asText()).isEqualTo("REVOKED");
        assertThat(request("GET",base()+"/"+id,null,token(),Map.of()).body()).doesNotContain("signingSecret");
    }
    @Test void strictRevisionAndInvalidTargetNeverWrite()throws Exception {
        String id=createHttp();
        for(String value:List.of("1","\"01\"","\"+1\"","\"9223372036854775808\"","null")) {
            var response=write("POST",base()+"/"+id+"/pause","{\"operationId\":\""+Uuid7.generate()+"\",\"expectedRevision\":"+value+"}");assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        }
        for(String target:List.of("http://public.example.com","https://127.0.0.1/events","https://receiver.example.com/?secret=value")) {
            var body=(tools.jackson.databind.node.ObjectNode)json.readTree(createBody(Uuid7.generate()));body.put("targetUrl",target);
            assertThat(write("POST",base(),json.writeValueAsString(body)).statusCode()).isEqualTo(400);
        }
        assertThat(request("POST",base(),createBody(Uuid7.generate()),token(),Map.of()).statusCode()).isEqualTo(400);
        assertThat(rows("integ_webhook_subscription")).isEqualTo(1);assertThat(rows("integ_webhook_revision")).isEqualTo(1);
    }
    @Test void allReadAndWriteEndpointsRequireCurrentConsoleManager()throws Exception {
        String id=createHttp();String app=appTokens.issue(new com.things.link.enduser.application.AppAuthenticatedPrincipal(tenant,project,account)).value();
        for(String path:List.of(base(),base()+"/"+id,base()+"/deliveries",base()+"/events?eventType=device.online",base()+"/operations/"+Uuid7.generate(),base()+"/recovery-operations/"+Uuid7.generate())) {
            assertThat(request("GET",path,null,null,Map.of("X-Api-Key",secret)).statusCode()).isEqualTo(401);
            assertThat(request("GET",path,null,app,Map.of()).statusCode()).isEqualTo(401);
            assertThat(request("GET",path,null,token(),Map.of("X-Api-Key",secret)).statusCode()).isEqualTo(403);
        }
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",project,account);
        var denied=request("GET",base(),null,token(),Map.of());assertThat(denied.statusCode()).isEqualTo(403);assertThat(denied.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(write("POST",base(),createBody(Uuid7.generate())).statusCode()).isEqualTo(403);
    }
    @Test void actualDeadDeliveryQueryAndRecoveryPreserveOriginalReceipt()throws Exception {
        createHttp();var time=owner.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();UUID event=Uuid7.generate();
        admission.accept(new PublicWebhookEvent(event,"device.online",tenant,project,0,"device",device,device,time,time,null,"{}"));
        UUID id=owner.queryForObject("SELECT id FROM integ_webhook_delivery WHERE project_id=? AND event_id=?",UUID.class,project,event);
        var candidate=new WebhookDeliveryRepository.Candidate(tenant,project,id);var claim=state.claim(candidate,true).orElseThrow();state.finish(candidate,claim.token(),WebhookDeliveryState.Outcome.PERMANENT,400,1);
        var detail=request("GET",base()+"/deliveries/"+id,null,token(),Map.of());assertThat(detail.statusCode()).isEqualTo(200);assertThat(json.readTree(detail.body()).path("attempts")).hasSize(1);assertThat(detail.body()).doesNotContain(claim.token().toString(),"eventText");
        var events=request("GET",base()+"/events?eventType=device.online&result=ACCEPTED",null,token(),Map.of());assertThat(events.statusCode()).isEqualTo(200);assertThat(json.readTree(events.body()).path("items")).hasSize(1);
        UUID op=Uuid7.generate();String body=json.writeValueAsString(Map.of("operationId",op,"expectedRound",1));
        var recovered=write("POST",base()+"/deliveries/"+id+"/recover",body);assertThat(recovered.statusCode()).as(recovered.body()).isEqualTo(200);assertThat(json.readTree(recovered.body()).path("resultRound").asInt()).isEqualTo(2);
        assertThat(write("POST",base()+"/deliveries/"+id+"/recover",body).statusCode()).isEqualTo(200);
        owner.update("DELETE FROM integ_webhook_attempt WHERE delivery_id=?",id);owner.update("DELETE FROM integ_webhook_delivery WHERE id=?",id);
        var receipt=request("GET",base()+"/recovery-operations/"+op,null,token(),Map.of());assertThat(receipt.statusCode()).isEqualTo(200);assertThat(json.readTree(receipt.body()).path("current").isNull()).isTrue();
    }
    @Test void auditFailureRollsBackHttpCreationAndOriginalOperationCanRetry()throws Exception {
        UUID operation=Uuid7.generate();String body=createBody(operation);
        owner.execute("REVOKE INSERT ON sys_audit_log FROM thingslink_app");
        try{assertThat(write("POST",base(),body).statusCode()).isEqualTo(500);}
        finally{owner.execute("GRANT INSERT ON sys_audit_log TO thingslink_app");}
        assertThat(rows("integ_webhook_subscription")).isZero();assertThat(rows("integ_webhook_operation")).isZero();
        assertThat(write("POST",base(),body).statusCode()).isEqualTo(201);
    }
    @Test void foreignPathsAndUnknownDeliveryCannotExposeProjectData()throws Exception {
        String id=createHttp();
        var wrong=request("GET","/api/v1/projects/"+Uuid7.generate()+"/webhooks/"+id,null,token(),Map.of());
        assertThat(wrong.statusCode()).isIn(403,404);
        assertThat(request("GET",base()+"/deliveries/"+Uuid7.generate(),null,token(),Map.of()).statusCode()).isEqualTo(404);
        assertThat(request("GET",base()+"/events?eventType=unknown",null,token(),Map.of()).statusCode()).isEqualTo(400);
    }

}
