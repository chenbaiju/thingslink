package com.things.link.bootstrap.integration;

import com.things.link.integration.application.*;
import com.things.link.integration.domain.WebhookDeliveryRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.support.notification.delivery.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** ADR0211：管理HTTP真正参与TLS发送与持久资格竞争，而非只测内部方法。 */
@Import(WebhookOutboundTests.ReceiverConfiguration.class)
class WebhookManagementDeliveryTests extends WebhookFixture {
    @Autowired WebhookTlsReceiver receiver;@Autowired WebhookEventAdmission admission;
    @Autowired WebhookDeliveryDispatcher dispatcher;@Autowired WebhookDeliveryState state;@Autowired WebhookOutboundDelivery outbound;
    @BeforeEach void resetReceiver(){receiver.reset();}
    @AfterEach void releaseReceiver(){receiver.release.countDown();}
    String base(){return "/api/v1/projects/"+project+"/webhooks";}
    java.net.http.HttpResponse<String> write(String method,String path,Object value)throws Exception {
        return request(method,path,json.writeValueAsString(value),token(),Map.of("Idempotency-Key",Uuid7.generate().toString()));
    }
    tools.jackson.databind.JsonNode createHttp()throws Exception {
        var response=write("POST",base(),Map.of("operationId",Uuid7.generate(),"name","TLS receiver","targetUrl",receiver.target(),"eventTypes",List.of("device.online"),"deviceIds",List.of(device)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);return json.readTree(response.body());
    }
    WebhookDeliveryRepository.Candidate enqueue(){var time=owner.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();UUID event=Uuid7.generate();admission.accept(new PublicWebhookEvent(event,"device.online",tenant,project,0,"device",device,device,time,time,null,"{\"valueJson\":\"9007199254740993\"}"));return new WebhookDeliveryRepository.Candidate(tenant,project,owner.queryForObject("SELECT id FROM integ_webhook_delivery WHERE project_id=? AND event_id=?",UUID.class,project,event));}
    String status(UUID id){return owner.queryForObject("SELECT status FROM integ_webhook_delivery WHERE id=?",String.class,id);}
    @Test void httpRecoveryResendsExactOriginalBodyWithPersistentReceiverDedup()throws Exception {
        var issued=createHttp();var candidate=enqueue();receiver.status=400;dispatcher.dispatch(candidate);assertThat(status(candidate.id())).isEqualTo("DEAD");
        UUID operation=Uuid7.generate();var recovered=write("POST",base()+"/deliveries/"+candidate.id()+"/recover",Map.of("operationId",operation,"expectedRound",1));assertThat(recovered.statusCode()).as(recovered.body()).isEqualTo(200);
        receiver.status=200;dispatcher.dispatch(candidate);assertThat(status(candidate.id())).isEqualTo("SUCCEEDED");assertThat(receiver.received).hasSize(2);assertThat(receiver.acceptedEffects()).isEqualTo(1);
        assertThat(receiver.received.get(1).body()).isEqualTo(receiver.received.getFirst().body());
        for(var wire:receiver.received){var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(issued.path("signingSecret").asText()),"HmacSHA256"));String value=wire.headers().getFirst("X-ThingsLink-Timestamp")+"\n"+wire.headers().getFirst("X-ThingsLink-Nonce")+"\n"+candidate.id()+"\n"+new String(wire.body(),StandardCharsets.UTF_8);assertThat(wire.headers().getFirst("X-ThingsLink-Signature")).isEqualTo("v1="+HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))));}
        var details=request("GET",base()+"/deliveries/"+candidate.id(),null,token(),Map.of());assertThat(details.statusCode()).isEqualTo(200);var body=json.readTree(details.body());assertThat(body.path("delivery").path("attempts").asInt()).isEqualTo(2);assertThat(body.path("attempts").get(0).path("result").asText()).isEqualTo("PERMANENT");assertThat(body.path("attempts").get(1).path("result").asText()).isEqualTo("SUCCEEDED");
        assertThat(write("POST",base()+"/deliveries/"+candidate.id()+"/recover",Map.of("operationId",operation,"expectedRound",1)).statusCode()).isEqualTo(200);
        assertThat(write("POST",base()+"/deliveries/"+candidate.id()+"/recover",Map.of("operationId",Uuid7.generate(),"expectedRound",2)).statusCode()).isEqualTo(409);assertThat(receiver.received).hasSize(2);
    }
    @Test void httpPauseWaitsForAdmittedTlsThenCancelsQueuedOldRevision()throws Exception {
        var issued=createHttp();String subscription=issued.path("subscription").path("id").asText();var first=enqueue();var queued=enqueue();receiver.release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var send=pool.submit(()->dispatcher.dispatch(first));assertThat(receiver.entered.await(4,TimeUnit.SECONDS)).isTrue();
            var pause=pool.submit(()->write("POST",base()+"/"+subscription+"/pause",Map.of("operationId",Uuid7.generate(),"expectedRevision","1")));
            try{org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(()->owner.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)>0);assertThat(pause.isDone()).isFalse();}finally{receiver.release.countDown();}
            send.get(10,TimeUnit.SECONDS);assertThat(pause.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        dispatcher.dispatch(queued);assertThat(status(first.id())).isEqualTo("SUCCEEDED");assertThat(status(queued.id())).isEqualTo("CANCELLED");assertThat(receiver.received).hasSize(1);
        assertThat(owner.queryForObject("SELECT attempt_count FROM integ_webhook_delivery WHERE id=?",Integer.class,queued.id())).isZero();
    }
    @Test void httpPauseAfterClaimFencesSocketAndCancelledRecovery()throws Exception {
        var issued=createHttp();String subscription=issued.path("subscription").path("id").asText();var candidate=enqueue();var claim=state.claim(candidate,true).orElseThrow();
        assertThat(write("POST",base()+"/"+subscription+"/pause",Map.of("operationId",Uuid7.generate(),"expectedRevision","1")).statusCode()).isEqualTo(200);
        outbound.send(candidate,claim.token());assertThat(status(candidate.id())).isEqualTo("CANCELLED");assertThat(receiver.received).isEmpty();
        assertThat(write("POST",base()+"/"+subscription+"/resume",Map.of("operationId",Uuid7.generate(),"expectedRevision","2")).statusCode()).isEqualTo(200);
        assertThat(write("POST",base()+"/deliveries/"+candidate.id()+"/recover",Map.of("operationId",Uuid7.generate(),"expectedRound",1)).statusCode()).isEqualTo(409);
        var next=enqueue();dispatcher.dispatch(next);assertThat(status(next.id())).isEqualTo("SUCCEEDED");assertThat(receiver.received).hasSize(1);
    }
    @Test void httpRotationCannotRedirectDeadDeliveryToNewRevision()throws Exception {
        var issued=createHttp();String subscription=issued.path("subscription").path("id").asText();var candidate=enqueue();receiver.status=400;dispatcher.dispatch(candidate);
        var rotated=write("POST",base()+"/"+subscription+"/rotate",Map.of("operationId",Uuid7.generate(),"expectedRevision","1"));assertThat(rotated.statusCode()).isEqualTo(200);assertThat(json.readTree(rotated.body()).path("signingSecret").asText()).isNotEqualTo(issued.path("signingSecret").asText());
        assertThat(write("POST",base()+"/deliveries/"+candidate.id()+"/recover",Map.of("operationId",Uuid7.generate(),"expectedRound",1)).statusCode()).isEqualTo(409);
        assertThat(status(candidate.id())).isEqualTo("DEAD");assertThat(receiver.received).hasSize(1);
    }
}
