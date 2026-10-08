package com.things.link.bootstrap.alarm.fixture;

import com.things.link.ThingsLinkApplication;
import com.things.link.alarm.application.AlarmEvaluationInput;
import com.things.link.alarm.application.AlarmEvaluationService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.shared.tenant.RlsScopeContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** 仅测试classpath可用的可信评估夹具，不新增生产HTTP入口，不伪造告警或投递事实。 */
public final class AlarmDeliveryFixtureProcess {
    private AlarmDeliveryFixtureProcess() { }

    /** 仅允许专用本机库、普通APP角色及明确模板展开失败的合成配置；任何前置不符都拒绝写。 */
    public static void main(String[] args) throws Exception {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || !url.matches("jdbc:postgresql://localhost:5547/tc_console_[a-zA-Z0-9_]+"))
            throw new IllegalArgumentException("必须使用明确专用库");
        Path path = Path.of(System.getProperty("tc.alarm.fixture")).toAbsolutePath();
        if (!path.toString().contains("/things-link-console/logs/")) throw new IllegalArgumentException("夹具须位于忽略目录");
        ObjectMapper json = new ObjectMapper();
        var input = json.readTree(Files.readString(path));
        UUID tenant = UUID.fromString(input.path("tenantId").asString());
        UUID project = UUID.fromString(input.path("projectId").asString());
        UUID account = UUID.fromString(input.path("accountId").asString());
        UUID device = UUID.fromString(input.path("deviceId").asString());
        UUID rule = UUID.fromString(input.path("ruleId").asString());
        String type = input.path("alarmType").asString();
        if (!type.startsWith("DELIVERY_FIXTURE_") || type.length() != 48) throw new IllegalArgumentException("非合成规则");
        try (var context = new SpringApplicationBuilder(ThingsLinkApplication.class).profiles("test").run(args)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            if (!jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class))
                throw new IllegalStateException("必须使用普通APP角色");
            TenantContext.set(new TenantScope(tenant, project, account));
            try {
                Integer valid = jdbc.queryForObject("SELECT count(*) FROM alarm_rule r JOIN dev_device d ON d.id=r.originator_id "
                        + "JOIN alarm_notification_binding b ON b.rule_id=r.id JOIN alarm_notification_template t ON t.id=b.template_id "
                        + "WHERE r.id=? AND r.project_id=? AND r.tenant_id=? AND d.id=? AND r.alarm_type=? "
                        + "AND d.device_key LIKE 'delivery_%' AND r.enabled=true AND r.deleted_at IS NULL "
                        + "AND b.enabled=true AND b.deleted_at IS NULL AND t.enabled=true AND t.deleted_at IS NULL "
                        + "AND t.channel='EMAIL' AND t.subject_template=? AND t.body_template='受控投递查询夹具'",
                        Integer.class, rule, project, tenant, device, type, "${alarm.type}".repeat(6));
                if (valid == null || valid != 1) throw new IllegalStateException("受控配置不符");
                if (jdbc.queryForObject("SELECT count(*) FROM alarm_instance WHERE project_id=? AND originator_id=?", Integer.class, project, device) != 0)
                    throw new IllegalStateException("拒绝复用既有事故");
                if (jdbc.queryForObject("SELECT count(*) FROM sys_project_member WHERE project_id=? AND account_id=? AND role='OWNER'", Integer.class, project, account) != 1)
                    throw new IllegalStateException("操作者不是夹具项目OWNER");
                if (jdbc.queryForObject("SELECT count(*) FROM alarm_rule WHERE project_id=? AND originator_id=? AND enabled=true AND deleted_at IS NULL", Integer.class, project, device) != 1)
                    throw new IllegalStateException("设备存在其他生效规则");
                int recipients = jdbc.queryForObject("SELECT count(*) FROM alarm_notification_recipient n JOIN alarm_notification_binding b ON b.group_id=n.group_id "
                        + "WHERE b.rule_id=? AND n.enabled=true AND n.deleted_at IS NULL AND n.channel='EMAIL' AND n.target LIKE 'synthetic-%@example.invalid'", Integer.class, rule);
                int allRecipients = jdbc.queryForObject("SELECT count(*) FROM alarm_notification_recipient n JOIN alarm_notification_binding b ON b.group_id=n.group_id "
                        + "WHERE b.rule_id=? AND n.enabled=true AND n.deleted_at IS NULL", Integer.class, rule);
                if (recipients != 21 || allRecipients != recipients) throw new IllegalStateException("收件人非21个合成地址");
                Instant now = Instant.now();
                context.getBean(AlarmEvaluationService.class).evaluate(new AlarmEvaluationInput(Uuid7.generate(), tenant,
                        project, device, "temperature", 31, now.minusMillis(10), now, "console-delivery-fixture"));
                UUID instance = jdbc.queryForObject("SELECT id FROM alarm_instance WHERE project_id=? AND originator_id=?", UUID.class, project, device);
                int count = jdbc.queryForObject("SELECT count(*) FROM alarm_notification_delivery WHERE project_id=? AND instance_id=? "
                        + "AND status='TEMPLATE_INVALID' AND next_attempt_at IS NULL AND last_outbox_event_id IS NULL", Integer.class, project, instance);
                int total = jdbc.queryForObject("SELECT count(*) FROM alarm_notification_delivery WHERE project_id=? AND instance_id=?", Integer.class, project, instance);
                if (count != 21 || total != count) throw new IllegalStateException("终态意图数量或不外发边界不符");
                Files.writeString(path, json.createObjectNode().put("instanceId", instance.toString()).put("count", count).toString());
            } finally { TenantContext.clear(); RlsScopeContext.clear(); }
        }
    }
}
