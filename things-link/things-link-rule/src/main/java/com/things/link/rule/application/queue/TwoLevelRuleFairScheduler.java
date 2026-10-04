package com.things.link.rule.application.queue;

import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import jakarta.annotation.PreDestroy;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 为规则执行提供租户外层、项目内层的两级有界轮转调度。
 *
 * <p>架构文档 7.1 与 ADR 0024 要求共享入口不能被慢租户或单个热点项目长期垄断。本实现只向固定线程池
 * 投递实际可运行的任务，其余工作留在受业务额度和实例物理容量双重约束的两级队列中。外层每次只从一个
 * 租户取一项，内层每次只从一个项目取一项；完成后重新进入队尾，从而给其他租户和项目稳定的轮转机会。</p>
 */
public final class TwoLevelRuleFairScheduler implements AutoCloseable {

    /** 默认工作并发；至少两条通道才能实现慢租户隔离。 */
    private static final int DEFAULT_WORKER_COUNT = 4;
    /** 默认实例等待项物理上限；业务额度为空时仍由此阻止内存无限增长。 */
    private static final int DEFAULT_INSTANCE_QUEUE_CAPACITY = 1_000;
    /** 默认活跃租户物理上限。 */
    private static final int DEFAULT_ACTIVE_TENANT_CAPACITY = 200;
    /** 默认活跃项目物理上限。 */
    private static final int DEFAULT_ACTIVE_PROJECT_CAPACITY = 1_000;

    /** 所有轮转队列与容量计数共用此锁，避免提交和完成并发时重复入环。 */
    private final Object monitor = new Object();
    /** 固定线程池；只接收最多 workerCount 个已占槽任务。 */
    private final ExecutorService executor;
    /** 工作线程数，同时也是实例运行槽数量。 */
    private final int workerCount;
    /** 实例等待项物理上限。 */
    private final int instanceQueueCapacity;
    /** 活跃租户物理上限。 */
    private final int activeTenantCapacity;
    /** 活跃项目物理上限。 */
    private final int activeProjectCapacity;
    /** 活跃租户状态；存在即表示该租户有运行项或等待项。 */
    private final Map<UUID, TenantState> tenants = new HashMap<>();
    /** 外层租户轮转环。 */
    private final ArrayDeque<UUID> readyTenants = new ArrayDeque<>();
    /** 防止同一租户被重复放入外层轮转环。 */
    private final Set<UUID> readyTenantSet = new HashSet<>();
    /** 当前已占用的工作槽。 */
    private int runningCount;
    /** 当前实例内等待项总数。 */
    private int queuedCount;
    /** 当前活跃项目总数。 */
    private int activeProjectCount;
    /** 关闭后拒绝新工作且不再续排等待项。 */
    private boolean closed;

    /** 使用架构基线默认物理容量创建调度器。 */
    public TwoLevelRuleFairScheduler() {
        this(DEFAULT_WORKER_COUNT, DEFAULT_INSTANCE_QUEUE_CAPACITY,
                DEFAULT_ACTIVE_TENANT_CAPACITY, DEFAULT_ACTIVE_PROJECT_CAPACITY);
    }

    /**
     * 创建容量可控的调度器，供应用配置与故障验收使用。
     *
     * @param workerCount 固定工作线程数
     * @param instanceQueueCapacity 实例等待项物理上限
     * @param activeTenantCapacity 活跃租户物理上限
     * @param activeProjectCapacity 活跃项目物理上限
     */
    public TwoLevelRuleFairScheduler(int workerCount, int instanceQueueCapacity,
                                     int activeTenantCapacity, int activeProjectCapacity) {
        if (workerCount < 2 || instanceQueueCapacity < 1
                || activeTenantCapacity < 1 || activeProjectCapacity < 1) {
            throw new IllegalArgumentException("调度器容量必须满足 worker>=2 且其他物理容量>=1");
        }
        this.workerCount = workerCount;
        this.instanceQueueCapacity = instanceQueueCapacity;
        this.activeTenantCapacity = activeTenantCapacity;
        this.activeProjectCapacity = activeProjectCapacity;
        ThreadFactory threadFactory = Thread.ofPlatform().daemon(true).name("rule-fair-", 0).factory();
        this.executor = Executors.newFixedThreadPool(workerCount, threadFactory);
    }

    /**
     * 按最新有效策略快照提交一项规则执行。
     *
     * <p>策略容量只统计等待项；若存在空闲工作槽且租户并发尚未用尽，即使等待容量为零也可直接执行。
     * 同一活跃租户后续提交携带的新快照会立即替换旧快照，但不会驱逐已经接受的工作。</p>
     *
     * @param item 可信租户、项目和执行体
     * @param limits 当前有效策略限制
     * @return 有限提交结果
     */
    public RuleQueueSubmissionResult submit(RuleQueueWorkItem item, RuleQueueLimits limits) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(limits, "limits");
        synchronized (monitor) {
            if (closed) {
                return RuleQueueSubmissionResult.CLOSED;
            }
            if (limits.tenantConcurrencyLimit() != null && limits.tenantConcurrencyLimit() == 0) {
                return RuleQueueSubmissionResult.TENANT_QUEUE_FULL;
            }
            TenantState tenant = tenants.get(item.tenantId());
            boolean newTenant = tenant == null;
            if (newTenant && tenants.size() >= activeTenantCapacity) {
                return RuleQueueSubmissionResult.ACTIVE_TENANT_LIMIT;
            }
            ProjectState project = tenant == null ? null : tenant.projects.get(item.projectId());
            boolean newProject = project == null;
            if (newProject && activeProjectCount >= activeProjectCapacity) {
                return RuleQueueSubmissionResult.ACTIVE_PROJECT_LIMIT;
            }

            int tenantRunning = tenant == null ? 0 : tenant.runningCount;
            int tenantLimit = effectiveConcurrency(limits.tenantConcurrencyLimit());
            boolean executesImmediately = runningCount < workerCount && tenantRunning < tenantLimit;
            if (!executesImmediately) {
                int tenantQueued = tenant == null ? 0 : tenant.queuedCount;
                int projectQueued = project == null ? 0 : project.waiting.size();
                if (isFull(tenantQueued, limits.tenantQueueCapacity())) {
                    return RuleQueueSubmissionResult.TENANT_QUEUE_FULL;
                }
                if (isFull(projectQueued, limits.projectQueueCapacity())) {
                    return RuleQueueSubmissionResult.PROJECT_QUEUE_FULL;
                }
                if (queuedCount >= instanceQueueCapacity) {
                    return RuleQueueSubmissionResult.INSTANCE_QUEUE_FULL;
                }
            }

            if (tenant == null) {
                tenant = new TenantState(limits);
                tenants.put(item.tenantId(), tenant);
            } else {
                tenant.limits = limits;
            }
            if (project == null) {
                project = new ProjectState();
                tenant.projects.put(item.projectId(), project);
                activeProjectCount++;
            }
            project.waiting.addLast(item.work());
            tenant.queuedCount++;
            queuedCount++;
            addReadyProject(tenant, item.projectId());
            addReadyTenant(item.tenantId());
            dispatchAvailable();
            return RuleQueueSubmissionResult.ACCEPTED;
        }
    }

    /**
     * 在锁内把空闲槽分配给外层轮转环中的租户。
     *
     * <p>每轮租户最多领取一项，即使仍有多余并发额度也必须先回到队尾，否则高额度租户会在一次提交中
     * 抢走所有工作槽。</p>
     */
    private void dispatchAvailable() {
        int tenantsToInspect = readyTenants.size();
        while (!closed && runningCount < workerCount && tenantsToInspect > 0 && !readyTenants.isEmpty()) {
            UUID tenantId = readyTenants.removeFirst();
            readyTenantSet.remove(tenantId);
            TenantState tenant = tenants.get(tenantId);
            tenantsToInspect--;
            if (tenant == null || tenant.readyProjects.isEmpty()) {
                continue;
            }
            if (tenant.runningCount >= effectiveConcurrency(tenant.limits.tenantConcurrencyLimit())) {
                addReadyTenant(tenantId);
                continue;
            }
            Dispatch dispatch = pollOne(tenantId, tenant);
            if (dispatch == null) {
                continue;
            }
            runningCount++;
            tenant.runningCount++;
            tenant.queuedCount--;
            queuedCount--;
            if (!tenant.readyProjects.isEmpty()) {
                addReadyTenant(tenantId);
            }
            executor.execute(() -> runOne(dispatch));
            // 新入队租户也应在本次空闲槽分配中获得机会。
            tenantsToInspect = Math.max(tenantsToInspect, readyTenants.size());
        }
    }

    /**
     * 从租户内部项目环取一项，并把仍有积压的项目放回队尾。
     *
     * @param tenantId 租户 ID
     * @param tenant 租户状态
     * @return 可执行项；状态不一致时返回 {@code null}
     */
    private Dispatch pollOne(UUID tenantId, TenantState tenant) {
        UUID projectId = tenant.readyProjects.pollFirst();
        if (projectId == null) {
            return null;
        }
        tenant.readyProjectSet.remove(projectId);
        ProjectState project = tenant.projects.get(projectId);
        if (project == null) {
            return null;
        }
        Runnable work = project.waiting.pollFirst();
        project.runningCount++;
        if (!project.waiting.isEmpty()) {
            addReadyProject(tenant, projectId);
        }
        return new Dispatch(tenantId, projectId, work);
    }

    /**
     * 执行单项工作；业务异常只结束当前项，不能破坏调度器续排。
     *
     * @param dispatch 已占用运行槽的工作
     */
    private void runOne(Dispatch dispatch) {
        // 规则执行器线程不继承 Kafka 入口上下文，显式绑定数据面池并在本项结束后清理。
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            try {
                dispatch.work.run();
            } finally {
                complete(dispatch.tenantId, dispatch.projectId);
            }
        }
    }

    /**
     * 释放运行槽、回收空状态并继续轮转。
     *
     * @param tenantId 已完成租户
     * @param projectId 已完成项目
     */
    private void complete(UUID tenantId, UUID projectId) {
        synchronized (monitor) {
            runningCount--;
            TenantState tenant = tenants.get(tenantId);
            if (tenant == null) {
                return;
            }
            tenant.runningCount--;
            ProjectState project = tenant.projects.get(projectId);
            if (project != null) {
                project.runningCount--;
            }
            if (project != null && project.runningCount == 0 && project.waiting.isEmpty()) {
                tenant.projects.remove(projectId);
                tenant.readyProjectSet.remove(projectId);
                tenant.readyProjects.remove(projectId);
                activeProjectCount--;
            }
            if (tenant.runningCount == 0 && tenant.queuedCount == 0) {
                tenants.remove(tenantId);
                readyTenantSet.remove(tenantId);
                readyTenants.remove(tenantId);
            } else if (tenant.queuedCount > 0) {
                addReadyTenant(tenantId);
            }
            dispatchAvailable();
        }
    }

    /**
     * 将项目至多一次放入租户内层轮转环。
     *
     * @param tenant 租户状态
     * @param projectId 项目 ID
     */
    private static void addReadyProject(TenantState tenant, UUID projectId) {
        if (tenant.readyProjectSet.add(projectId)) {
            tenant.readyProjects.addLast(projectId);
        }
    }

    /**
     * 将租户至多一次放入外层轮转环。
     *
     * @param tenantId 租户 ID
     */
    private void addReadyTenant(UUID tenantId) {
        if (readyTenantSet.add(tenantId)) {
            readyTenants.addLast(tenantId);
        }
    }

    /**
     * 把未配置并发转换为实例工作槽上限。
     *
     * @param configured 策略值
     * @return 有效并发
     */
    private int effectiveConcurrency(Integer configured) {
        return configured == null ? workerCount : Math.min(configured, workerCount);
    }

    /**
     * 判断业务等待额度是否已满；空值由实例物理容量兜底。
     *
     * @param current 当前等待项
     * @param configured 策略容量
     * @return 是否拒绝下一项等待工作
     */
    private static boolean isFull(int current, Integer configured) {
        return configured != null && current >= configured;
    }

    /** 应用关闭时清除等待项并中断仍在运行的规则工作。 */
    @Override
    @PreDestroy
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            readyTenants.clear();
            readyTenantSet.clear();
            tenants.values().forEach(tenant -> {
                tenant.readyProjects.clear();
                tenant.readyProjectSet.clear();
                tenant.projects.values().forEach(project -> project.waiting.clear());
            });
            queuedCount = 0;
        }
        executor.shutdownNow();
    }

    /** 单租户的并发、等待总量和项目轮转状态。 */
    private static final class TenantState {

        /** 项目状态映射。 */
        private final Map<UUID, ProjectState> projects = new HashMap<>();
        /** 内层项目轮转环。 */
        private final ArrayDeque<UUID> readyProjects = new ArrayDeque<>();
        /** 防止项目重复入环。 */
        private final Set<UUID> readyProjectSet = new HashSet<>();
        /** 最新有效策略快照。 */
        private RuleQueueLimits limits;
        /** 当前运行项数量。 */
        private int runningCount;
        /** 当前等待项数量。 */
        private int queuedCount;

        /**
         * @param limits 首项工作携带的策略快照
         */
        private TenantState(RuleQueueLimits limits) {
            this.limits = limits;
        }
    }

    /** 单项目的有限 FIFO 等待队列。 */
    private static final class ProjectState {

        /** 等待执行的项目工作。 */
        private final ArrayDeque<Runnable> waiting = new ArrayDeque<>();
        /** 当前运行项数量；项目仍有在途工作时不能回收其状态。 */
        private int runningCount;
    }

    /**
     * 已从两级队列取出并占用运行槽的工作。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param work 执行体
     */
    private record Dispatch(UUID tenantId, UUID projectId, Runnable work) {
    }
}
