package com.things.link.bootstrap.integration;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** 只使用真实HTTP/数据库与签名Console令牌；无模拟认证上下文。 */
abstract class ApiKeyHttpFixture extends AbstractIntegrationTest {
    @Autowired TokenIssuer tokens;
    @Autowired TenantProvisioning tenants;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    UUID tenant,project,account;
    JdbcTemplate owner;
    HikariDataSource ownerPool;
    List<UUID> additionalAccounts=new ArrayList<>();
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @BeforeEach void seedHttp(){
        ownerPool=new HikariDataSource();
        ownerPool.setJdbcUrl(fixtureJdbcUrl());
        ownerPool.setUsername(POSTGRES.getUsername());
        ownerPool.setPassword(POSTGRES.getPassword());
        ownerPool.setMinimumIdle(0);
        // 锁竞争用例最多并发三次变更，还需独立连接观察 pg_stat_activity。
        ownerPool.setMaximumPoolSize(6);
        owner=new JdbcTemplate(ownerPool);
        tenant=tx.execute(s->tenants.createTenant("key-http"));account=Uuid7.generate();project=Uuid7.generate();
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'unused','key-http')",account,account+"@example.com");
        owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",Uuid7.generate(),tenant,account);
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'key-http','sh-1',?)",project,tenant,"kh"+project.toString().replace("-",""));
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",Uuid7.generate(),project,account);
    }
    /** 专库用例覆盖此 URL，使 owner 夹具与被测 Spring 数据源指向同一数据库。 */
    protected String fixtureJdbcUrl(){return POSTGRES.getJdbcUrl();}
    @AfterEach void cleanupHttp(){
        TenantContext.clear();
        try {
            for(String table:List.of("integ_api_key","sys_idempotency_record","sys_usage_fact","sys_usage_counter_daily"))owner.update("DELETE FROM "+table+" WHERE project_id=?",project);
            owner.update("DELETE FROM sys_project_member WHERE project_id=?",project);
            owner.update("DELETE FROM sys_project WHERE id=?",project);
            owner.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",tenant);
            owner.update("DELETE FROM sys_tenant_member WHERE tenant_id=?",tenant);
            owner.update("DELETE FROM sys_tenant WHERE id=?",tenant);
            owner.update("DELETE FROM sys_account WHERE id=?",account);
            for(UUID id:additionalAccounts)owner.update("DELETE FROM sys_account WHERE id=?",id);
        } finally {
            if (ownerPool != null) ownerPool.close();
        }
    }
    String base(){return "/api/v1/projects/"+project+"/api-keys";}
    String token(){return tokens.issue(new AuthenticatedPrincipal(account,tenant,project)).value();}
    String requestBody(UUID operation)throws Exception{
        return json.writeValueAsString(Map.of("operationId",operation,"name","Http key","scopes",List.of("device:read"),
            "ipCidrs",List.of("127.0.0.1/32"),"expiresAt",Instant.now().plus(Duration.ofDays(7)).toString()));
    }
    HttpResponse<String> request(String method,String path,String body,String token,Map<String,String> headers)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        headers.forEach(builder::header);
        builder.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
}
