package com.things.link.support.webhook;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.shared.message.PublicWebhookSource;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;

/** 调用方须在领域行锁之前持有项目权限及代次保护；此端口仅持久化可信事实。 */
@Component
public class PublicWebhookSourceWriter {
    private final TransactionalOutboxRepository outbox;
    private final TransactionLocalRlsScope rls;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PublicWebhookCodec codec;
    private final boolean enabled;
    public PublicWebhookSourceWriter(TransactionalOutboxRepository outbox,TransactionLocalRlsScope rls,
            JdbcTemplate jdbc,ObjectMapper json,
            @Value("${things-link.integration.webhook.enabled:false}") boolean publicWebhookEnabled,
            @Value("${things-link.integration.internal-event-source.enabled:false}") boolean internalSourceEnabled){
        this.outbox=outbox;this.rls=rls;this.jdbc=jdbc;this.json=json;this.codec=new PublicWebhookCodec(json);
        // 内部可信事件源与公开订阅分别启用；内网业务接收不得要求开放公开 Webhook。
        this.enabled=publicWebhookEnabled||internalSourceEnabled;
    }
    public boolean enabled(){return enabled;}
    @Transactional(propagation=Propagation.MANDATORY)
    public Instant recordedAt(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(PublicWebhookEvent event){
        if(!enabled)return;
        rls.establish(event.tenantId(),event.projectId());
        PublicWebhookSource source=codec.prepare(event);
        String payload=json.writeValueAsString(source);
        if(payload.getBytes(StandardCharsets.UTF_8).length>1_048_576)throw new IllegalArgumentException("Webhook source exceeds transport limit");
        outbox.append(new OutboxEvent(Uuid7.generate(),event.tenantId(),event.projectId(),PublicWebhookSource.AGGREGATE_TYPE,
            source.aggregateId(),PublicWebhookSource.EVENT_TYPE,source.aggregateId().toString(),payload,source.transportTrace(),event.recordedAt()));
    }
}
