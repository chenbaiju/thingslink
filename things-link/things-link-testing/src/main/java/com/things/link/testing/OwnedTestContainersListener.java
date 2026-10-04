package com.things.link.testing;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.Lifecycle;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * 明确所有权的类末资源关闭；任何上下文关闭证据失败都保留容器供最终JVM清理。
 *
 * <p>同时负责**在触发 Spring 上下文创建之前**启动本类专属容器，并对 JDBC 数据库容器做一次有界的
 * 可连接探活：容器日志就绪不等于宿主端口可以握手（TimescaleDB HA 镜像初始化扩展后还会重启
 * {@code postgres}），否则 Flyway 会以难读的读超时失败，一个类的十几个用例会全部倒在上下文加载上。
 */
public final class OwnedTestContainersListener extends AbstractTestExecutionListener {
    /** TestContext属性随类共享，避免JUnit每方法实例造成重复关闭。 */
    private static final String STATE = OwnedTestContainersListener.class.getName() + ".state";
    /** 数据库容器探活次数上限：覆盖镜像初始化重启窗口，失败即给出可诊断的明确异常。 */
    private static final int DATABASE_READY_ATTEMPTS = 30;
    /** 两次探活之间的等待毫秒数。 */
    private static final long DATABASE_READY_BACKOFF_MILLIS = 1_000L;

    /** afterTestClass逆序执行，低优先序保证普通测试监听先完成。 */
    @Override
    public int getOrder() { return 100; }

    /** @param testContext 当前类上下文；在测试运行前捕获不可替代的真实资源引用 */
    @Override
    public void prepareTestInstance(TestContext testContext) throws Exception {
        Class<?> owner = testContext.getTestClass();
        OwnedTestContainers declaration = owner.getDeclaredAnnotation(OwnedTestContainers.class);
        if (declaration == null) return;
        if (testContext.getAttribute(STATE) instanceof State previous) {
            synchronized (previous) {
                if (previous.failure != null) throw previous.failure;
                if (previous.completed || !testContext.hasApplicationContext()
                        || testContext.getApplicationContext() != previous.context) {
                    previous.failure = new IllegalStateException("专属容器测试类的上下文已关闭或替换，禁止复用静态资源");
                    throw previous.failure;
                }
            }
            return;
        }
        // 先启动并探活专属容器，再取上下文：动态属性源只负责发布 URL，Flyway 不再撞上数据库的初始化重启窗口。
        List<GenericContainer<?>> containers = resolve(owner, declaration.value());
        startOwnedContainers(containers);
        if (!(testContext.getApplicationContext() instanceof ConfigurableApplicationContext context)
                || context.getParent() != null) {
            throw new IllegalStateException("专属容器要求无父上下文的可关闭Spring上下文");
        }
        var monitors = List.copyOf(context.getBeansOfType(
                PausedSchedulerShutdownTestConfiguration.ShutdownMonitor.class).values());
        if (monitors.isEmpty()) throw new IllegalStateException("专属容器缺少调度器物理关闭监控");
        testContext.setAttribute(STATE, new State(context, containers, monitors, existingLifecycles(context)));
    }

    /** @param testContext 精确原上下文；close成功和缓存移除成功后才允许停止容器 */
    @Override
    public void afterTestClass(TestContext testContext) {
        if (testContext.getTestClass().getDeclaredAnnotation(OwnedTestContainers.class) == null) return;
        Object captured = testContext.getAttribute(STATE);
        if (!(captured instanceof State state)) {
            throw new IllegalStateException("专属容器没有已捕获的上下文关闭证据，保留资源");
        }
        synchronized (state) {
            if (state.completed) return;
            if (state.failure != null) throw state.failure;
            try {
                // hasApplicationContext只检查缓存；禁止为了核对而重建已被淘汰的上下文。
                if (testContext.hasApplicationContext()
                        && testContext.getApplicationContext() != state.context) {
                    throw new IllegalStateException("专属容器测试类的上下文已替换，禁止清理未验证的上下文");
                }
                if (!state.context.isActive()) {
                    throw new IllegalStateException("专属上下文已提前关闭，缺少类末完整生命周期证据");
                }
                Set<Lifecycle> closing = Collections.newSetFromMap(new IdentityHashMap<>());
                closing.addAll(state.lifecycles);
                closing.addAll(existingLifecycles(state.context));
                state.context.close();
                if (state.context.isActive()) throw new IllegalStateException("上下文仍然活动，禁止停止专属容器");
                state.monitors.forEach(PausedSchedulerShutdownTestConfiguration.ShutdownMonitor::assertSuccessful);
                if (closing.stream().anyMatch(Lifecycle::isRunning)) {
                    throw new IllegalStateException("上下文生命周期资源仍在运行，禁止停止专属容器");
                }
                testContext.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
                RuntimeException first = null;
                for (int i = state.containers.size() - 1; i >= 0; i--) {
                    try { state.containers.get(i).stop(); }
                    catch (RuntimeException error) {
                        if (first == null) first = error;
                        else if (first != error) first.addSuppressed(error);
                    }
                }
                if (first != null) throw first;
                state.completed = true;
            } catch (RuntimeException error) {
                state.failure = error;
                throw error;
            }
        }
    }

    /** 启动本类声明的全部容器；JDBC 数据库容器额外等待到真正可连接。 */
    private static void startOwnedContainers(List<GenericContainer<?>> containers) {
        for (GenericContainer<?> container : containers) {
            container.start();
            if (container instanceof JdbcDatabaseContainer<?> database) {
                awaitDatabaseReady(database);
            }
        }
    }

    /**
     * 有界等待数据库容器可连接，失败时保留驱动原因链与容器日志。
     *
     * <p>只用短连接做探活，不改变 Flyway、业务连接或 SSL 的默认行为，也不放宽任何生产配置。
     *
     * @param database 待探活的 JDBC 数据库容器
     */
    private static void awaitDatabaseReady(JdbcDatabaseContainer<?> database) {
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= DATABASE_READY_ATTEMPTS; attempt++) {
            try (Connection connection = DriverManager.getConnection(database.getJdbcUrl(),
                    database.getUsername(), database.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
                return;
            } catch (SQLException failure) {
                lastFailure = failure;
                try {
                    Thread.sleep(DATABASE_READY_BACKOFF_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        IllegalStateException failure = new IllegalStateException(
                "专属数据库容器在 " + DATABASE_READY_ATTEMPTS + " 次探活内仍不可连接，未启动 Spring 上下文");
        if (lastFailure != null) {
            failure.addSuppressed(lastFailure);
            try {
                failure.addSuppressed(new IllegalStateException("容器启动日志：\n" + database.getLogs()));
            } catch (RuntimeException diagnosticFailure) {
                failure.addSuppressed(diagnosticFailure);
            }
        }
        throw failure;
    }

    /** 仅采集已经创建的本地单例；不会实例化lazy定义或读取父上下文。 */
    private static List<Lifecycle> existingLifecycles(ConfigurableApplicationContext context) {
        List<Lifecycle> result = new ArrayList<>();
        for (String name : context.getBeanFactory().getSingletonNames()) {
            if (context.getBeanFactory().getSingleton(name) instanceof Lifecycle lifecycle) result.add(lifecycle);
        }
        return List.copyOf(result);
    }

    /** 只读取显式本类字段；无效声明整体拒绝，不部分清理。 */
    private static List<GenericContainer<?>> resolve(Class<?> owner, String[] names) throws ReflectiveOperationException {
        if (names.length == 0) throw new IllegalArgumentException("必须显式列出专属容器字段");
        List<GenericContainer<?>> result = new ArrayList<>();
        Set<GenericContainer<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : names) {
            Field field = owner.getDeclaredField(name);
            if (!Modifier.isStatic(field.getModifiers()) || !GenericContainer.class.isAssignableFrom(field.getType())
                    || !field.trySetAccessible()) {
                throw new IllegalArgumentException("专属容器必须为本类静态GenericContainer字段: " + name);
            }
            if (!(field.get(null) instanceof GenericContainer<?> container) || !seen.add(container)) {
                throw new IllegalArgumentException("专属容器不可为空或重复登记: " + name);
            }
            result.add(container);
        }
        return List.copyOf(result);
    }

    /** 关闭状态只属于一个精确测试上下文；失败单赋值，不能在第二次回调改绿。 */
    private static final class State {
        /** 被隔离缓存键保护的原上下文。 */
        private final ConfigurableApplicationContext context;
        /** 按声明顺序登记，释放时逆序。 */
        private final List<GenericContainer<?>> containers;
        /** 关闭后仍可读取的调度器终态。 */
        private final List<PausedSchedulerShutdownTestConfiguration.ShutdownMonitor> monitors;
        /** 初始化时已创建的生命周期资源，不创建额外lazy bean。 */
        private final List<Lifecycle> lifecycles;
        /** 全部释放完成标记。 */
        private boolean completed;
        /** 首次关闭失败，不以重试覆盖。 */
        private RuntimeException failure;
        /** 保存当前上下文关闭所需引用，不建立全局资源表。 */
        private State(ConfigurableApplicationContext context, List<GenericContainer<?>> containers,
                List<PausedSchedulerShutdownTestConfiguration.ShutdownMonitor> monitors, List<Lifecycle> lifecycles) {
            this.context = context;
            this.containers = containers;
            this.monitors = monitors;
            this.lifecycles = lifecycles;
        }
    }
}
