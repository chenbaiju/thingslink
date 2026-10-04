package com.things.link.bootstrap.integration;

import com.things.link.alarm.application.AlarmEvaluationInput;
import com.things.link.alarm.application.AlarmEvaluationService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 公开来源关闭不得改变告警状态机，也不得偷偷保存新公开事件。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"things-link.integration.api-key.enabled=true", "things-link.integration.webhook.enabled=false"})
class WebhookDisabledAlarmTests extends OpenDeviceHttpFixture {
    @Autowired AlarmEvaluationService evaluation;
    @AfterEach void cleanupAlarm() {
        for (String table : List.of("alarm_event", "alarm_instance", "alarm_rule", "sys_outbox_event")) owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
    }
    @Test void disabledWebhookRetainsAlarmActivationAndRecoveryWithoutSource() {
        var rule = Uuid7.generate(); var now = Instant.now();
        owner.update("INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity) VALUES (?,?,?,'disabled','TYPE',?,'value','GT',30,'LT',25,'WARNING')", rule, tenant, project, device);
        TenantContext.set(new TenantScope(tenant, project, account));
        try {
            evaluation.evaluate(new AlarmEvaluationInput(Uuid7.generate(), tenant, project, device, "value", 40, now, now, "disabled"));
            evaluation.evaluate(new AlarmEvaluationInput(Uuid7.generate(), tenant, project, device, "value", 20, now.plusSeconds(1), now.plusSeconds(1), "disabled"));
        } finally { TenantContext.clear(); }
        assertThat(owner.queryForObject("SELECT condition_state FROM alarm_instance WHERE project_id=?", String.class, project)).isEqualTo("CLEARED");
        assertThat(owner.queryForObject("SELECT count(*) FROM alarm_event WHERE project_id=?", Integer.class, project)).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'", Integer.class, project)).isZero();
    }
}
