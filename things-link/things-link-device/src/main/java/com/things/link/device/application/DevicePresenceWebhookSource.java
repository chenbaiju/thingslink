package com.things.link.device.application;

import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.webhook.PublicWebhookSourceWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** ADR0188：适配器负责真实协议事实与设备锁；此端口仅记录已接受的边沿事件。 */
@Service
public class DevicePresenceWebhookSource {
    private final PublicWebhookSourceWriter source;
    private final ProjectLifecycleAccessService projects;
    private final TransactionLocalRlsScope rls;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final com.things.link.project.application.ProjectService management;
    public DevicePresenceWebhookSource(PublicWebhookSourceWriter source,ProjectLifecycleAccessService projects,
            TransactionLocalRlsScope rls,JdbcTemplate jdbc,ObjectMapper json,com.things.link.project.application.ProjectService management){this.source=source;this.projects=projects;this.rls=rls;this.jdbc=jdbc;this.json=json;this.management=management;}
    /** 须在获取设备或会话锁之前调用；已知不活跃项目仍允许必要的物理连接清理。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public OptionalLong capture(UUID tenant,UUID project){
        if(!source.enabled())return OptionalLong.empty();
        rls.establish(tenant,project);var generation=projects.lockReadableGeneration(tenant,project);
        return generation.isPresent()&&projects.snapshot(tenant,project).writeAllowed()?generation:OptionalLong.empty();
    }
    /** 必须先执行既有 Console 管理权限检查，再查询归属；来源被禁用时不增加查询。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public OptionalLong captureForConsole(UUID project){
        return source.enabled()?capture(management.requireProjectTenant(project),project):OptionalLong.empty();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(UUID tenant,UUID project,UUID device,OptionalLong generation,String before,String after,
            String origin,String reason,UUID sourceMessageId,UUID gatewayId,Instant occurredAt){
        append(tenant,project,device,generation,before,after,origin,reason,sourceMessageId,gatewayId,occurredAt,null);
    }
    /** occurredAt 为空表示控制操作，其观测时间取原始数据库写入时间。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(UUID tenant,UUID project,UUID device,OptionalLong generation,String before,String after,
            String origin,String reason,UUID sourceMessageId,UUID gatewayId,Instant occurredAt,String traceId){
        if(generation.isEmpty()||before.equals(after)||!"ONLINE".equals(before)&&!"ONLINE".equals(after))return;
        if(!Set.of("ONLINE","OFFLINE","INACTIVE").contains(before)||!Set.of("ONLINE","OFFLINE","INACTIVE").contains(after)
            ||!Set.of("MQTT","TOPOLOGY","HTTP","COAP","TCP").contains(origin)||reason==null||!reason.matches("[A-Z_]{1,48}"))
            throw new IllegalArgumentException("Invalid public presence edge");
        rls.establish(tenant,project);
        Long revision=jdbc.queryForObject("UPDATE dev_device SET webhook_presence_revision=webhook_presence_revision+1 WHERE tenant_id=? AND project_id=? AND id=? RETURNING webhook_presence_revision",Long.class,tenant,project,device);
        var payload=json.createObjectNode();payload.put("from",before);payload.put("to",after);payload.put("source",origin);
        payload.put("reason",reason);payload.put("presenceRevision",Long.toString(Objects.requireNonNull(revision)));
        payload.put("sourceMessageId",sourceMessageId==null?null:sourceMessageId.toString());payload.put("gatewayId",gatewayId==null?null:gatewayId.toString());
        Instant recordedAt=source.recordedAt();
        source.append(new PublicWebhookEvent(Uuid7.generate(),"ONLINE".equals(after)?"device.online":"device.offline",tenant,project,
            generation.getAsLong(),"device",device,device,occurredAt==null?recordedAt:occurredAt,recordedAt,traceId,payload.toString()));
    }
}
