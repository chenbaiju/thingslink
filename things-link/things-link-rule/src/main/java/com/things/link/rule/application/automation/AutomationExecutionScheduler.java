package com.things.link.rule.application.automation;

import com.things.link.rule.domain.AutomationExecutionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.slf4j.LoggerFactory;

/** 每轮最多100个租户头部，逐个执行；工作槽跨实例互斥，禁止无界内存排队。 */
@Component
@DataPlaneDatabase
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${things-link.automation.property.enabled:false} || ${things-link.automation.time.enabled:false}")
public class AutomationExecutionScheduler {
    private static final Logger LOGGER=LoggerFactory.getLogger(AutomationExecutionScheduler.class);
    private final AutomationExecutionRepository store;
    private final AutomationExecutionRunner runner;
    public AutomationExecutionScheduler(AutomationExecutionRepository store,AutomationExecutionRunner runner){this.store=store;this.runner=runner;}
    @Scheduled(fixedDelayString="${things-link.automation.execution.scan-delay-millis:1000}",scheduler="automationLifecycleScheduler")
    public void scan(){
        for(var candidate:store.candidates()){
            try{runner.run(candidate);}
            catch(RuntimeException failure){
                // 未能登记的异常由过期租约恢复，不记录私有动作、输入或异常正文。
                LOGGER.warn("自动化执行本轮未完成，保留持久租约恢复；类型={}",failure.getClass().getSimpleName());
            }
        }
    }
}
