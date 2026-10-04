package com.things.link.bootstrap.integration;
import com.things.link.integration.application.ApiKeyManagementService;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 真HTTP/PG验证窗口裁剪、模型先验与告警分页，不用空集合证明查询接线。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties="things-link.integration.api-key.enabled=true")
class OpenHistoryAlarmApiTests extends OpenDeviceHttpFixture {
    Instant now;
    @BeforeEach void time(){now=owner.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant().minusSeconds(10);}
    @AfterEach void removeReadFacts(){
        owner.update("DELETE FROM alarm_instance WHERE project_id=?",project);
        owner.update("DELETE FROM alarm_rule WHERE project_id=?",project);
        owner.update("DELETE FROM ts_property_point_internal WHERE project_id=?",project);
    }
    void point(Instant at,double value){owner.update("""
        INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,data_type,
            thing_model_version_id,model_version,value_double,quality)
        VALUES (?,?,'value',?,?,'NUMBER',?,'1.0.0',?,0)
        """,project,device,Timestamp.from(at),Uuid7.generate(),model,value);}
    String history(UUID expected,Instant from){return "/devices/"+device+"/history?propertyKey=value&expectedModelVersionId="+expected+"&from="+from+"&to="+now+"&granularity=RAW&aggregation=AVG";}
    String alarmKey(){return keys.issue(tenant,project,account,Uuid7.generate(),new ApiKeyManagementService.Spec("alarm",List.of("alarm:read"),List.of("127.0.0.1/32"),Instant.now().plusSeconds(3600))).secret();}
    @Test void historyClipsOwnerWindowAndPreservesVersionAndCount()throws Exception{
        point(now.minusSeconds(40),12.5);point(now.minusSeconds(90*86400),99);
        var response=get(history(model,now.minusSeconds(100*86400)),secret);assertThat(response.statusCode()).isEqualTo(200);
        var data=json.readTree(response.body());assertThat(data.path("points")).hasSize(1);
        assertThat(data.path("points").get(0).path("value").asDouble()).isEqualTo(12.5);
        assertThat(data.path("points").get(0).path("sampleCount").asString()).isEqualTo("1");
        assertThat(data.path("points").get(0).path("thingModelVersionId").asString()).isEqualTo(model.toString());
        assertThat(get(history(Uuid7.generate(),now.minusSeconds(60)),secret).statusCode()).isEqualTo(400);
        assertThat(get(history(model,now.minusSeconds(60))+"&extra=1",secret).statusCode()).isEqualTo(400);
        assertThat(get(history(model,now.minusSeconds(60)),alarmKey()).statusCode()).isEqualTo(403);
    }
    @Test void absentWindowCannotFallBackToUnboundedRead()throws Exception{
        owner.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='FREE') WHERE id=?",tenant);
        var response=get(history(model,now.minusSeconds(60)),secret);
        assertThat(response.statusCode()).isEqualTo(503);assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(50048);
    }
    UUID alarm(UUID target,String severity,Instant at){UUID rule=Uuid7.generate(),id=Uuid7.generate();
        owner.update("""
            INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,
                trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity)
            VALUES (?,?,?,?,?,?,'value','GT',30,'LT',25,?)
            """,rule,tenant,project,"公开告警-"+rule,"VALUE_"+rule.toString().replace("-",""),target,severity);
        owner.update("""
            INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,
                condition_state,ack_state,first_condition_at,activated_at,last_received_at,last_value,created_at,updated_at)
            VALUES (?,?,?,?,'DEVICE',?,?,?,'ACTIVE','UNACKNOWLEDGED',?,?,?,31,?,?)
            """,id,tenant,project,rule,target,"VALUE_"+rule.toString().replace("-",""),severity,Timestamp.from(at),Timestamp.from(at),Timestamp.from(at),Timestamp.from(at),Timestamp.from(at));return id;}
    Map<String,Object> query(){return new LinkedHashMap<>(Map.of("devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model)),
        "conditionStates",List.of("ACTIVE"),"ackStates",List.of("UNACKNOWLEDGED"),"severities",List.of("WARNING"),"limit",1));}
    java.net.http.HttpResponse<String> alarms(Map<String,Object> body,String key)throws Exception{
        return request("POST","/api/open/v1/alarms/query",json.writeValueAsString(body),null,Map.of("X-Api-Key",key,"Idempotency-Key","read-alarm"));}
    @Test void alarmFiltersBeforePagingAndBindsCursorToKeyAndFilters()throws Exception{
        alarm(device,"CRITICAL",now);UUID newest=alarm(device,"WARNING",now.minusSeconds(1));UUID old=alarm(device,"WARNING",now.minusSeconds(2));
        String key=alarmKey();Map<String,Object> q=query();var first=alarms(q,key);assertThat(first.statusCode()).isEqualTo(200);
        var data=json.readTree(first.body());assertThat(data.path("items").get(0).path("id").asString()).isEqualTo(newest.toString());
        assertThat(data.path("hasMore").asBoolean()).isTrue();q.put("cursor",data.path("nextCursor").asString());
        var second=alarms(q,key);assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body()).path("items").get(0).path("id").asString()).isEqualTo(old.toString());
        assertThat(alarms(q,alarmKey()).statusCode()).isEqualTo(400);
        q.put("severities",List.of("CRITICAL"));assertThat(alarms(q,key).statusCode()).isEqualTo(400);
        assertThat(alarms(query(),secret).statusCode()).isEqualTo(403);
        assertThat(first.body()).doesNotContain("notification","secret","lastValue","ruleId");
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_idempotency_record WHERE project_id=?",Integer.class,project)).isZero();
    }
    @Test void alarmModelMismatchUnknownFieldsAndDuplicatesReject()throws Exception{
        String key=alarmKey();var q=query();q.put("devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",Uuid7.generate())));
        assertThat(alarms(q,key).statusCode()).isEqualTo(400);
        q=query();q.put("owner",tenant);assertThat(alarms(q,key).statusCode()).isEqualTo(400);
        q=query();q.put("severities",List.of("WARNING","WARNING"));assertThat(alarms(q,key).statusCode()).isEqualTo(400);
        q=query();q.put("devices",List.of(Map.of("deviceId",Uuid7.generate(),"expectedModelVersionId",model)));
        assertThat(alarms(q,key).statusCode()).isEqualTo(404);
    }
}
