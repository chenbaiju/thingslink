package com.things.link.bootstrap.integration;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.Cursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** ADR0170真实Console管理面，不能用Key或请求体声明替代当前主体。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="things-link.integration.api-key.enabled=true")
class ApiKeyHttpTests extends ApiKeyHttpFixture {
    @org.springframework.beans.factory.annotation.Autowired
    com.things.link.enduser.application.AppTokenIssuer appTokens;

    @Test void appSignedTokenCannotImpersonateConsoleEvenWithSameSubject()throws Exception{
        String appToken=appTokens.issue(new com.things.link.enduser.application.AppAuthenticatedPrincipal(
                tenant,project,account)).value();
        assertThat(request("GET",base(),null,appToken,Map.of()).statusCode()).isEqualTo(401);
        assertThat(request("POST",base(),requestBody(Uuid7.generate()),appToken,Map.of()).statusCode()).isEqualTo(401);
    }

    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;
    @Test void productionConfigurationExposesOnlyQualifiedPublicResources()throws Exception{
        assertThat(context.getBeansOfType(com.things.link.integration.application.OpenApiRoute.class)).hasSize(9);
        var issued=request("POST",base(),requestBody(Uuid7.generate()),token(),Map.of());
        assertThat(issued.statusCode()).isEqualTo(201);
        String secret=json.readTree(issued.body()).path("secret").asText();
        assertThat(request("GET","/api/open/v1/devices",null,null,Map.of("X-Api-Key",secret)).statusCode()).isEqualTo(200);
    }
    @Test void onlyFirstResponseContainsSecretAndBothRecoveryPathsAreRedacted()throws Exception{
        UUID operation=Uuid7.generate();String body=requestBody(operation),token=token();
        var first=request("POST",base(),body,token,Map.of("Idempotency-Key",operation.toString()));
        assertThat(first.statusCode()).isEqualTo(201);assertThat(first.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var created=json.readTree(first.body());String secret=created.path("secret").asText();assertThat(secret).startsWith("tcak1.");
        var completed=request("POST",base(),body,token,Map.of("Idempotency-Key",operation.toString()));
        assertThat(completed.statusCode()).isEqualTo(409);assertThat(json.readTree(completed.body()).path("code").asInt()).isEqualTo(10014);
        var replay=request("POST",base(),body,token,Map.of("Idempotency-Key",Uuid7.generate().toString()));
        assertThat(replay.statusCode()).isEqualTo(201);assertThat(json.readTree(replay.body()).has("secret")).isFalse();
        var recovered=request("GET",base()+"/operations/"+operation,null,token,Map.of());
        assertThat(recovered.statusCode()).isEqualTo(200);assertThat(recovered.body()).doesNotContain(secret,"secretHash","secret_hash");
        assertThat(json.readTree(recovered.body()).path("id").asText()).isEqualTo(created.path("key").path("id").asText());
        assertThat(owner.queryForObject("SELECT count(*) FROM integ_api_key WHERE project_id=?",Integer.class,project)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"OPERATOR","VIEWER"})
    void lowerRolesCannotReadOrIssue(String role)throws Exception{
        owner.update("UPDATE sys_project_member SET role=? WHERE project_id=?",role,project);
        var result=request("POST",base(),requestBody(Uuid7.generate()),token(),Map.of());
        assertThat(result.statusCode()).isEqualTo(403);assertThat(json.readTree(result.body()).path("code").asInt()).isEqualTo(80001);
        assertThat(request("GET",base(),null,token(),Map.of()).statusCode()).isEqualTo(403);
    }
    @Test void anonymousApiKeyOnlyAndMixedIdentityAreRejected()throws Exception{
        String body=requestBody(Uuid7.generate());
        assertThat(request("POST",base(),body,null,Map.of()).statusCode()).isEqualTo(401);
        assertThat(request("POST",base(),body,null,Map.of("X-Api-Key","not-a-console-token")).statusCode()).isEqualTo(401);
        assertThat(request("POST",base(),body,token(),Map.of("X-Api-Key","conflicting")).statusCode()).isEqualTo(403);
    }
    @Test void actorAndTenantAreDerivedDespiteForgedBodyFields()throws Exception{
        var body=json.readTree(requestBody(Uuid7.generate())).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)body).put("actorAccountId",UUID.randomUUID().toString()).put("tenantId",UUID.randomUUID().toString());
        var result=request("POST",base(),json.writeValueAsString(body),token(),Map.of());
        assertThat(result.statusCode()).isEqualTo(201);
        var key=json.readTree(result.body()).path("key");assertThat(key.path("issuerAccountId").asText()).isEqualTo(account.toString());
        assertThat(owner.queryForObject("SELECT tenant_id FROM integ_api_key WHERE id=?",UUID.class,UUID.fromString(key.path("id").asText()))).isEqualTo(tenant);
    }
    @Test void rotationRevokeAndBoundedPaginationNeverExposeDigest()throws Exception{
        String token=token();
        var first=request("POST",base(),requestBody(Uuid7.generate()),token,Map.of());
        String old=json.readTree(first.body()).path("key").path("id").asText();
        var rotated=request("POST",base()+"/"+old+"/rotate",requestBody(Uuid7.generate()),token,Map.of());
        assertThat(rotated.statusCode()).isEqualTo(201);
        String key=json.readTree(rotated.body()).path("key").path("id").asText();
        var revoked=request("POST",base()+"/"+key+"/revoke",json.writeValueAsString(Map.of("operationId",Uuid7.generate())),token,Map.of());
        assertThat(revoked.statusCode()).isEqualTo(200);assertThat(json.readTree(revoked.body()).path("status").asText()).isEqualTo("REVOKED");
        var page=request("GET",base()+"?limit=1",null,token,Map.of());assertThat(page.statusCode()).isEqualTo(200);
        var firstPage=json.readTree(page.body());assertThat(firstPage.path("items").size()).isEqualTo(1);assertThat(firstPage.path("hasMore").asBoolean()).isTrue();
        var last=request("GET",base()+"?limit=1&cursor="+firstPage.path("nextCursor").asText(),null,token,Map.of());
        assertThat(last.statusCode()).isEqualTo(200);assertThat(json.readTree(last.body()).path("hasMore").asBoolean()).isFalse();
        assertThat(page.body()+last.body()+revoked.body()).doesNotContain("secret","Hash");
        assertThat(request("GET",base()+"?limit=101",null,token,Map.of()).statusCode()).isEqualTo(400);
        String wrong=Cursor.encode("key-v1|"+UUID.randomUUID()+"|"+old);
        assertThat(request("GET",base()+"?cursor="+wrong,null,token,Map.of()).statusCode()).isEqualTo(400);
    }
    @Test void roleChangesAndSelectedProjectMismatchAreNotHiddenByOldToken()throws Exception{
        String token=token();UUID op=Uuid7.generate();assertThat(request("POST",base(),requestBody(op),token,Map.of()).statusCode()).isEqualTo(201);
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=?",project);
        assertThat(request("GET",base()+"/operations/"+op,null,token,Map.of()).statusCode()).isEqualTo(403);
        assertThat(request("GET","/api/v1/projects/"+UUID.randomUUID()+"/api-keys",null,token,Map.of()).statusCode()).isIn(403,404);
    }
    @Test void externalTenantAdministratorUsesProjectsOwnerTenant()throws Exception{
        UUID admin=Uuid7.generate(),personalTenant=tx.execute(s->tenants.createTenant("external-admin"));additionalAccounts.add(admin);
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'unused','external-admin')",admin,admin+"@example.com");
        owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",Uuid7.generate(),personalTenant,admin);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",Uuid7.generate(),project,admin);
        try {
            String token=tokens.issue(new AuthenticatedPrincipal(admin,personalTenant,project)).value();
            var result=request("POST",base(),requestBody(Uuid7.generate()),token,Map.of());assertThat(result.statusCode()).isEqualTo(201);
            assertThat(owner.queryForObject("SELECT tenant_id FROM integ_api_key WHERE project_id=?",UUID.class,project)).isEqualTo(tenant);
            assertThat(json.readTree(result.body()).path("key").path("issuerAccountId").asText()).isEqualTo(admin.toString());
        } finally {
            owner.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",personalTenant);
            owner.update("DELETE FROM sys_tenant_member WHERE tenant_id=?",personalTenant);
            owner.update("DELETE FROM sys_tenant WHERE id=?",personalTenant);
        }
    }
}
