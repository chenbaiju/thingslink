package com.things.link.bootstrap.assistant;

import com.things.link.assistant.domain.KnowledgeDocumentRepository;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@AutoConfigureMockMvc
class ProjectKnowledgeApiTests extends AbstractAssistantIntegrationTest {
    @Autowired MockMvc mvc; @Autowired TokenIssuer tokens; @Autowired JdbcTemplate application;
    @MockitoSpyBean KnowledgeDocumentRepository documents;
    JdbcTemplate owner; DataFixture data; String token; final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        assertThat(application.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);
        data=WebAppDataRuntimeFixture.seed(owner); var f=data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",UUID.randomUUID(),f.projectId(),f.actorId());
        token=tokens.issue(new AuthenticatedPrincipal(f.actorId(),f.tenantId(),f.projectId())).value();
    }
    String base() { return "/api/v1/projects/"+data.runtime().projectId()+"/assistant/knowledge"; }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder r) { return r.header(HttpHeaders.AUTHORIZATION,"Bearer "+token); }
    void status(MockHttpServletRequestBuilder r,int expected) throws Exception {
        var result=mvc.perform(auth(r)).andReturn();assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expected);
    }
    String body(UUID expected,String content,boolean approved) {
        var value=json.createObjectNode().put("content",content).put("approvedForProjectMembers",approved);
        if(expected==null)value.putNull("expectedCurrentVersionId");else value.put("expectedCurrentVersionId",expected.toString());
        return value.toString();
    }
    UUID publish(String source,UUID expected,String content) throws Exception {
        var result=mvc.perform(auth(put(base()+"/sources/"+source).contentType("application/json").content(body(expected,content,true)))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asString());
    }
    JsonNode search(String keyword) throws Exception {
        var request=json.createObjectNode(); request.putArray("keywords").add(keyword);
        var result=mvc.perform(auth(post(base()+"/search").contentType("application/json").content(request.toString()))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        return json.readTree(result.getResponse().getContentAsString());
    }
    void role(String role) { owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",role,data.runtime().projectId(),data.runtime().actorId()); }
    @Test void explicitApprovalFourRoleReadsAndAdministratorOnlyMutation() throws Exception {
        status(put(base()+"/sources/guide").contentType("application/json").content(body(null,"灌溉说明",false)),400);
        var id=publish("guide",null,"cafe\u0301\r\n灌溉检查水位");
        for(String role:List.of("OWNER","ADMIN","OPERATOR","VIEWER")) {
            role(role); var hit=search("灌溉"); assertThat(hit.path("mode").asString()).isEqualTo("LOCAL_LITERAL");
            assertThat(hit.path("externalAllowed").asBoolean()).isFalse();
            assertThat(hit.path("hits").get(0).path("source").path("id").asString()).isEqualTo(id.toString());
            status(get(base()+"/sources/guide"),200); status(get(base()+"/sources"),200);
            if(List.of("OPERATOR","VIEWER").contains(role)) {
                status(put(base()+"/sources/guide").contentType("application/json").content(body(id,"灌溉新说明",true)),403);
                status(delete(base()+"/sources/guide").queryParam("expectedCurrentVersionId",id.toString()),403);
            }
        }
    }
    @Test void immutableVersionsCasDeletionRecreationAndOldTextNeverRetrieves() throws Exception {
        var first=publish("guide",null,"灌溉旧说明");
        status(put(base()+"/sources/guide").contentType("application/json").content(body(null,"灌溉新说明",true)),409);
        var second=publish("guide",first,"排水新说明");
        assertThat(search("灌溉").path("state").asString()).isEqualTo("NO_MATCH");
        assertThat(search("排水").path("hits").get(0).path("source").path("versionNumber").asInt()).isEqualTo(2);
        status(delete(base()+"/sources/guide").queryParam("expectedCurrentVersionId",first.toString()),409);
        status(delete(base()+"/sources/guide").queryParam("expectedCurrentVersionId",second.toString()),204);
        assertThat(search("排水").path("state").asString()).isEqualTo("NO_SOURCES");
        status(get(base()+"/sources/guide"),404);
        assertThat(publish("guide",null,"灌溉重建说明")).isNotIn(first,second);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_knowledge_document WHERE project_id=?",Integer.class,data.runtime().projectId())).isEqualTo(1);
    }
    @Test void strictBodyQueryByteLimitsAndDuplicateKeysReject() throws Exception {
        String valid=body(null,"灌溉说明",true);
        for(String invalid:List.of(valid+" {}",valid.replace("\"content\":", "\"content\":\"重复\",\"content\":"),valid.replace("\"content\":", "\"identity\":\"伪造\",\"content\":"),body(null,"水".repeat(5462),true)))
            status(put(base()+"/sources/guide").contentType("application/json").content(invalid),400);
        status(put(base()+"/sources/guide").queryParam("extra","x").contentType("application/json").content(valid),400);
        status(put(base()+"/sources/guide").contentType("application/json").content(" ".repeat(131073)),400);
        status(post(base()+"/search").contentType("application/json").content("{\"keywords\":[\"灌溉\"],\"keywords\":[\"排水\"]}"),400);
        status(post(base()+"/search").contentType("application/json").content("{\"keywords\":[\"灌溉\",\" 灌溉 \"]}"),400);
        status(post(base()+"/search").contentType("application/json").content(" ".repeat(2049)),400);
        status(delete(base()+"/sources/guide").queryParam("expectedCurrentVersionId",UUID.randomUUID().toString(),UUID.randomUUID().toString()),400);
    }
    @Test void crossProjectStaleIdentityAndTrueTenantCollaboration() throws Exception {
        publish("guide",null,"灌溉说明"); var other=WebAppDataRuntimeFixture.seed(owner);
        status(get("/api/v1/projects/"+other.runtime().projectId()+"/assistant/knowledge/sources"),404);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",UUID.randomUUID(),data.runtime().projectId(),other.runtime().actorId());
        token=tokens.issue(new AuthenticatedPrincipal(other.runtime().actorId(),other.runtime().tenantId(),data.runtime().projectId())).value();
        assertThat(search("灌溉").path("hits").size()).isEqualTo(1);
        token=tokens.issue(new AuthenticatedPrincipal(data.runtime().actorId(),data.runtime().tenantId(),data.runtime().projectId(),999)).value();
        status(get(base()+"/sources"),401);
    }
    @Test void revokedMemberAfterReadCannotReceiveCollectedSnippet() throws Exception {
        publish("guide",null,"灌溉说明");
        doAnswer(call -> { var result=call.callRealMethod(); if(Boolean.TRUE.equals(call.getArgument(2)))
            owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId()); return result;
        }).when(documents).current(any(),any(),anyBoolean());
        status(post(base()+"/search").contentType("application/json").content("{\"keywords\":[\"灌溉\"]}"),404);
    }
    @Test void deletionAfterCollectionRejectsStaleVersionAndSourceFailureIsNotNoMatch() throws Exception {
        publish("guide",null,"灌溉说明"); var once=new AtomicBoolean();
        doAnswer(call -> { var result=call.callRealMethod(); if(Boolean.TRUE.equals(call.getArgument(2)) && once.compareAndSet(false,true))
            owner.update("DELETE FROM assistant_knowledge_document WHERE project_id=?",data.runtime().projectId()); return result;
        }).when(documents).current(any(),any(),anyBoolean());
        status(post(base()+"/search").contentType("application/json").content("{\"keywords\":[\"灌溉\"]}"),409);
        reset(documents); doThrow(new IllegalStateException("fixed source failure")).when(documents).current(any(),any(),anyBoolean());
        status(post(base()+"/search").contentType("application/json").content("{\"keywords\":[\"灌溉\"]}"),500);
    }
    @Test void versionAndSourceCapAreEnforcedWithoutChangingSharedQuota() throws Exception {
        UUID id=null; for(int i=0;i<20;i++) id=publish("guide",id,"灌溉版本"+i);
        status(put(base()+"/sources/guide").contentType("application/json").content(body(id,"灌溉过量版本",true)),429);
        owner.update("""
            INSERT INTO assistant_knowledge_document(id,tenant_id,project_id,created_by,source_key,version_number,content_sha256,content)
            SELECT gen_random_uuid(),?,?,?,'source_'||i,1,encode(digest('灌溉说明','sha256'),'hex'),'灌溉说明' FROM generate_series(1,99)i
            """,data.runtime().tenantId(),data.runtime().projectId(),data.runtime().actorId());
        status(put(base()+"/sources/overflow").contentType("application/json").content(body(null,"灌溉说明",true)),429);
        assertThat(owner.queryForObject("SELECT count(DISTINCT source_key) FROM assistant_knowledge_document WHERE project_id=?",Integer.class,data.runtime().projectId())).isEqualTo(100);
    }
    @Test void readOnlySearchWithIdempotencyHeaderAlwaysReadsFreshVersion() throws Exception {
        var first=publish("guide",null,"灌溉旧说明");
        var request=post(base()+"/search").header("Idempotency-Key","knowledge-read-only")
            .contentType("application/json").content("{\"keywords\":[\"灌溉\"]}");
        status(request,200); publish("guide",first,"排水新说明");
        var result=mvc.perform(auth(request)).andReturn(); assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("state").asString()).isEqualTo("NO_MATCH");
    }
    @Test void replacementAfterCollectionAndCorruptionFailClosed() throws Exception {
        publish("guide",null,"灌溉说明"); var once=new AtomicBoolean();
        doAnswer(call -> {var result=call.callRealMethod(); if(Boolean.TRUE.equals(call.getArgument(2)) && once.compareAndSet(false,true))
            owner.update("INSERT INTO assistant_knowledge_document(id,tenant_id,project_id,created_by,source_key,version_number,content_sha256,content) VALUES (?,?,?,?,'guide',2,encode(digest('排水说明','sha256'),'hex'),'排水说明')",
                UUID.randomUUID(),data.runtime().tenantId(),data.runtime().projectId(),data.runtime().actorId()); return result;
        }).when(documents).current(any(),any(),anyBoolean());
        status(post(base()+"/search").contentType("application/json").content("{\"keywords\":[\"灌溉\"]}"),409);
        reset(documents); owner.update("UPDATE assistant_knowledge_document SET content_sha256=repeat('0',64) WHERE project_id=?",data.runtime().projectId());
        status(get(base()+"/sources/guide"),500);
        status(post(base()+"/search").contentType("application/json").content("{\"keywords\":[\"排水\"]}"),500);
    }

}
