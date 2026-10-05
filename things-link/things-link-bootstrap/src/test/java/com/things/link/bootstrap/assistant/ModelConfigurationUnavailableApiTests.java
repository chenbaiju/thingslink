package com.things.link.bootstrap.assistant;

import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 实际空主密钥配置仍能启动，但HTTP不能保存秘密；专用测试库及合成Key。 */
@AutoConfigureMockMvc
class ModelConfigurationUnavailableApiTests extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TokenIssuer tokens;
    @Test void missingMasterKeyReturnsSpecificUnavailableWithoutWritingOrEchoing() throws Exception {
        var owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        var f=WebAppDataRuntimeFixture.seed(owner).runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",UUID.randomUUID(),f.projectId(),f.actorId());
        String token=tokens.issue(new AuthenticatedPrincipal(f.actorId(),f.tenantId(),f.projectId())).value();
        String path="/api/v1/projects/"+f.projectId()+"/assistant/model-configurations/deepseek-chat";
        String secret="synthetic-missing-master-key";
        for(int i=0;i<3;i++) {
            var response=mvc.perform(put(path).header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"expectedRevision\":\"0\",\"apiKey\":\""+secret+"\"}")).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("50061","服务器模型凭据保护不可用").doesNotContain(secret,"ciphertext","nonce");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_model_configuration WHERE project_id=?",Integer.class,f.projectId())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action LIKE 'assistant.model_configuration.%'",Integer.class,f.projectId())).isZero();
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",f.projectId(),f.actorId());
        assertThat(mvc.perform(put(path).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"expectedRevision\":\"0\",\"apiKey\":\""+secret+"\"}")).andReturn().getResponse().getStatus()).isEqualTo(403);
    }
}
