package com.things.link.bootstrap.integration;
import com.things.link.integration.application.ApiKeyManagementService;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 真HTTP、普通APP/RLS读取与真实模型/当前值，不用mock替代域端口。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties="things-link.integration.api-key.enabled=true")
class OpenDeviceApiTests extends OpenDeviceHttpFixture {
    @Test void catalogPaginationIsKeyBoundAndFullModelDoesNotLoseCommands()throws Exception{
        var first=get("/devices?limit=1",secret);assertThat(first.statusCode()).isEqualTo(200);
        var data=json.readTree(first.body());assertThat(data.path("items").size()).isEqualTo(1);
        String cursor=data.path("nextCursor").asString();assertThat(data.path("hasMore").asBoolean()).isTrue();
        var next=get("/devices?limit=1&cursor="+cursor,secret);assertThat(next.statusCode()).isEqualTo(200);
        assertThat(json.readTree(next.body()).path("items").get(0).path("deviceId").asString()).isNotEqualTo(data.path("items").get(0).path("deviceId").asString());
        assertThat(get("/devices?limit=1&cursor="+cursor,key()).statusCode()).isEqualTo(400);
        assertThat(get("/devices?limit=2&cursor="+cursor,secret).statusCode()).isEqualTo(400);
        var response=get("/models/"+model,secret);assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("9007199254740993","1.12345678901234567890123456789","commands","events","PG_JSONB_TEXT_V1_SHA256").doesNotContain("secretHash","device_key");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(get("/devices/"+unbound,secret).statusCode()).isEqualTo(200);
    }
    @Test void strictCurrentReadPreservesPrecisionAndSparseStatesWithoutIdempotency()throws Exception{
        String body=json.writeValueAsString(Map.of("devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value","empty")))));
        var response=request("POST","/api/open/v1/devices/current-values/query",body,null,Map.of("X-Api-Key",secret,"Idempotency-Key","read-only"));
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).contains("9007199254740993","VALUE","NO_VALUE");
        assertThat(request("POST","/api/open/v1/devices/current-values/query",body,null,Map.of("X-Api-Key",secret,"Idempotency-Key","read-only")).statusCode()).isEqualTo(200);
        for(String bad:List.of(body.substring(0,body.length()-1)+",\"extra\":1}","{\"devices\":[],\"devices\":[]}",body+" {}"))
            assertThat(request("POST","/api/open/v1/devices/current-values/query",bad,null,Map.of("X-Api-Key",secret)).statusCode()).isEqualTo(400);
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_idempotency_record WHERE project_id=?",Integer.class,project)).isZero();
    }
    @Test void unknownFieldsMissingResourcesAndScopeFailClosed()throws Exception{
        assertThat(get("/devices?owner="+tenant,secret).statusCode()).isEqualTo(400);
        assertThat(get("/devices?limit=1&limit=2",secret).statusCode()).isEqualTo(400);
        assertThat(get("/devices/"+Uuid7.generate(),secret).statusCode()).isEqualTo(404);
        assertThat(get("/models/"+Uuid7.generate(),secret).statusCode()).isEqualTo(404);
        assertThat(get("/devices",token()).statusCode()).isEqualTo(401);
        assertThat(get("/devices/"+device+"/credentials",secret).statusCode()).isEqualTo(403);
    }
    @Test void crossProjectFactsAreUnavailableEvenWithKnownIdentifiers()throws Exception{
        UUID neighbor=Uuid7.generate();
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'neighbor',?)",neighbor,tenant,"n"+neighbor.toString().replace("-",""));
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",Uuid7.generate(),neighbor,account);
        try{
            String other=keys.issue(tenant,neighbor,account,Uuid7.generate(),new ApiKeyManagementService.Spec("other",List.of("device:read"),List.of("127.0.0.1/32"),Instant.now().plusSeconds(3600))).secret();
            assertThat(get("/devices/"+device,other).statusCode()).isEqualTo(404);
            assertThat(get("/models/"+model,other).statusCode()).isEqualTo(404);
            var page=get("/devices",other);assertThat(page.statusCode()).isEqualTo(200);
            assertThat(json.readTree(page.body()).path("items").isEmpty()).isTrue();
        }finally{
            for(String table:List.of("integ_api_key","sys_usage_fact","sys_usage_counter_daily","sys_project_member"))owner.update("DELETE FROM "+table+" WHERE project_id=?",neighbor);
            owner.update("DELETE FROM sys_project WHERE id=?",neighbor);
        }
    }
}
