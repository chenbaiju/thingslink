package com.things.link.bootstrap.integration;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.telemetry.application.PropertyIngestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** D-193：消息由生产摄入事务生成，浏览器读取真实HTTP，不替换消息/诊断/权限查询。 */
@EnabledIfSystemProperty(named="thingslink.test.device-debug-browser", matches="true")
class DeviceMessageDebugConsoleJourneyTests extends WebhookFixture {
    // 本片不验旧实时广播；避免非Kafka夹具误连开发Broker。消息落库与查询均为真实实现。
    @MockitoBean(enforceOverride=true)
    com.things.link.ingestion.application.RealtimeKafkaPublisher isolatedRealtime;
    @Autowired PropertyIngestionService ingestion;
    @Autowired PasswordEncoder passwords;
    UUID otherProject;
    @Override String modelSnapshot() {
        return "{\"properties\":{\"value\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"},\"accessToken\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"}},\"events\":{},\"commands\":{}}";
    }
    @AfterEach void cleanupDebug() {
        owner.update("DELETE FROM sys_refresh_token WHERE account_id=?", account);
        for (String table : List.of("sys_outbox_event","ts_property_point_internal","ts_device_message_log",
                "ts_property_aggregate_backfill","sys_message_log_inbox","sys_inbox_message","dev_access_binding"))
            owner.update("DELETE FROM " + table + " WHERE project_id=?",project);
        if (otherProject != null) {
            owner.update("DELETE FROM sys_project_member WHERE project_id=?",otherProject);
            owner.update("DELETE FROM sys_project WHERE id=?",otherProject);
        }
    }
    @ParameterizedTest @ValueSource(strings={"OWNER","VIEWER"})
    void actualMessageDetailsDiagnosticsAndProjectIsolation(String role) throws Exception {
        String password="Local-Debug-Journey-Only-2030!";
        owner.update("UPDATE sys_account SET password_hash=?,email_verified_at=clock_timestamp() WHERE id=?",passwords.encode(password),account);
        owner.update("UPDATE sys_project SET name='集成测试项目' WHERE id=?",project);
        owner.update("UPDATE dev_device SET name='调试旅程设备' WHERE id=?",device);
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,config_version) VALUES(?,?,?,'HTTP',1)",device,tenant,project);
        otherProject=Uuid7.generate();
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES(?,?,'隔离空项目','sh-1',?)",otherProject,tenant,"debug"+otherProject.toString().replace("-",""));
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,?)",Uuid7.generate(),otherProject,account,role);
        owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",role,project,account);
        var now=Instant.now();
        assertThat(ingestion.ingest(new StandardUplinkMessage(Uuid7.generate(),tenant,project,device,null,
                TransportProtocol.HTTP,StandardUplinkMessage.Direction.UP,StandardUplinkMessage.Type.PROPERTY_REPORT,
                "1.0.0",now,now,"debug-browser",100,Map.of("value",42,"accessToken",987654321)))).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM ts_device_message_log WHERE project_id=?",Long.class,project)).isEqualTo(1L);
        Path root=Path.of("../..").toAbsolutePath().normalize();
        Path log=root.resolve("logs/verify/g3-local-2/browser-"+role+".log");Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder("node",root.resolve("scripts/tests/console-device-debug-journey.cjs").toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.of("ACCESS_BACKEND","http://127.0.0.1:"+port,"ACCESS_EMAIL",account+"@example.com",
                "ACCESS_PASSWORD",password,"ACCESS_MODE",role,"DEBUG_DEVICE",device.toString(),"DEBUG_PROJECT",project.toString(),
                "DEBUG_OTHER_PROJECT",otherProject.toString()));
        var process=builder.start();
        try {
            assertThat(process.waitFor(120,TimeUnit.SECONDS)).as("browser deadline: %s",log).isTrue();
            assertThat(process.exitValue()).as("browser evidence: %s",log).isZero();
        } finally { if(process.isAlive())process.destroyForcibly(); }
    }
}
