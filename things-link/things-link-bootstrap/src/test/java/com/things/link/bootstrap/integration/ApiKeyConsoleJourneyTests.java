package com.things.link.bootstrap.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties="things-link.integration.api-key.enabled=true")
class ApiKeyConsoleJourneyTests extends ApiKeyHttpFixture {
    @Autowired PasswordEncoder passwords;
    @org.junit.jupiter.api.AfterEach void cleanupBrowserSession() {
        owner.update("DELETE FROM sys_refresh_token WHERE account_id=?",account);
    }
    @Test @EnabledIfSystemProperty(named="thingslink.test.api-key-browser",matches="true")
    void realConsoleSecretRecoveryRotationAndRevocation()throws Exception {
        String password="Local-Key-Journey-Only-2030!";
        owner.update("UPDATE sys_account SET password_hash=?,email_verified_at=clock_timestamp() WHERE id=?",passwords.encode(password),account);
        owner.update("UPDATE sys_project SET name='集成测试项目' WHERE id=?",project);
        Path root=Path.of("../..").toAbsolutePath().normalize(),log=root.resolve("logs/verify/s14-r8b-5/browser.log");
        Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder("node",root.resolve("scripts/tests/console-api-key-journey.cjs").toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.of("KEY_BACKEND","http://127.0.0.1:"+port,"KEY_EMAIL",account+"@example.com","KEY_PASSWORD",password));
        var process=builder.start();
        try {
            assertThat(process.waitFor(100,TimeUnit.SECONDS)).as("真实浏览器须有界结束；证据"+log).isTrue();
            System.out.println(Files.readString(log));assertThat(process.exitValue()).isZero();
            assertThat(owner.queryForObject("SELECT count(*) FROM integ_api_key WHERE project_id=?",Integer.class,project)).isEqualTo(3);
            assertThat(owner.queryForObject("SELECT count(*) FROM integ_api_key WHERE project_id=? AND status='REVOKED'",Integer.class,project)).isEqualTo(2);
        } finally {if(process.isAlive())process.destroyForcibly();}
    }
}
