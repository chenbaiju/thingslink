package com.things.link.testing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.Lifecycle;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 专属容器关闭顺序、缓存隔离和失败边界；使用真实Spring上下文，不启动Docker。 */
class OwnedTestContainersTests {
    /** 当前测试创建的真实上下文，测试失败时也保证关闭。 */
    private final List<GenericApplicationContext> contexts = new ArrayList<>();
    /** 每个反例重新清理容器观察，不调用真实Docker。 */
    @BeforeEach
    void resetContainers() { reset(Owner.FIRST, Owner.SECOND, Shared.SHARED); }
    /** @return 无；清除低层测试的真实Spring上下文 */
    @AfterEach
    void closeContexts() { contexts.forEach(GenericApplicationContext::close); }

    /** 上下文先销毁，再从缓存移除，最后逆序停止；重复类末回调不重复停。 */
    @Test
    void closesContextBeforeCacheRemovalAndOwnedContainers() throws Exception {
        List<String> events = new ArrayList<>();
        var context = context();
        context.getDefaultListableBeanFactory().registerDisposableBean("proof", () -> events.add("destroy"));
        var test = testContext(Owner.class, context);
        doAnswer(call -> { events.add("dirty"); return null; }).when(test)
                .markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
        doAnswer(call -> { events.add("first"); return null; }).when(Owner.FIRST).stop();
        doAnswer(call -> { events.add("second"); return null; }).when(Owner.SECOND).stop();
        var listener = new OwnedTestContainersListener();
        listener.prepareTestInstance(test);
        listener.afterTestClass(test);
        listener.afterTestClass(test);
        assertThat(events).containsExactly("destroy", "dirty", "second", "first");
        assertThat(context.isActive()).isFalse();
        verify(Shared.SHARED, never()).stop();
    }

    /** 缓存清理失败保留同一首因，禁止容器停止，重复回调不能改绿。 */
    @Test
    void cacheRemovalFailureIsLatchedBeforeStoppingContainers() throws Exception {
        var test = testContext(Owner.class, context());
        var first = new IllegalStateException("cache failure");
        doThrow(first).when(test).markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
        var listener = new OwnedTestContainersListener();
        listener.prepareTestInstance(test);
        assertThatThrownBy(() -> listener.afterTestClass(test)).isSameAs(first);
        assertThatThrownBy(() -> listener.afterTestClass(test)).isSameAs(first);
        verify(Owner.FIRST, never()).stop();
        verify(Owner.SECOND, never()).stop();
    }

    /** Spring吞掉关闭监听异常时，监控冻结失败仍禁止停依赖。 */
    @Test
    void failedPhysicalCloseProofCannotBeReplacedByInactiveContext() throws Exception {
        var context = context();
        var monitor = mock(PausedSchedulerShutdownTestConfiguration.ShutdownMonitor.class);
        context.getBeanFactory().registerSingleton("failedMonitor", monitor);
        var first = new IllegalStateException("not physically closed");
        doThrow(first).when(monitor).assertSuccessful();
        var test = testContext(Owner.class, context);
        var listener = new OwnedTestContainersListener();
        listener.prepareTestInstance(test);
        assertThatThrownBy(() -> listener.afterTestClass(test)).isSameAs(first);
        assertThat(context.isActive()).isFalse();
        verify(Owner.FIRST, never()).stop();
        verify(test, never()).markApplicationContextDirty(any());
    }

    /** 生命周期仍运行时，即使调度器已通过也不能停止外部依赖。 */
    @Test
    void runningLifecycleBlocksContainerRelease() throws Exception {
        var context = context();
        Lifecycle resource = mock(Lifecycle.class);
        when(resource.isRunning()).thenReturn(true);
        context.getBeanFactory().registerSingleton("stillRunning", resource);
        var test = testContext(Owner.class, context);
        var listener = new OwnedTestContainersListener();
        listener.prepareTestInstance(test);
        assertThatThrownBy(() -> listener.afterTestClass(test)).hasMessageContaining("仍在运行");
        verify(Owner.FIRST, never()).stop();
    }

    /** 一个容器停止失败仍清理已无活动依赖的其他专属容器，并保留首因。 */
    @Test
    void containerFailureDoesNotHideFirstCauseOrSkipOtherOwnedResource() throws Exception {
        var first = new IllegalStateException("second stop failed");
        doThrow(first).when(Owner.SECOND).stop();
        var test = testContext(Owner.class, context());
        var listener = new OwnedTestContainersListener();
        listener.prepareTestInstance(test);
        assertThatThrownBy(() -> listener.afterTestClass(test)).isSameAs(first);
        verify(Owner.FIRST).stop();
    }

    /** 继承字段不在本类声明白名单内，避免停止共享基类容器。 */
    @Test
    void inheritedSharedFieldIsRejected() {
        var test = testContext(InheritedField.class, context());
        assertThatThrownBy(() -> new OwnedTestContainersListener().prepareTestInstance(test))
                .isInstanceOf(NoSuchFieldException.class);
        verify(Shared.SHARED, never()).stop();
    }

    /** 重复对象登记应整体拒绝而不是多次销毁。 */
    @Test
    void duplicateDeclarationsAreRejected() {
        var test = testContext(Duplicate.class, context());
        assertThatThrownBy(() -> new OwnedTestContainersListener().prepareTestInstance(test))
                .hasMessageContaining("重复");
        verify(Owner.FIRST, never()).stop();
    }

    /** 父子上下文不能使用EXHAUSTIVE清理其他所有者。 */
    @Test
    void parentContextIsRejectedBeforeAnyContainerRelease() {
        var context = context();
        context.setParent(context());
        var test = testContext(Owner.class, context);
        assertThatThrownBy(() -> new OwnedTestContainersListener().prepareTestInstance(test))
                .hasMessageContaining("无父上下文");
        verify(Owner.FIRST, never()).stop();
    }

    /** 无捕获证据不得借重新加载上下文来声称原上下文已关闭。 */
    @Test
    void missingCaptureFailsClosed() {
        var test = testContext(Owner.class, context());
        assertThatThrownBy(() -> new OwnedTestContainersListener().afterTestClass(test))
                .hasMessageContaining("没有已捕获");
        verify(Owner.FIRST, never()).stop();
    }

    /** 不同所有者缓存键不相等；继承类没有自己的声明时不会取得资源所有权。 */
    @Test
    void ownerCacheKeysAreDistinctAndAnnotationIsNotInherited() {
        var factory = new OwnedTestContainersContextCustomizerFactory();
        assertThat(factory.createContextCustomizer(Owner.class, List.of()))
                .isEqualTo(factory.createContextCustomizer(Owner.class, List.of()))
                .isNotEqualTo(factory.createContextCustomizer(Duplicate.class, List.of()));
        assertThat(factory.createContextCustomizer(NoOwnership.class, List.of())).isNull();
    }

    /** 测试中才创建的lazy生命周期资源也必须参与类末关闭证明。 */
    @Test
    void lateCreatedLifecycleCannotEscapeCloseVerification() throws Exception {
        var context = context();
        Lifecycle resource = mock(Lifecycle.class);
        when(resource.isRunning()).thenReturn(true);
        context.registerBean("lazyWorker", Lifecycle.class, () -> resource, definition -> definition.setLazyInit(true));
        var test = testContext(Owner.class, context);
        var listener = new OwnedTestContainersListener();
        listener.prepareTestInstance(test);
        assertThat(context.getBeanFactory().containsSingleton("lazyWorker")).isFalse();
        context.getBean("lazyWorker");
        assertThatThrownBy(() -> listener.afterTestClass(test)).hasMessageContaining("仍在运行");
        verify(Owner.FIRST, never()).stop();
    }

    /** 新测试实例不得把替换后的上下文作为已捕获上下文复用。 */
    @Test
    void replacementAtPrepareIsLatched() throws Exception {
        var test = testContext(Owner.class, context());
        var listener = new OwnedTestContainersListener();
        listener.prepareTestInstance(test);
        when(test.getApplicationContext()).thenReturn(context());
        assertThatThrownBy(() -> listener.prepareTestInstance(test)).hasMessageContaining("替换");
        assertThatThrownBy(() -> listener.afterTestClass(test)).hasMessageContaining("替换");
        verify(Owner.FIRST, never()).stop();
    }

    /** 类末缓存指向另一个上下文时不得调用markDirty间接关闭它。 */
    @Test
    void replacementAtClassEndDoesNotEvictUnverifiedContext() throws Exception {
        var test = testContext(Owner.class, context());
        var listener = new OwnedTestContainersListener();
        listener.prepareTestInstance(test);
        var replacement = context();
        when(test.getApplicationContext()).thenReturn(replacement);
        assertThatThrownBy(() -> listener.afterTestClass(test)).hasMessageContaining("替换");
        assertThat(replacement.isActive()).isTrue();
        verify(test, never()).markApplicationContextDirty(any());
        verify(Owner.FIRST, never()).stop();
    }

    /** 已关闭且退出缓存的上下文不能因检查而重新创建。 */
    @Test
    void earlyCloseIsRejectedWithoutReloadingMissingContext() throws Exception {
        var original = context();
        var test = testContext(Owner.class, original);
        var listener = new OwnedTestContainersListener();
        listener.prepareTestInstance(test);
        original.close();
        when(test.hasApplicationContext()).thenReturn(false);
        when(test.getApplicationContext()).thenThrow(new AssertionError("must not reload"));
        assertThatThrownBy(() -> listener.afterTestClass(test)).hasMessageContaining("提前关闭");
        verify(Owner.FIRST, never()).stop();
    }

    /** @return 带真实同步关闭监控的轻量上下文 */
    private GenericApplicationContext context() {
        var context = new GenericApplicationContext();
        context.registerBean("monitor", PausedSchedulerShutdownTestConfiguration.ShutdownMonitor.class,
                () -> new PausedSchedulerShutdownTestConfiguration.ShutdownMonitor(context, Duration.ofSeconds(1)));
        context.refresh();
        contexts.add(context);
        return context;
    }

    /** @param owner 所有者 @param context 真实上下文 @return 保留类级属性的Spring测试端口 */
    private TestContext testContext(Class<?> owner, GenericApplicationContext context) {
        TestContext test = mock(TestContext.class);
        Map<String, Object> attributes = new HashMap<>();
        doReturn(owner).when(test).getTestClass();
        when(test.getApplicationContext()).thenReturn(context);
        when(test.hasApplicationContext()).thenReturn(true);
        when(test.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
        doAnswer(call -> { attributes.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(test).setAttribute(anyString(), any());
        return test;
    }

    /** 共享基类夹具不允许类末关闭。 */
    private static class Shared {
        /** 只观察共享资源绝不被停止。 */
        static final GenericContainer<?> SHARED = mock(GenericContainer.class);
    }
    /** 本类两项专有资源。 */
    @OwnedTestContainers({"FIRST", "SECOND"})
    private static class Owner extends Shared {
        /** 第一项资源。 */
        static final GenericContainer<?> FIRST = mock(GenericContainer.class);
        /** 第二项资源。 */
        static final GenericContainer<?> SECOND = mock(GenericContainer.class);
    }
    /** 没有自己的声明，不继承所有权。 */
    private static class NoOwnership extends Owner { }
    /** 故意登记继承字段的反例。 */
    @OwnedTestContainers("SHARED")
    private static class InheritedField extends Shared { }
    /** 故意重复同一对象的反例。 */
    @OwnedTestContainers({"RESOURCE", "RESOURCE"})
    private static class Duplicate {
        /** 只用于重复声明拒绝测试。 */
        static final GenericContainer<?> RESOURCE = Owner.FIRST;
    }
}
