package com.things.link.bootstrap.integration;

import com.things.link.integration.application.*;
import com.things.link.integration.domain.*;
import com.things.link.shared.error.*;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WebhookQueryTests extends WebhookFixture {
    @Autowired WebhookQueryService queries;
    @Autowired WebhookQueryRepository repository;
    @Autowired WebhookEventAdmission admission;
    @Autowired WebhookDeliveryState state;
    @Autowired tools.jackson.databind.ObjectMapper json;
    PublicWebhookEvent event(UUID id,String type,String body) {
        Instant time=owner.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();
        return new PublicWebhookEvent(id,type,tenant,project,0,"device",device,device,time,time,null,body);
    }
    UUID enqueue(){var id=Uuid7.generate();admission.accept(event(id,"device.online","{}"));return owner.queryForObject("SELECT id FROM integ_webhook_delivery WHERE project_id=? AND event_id=?",UUID.class,project,id);}
    @Test void paginatesFrozenDeliveriesAndFiltersWithoutDuplicates() {
        var sub=create(Uuid7.generate()).subscription();var ids=new HashSet<UUID>();for(int i=0;i<3;i++)ids.add(enqueue());
        var first=queries.deliveries(tenant,project,account,sub.id(),"READY",null,2);
        assertThat(first.items()).hasSize(2);assertThat(first.hasMore()).isTrue();
        var second=queries.deliveries(tenant,project,account,sub.id(),"READY",first.nextCursor(),2);
        assertThat(second.hasMore()).isFalse();ids.removeAll(first.items().stream().map(WebhookQueryRepository.DeliveryView::id).toList());
        assertThat(second.items().stream().map(WebhookQueryRepository.DeliveryView::id)).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(queries.deliveries(tenant,project,account,UUID.randomUUID(),null,null,100).items()).isEmpty();
        assertThat(queries.deliveries(tenant,project,account,null,"DEAD",null,100).items()).isEmpty();
    }
    @Test void detailUsesFrozenTargetAndSafeAttemptProjection() {
        var sub=create(Uuid7.generate()).subscription();UUID id=enqueue();var candidate=new WebhookDeliveryRepository.Candidate(tenant,project,id);
        var claim=state.claim(candidate,true).orElseThrow();state.finish(candidate,claim.token(),WebhookDeliveryState.Outcome.PERMANENT,400,9007199254740993L);
        service.change(tenant,project,account,Uuid7.generate(),sub.id(),1,"UPDATE",new WebhookSubscriptionService.Spec("changed","https://new.example.com/events",spec().eventTypes(),List.of()));
        var detail=queries.detail(tenant,project,account,id);
        assertThat(detail.name()).isEqualTo("receiver");assertThat(detail.targetUrl()).isEqualTo(spec().targetUrl());
        assertThat(detail.delivery().subscriptionRevision()).isEqualTo("1");assertThat(detail.attempts()).hasSize(1);
        assertThat(detail.attempts().getFirst().elapsedMillis()).isEqualTo("9007199254740993");
        String encoded=json.writeValueAsString(detail);
        assertThat(encoded).doesNotContain("leaseToken","leaseUntil","signingSecret","eventText",claim.token().toString());
    }
    @Test void sourceRejectionConflictAndSameIdDifferentTypesStayDistinct() {
        create(Uuid7.generate());UUID shared=Uuid7.generate();var original=event(shared,"device.online","{}");admission.accept(original);
        admission.accept(new PublicWebhookEvent(shared,"device.online",tenant,project,0,"device",device,device,original.occurredAt(),original.recordedAt(),null,"{\"changed\":true}"));
        admission.accept(event(shared,"device.offline","{}"));
        var online=queries.events(tenant,project,account,"device.online",null,null,1);
        assertThat(online.items()).hasSize(1);assertThat(online.items().getFirst().hasConflict()).isTrue();assertThat(online.items().getFirst().result()).isEqualTo("ACCEPTED");
        assertThat(queries.events(tenant,project,account,"device.offline",null,null,1).items().getFirst().eventId()).isEqualTo(shared);
        UUID rejected=Uuid7.generate();owner.update("INSERT INTO integ_webhook_event(tenant_id,project_id,event_type,event_id,project_generation,source_hash,recorded_at,source_occurred_at,accepted_at,result) VALUES (?,?,'device.online',?,0,repeat('a',64),clock_timestamp(),clock_timestamp(),clock_timestamp(),'OVERSIZE')",tenant,project,rejected);
        assertThat(queries.events(tenant,project,account,"device.online","OVERSIZE",null,20).items().getFirst().eventId()).isEqualTo(rejected);
    }
    @Test void cursorsBindPurposeFiltersActorAndRejectTampering() {
        create(Uuid7.generate());enqueue();enqueue();
        String cursor=queries.deliveries(tenant,project,account,null,null,null,1).nextCursor();
        assertInvalid(()->queries.deliveries(tenant,project,account,null,"READY",cursor,1));
        assertInvalid(()->queries.deliveries(tenant,project,account,null,null,cursor+"x",1));
        assertInvalid(()->queries.events(tenant,project,account,"device.online",null,cursor,1));
        UUID actor=Uuid7.generate();additionalAccounts.add(actor);
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'unused','query')",actor,actor+"@example.com");
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",Uuid7.generate(),project,actor);
        assertInvalid(()->queries.deliveries(tenant,project,actor,null,null,cursor,1));
    }
    @Test void eventPaginationHasExactBoundaryAndTypedFilter() {
        create(Uuid7.generate());enqueue();enqueue();enqueue();
        var one=queries.events(tenant,project,account,"device.online",null,null,2);
        var two=queries.events(tenant,project,account,"device.online",null,one.nextCursor(),2);
        assertThat(two.items()).hasSize(1);assertThat(two.items()).doesNotContainAnyElementsOf(one.items());assertThat(two.hasMore()).isFalse();
        assertInvalid(()->queries.events(tenant,project,account,"device.offline",null,one.nextCursor(),2));
        assertInvalid(()->queries.events(tenant,project,account,"device.online","ACCEPTED",one.nextCursor(),2));
    }
    @Test void scopeAndCurrentPermissionsCannotBeSuppliedByCursor() {
        create(Uuid7.generate());UUID id=enqueue();
        assertThatThrownBy(()->queries.detail(UUID.randomUUID(),project,account,id)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->queries.detail(tenant,UUID.randomUUID(),account,id)).isInstanceOf(BusinessException.class);
        for(UUID wrong:List.of(UUID.randomUUID(),tenant)) {
            var rows=tx.execute(s->{rls.establish(wrong,wrong.equals(tenant)?UUID.randomUUID():project);return repository.deliveries(project,null,null,null,null,10);});
            assertThat(rows).isEmpty();
        }
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",project,account);
        assertThatThrownBy(()->queries.detail(tenant,project,account,id)).isInstanceOf(BusinessException.class);
    }
    @Test void invalidBoundsAndMissingDetailsFailExplicitly() {
        for(int limit:List.of(0,101))assertInvalid(()->queries.deliveries(tenant,project,account,null,null,null,limit));
        for(String cursor:List.of("","x".repeat(2049)))assertInvalid(()->queries.deliveries(tenant,project,account,null,null,cursor,1));
        assertInvalid(()->queries.events(tenant,project,account,"unknown",null,null,1));
        assertInvalid(()->queries.events(tenant,project,account,"device.online","UNKNOWN",null,1));
        assertThatThrownBy(()->queries.detail(tenant,project,account,UUID.randomUUID())).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
    @Test void sameTimestampUsesUuidTieBreakerAndCleanupDoesNotBreakCursor() {
        var sub=create(Uuid7.generate()).subscription();var time=Instant.parse("2026-09-01T00:00:00Z");
        for(int i=0;i<3;i++) {
            UUID event=Uuid7.generate();
            owner.update("INSERT INTO integ_webhook_event VALUES (?,?,'device.online',?,0,repeat('a',64),?,?,?,'ACCEPTED','{}')",tenant,project,event,java.sql.Timestamp.from(time),java.sql.Timestamp.from(time),java.sql.Timestamp.from(time));
            owner.update("INSERT INTO integ_webhook_delivery(id,tenant_id,project_id,event_type,event_id,subscription_id,subscription_revision,created_at,deadline_at,status,next_attempt_at) VALUES (?,?,?,'device.online',?,?,1,?,?,'READY',?)",Uuid7.generate(),tenant,project,event,sub.id(),java.sql.Timestamp.from(time),java.sql.Timestamp.from(time.plusSeconds(86400)),java.sql.Timestamp.from(time));
        }
        var first=queries.deliveries(tenant,project,account,null,null,null,1);
        owner.update("DELETE FROM integ_webhook_delivery WHERE id=?",first.items().getFirst().id());
        var rest=queries.deliveries(tenant,project,account,null,null,first.nextCursor(),100);
        assertThat(rest.items()).hasSize(2);assertThat(rest.items()).doesNotContainAnyElementsOf(first.items());
        var eventFirst=queries.events(tenant,project,account,"device.online",null,null,1);
        assertThat(queries.events(tenant,project,account,"device.online",null,eventFirst.nextCursor(),100).items()).hasSize(2);
    }
    @Test void disabledAccountAndNonWritableProjectCannotRead() {
        create(Uuid7.generate());UUID id=enqueue();
        owner.update("UPDATE sys_account SET status='DISABLED' WHERE id=?",account);
        assertThatThrownBy(()->queries.detail(tenant,project,account,id)).isInstanceOf(BusinessException.class);
        owner.update("UPDATE sys_account SET status='ACTIVE' WHERE id=?",account);
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",project);
        assertThatThrownBy(()->queries.detail(tenant,project,account,id)).isInstanceOf(BusinessException.class);
    }
    private void assertInvalid(Runnable operation){assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));}
}
