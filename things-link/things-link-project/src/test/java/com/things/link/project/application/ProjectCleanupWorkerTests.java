package com.things.link.project.application;

import com.things.link.project.domain.ProjectCleanupRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadAspect;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR0092：装配、单次边界与DATA路由在低层拒绝错误配置；持久租约另由真实PG验证。 */
class ProjectCleanupWorkerTests {
    /** 缺任意一个阶段，显式启用时均拒绝启动，不把部分领域当全部完成。 */
    @ParameterizedTest
    @EnumSource(ProjectCleanupStage.class)
    void rejectsEveryMissingStageAtStartup(ProjectCleanupStage missing) {
        List<ProjectCleanupContributor> steps=steps(); steps.removeIf(s -> s.stage()==missing);
        runner(steps).withPropertyValues("things-link.project.cleanup.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining(missing.name());
        });
    }

    /** 未配置或显式关闭不构造worker，允许部分模块开发但绝不触发自动领取。 */
    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void staysInactiveUnlessExplicitlyEnabled(boolean explicit) {
        ApplicationContextRunner context=runner(List.of());
        if (explicit) context=context.withPropertyValues("things-link.project.cleanup.enabled=false");
        context.run(app -> { assertThat(app).hasNotFailed().doesNotHaveBean(ProjectCleanupWorker.class); });
    }

    /** 全部当前阶段齐备时才能构造一个worker，重复阶段仍由批次装配拒绝。 */
    @Test
    void acceptsExactlyOneContributorForEveryStage() {
        runner(steps()).withPropertyValues("things-link.project.cleanup.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ProjectCleanupWorker.class));
        List<ProjectCleanupContributor> duplicate=steps(); duplicate.add(duplicate.getFirst());
        assertThatThrownBy(() -> batch(duplicate)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 无候选时只领取一次，不执行空批次或创建退避事实。 */
    @Test
    void doesNoWorkForEmptyClaim() {
        ProjectCleanupAdmissionService admission=mock(ProjectCleanupAdmissionService.class);
        ProjectCleanupBatchService batches=mock(ProjectCleanupBatchService.class);
        when(admission.claimNext()).thenReturn(Optional.empty());
        new ProjectCleanupWorker(admission,batches).cleanNextBatch();
        verify(admission).claimNext(); verify(batches,never()).execute(any()); verify(admission,never()).defer(any(),anyString());
    }

    /** 完成、等待和失权结果均不额外释放租约，也不在同次调用循环领取。 */
    @ParameterizedTest
    @ValueSource(strings = {"complete","blocked","stale"})
    void performsOneBatchWithoutDuplicatingProgressOrDeferral(String mode) {
        ProjectCleanupAdmissionService admission=mock(ProjectCleanupAdmissionService.class);
        ProjectCleanupBatchService batches=mock(ProjectCleanupBatchService.class);
        ProjectCleanupClaim claim=claim(); when(admission.claimNext()).thenReturn(Optional.of(claim));
        when(batches.execute(claim)).thenReturn(mode.equals("stale")?Optional.empty():Optional.of(mode.equals("blocked")?
                ProjectCleanupBatchResult.blocked("SUPPORT_OUTBOX_PENDING"):ProjectCleanupBatchResult.done()));
        new ProjectCleanupWorker(admission,batches).cleanNextBatch();
        verify(admission).claimNext(); verify(batches).execute(claim); verify(admission,never()).defer(any(),anyString());
    }

    /** 执行异常只用原完整claim释放；退避SQL失败或失权不得取消后续定时触发。 */
    @ParameterizedTest
    @ValueSource(strings = {"released","stale","database"})
    void defersOnlyCurrentClaimAfterFailureAndSurvivesDeferralFailure(String mode) {
        ProjectCleanupAdmissionService admission=mock(ProjectCleanupAdmissionService.class);
        ProjectCleanupBatchService batches=mock(ProjectCleanupBatchService.class);
        ProjectCleanupClaim claim=claim(); when(admission.claimNext()).thenReturn(Optional.of(claim));
        when(batches.execute(claim)).thenThrow(new IllegalStateException("不应持久化这段文本"));
        if (mode.equals("database")) when(admission.defer(claim,"PROJECT_CLEANUP_FAILED")).thenThrow(new IllegalStateException("退避数据库不可用"));
        else when(admission.defer(claim,"PROJECT_CLEANUP_FAILED")).thenReturn(mode.equals("released"));
        new ProjectCleanupWorker(admission,batches).cleanNextBatch();
        verify(admission).defer(claim,"PROJECT_CLEANUP_FAILED"); verify(admission).claimNext(); verify(batches).execute(claim);
    }

    /** DATA真实切面在领取前已生效，领取故障后线程范围仍恢复CONTROL。 */
    @Test
    void routesBeforeClaimAndRestoresThreadContextAfterFailure() {
        ProjectCleanupAdmissionService admission=mock(ProjectCleanupAdmissionService.class);
        ProjectCleanupBatchService batches=mock(ProjectCleanupBatchService.class);
        when(admission.claimNext()).thenAnswer(invocation -> {
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);
            throw new IllegalStateException("受控领取失败");
        });
        AspectJProxyFactory proxy=new AspectJProxyFactory(new ProjectCleanupWorker(admission,batches));
        proxy.addAspect(new DatabaseWorkloadAspect());
        ProjectCleanupWorker worker=proxy.getProxy(); worker.cleanNextBatch();
        assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);
        verify(batches,never()).execute(any()); verify(admission,never()).defer(any(),anyString());
    }

    /** 真实Spring定时注册使用命名维护线程，首次触发进入DATA；关闭上下文回收线程。 */
    @Test
    void schedulesEnabledWorkerOnNamedMaintenanceExecutor() {
        var triggered=new java.util.concurrent.CountDownLatch(1);
        ProjectCleanupAdmissionService admission=mock(ProjectCleanupAdmissionService.class);
        when(admission.claimNext()).thenAnswer(invocation -> {
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);
            assertThat(Thread.currentThread().getName()).startsWith("cleanup-test-maintenance-");
            triggered.countDown(); return Optional.empty();
        });
        new ApplicationContextRunner().withUserConfiguration(ProjectCleanupWorker.class,SchedulingFixture.class)
                .withBean(ProjectCleanupAdmissionService.class,() -> admission)
                .withBean(ProjectCleanupBatchService.class,() -> batch(steps()))
                .withPropertyValues("things-link.project.cleanup.enabled=true", "things-link.project.cleanup.initial-delay-millis=0",
                        "things-link.project.cleanup.fixed-delay-millis=60000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(triggered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                });
    }

    /** 仅供定时装配验证，使用真实Spring调度与DATA切面而不访问数据库。 */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.scheduling.annotation.EnableScheduling
    @org.springframework.context.annotation.EnableAspectJAutoProxy
    @org.springframework.context.annotation.Import(DatabaseWorkloadAspect.class)
    static class SchedulingFixture {
        /** @return 随测试上下文关闭的命名维护线程池 */
        @org.springframework.context.annotation.Bean(name = "maintenanceScheduler")
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler() {
            var result=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
            result.setThreadNamePrefix("cleanup-test-maintenance-"); return result;
        }
    }

    /** @param steps 装配清单 @return 无外部依赖的真实条件装配 */
    private static ApplicationContextRunner runner(List<ProjectCleanupContributor> steps) {
        return new ApplicationContextRunner().withUserConfiguration(ProjectCleanupWorker.class)
                .withBean(ProjectCleanupAdmissionService.class,() -> mock(ProjectCleanupAdmissionService.class))
                .withBean(ProjectCleanupBatchService.class,() -> batch(steps));
    }
    /** @param steps 仅用于装配验证的领域实现 @return 真实批次注册表 */
    private static ProjectCleanupBatchService batch(List<ProjectCleanupContributor> steps) {
        return new ProjectCleanupBatchService(mock(ProjectCleanupAdmissionService.class),
                mock(ProjectCleanupRepository.class), mock(TransactionLocalRlsScope.class), steps);
    }
    /** @return 全部固定阶段，只有装配测试使用，不冒充真实领域清理 */
    private static List<ProjectCleanupContributor> steps() {
        List<ProjectCleanupContributor> result=new ArrayList<>();
        for (ProjectCleanupStage stage:ProjectCleanupStage.values()) result.add(new ProjectCleanupContributor() {
            /** 返回本项固定身份。 */
            @Override public ProjectCleanupStage stage() { return stage; }
            /** 装配不得执行领域方法，误调用立即失败。 */
            @Override public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { throw new AssertionError("仅装配夹具"); }
        });
        return result;
    }
    /** @return 不可混淆的完整能力 */
    private static ProjectCleanupClaim claim() {
        return new ProjectCleanupClaim(UUID.randomUUID(),UUID.randomUUID(),3,"TASK",UUID.randomUUID(),Instant.now().plusSeconds(120),false);
    }
}
