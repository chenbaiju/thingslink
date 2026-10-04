package com.things.link.bootstrap.integration;

import com.things.link.integration.application.*;
import com.things.link.integration.domain.WebhookDeliveryRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class WebhookConsoleJourneyTests extends WebhookFixture {
    @Autowired PasswordEncoder passwords;@Autowired WebhookEventAdmission admission;@Autowired WebhookDeliveryState state;
    UUID other;
    @AfterEach void cleanupBrowser() {
        owner.update("DELETE FROM sys_refresh_token WHERE account_id=?",account);
        if(other!=null){owner.update("DELETE FROM sys_project_member WHERE project_id=?",other);owner.update("DELETE FROM sys_project WHERE id=?",other);}
    }
    @Test @EnabledIfSystemProperty(named="thingslink.test.webhook-browser",matches="true")
    void realConsoleLifecycleRecoveryQueriesAndIsolation()throws Exception {
        String password="Local-Webhook-Journey-Only-2030!";
        owner.update("UPDATE sys_account SET password_hash=?,email_verified_at=clock_timestamp() WHERE id=?",passwords.encode(password),account);
        owner.update("UPDATE sys_project SET name='集成测试项目' WHERE id=?",project);
        other=Uuid7.generate();owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'隔离测试项目','sh-1',?)",other,tenant,"wh"+other.toString().replace("-",""));
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",Uuid7.generate(),other,account);
        create(Uuid7.generate());var time=owner.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();UUID event=Uuid7.generate(),rejected=Uuid7.generate();
        admission.accept(new PublicWebhookEvent(event,"device.online",tenant,project,0,"device",device,device,time,time,null,"{}"));
        assertThat(admission.accept(new PublicWebhookEvent(rejected,"device.online",tenant,project,0,"device",device,device,time,time,null,"{\"oversize\":\""+"x".repeat(262144)+"\"}"))).isEqualTo("OVERSIZE");
        UUID delivery=owner.queryForObject("SELECT id FROM integ_webhook_delivery WHERE project_id=? AND event_id=?",UUID.class,project,event);
        var candidate=new WebhookDeliveryRepository.Candidate(tenant,project,delivery);var claim=state.claim(candidate,true).orElseThrow();state.finish(candidate,claim.token(),WebhookDeliveryState.Outcome.PERMANENT,400,1);
        Path root=Path.of("../..").toAbsolutePath().normalize(),log=root.resolve("logs/verify/s14-r8d-4d/browser.log");Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder("node",root.resolve("scripts/tests/console-webhook-journey.cjs").toString()).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.of("KEY_BACKEND","http://127.0.0.1:"+port,"KEY_EMAIL",account+"@example.com","KEY_PASSWORD",password,"WEBHOOK_DELIVERY",delivery.toString(),"WEBHOOK_REJECTED",rejected.toString()));
        var process=builder.start();try{assertThat(process.waitFor(120,TimeUnit.SECONDS)).as("browser bounded: "+log).isTrue();System.out.println(Files.readString(log));assertThat(process.exitValue()).isZero();
            assertThat(rows("integ_webhook_subscription")).isEqualTo(3);
            assertThat(owner.queryForObject("SELECT status FROM integ_webhook_delivery WHERE id=?",String.class,delivery)).isEqualTo("READY");
            assertThat(owner.queryForObject("SELECT recovery_round FROM integ_webhook_delivery WHERE id=?",Integer.class,delivery)).isEqualTo(2);
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
}
