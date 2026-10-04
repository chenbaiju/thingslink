package com.things.link.ota.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每轮一个可信候选，批次推进与活动完成仅由有界数据库事务裁决。 */
@Component
@DataPlaneDatabase
public class OtaCampaignAdvancementWorker implements SmartLifecycle {
    /** 只输出固定分类，不记录设备证据或完整异常正文。 */
    private static final Logger LOG = LoggerFactory.getLogger(OtaCampaignAdvancementWorker.class);
    /** 单候选事务经过独立代理。 */ private final OtaCampaignAdvancementService service;
    /** 测试可关闭自动执行而保留真实服务和数据库。 */ private final boolean enabled;
    /** 停机先阻止新领取，调度器随后等待有界数据库事务退出。 */
    private final AtomicBoolean running = new AtomicBoolean();

    /** 生产默认开启，每次处理最多一条且不新增线程队列。 */
    public OtaCampaignAdvancementWorker(OtaCampaignAdvancementService service,
            @Value("${things-link.ota.campaign-advancement.enabled:true}") boolean enabled) {
        this.service = service; this.enabled = enabled;
    }
    /** 无网络副作用；异常仅输出固定分类，下轮重新定位权威候选。 */
    @Scheduled(fixedDelayString = "${things-link.ota.campaign-advancement.delay-millis:1000}", scheduler = "otaUploadScheduler")
    public void tick() {
        if (!enabled || !running.get()) return;
        try {
            service.advanceOne();
        } catch (RuntimeException failure) {
            LOG.warn("OTA扩批事务未完成，保留数据库能力等待恢复，分类={}", failure.getClass().getSimpleName());
        }
    }
    /** 上下文就绪后接收调度。 */ @Override public void start() { running.set(true); }
    /** 不再领取新的责任。 */ @Override public void stop() { running.set(false); }
    /** 调度器生命周期继续等待已经开始的短事务。 */
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    /** 是否仍接受工作。 */ @Override public boolean isRunning() { return running.get(); }
    /** 先于调度器和数据库停止接收。 */ @Override public int getPhase() { return Integer.MAX_VALUE; }
}
