package com.things.link.bootstrap.integration;

import com.things.link.integration.application.ApiKeyManagementService;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 全部正式HTTP操作的范围矩阵；探针不能替代已交付资源接线。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "things-link.integration.api-key.enabled=true", "things-link.quota.daily-usage-recording-enabled=true"})
class OpenRestAllowlistTests extends OpenCommandHttpFixture {
    record Call(String method,String path,String body,String scope,int status) {}
    String scoped(String scope) {
        return keys.issue(tenant,project,account,Uuid7.generate(),new ApiKeyManagementService.Spec(
            scope,List.of(scope),List.of("127.0.0.1/32"),Instant.now().plusSeconds(3600))).secret();
    }
    List<Call> calls()throws Exception {
        var command=service.submit(principal,device,"matrix","start",json.readTree("{}"));
        String devices=json.writeValueAsString(List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value"))));
        String alarms=json.writeValueAsString(Map.of("devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model)),
            "conditionStates",List.of("ACTIVE"),"ackStates",List.of("UNACKNOWLEDGED"),"severities",List.of("WARNING")));
        String path="/devices/"+device;
        return List.of(new Call("GET","/devices",null,"device:read",200),
            new Call("GET",path,null,"device:read",200),new Call("GET","/models/"+model,null,"device:read",200),
            new Call("POST","/devices/current-values/query","{\"devices\":"+devices+"}","device:read",200),
            new Call("GET",path+"/history?propertyKey=value&expectedModelVersionId="+model+"&from="+Instant.now().minusSeconds(60)+"&to="+Instant.now().minusSeconds(1)+"&granularity=RAW&aggregation=AVG",null,"device:read",200),
            new Call("POST","/alarms/query",alarms,"alarm:read",200),
            new Call("POST",path+"/commands","{\"commandKey\":\"start\",\"input\":{}}","device:control",202),
            new Call("GET",path+"/commands/"+command.commandId(),null,"device:control",200),
            new Call("GET",path+"/commands/by-key?idempotencyKey=matrix",null,"device:control",200));
    }
    @Test void everyOperationEnforcesExactScopeBeforeQuotaOrBusiness()throws Exception {
        Map<String,String> credentials=Map.of("device:read",scoped("device:read"),"alarm:read",scoped("alarm:read"),"device:control",secret);
        int accepted=0;
        for(Call call:calls())for(var credential:credentials.entrySet()) {
            var response=request(call.method(),"/api/open/v1"+call.path(),call.body(),null,
                Map.of("X-Api-Key",credential.getValue(),"Idempotency-Key",Uuid7.generate().toString()));
            boolean allowed=call.scope().equals(credential.getKey());
            assertThat(response.statusCode()).as(call.method()+" "+call.path()+" scope="+credential.getKey()+" body="+response.body()).isEqualTo(allowed?call.status():403);
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            if(allowed)accepted++; else assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(80004);
        }
        assertThat(count("sys_usage_fact")).isEqualTo(accepted);
        assertThat(count("ts_device_command")).isEqualTo(2);
    }
    @Test void allOperationsRejectConsoleOrMixedIdentityBeforeDomainWork()throws Exception {
        String console=token();
        for(Call call:calls()) {
            assertThat(request(call.method(),"/api/open/v1"+call.path(),call.body(),console,Map.of()).statusCode()).isEqualTo(401);
            assertThat(request(call.method(),"/api/open/v1"+call.path(),call.body(),console,Map.of("X-Api-Key",secret)).statusCode()).isEqualTo(401);
        }
        assertThat(count("sys_usage_fact")).isZero();assertThat(count("ts_device_command")).isEqualTo(1);
    }
    @Test void unknownWritesStayClosedAndDailyHardLimitPreservesReadOnlyPosts()throws Exception {
        for(String path:List.of("/devices","/projects","/devices/"+device,"/alarms/ack","/models/"+model)) {
            var response=request("DELETE","/api/open/v1"+path,null,null,Map.of("X-Api-Key",secret));
            assertThat(response.statusCode()).isEqualTo(403);
        }
        owner.update("INSERT INTO sys_usage_counter_daily(id,tenant_id,project_id,usage_date,metric,used_value) VALUES (?,?,?,(now() at time zone 'UTC')::date,'REST_API_CALL',1000000000)",Uuid7.generate(),tenant,project);
        var denied=submit(secret,"hard-limit","{}");assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(denied.headers().firstValue("Retry-After")).isPresent();assertThat(count("ts_device_command")).isZero();assertThat(count("sys_usage_fact")).isZero();
        for(Call call:calls())if(call.method().equals("POST")&&!call.scope().equals("device:control")) {
            String key=scoped(call.scope());
            assertThat(request(call.method(),"/api/open/v1"+call.path(),call.body(),null,Map.of("X-Api-Key",key)).statusCode()).isEqualTo(200);
        }
        assertThat(count("sys_usage_fact")).isEqualTo(2);
        assertThat(count("sys_idempotency_record")).isZero();
    }
}
