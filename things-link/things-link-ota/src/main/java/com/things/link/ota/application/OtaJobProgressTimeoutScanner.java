package com.things.link.ota.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 单条数据库超时调度；权威期限和恢复能力持久化，进程调度不是唯一事实。 */
@Component
@DataPlaneDatabase
public class OtaJobProgressTimeoutScanner implements SmartLifecycle {
    /** 只输出固定分类，不记录设备证据或完整异常正文。 */
    private static final Logger LOG = LoggerFactory.getLogger(OtaJobProgressTimeoutScanner.class);
    /** 领取/采用分别经过事务代理。 */ private final OtaJobProgressTimeoutService service;
    /** 测试可关闭自动执行而保留真实服务和数据库。 */ private final boolean enabled;
    /** 停机先阻止新领取，调度器随后等待有界数据库事务退出。 */
    private final AtomicBoolean running = new AtomicBoolean();

    /** 生产默认开启，每次处理最多一条且不新增线程队列。 */
    public OtaJobProgressTimeoutScanner(OtaJobProgressTimeoutService service,
            @Value("${things-link.ota.progress-timeout.enabled:true}") boolean enabled) {
        this.service = service; this.enabled = enabled;
    }
    /** 独立持久租约允许进程重启后恢复，不把异常改写成设备失败。 */
    @Scheduled(fixedDelayString = "${things-link.ota.progress-timeout.delay-millis:1000}", scheduler = "otaUploadScheduler")
    public void tick() {
        if (!enabled || !running.get()) return;
        try {
            service.claimOne().ifPresent(claim -> {
                if (running.get()) service.expire(claim.context().jobId(), claim.token());
            });
        } catch (RuntimeException failure) {
            LOG.warn("OTA期限事务未完成，保留数据库能力等待恢复，分类={}", failure.getClass().getSimpleName());
        }
    }
    /** 上下文就绪后接收调度。 */ @Override public void start() { running.set(true); }
    /** 不再领取新的责任。 */ @Override public void stop() { running.set(false); }
    /** 调度器生命周期继续等待已经开始的短事务。 */
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    /** 是否仍接受工作。 */ @Override public boolean isRunning() { return running.get(); }
    /** 先于调度器和数据库停止接收。 */ @Override public int getPhase() { return Integer.MAX_VALUE; }
}
