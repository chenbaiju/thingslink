package com.things.link.iam.infrastructure.security;
import com.things.link.iam.application.*;
import com.things.link.project.application.*;
import com.things.link.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 真Redis共享桶及多引擎竞争；日账本真PG另由Bootstrap验证。 */
class RestQuotaAdmissionEngineRedisTests extends AbstractIntegrationTest {
    @Autowired StringRedisTemplate redis;
    final Set<UUID> identities=new HashSet<>();
    UUID id(){UUID id=UUID.randomUUID();identities.add(id);return id;}
    @AfterEach void cleanup(){
        for(UUID id:identities)for(String kind:List.of("read","write"))for(String scope:List.of("key","account","project","tenant"))
            redis.delete("quota:rest:rate:"+kind+":"+scope+":"+id);
    }
    RestQuotaAdmissionEngine engine(RestQuotaPolicy policy){
        return new RestQuotaAdmissionEngine(project->Optional.of(policy),redis,new RestQuotaRateLimitMetrics(new SimpleMeterRegistry()),
                mock(ProjectDailyQuotaDecisionService.class),(a,b,c,d,e)->true,false);
    }
    @Test void consoleAndDifferentKeysConsumeSameAccountBucket(){
        UUID tenant=id(),project=id(),actor=id(),key1=id(),key2=id();var engine=engine(new RestQuotaPolicy(tenant,2L,null,null,null));
        assertThat(engine.admit(actor,project,tenant,key1,RestQuotaRateLimitMetrics.RequestKind.READ).allowed()).isTrue();
        assertThat(engine.admit(actor,project,null,null,RestQuotaRateLimitMetrics.RequestKind.READ).allowed()).isTrue();
        var denied=engine.admit(actor,project,tenant,key2,RestQuotaRateLimitMetrics.RequestKind.READ);
        assertThat(denied.allowed()).isFalse();assertThat(denied.scope()).isEqualTo(RestQuotaRateLimitMetrics.LimitScope.ACCOUNT_SECOND);
        assertThat(redis.hasKey("quota:rest:rate:read:key:"+key1)).isTrue();assertThat(redis.hasKey("quota:rest:rate:read:key:"+key2)).isTrue();
    }
    @Test void twoEnginesAndManyKeysCannotExpandSharedProjectCapacity()throws Exception{compete(false);}
    @Test void differentProjectsStillShareOneTenantCapacity()throws Exception{compete(true);}
    private void compete(boolean manyProjects)throws Exception{
        UUID tenant=id(),project=id();var policy=new RestQuotaPolicy(tenant,null,null,1L,null);
        var engines=List.of(engine(policy),engine(policy));var ready=new CountDownLatch(16);var start=new CountDownLatch(1);
        var work=new ArrayList<Callable<Boolean>>();
        for(int i=0;i<16;i++){
            UUID actor=id(),key=id(),selected=manyProjects?id():project;var engine=engines.get(i%2);boolean console=i%3==0;
            work.add(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("barrier timeout");
                return engine.admit(actor,selected,console?null:tenant,console?null:key,RestQuotaRateLimitMetrics.RequestKind.READ).allowed();});
        }
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=work.stream().map(pool::submit).toList();assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            int allowed=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS))allowed++;
            assertThat(allowed).isEqualTo(1);
        }finally{start.countDown();}
    }
    @Test void keyBucketIsIndependentAndWriteDoesNotConsumeReadBucket(){
        UUID tenant=id(),project=id(),actor=id(),key=id();var engine=engine(new RestQuotaPolicy(tenant,1L,null,null,null));
        assertThat(engine.admit(actor,project,tenant,key,RestQuotaRateLimitMetrics.RequestKind.READ).allowed()).isTrue();
        assertThat(engine.admit(actor,project,tenant,key,RestQuotaRateLimitMetrics.RequestKind.READ).scope()).isEqualTo(RestQuotaRateLimitMetrics.LimitScope.KEY_SECOND);
        // 本用例日额度替身null不表示真实日资格，仅验证独立短桶；生产PG在Bootstrap测试。
        assertThat(engine.admit(actor,project,tenant,key,RestQuotaRateLimitMetrics.RequestKind.WRITE).allowed()).isTrue();
    }
}
