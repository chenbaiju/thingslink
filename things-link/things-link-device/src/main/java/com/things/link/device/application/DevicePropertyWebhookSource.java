package com.things.link.device.application;

import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.webhook.PublicWebhookSourceWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.OptionalLong;
import java.util.UUID;

/** ADR0187: original accepted shadow values, not a best-effort realtime listener. */
@Service
public class DevicePropertyWebhookSource {
    private final PublicWebhookSourceWriter source;
    private final ProjectLifecycleAccessService projects;
    private final TransactionLocalRlsScope rls;
    private final ObjectMapper json;
    public DevicePropertyWebhookSource(PublicWebhookSourceWriter source,ProjectLifecycleAccessService projects,
            TransactionLocalRlsScope rls,ObjectMapper json){this.source=source;this.projects=projects;this.rls=rls;this.json=json;}
    /** Called before shadow locks; absence means disabled, never a failed project permission. */
    @Transactional(propagation=Propagation.MANDATORY)
    public OptionalLong begin(UUID tenant,UUID project){
        if(!source.enabled())return OptionalLong.empty();
        rls.establish(tenant,project);projects.requireActiveForWrite(tenant,project);
        return OptionalLong.of(projects.snapshot(tenant,project).lifecycleGeneration());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(DeviceRealtimeUpdate update,long generation){
        var payload=json.createObjectNode();payload.put("sourceMessageId",update.messageId().toString());
        payload.put("modelVersionId",update.thingModelVersionId().toString());payload.put("modelVersion",update.modelVersion());
        payload.put("shadowVersion",Integer.toString(update.shadowVersion()));
        var properties=payload.putObject("properties");
        update.propertiesJson().forEach((key,value)->{
            var item=properties.putObject(key);item.put("valueJson",value);
            item.put("dataType",java.util.Objects.requireNonNull(update.propertyDataTypes().get(key)));
            item.put("reportedRevision",java.util.Objects.requireNonNull(update.reportedRevisions().get(key)));
        });
        source.append(new PublicWebhookEvent(Uuid7.generate(),"device.property.report",update.tenantId(),update.projectId(),
            generation,"device",update.deviceId(),update.deviceId(),update.occurredAt(),source.recordedAt(),update.traceId(),payload.toString()));
    }
}
