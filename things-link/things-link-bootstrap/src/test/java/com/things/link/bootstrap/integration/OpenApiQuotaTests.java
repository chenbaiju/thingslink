package com.things.link.bootstrap.integration;
import com.things.link.integration.application.ApiKeyManagementService;
import com.things.link.project.application.ProjectRestQuotaAdmission;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "things-link.integration.api-key.enabled=true","things-link.quota.daily-usage-recording-enabled=true"})
@Import(OpenApiSecurityTests.ProbeConfiguration.class)
class OpenApiQuotaTests extends ApiKeyHttpFixture {
    @Autowired ApiKeyManagementService keys;
    @Autowired ProjectRestQuotaAdmission quotas;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    List<UUID> keyIds=new ArrayList<>();
    String key(){var result=keys.issue(tenant,project,account,Uuid7.generate(),new ApiKeyManagementService.Spec("quota",List.of("device:read","device:control"),List.of("127.0.0.1/32"),Instant.now().plusSeconds(3600)));keyIds.add(result.key().id());return result.secret();}
    @AfterEach void clearBuckets(){
        var ids=new ArrayList<>(keyIds);ids.addAll(List.of(tenant,project,account));
        for(UUID id:ids)for(String kind:List.of("read","write"))for(String scope:List.of("key","account","project","tenant"))redis.delete("quota:rest:rate:"+kind+":"+scope+":"+id);
    }
    int facts(){return owner.queryForObject("SELECT count(*) FROM sys_usage_fact WHERE project_id=? AND tenant_id=? AND metric='REST_API_CALL'",Integer.class,project,tenant);}
    @Test void consoleAndTwoKeysCreateSameOwnerDailyFactsWithoutConsoleContext()throws Exception{
        String first=key(),second=key();assertThat(TenantContext.current()).isEmpty();
        assertThat(request("GET",OpenApiSecurityTests.PROBE,null,null,Map.of("X-Api-Key",first)).statusCode()).isEqualTo(200);
        assertThat(request("GET",OpenApiSecurityTests.PROBE,null,null,Map.of("X-Api-Key",second)).statusCode()).isEqualTo(200);
        assertThat(request("GET",base(),null,token(),Map.of()).statusCode()).isEqualTo(200);
        assertThat(facts()).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT count(DISTINCT usage_date) FROM sys_usage_fact WHERE project_id=?",Integer.class,project)).isEqualTo(1);
        assertThat(TenantContext.current()).isEmpty();
        assertThat(request("GET","/api/open/v1/not-registered",null,null,Map.of("X-Api-Key",first)).statusCode()).isEqualTo(403);
        assertThat(facts()).isEqualTo(3);
    }
    @Test void realDailyHardLimitRejectsWriteBeforeRecordingButRetainsRead(){
        key();owner.update("INSERT INTO sys_usage_counter_daily(id,tenant_id,project_id,usage_date,metric,used_value) VALUES (?,?,?,(now() at time zone 'UTC')::date,'REST_API_CALL',1000000000)",Uuid7.generate(),tenant,project);
        assertThat(quotas.admit(tenant,project,account,keyIds.getFirst(),true).allowed()).isFalse();assertThat(facts()).isZero();
        assertThat(quotas.admit(tenant,project,account,keyIds.getFirst(),false).allowed()).isTrue();assertThat(facts()).isEqualTo(1);
    }
    @Test void mismatchedOwnerDoesNotProduceUsageOrAllowRequest(){
        key();assertThatThrownBy(()->quotas.admit(UUID.randomUUID(),project,account,keyIds.getFirst(),false)).isInstanceOf(IllegalStateException.class);
        assertThat(facts()).isZero();
    }
}
