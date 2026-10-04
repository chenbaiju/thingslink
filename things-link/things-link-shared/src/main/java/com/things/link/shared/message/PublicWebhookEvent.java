package com.things.link.shared.message;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
/** Trusted source fact, not an HTTP input. recordedAt belongs to the original stable fact, never retry time. */
public record PublicWebhookEvent(UUID eventId,String eventType,UUID tenantId,UUID projectId,long projectGeneration,
    String resourceType,UUID resourceId,UUID deviceId,Instant occurredAt,Instant recordedAt,String traceId,String payloadJson) {
    public static final Set<String> EVENT_TYPES=Set.of("device.online","device.offline","device.property.report","alarm.triggered","alarm.recovered","command.completed","ota.job.completed");
    public PublicWebhookEvent {
        Objects.requireNonNull(eventId);Objects.requireNonNull(tenantId);Objects.requireNonNull(projectId);Objects.requireNonNull(resourceId);Objects.requireNonNull(payloadJson);
        if(eventType==null||!EVENT_TYPES.contains(eventType)||projectGeneration<0||resourceType==null||!resourceType.matches("[a-z][a-z0-9_.]{0,31}"))throw new IllegalArgumentException("Invalid public source identity");
        occurredAt=Objects.requireNonNull(occurredAt).truncatedTo(ChronoUnit.MICROS);recordedAt=Objects.requireNonNull(recordedAt).truncatedTo(ChronoUnit.MICROS);
        if(traceId!=null&&traceId.length()>128)throw new IllegalArgumentException("Invalid public source trace");
    }
    @Override public String toString(){return "PublicWebhookEvent[eventId="+eventId+",eventType="+eventType+",payload=REDACTED]";}
}
