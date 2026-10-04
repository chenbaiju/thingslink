package com.things.link.bootstrap.integration;

import com.things.link.integration.application.*;
import com.things.link.integration.domain.ApiKeyCredential;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** ADR0171真实PG；不靠Console上下文模拟Key身份，测试端口尚未开放资源。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="things-link.integration.api-key.enabled=true")
class ApiKeyAuthenticationTests extends ApiKeyHttpFixture {
    @Autowired ApiKeyAuthenticationService authentication;
    @Autowired ApiKeyManagementService management;
    ApiKeyManagementService.Result issue(){
        return management.issue(tenant,project,account,Uuid7.generate(),new ApiKeyManagementService.Spec("auth",
                List.of("device:read","device:control","alarm:read"),List.of("127.0.0.1/32","2001:db8::/32"),
                Instant.now().plus(Duration.ofDays(1))));
    }
    void invalid(String secret,String ip){
        assertThatThrownBy(()->authentication.authenticate(secret,ip)).isInstanceOfSatisfying(BusinessException.class,
                ex->assertThat(ex.errorCode().code()).isEqualTo(80003));
    }
    @Test void proofWorksWithoutScopeButOrdinaryRlsCannotEnumerateAndNoSecretEscapes(){
        var key=issue();assertThat(TenantContext.current()).isEmpty();assertThat(RlsScopeContext.current()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integ_api_key",Integer.class)).isZero();
        var principal=authentication.authenticate(key.secret(),"127.0.0.1");
        assertThat(principal.tenantId()).isEqualTo(tenant);assertThat(principal.projectId()).isEqualTo(project);
        assertThat(principal.issuerAccountId()).isEqualTo(account);assertThat(principal.scopes()).hasSize(3);
        assertThat(principal.getName()).isEqualTo("api-key:"+key.key().id());
        assertThat(principal.toString()).doesNotContain(key.secret(),ApiKeyCredential.parse(key.secret()).orElseThrow().digest());
        assertThat(TenantContext.current()).isEmpty();assertThat(RlsScopeContext.current()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integ_api_key",Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p, LATERAL aclexplode(p.proacl) a WHERE p.proname='integ_authenticate_api_key' AND a.grantee=0",Integer.class)).isZero();
    }
    @Test void ipAndSecretMustBothMatchWithoutDnsOrForwardedHeaderInterpretation(){
        var key=issue();assertThat(authentication.authenticate(key.secret(),"2001:db8:0:0::1").keyId()).isEqualTo(key.key().id());
        invalid(key.secret(),"127.0.0.2");invalid(key.secret(),"2001:db9::1");invalid(key.secret(),"localhost");
        invalid(key.secret(),"127.0.0.1, 192.0.2.1");invalid(key.secret(),"999.0.0.1");
        invalid(ApiKeyCredential.generate(key.key().id()).reveal(),"127.0.0.1");invalid(key.secret()+" ","127.0.0.1");
    }
    @Test void roleDowngradeNarrowsScopesAndRemovalInvalidatesIdentity(){
        var key=issue();owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=?",project);
        var reader=authentication.authenticate(key.secret(),"127.0.0.1");
        assertThat(reader.scopes()).containsExactlyInAnyOrder("device:read","alarm:read");
        assertThatThrownBy(()->reader.require("device:control",true)).isInstanceOfSatisfying(BusinessException.class,
                ex->assertThat(ex.errorCode().code()).isEqualTo(80004));
        owner.update("UPDATE sys_project_member SET role='OPERATOR' WHERE project_id=?",project);
        assertThat(authentication.authenticate(key.secret(),"127.0.0.1").scopes()).contains("device:control");
        owner.update("DELETE FROM sys_project_member WHERE project_id=?",project);invalid(key.secret(),"127.0.0.1");
    }
    @Test void revokedAndRotatedKeysDoNotAuthenticateAgain(){
        var key=issue();management.revoke(tenant,project,account,Uuid7.generate(),key.key().id());invalid(key.secret(),"127.0.0.1");
        var old=issue();var rotated=management.rotate(tenant,project,account,Uuid7.generate(),old.key().id(),
                new ApiKeyManagementService.Spec("rotated",List.of("device:read"),List.of("127.0.0.1/32"),Instant.now().plusSeconds(3600)));
        invalid(old.secret(),"127.0.0.1");assertThat(authentication.authenticate(rotated.secret(),"127.0.0.1").scopes()).containsExactly("device:read");
    }
    @Test void archivedProjectRemainsReadOnlyButGenerationNeverRevivesOldKey(){
        var key=issue();owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",project);
        var reader=authentication.authenticate(key.secret(),"127.0.0.1");assertThat(reader.writeAllowed()).isFalse();
        reader.require("device:read",false);assertThatThrownBy(()->reader.require("device:control",true)).isInstanceOf(BusinessException.class);
        owner.update("UPDATE sys_project SET status='ACTIVE',lifecycle_generation=lifecycle_generation+1 WHERE id=?",project);
        invalid(key.secret(),"127.0.0.1");
    }
    @Test void inactiveAccountAndDeletingProjectRejectAllScopes(){
        var key=issue();owner.update("UPDATE sys_account SET status='DISABLED' WHERE id=?",account);invalid(key.secret(),"127.0.0.1");
        owner.update("UPDATE sys_account SET status='ACTIVE' WHERE id=?",account);
        owner.update("UPDATE sys_project SET status='DELETING' WHERE id=?",project);invalid(key.secret(),"127.0.0.1");
    }
    @Test void databaseTimeExpiresCredentialWithoutApplicationClockOverride(){
        UUID id=Uuid7.generate();var credential=ApiKeyCredential.generate(id);
        owner.update("INSERT INTO integ_api_key(id,tenant_id,project_id,project_generation,issuer_account_id,name,secret_hash,scopes,ip_cidrs,status,created_at,expires_at) VALUES (?,?,?,0,?,'expired',?,ARRAY['device:read'],ARRAY['127.0.0.1/32']::cidr[],'ACTIVE',clock_timestamp()-interval '2 hours',clock_timestamp()-interval '1 hour')",
                id,tenant,project,account,credential.digest());invalid(credential.reveal(),"127.0.0.1");
    }
    @Test void authorityFailureDoesNotBecomeSuccessOrInvalidCredential(){
        var key=issue();owner.execute("REVOKE EXECUTE ON FUNCTION integ_authenticate_api_key(uuid,text,inet) FROM thingslink_app");
        try{assertThatThrownBy(()->authentication.authenticate(key.secret(),"127.0.0.1")).isInstanceOf(DataAccessException.class);}
        finally{owner.execute("GRANT EXECUTE ON FUNCTION integ_authenticate_api_key(uuid,text,inet) TO thingslink_app");}
        assertThat(authentication.authenticate(key.secret(),"127.0.0.1").keyId()).isEqualTo(key.key().id());
    }
}
