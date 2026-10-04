package com.things.link.ota.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 有界数据库准入调度；停机停止新领取，已开始事务由短超时及租约恢复收束。 */
@Component
@DataPlaneDatabase
public class OtaCampaignAdmissionWorker implements SmartLifecycle {
    /** 不输出设备报告、签名正文或基础设施异常敏感内容。 */
    private static final Logger LOG = LoggerFactory.getLogger(OtaCampaignAdmissionWorker.class);
    /** 独立代理保证领取和处理各自提交。 */ private final OtaCampaignAdmissionService service;
    /** 测试可以停调度而保留真实服务。 */ private final boolean enabled;
    /** 最高阶段优先停止新领取。 */ private final AtomicBoolean running = new AtomicBoolean();
    /** 生产默认开启，每轮最多处理一个数据库作业。 */
    public OtaCampaignAdmissionWorker(OtaCampaignAdmissionService service,
            @Value("${things-link.ota.campaign.runtime-enabled:true}") boolean enabled) {
        this.service = service; this.enabled = enabled;
    }
    /** 无网络调用、不复制账号、不建立无界线程或队列。 */
    @Scheduled(fixedDelayString = "${things-link.ota.campaign.runtime-delay-millis:1000}",
            scheduler = "otaUploadScheduler")
    public void tick() {
        if (!enabled || !running.get()) return;
        try {
            service.claimOne().ifPresent(claim -> {
                if (running.get()) service.admit(claim.jobId(), claim.token());
            });
        } catch (RuntimeException failure) {
            LOG.warn("OTA活动准入事务未完成，保留租约等待恢复，分类={}", failure.getClass().getSimpleName());
        }
    }
    /** Spring完成初始化后才接收工作。 */
    @Override public void start() { running.set(true); }
    /** 只停止新领取，不将事务未知结果误写为跳过。 */
    @Override public void stop() { running.set(false); }
    /** 调度器后续等待实际有界事务，不能销毁其基础设施在先。 */
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    /** 当前是否接受工作。 */
    @Override public boolean isRunning() { return running.get(); }
    /** 早于调度器和数据库资源关闭。 */
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
