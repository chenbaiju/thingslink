package com.things.link.support.tenant;

import com.things.link.support.observability.DatabaseAvailabilityMetrics;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

/**
 * 装配 G1-C3c / D-048 冻结的 CONTROL/DATA 双物理池、单路由和统一 RLS 外包装。
 *
 * <p>物理池刻意不注册成 Bean：业务仓储只能注入唯一主 DataSource，不能绕过路由或 RLS 直接借原始连接。</p>
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class TenantDataSourceConfiguration {
    /**
     * 物理连接最多存活十分钟，必须早于 nightly #15 暴露的约十六分钟失效窗口主动轮换。
     *
     * <p>该值不改变池容量；缩短 Hikari 默认三十分钟生命周期，是为了避免失效连接集中到业务突发时才被发现。</p>
     */
    static final long DEFAULT_MAX_LIFETIME_MS = 600_000L;
    /**
     * 空闲连接每两分钟探活一次，避免固定池中的低频连接长期静默失效。
     *
     * <p>必须小于 {@link #DEFAULT_MAX_LIFETIME_MS}；Hikari 只对空闲连接执行 keepalive，不会打断借出的业务连接。</p>
     */
    static final long DEFAULT_KEEPALIVE_TIME_MS = 120_000L;
    /** CONTROL 物理池，只由本配置持有并在停机时关闭。 */
    private HikariDataSource controlPool;
    /** DATA 物理池，只由本配置持有并在停机时关闭。 */
    private HikariDataSource dataPool;

    /**
     * 创建唯一应用 DataSource。
     *
     * @param properties Boot 标准连接信息，继续兼容 spring.datasource 与测试动态属性
     * @param environment 池参数和 RLS 开关
     * @param meterRegistries 可选指标注册表
     * @return 路由后统一执行 RLS 的数据源
     */
    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource(DataSourceProperties properties, Environment environment,
                                 ObjectProvider<MeterRegistry> meterRegistries) {
        MeterRegistry registry = meterRegistries.getIfAvailable();
        registerRoutingMetrics(registry);
        controlPool = createPool(properties, environment, registry, "control", "thingslink-control", 6, 500);
        dataPool = createPool(properties, environment, registry, "data", "thingslink-data", 10, 2000);

        WorkloadRoutingDataSource routing = new WorkloadRoutingDataSource();
        routing.setTargetDataSources(Map.of(DatabaseWorkload.CONTROL, controlPool, DatabaseWorkload.DATA, dataPool));
        routing.setDefaultTargetDataSource(controlPool);
        // 未登记 key 必须报错，不能在拼写错误时悄悄回退到另一故障域。
        routing.setLenientFallback(false);
        routing.afterPropertiesSet();

        boolean rlsEnabled = environment.getProperty("things-link.rls.enabled", Boolean.class, true);
        return rlsEnabled ? new TenantAwareDataSource(routing) : routing;
    }

    /** 注册单一低基数路由冲突指标；重复测试上下文不得重复绑定同名 meter。 */
    private static void registerRoutingMetrics(MeterRegistry registry) {
        if (registry != null && registry.find("thingslink.datasource.route.conflicts").gauge() == null) {
            Gauge.builder("thingslink.datasource.route.conflicts", DatabaseWorkloadContext::routeConflicts)
                    .description("同一调用链试图跨 CONTROL/DATA 故障域切换的累计次数")
                    .register(registry);
        }
    }

    /** @return CONTROL 物理池健康检查，避免默认路由掩盖另一池故障 */
    @Bean
    @ConditionalOnProperty(name = "management.health.db.enabled", matchIfMissing = true)
    public HealthIndicator controlDataSourceHealthIndicator() {
        return () -> poolHealth("control", controlPool);
    }

    /** @return DATA 物理池健康检查，避免消费端只能从 lag 间接发现池故障 */
    @Bean
    @ConditionalOnProperty(name = "management.health.db.enabled", matchIfMissing = true)
    public HealthIndicator dataDataSourceHealthIndicator() {
        return () -> poolHealth("data", dataPool);
    }

    /**
     * 暴露独立物理池探针；路由 DataSource 参数只用于确保两个私有池已经完成初始化。
     *
     * @param initializedDataSource 已初始化的唯一业务 DataSource
     * @param metrics 低基数数据库可用性指标
     * @return 周期性 CONTROL/DATA 主动探针
     */
    @Bean
    public DatabaseAvailabilityProbe databaseAvailabilityProbe(
            @Qualifier("dataSource") DataSource initializedDataSource,
            DatabaseAvailabilityMetrics metrics) {
        if (initializedDataSource == null || controlPool == null || dataPool == null) {
            throw new IllegalStateException("数据库物理池未初始化，拒绝创建可用性探针");
        }
        return new DatabaseAvailabilityProbe(controlPool, dataPool, metrics);
    }

    /**
     * 按冻结默认值创建固定大小 HikariPool。
     *
     * @param properties 共享 JDBC 连接信息
     * @param environment 可覆盖池参数
     * @param registry 指标注册表，可为空
     * @param key 配置段名
     * @param defaultName 默认池名
     * @param defaultSize 默认固定连接数
     * @param defaultConnectionTimeout 默认获取超时毫秒
     * @return 未注册为 Bean 的物理池
     */
    static HikariDataSource createPool(DataSourceProperties properties, Environment environment,
                                       MeterRegistry registry, String key, String defaultName,
                                       int defaultSize, long defaultConnectionTimeout) {
        HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        String prefix = "things-link.datasource." + key + ".";
        pool.setPoolName(environment.getProperty(prefix + "pool-name", defaultName));
        int maximum = environment.getProperty(prefix + "maximum-pool-size", Integer.class, defaultSize);
        int minimum = environment.getProperty(prefix + "minimum-idle", Integer.class, maximum);
        pool.setMaximumPoolSize(maximum);
        pool.setMinimumIdle(minimum);
        pool.setConnectionTimeout(environment.getProperty(prefix + "connection-timeout-ms", Long.class,
                defaultConnectionTimeout));
        pool.setValidationTimeout(environment.getProperty(prefix + "validation-timeout-ms", Long.class, 250L));
        pool.setMaxLifetime(environment.getProperty(prefix + "max-lifetime-ms", Long.class,
                DEFAULT_MAX_LIFETIME_MS));
        pool.setKeepaliveTime(environment.getProperty(prefix + "keepalive-time-ms", Long.class,
                DEFAULT_KEEPALIVE_TIME_MS));
        if (registry != null) {
            pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
        }
        return pool;
    }

    /**
     * 执行不经过路由/RLS 的物理连通性探针；只运行 SELECT 1，不读取业务表。
     *
     * @param name 固定池名
     * @param pool 物理池
     * @return 健康状态
     */
    private static Health poolHealth(String name, HikariDataSource pool) {
        if (pool == null) {
            return Health.down().withDetail("pool", name).withDetail("reason", "not initialized").build();
        }
        try (Connection connection = pool.getConnection(); var statement = connection.prepareStatement("SELECT 1")) {
            statement.execute();
            return Health.up().withDetail("pool", name).build();
        } catch (Exception exception) {
            return Health.down(exception).withDetail("pool", name).build();
        }
    }

    /** 关闭两个私有池；物理池不是 Bean，生命周期必须由拥有者显式收口。 */
    @PreDestroy
    public void closePools() {
        if (dataPool != null) {
            dataPool.close();
        }
        if (controlPool != null) {
            controlPool.close();
        }
    }
}
