package com.things.link.project.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订阅到期宽限、受限切换与资源包生命周期的定时触发。
 *
 * <p>本类只做「触发」：每轮先调用 {@link SubscriptionLifecycleService#advanceDueTransitions()}，
 * 成功后再调用 {@link TenantResourcePackageService#advanceDueTransitions()}，两者分别以自己的事务完成
 * 幂等推进。订阅失败不继续推进包；包失败不回滚已提交的订阅，分别记录稳定错误类型；
 * 由于每条推进语句都自带 {@code WHERE status = ...} 幂等仲裁、通知意图由唯一键去重，
 * 下一轮重试不会产生重复状态、重复意图或重复审计。
 *
 * <h2>为什么默认开启（S14-6a 关闭 D-169）</h2>
 * 触发开关 {@code things-link.commercial.subscription-lifecycle.enabled} 使用
 * {@code matchIfMissing = true}：<b>不配置也必须运行</b>。D-169 记录了此前的取值（没有
 * {@code matchIfMissing}，即默认关闭）的后果——生产若不显式配置，到期→宽限→受限切换与预约降级
 * 永远不会自动发生，而「状态机已实现」被误读成「生产会自动推进」。
 *
 * <p>测试的隔离改由**测试 profile 显式关闭**承担（{@code application-test.yml}）：
 * bootstrap 集成测试共享同一个 PostgreSQL 容器，后台线程会与用例显式推进的时间轴竞争
 * （用例故意把服务期放在过去来验证到期）。需要验证默认路径的用例用
 * {@code @TestPropertySource} 显式开启该开关。
 *
 * <p><b>正确性不依赖调度器</b>：每条推进语句都自带 {@code WHERE status = ...} 幂等仲裁、
 * 通知意图由唯一键去重，因此调度周期（以及多实例并发触发）只影响「多久推进一次」，
 * 不影响状态收敛的结果。
 */
@Component
@DataPlaneDatabase
@ConditionalOnProperty(
        prefix = "things-link.commercial.subscription-lifecycle",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SubscriptionLifecycleWorker {

    /** 只记录稳定错误类型，不把异常正文当作业务状态。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionLifecycleWorker.class);

    /** 幂等状态机入口。 */
    private final SubscriptionLifecycleService lifecycleService;
    private final DeploymentEntitlementPolicy entitlementPolicy;
    private final TenantResourcePackageService resourcePackages;

    /**
     * @param lifecycleService 幂等订阅状态机入口
     * @param entitlementPolicy 部署模式围栏，非商用模式不推进商业订阅和资源包
     * @param resourcePackages 订阅推进成功后的资源包维护入口
     */
    @Autowired
    public SubscriptionLifecycleWorker(SubscriptionLifecycleService lifecycleService,
            DeploymentEntitlementPolicy entitlementPolicy, TenantResourcePackageService resourcePackages) {
        this.lifecycleService = lifecycleService;
        this.entitlementPolicy = entitlementPolicy;
        this.resourcePackages = java.util.Objects.requireNonNull(resourcePackages, "资源包维护服务");
    }

    /** 保留历史手工装配的订阅专用入口；Spring 只能使用上方完整构造器。 */
    public SubscriptionLifecycleWorker(SubscriptionLifecycleService lifecycleService,
            DeploymentEntitlementPolicy entitlementPolicy) {
        this.lifecycleService = lifecycleService;
        this.entitlementPolicy = entitlementPolicy;
        this.resourcePackages = null;
    }

    /** 保留非 Spring 单测原构造入口。 */
    public SubscriptionLifecycleWorker(SubscriptionLifecycleService lifecycleService) {
        this(lifecycleService, DeploymentEntitlementPolicy.commercial());
    }

    /** 每轮推进一次；返回计数只用于观测，不参与业务判定。 */
    @Scheduled(fixedDelayString = "${things-link.commercial.subscription-lifecycle.fixed-delay-millis:30000}",
            initialDelayString = "${things-link.commercial.subscription-lifecycle.initial-delay-millis:15000}",
            scheduler = "maintenanceScheduler")
    public void advanceNextBatch() {
        if (entitlementPolicy.nonCommercial()) return;
        try {
            SubscriptionLifecycleReport report = lifecycleService.advanceDueTransitions();
            if (report.graceEntered() > 0 || report.restrictedFreeEntered() > 0
                    || report.downgradesApplied() > 0 || report.downgradesNotApplied() > 0
                    || report.notificationIntentsCreated() > 0 || report.projectsRestricted() > 0
                    || report.projectsRestored() > 0) {
                LOGGER.info("订阅生命周期推进 grace={} restrictedFree={} downgradeApplied={} "
                                + "downgradeNotApplied={} intents={} projectsRestricted={} projectsRestored={}",
                        report.graceEntered(), report.restrictedFreeEntered(), report.downgradesApplied(),
                        report.downgradesNotApplied(), report.notificationIntentsCreated(),
                        report.projectsRestricted(), report.projectsRestored());
            }
        } catch (RuntimeException failure) {
            LOGGER.error("订阅生命周期推进失败，类型={}", failure.getClass().getSimpleName());
            return;
        }
        if (resourcePackages == null) return; // 历史手工装配保持原订阅专用行为。
        try {
            ResourcePackageAdvanceReport report = resourcePackages.advanceDueTransitions();
            if (report.expired() > 0 || report.activatedPending() > 0) {
                LOGGER.info("资源包生命周期推进 expired={} activatedPending={} tenantsInvalidated={}",
                        report.expired(), report.activatedPending(), report.tenantsInvalidated());
            }
        } catch (RuntimeException failure) {
            LOGGER.error("资源包生命周期推进失败，类型={}", failure.getClass().getSimpleName());
        }
    }
}
