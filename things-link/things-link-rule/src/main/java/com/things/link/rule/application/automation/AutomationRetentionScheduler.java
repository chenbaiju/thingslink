package com.things.link.rule.application.automation;

import com.things.link.rule.domain.AutomationRetentionRepository;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;

/** 清理不随受理开关关闭而停止；每项目独立限批，游标不保留私有载荷。 */
@Component
@DataPlaneDatabase
@ConditionalOnProperty(name="things-link.automation.retention.enabled",havingValue="true",matchIfMissing=true)
public class AutomationRetentionScheduler {
    private static final Logger LOGGER=LoggerFactory.getLogger(AutomationRetentionScheduler.class);
    private final AutomationRetentionRepository repository;
    private UUID afterProject;
    public AutomationRetentionScheduler(AutomationRetentionRepository repository){this.repository=repository;}
    @Scheduled(initialDelayString="${things-link.automation.retention.delay-millis:60000}",
            fixedDelayString="${things-link.automation.retention.delay-millis:60000}",scheduler="maintenanceScheduler")
    public void clean(){
        var previous=TenantContext.current();TenantContext.clear();
        try{
            repository.purgeInputs();repository.purgeIngressRejections();
            var scopes=repository.candidates(afterProject);
            if(scopes.isEmpty()){afterProject=null;return;}
            for(var scope:scopes){
                try{repository.purgeHistory(scope);}
                catch(RuntimeException failure){LOGGER.warn("自动化保留清理稍后重试，类型={}",failure.getClass().getSimpleName());}
                afterProject=scope.projectId();
            }
        }finally{TenantContext.clear();previous.ifPresent(TenantContext::set);}
    }
}
