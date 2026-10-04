package com.things.link.bootstrap.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;

/** ADR0193/0197：生产Console构建与真实随机端口后端，不伪造配置API结果。 */
@EnabledIfSystemProperty(named = "thingslink.test.device-access-browser", matches = "true")
class DeviceAccessConsoleJourneyTests extends WebhookFixture {
    @Autowired private PasswordEncoder passwords;

    /** 浏览器会话及配置专用事实先于父夹具设备/项目清理。 */
    @AfterEach void cleanBrowser() {
        owner.update("DELETE FROM sys_refresh_token WHERE account_id=?", account);
        for (String table : List.of("dev_access_binding", "sys_outbox_event")) {
            owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
        }
    }

    /** 三种真实资格分别启动独立浏览器上下文，未知响应只在真实提交后丢弃。 */
    @ParameterizedTest @ValueSource(strings = {"OWNER", "VIEWER", "ARCHIVED"})
    void actualConsoleConfigurationAndReadOnlyFeedback(String mode) throws Exception {
        String password = "Local-Access-Journey-Only-2030!";
        owner.update("UPDATE sys_account SET password_hash=?,email_verified_at=clock_timestamp() WHERE id=?", passwords.encode(password), account);
        owner.update("UPDATE sys_project SET name='集成测试项目' WHERE id=?", project);
        owner.update("UPDATE dev_device SET name='配置旅程设备' WHERE id=?", device);
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,config_version) VALUES(?,?,?,'MQTT',9007199254740993)", device, tenant, project);
        if (mode.equals("VIEWER")) owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?", project, account);
        Path root = Path.of("../..").toAbsolutePath().normalize();
        Path log = root.resolve("logs/verify/s14-r8d-2d-6c-4/browser-" + mode + ".log");
        Files.createDirectories(log.getParent());
        Path request = log.resolveSibling("archive-request"), ready = log.resolveSibling("archive-ready");
        Files.deleteIfExists(request); Files.deleteIfExists(ready);
        var builder = new ProcessBuilder("node", root.resolve("scripts/tests/console-device-access-journey.cjs").toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.of("ACCESS_BACKEND", "http://127.0.0.1:" + port,
                "ACCESS_EMAIL", account + "@example.com", "ACCESS_PASSWORD", password, "ACCESS_MODE", mode));
        var process = builder.start();
        try {
            if (mode.equals("ARCHIVED")) {
                // ADR0073只允许ACTIVE项目签发令牌；先真实登录选项目，再归档验证既有会话只读。
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(45)).until(() -> Files.exists(request) || !process.isAlive());
                assertThat(process.isAlive()).as("浏览器先完成真实登录及配置读取").isTrue();
                owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", project);
                Files.writeString(ready, "archived");
            }
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("真实浏览器有界结束；证据" + log).isTrue();
            assertThat(process.exitValue()).as("浏览器日志：" + log).isZero();
            assertThat(owner.queryForObject("SELECT config_version FROM dev_access_binding WHERE device_id=?", Long.class, device))
                    .isEqualTo(mode.equals("OWNER") ? 9007199254740998L : 9007199254740993L);
            assertThat(owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='DEVICE_ACCESS_CONFIG_CHANGED'", Long.class, project))
                    .isEqualTo(mode.equals("OWNER") ? 5L : 0L);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(request); Files.deleteIfExists(ready);
        }
    }
}
