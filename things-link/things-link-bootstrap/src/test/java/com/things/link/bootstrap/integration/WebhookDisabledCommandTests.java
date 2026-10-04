package com.things.link.bootstrap.integration;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandReply;
import com.things.link.telemetry.application.DeviceCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

/** 停用公开Webhook不改变原命令完成及内部终态事件。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.webhook.enabled=false"})
class WebhookDisabledCommandTests extends OpenCommandHttpFixture {
    @Autowired DeviceCommandService commands;
    @Test void disabledSourceKeepsOriginalTerminal() {
        var id=service.submit(principal,device,"disabled","start",json.readTree("{}")).commandId();
        assertThat(commands.applyReply(new DeviceCommandReply(Uuid7.generate(),tenant,project,device,id,Instant.now(),Instant.now(),DeviceCommandReply.Status.SUCCESS,"{}",null,null,"disabled"))).isTrue();
        assertThat(owner.queryForObject("SELECT status FROM ts_device_command WHERE id=?",String.class,id)).isEqualTo("SUCCEEDED");
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='DEVICE_COMMAND_TERMINAL'",Integer.class,project)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'",Integer.class,project)).isZero();
    }
}
