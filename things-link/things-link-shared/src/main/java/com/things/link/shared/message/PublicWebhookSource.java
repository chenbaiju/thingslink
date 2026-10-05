package com.things.link.shared.message;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** ADR0186 内部持久描述，不得作为 HTTP 请求或诊断正文暴露。 */
public record PublicWebhookSource(int schemaVersion, PublicWebhookEvent event, String hash, String eventText) {
    public static final String EVENT_TYPE="PUBLIC_WEBHOOK_SOURCE";
    public static final String AGGREGATE_TYPE="PUBLIC_WEBHOOK_SOURCE";
    public static final String TOPIC="tc.integration.webhook.source";
    public PublicWebhookSource {
        Objects.requireNonNull(event);
        if(schemaVersion!=1||!"{}".equals(event.payloadJson())||hash==null||!hash.matches("[0-9a-f]{64}")
            ||eventText!=null&&eventText.getBytes(StandardCharsets.UTF_8).length>262144)
            throw new IllegalArgumentException("Invalid Webhook source descriptor");
    }
    public UUID aggregateId(){return event.deviceId()==null?event.resourceId():event.deviceId();}
    public String transportTrace(){String trace=event.traceId();return trace==null||trace.isBlank()||trace.length()>64?event.eventId().toString():trace.strip();}
    @Override public String toString(){return "PublicWebhookSource[eventId="+event.eventId()+",body=REDACTED]";}
}
