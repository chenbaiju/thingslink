package com.things.link.bootstrap.ota;

import com.things.link.shared.message.PublicWebhookSource;
import com.things.link.support.webhook.PublicWebhookCodec;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** ADR0204真实状态机验收：只观察实际已提交事实，不构造终态或直接调用完成来源。 */
final class OtaCompletionSourceAssertions {
    private OtaCompletionSourceAssertions() { }
    /** 旧0360升级候选保持其原关闭配置；当前版本使用显式测试签名密钥。 */
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("things-link.integration.webhook.enabled",()->!Boolean.getBoolean("thingslink.ota.upgrade-test"));
        registry.add("things-link.integration.webhook.current-signing-key-id",()->"ota-test");
        registry.add("things-link.integration.webhook.signing-keys-json",()->"{\"ota-test\":\"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=\"}");
    }
    /** Outbox有独立保留期，OTA清理前后须保持原规范文本而非重新编码。 */
    static java.util.List<String> sources(JdbcTemplate owner,UUID project) {
        return owner.queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE' AND payload::jsonb->'event'->>'eventType'='ota.job.completed' ORDER BY id",String.class,project);
    }
    /** 每个真实封闭作业恰有一个原记录和一个公开来源，非终态没有完成来源。 */
    static void verify(JdbcTemplate owner, UUID project) {
        if(Boolean.getBoolean("thingslink.ota.upgrade-test")) return;
        var json=JsonMapper.builder().build();
        var originals=new HashMap<UUID,java.util.Map<String,Object>>();
        for(var row:owner.queryForList("SELECT * FROM ota_device_job WHERE project_id=? AND status IN ('SUCCEEDED','ROLLED_BACK','CANCELLED','SKIPPED_INELIGIBLE','TIMED_OUT')",project))
            originals.put((UUID)row.get("id"),row);
        var records=owner.queryForList("SELECT * FROM ota_job_completion WHERE project_id=?",project);
        assertThat(records).as("真实封闭作业的来源记录 project=%s",project).hasSize(originals.size());
        var events=new HashMap<UUID,PublicWebhookSource>();
        for(String text:owner.queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE' AND payload::jsonb->'event'->>'eventType'='ota.job.completed'",String.class,project)) {
            var source=json.readValue(text,PublicWebhookSource.class);
            new PublicWebhookCodec(json).validate(source);
            assertThat(events.put(source.event().eventId(),source)).as("每个作业仅一个Outbox来源").isNull();
        }
        assertThat(events.keySet()).containsExactlyInAnyOrderElementsOf(originals.keySet());
        for(var row:records) {
            UUID job=(UUID)row.get("job_id"); var original=originals.get(job); var source=events.get(job); var event=source.event();
            assertThat(original).isNotNull();
            assertThat(row.get("status")).isEqualTo(original.get("status"));
            assertThat(row.get("state_version")).isEqualTo(original.get("state_version"));
            assertThat(row.get("attempt_no")).isEqualTo(original.get("attempt_no"));
            assertThat(row.get("failure_code")).isEqualTo(original.get("failure_code"));
            assertThat(event.resourceType()).isEqualTo("ota_job"); assertThat(event.resourceId()).isEqualTo(job);
            assertThat(event.tenantId()).isEqualTo(row.get("tenant_id")); assertThat(event.projectId()).isEqualTo(project);
            assertThat(event.deviceId()).isEqualTo(row.get("device_id"));
            assertThat(event.projectGeneration()).isEqualTo(((Number)row.get("project_generation")).longValue());
            assertThat(event.traceId()).isEqualTo(row.get("trace_id"));
            assertThat(event.occurredAt()).isEqualTo(((Timestamp)row.get("completed_at")).toInstant());
            assertThat(event.recordedAt()).isEqualTo(event.occurredAt()); assertThat(event.occurredAt().getNano()%1000).isZero();
            var payload=json.readTree(source.eventText()).get("payload");
            var expected=json.createObjectNode();
            for(String[] field:new String[][]{{"jobId","job_id"},{"campaignId","campaign_id"},{"firmwareId","firmware_id"},
                    {"manifestSha256","manifest_sha256"},{"fromStatus","from_status"},{"status","status"},
                    {"stateVersion","state_version"},{"attemptNo","attempt_no"},{"failureCode","failure_code"}}) {
                Object value=row.get(field[1]); expected.put(field[0],value==null?null:value.toString());
            }
            expected.put("completedAt",event.occurredAt().toString());
            assertThat(payload).isEqualTo(expected);
        }
    }
}
