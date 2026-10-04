package com.things.link.testing;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * 集成测试基类：提供连着真实中间件的 Spring 上下文。
 *
 * <p>用法是直接继承，不需要再加 {@code @SpringBootTest}：
 * <pre>
 * class DeviceRepositoryTests extends AbstractIntegrationTest {
 *     &#64;Autowired DeviceRepository repository;
 *     &#64;Test void ... { }
 * }
 * </pre>
 *
 * <h2>为什么用真实容器而不是嵌入式替身</h2>
 * 本项目依赖的恰恰是中间件的真实行为：TimescaleDB 的 hypertable、PostgreSQL 的
 * 行级安全策略、Kafka 的分区与顺序语义。H2 与嵌入式 Kafka 在这些点上与真实实现
 * 有差异，而差异所在正是本项目最关键的部分（架构文档 5.1、开发手册 6.6）。
 * 用替身跑绿的测试给不了任何信心。
 *
 * <h2>容器为什么是静态的（singleton 模式）</h2>
 * 这里刻意<b>不用</b> {@code @Testcontainers} + {@code @Container} 注解 ——
 * 那套注解会为每个测试类启停一次容器。PostgreSQL 冷启动约 3–5 秒，二十个测试类
 * 就是一两分钟的纯等待，测试套件很快会慢到没人愿意跑，然后就没人跑了。
 *
 * <p>改用静态字段 + 静态初始化块：整个 Surefire JVM 内只启一次，全部测试类共用。
 * 不需要显式 stop —— Testcontainers 的 Ryuk 守护容器会在 JVM 退出后清理。
 *
 * <p>代价是<b>测试之间不再有数据隔离</b>。写数据的测试必须自己负责清理，
 * 或者用 {@code @Transactional} 让 Spring 在测试结束后回滚。这是拿隔离性换速度的
 * 明确取舍，不是疏漏。
 *
 * <h2>test profile</h2>
 * 激活 {@code test} profile，各模块的 {@code application-test.yml} 因此生效。
 * <b>不要改用 test/resources 下的 application.yml</b> —— 那会整体覆盖 main 下的
 * 同名文件而不是与之合并，症状是「配置项莫名变成 null」。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({AbstractIntegrationTest.RelaxRestQuotaTestConfiguration.class,
        PausedSchedulerShutdownTestConfiguration.class})
public abstract class AbstractIntegrationTest {

    /**
     * 数据库镜像。
     *
     * <p><b>必须与 {@code deploy/.env} 的 {@code TIMESCALEDB_TAG} 保持一致</b>，
     * 否则测试环境与开发环境跑的是两个不同版本的数据库 —— 那样测试通过并不能
     * 说明在开发环境也能跑通，而这类不一致极难察觉。
     *
     * <p>用 timescaledb-ha 而不是 timescaledb：前者是 multi-arch（含 arm64），
     * 后者部分 tag 只发布了 amd64，在 Apple Silicon 上会走 QEMU 模拟。
     */
    private static final String POSTGRES_IMAGE = "timescale/timescaledb-ha:pg17.4-ts2.18.2";

    /**
     * 全 JVM 共享的 PostgreSQL + TimescaleDB 容器。
     *
     * <p>{@code asCompatibleSubstituteFor("postgres")} 是必需的：镜像名不是官方的
     * {@code postgres}，不声明兼容性的话 {@link PostgreSQLContainer} 会直接拒绝启动。
     */
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("thingslink")
            .withUsername("thingslink")
            .withPassword("thingslink");

    /**
     * Redis 镜像。
     *
     * <p>与 {@code deploy/.env.example} 的 {@code REDIS_TAG} 保持一致，理由同上：
     * 测试与开发跑在两个不同版本上时，测试通过说明不了在开发环境也能跑通。
     */
    private static final String REDIS_IMAGE = "redis:7.4-alpine";

    /**
     * 全 JVM 共享的 Redis 容器。认证限流的计数存在这里（{@code AuthRateLimiter}）。
     *
     * <p><b>不设口令</b>，与 deploy/ 那套不同。这里刻意不追求一致：容器只监听随机
     * 端口且随 JVM 退出，口令在这个场景下不增加任何安全性，只增加一处会配错的地方。
     *
     * <p>也<b>不设 maxmemory-policy</b>：测试写入量远低于任何阈值，淘汰策略不会触发。
     * 生产上那一项是硬要求（见 deploy/README.md），但它不是这里能验证的东西。
     */
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);

    static {
        // 静态块内启动，保证在 Spring 上下文创建、@DynamicPropertySource 求值之前完成
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * 把容器的实际连接信息注入 Spring 环境。
     *
     * <p>容器端口是随机分配的（避免与宿主机上 deploy/ 那套栈的 5432 冲突），
     * 所以不能写死在配置文件里，只能在运行时注册。
     *
     * @param registry Spring 测试上下文的动态属性注册表
     */
    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);

        // 迁移用容器的超级用户（表 owner），不受 RLS 约束。
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        // 应用运行时用受 RLS 约束的非 owner 账号，与生产保持一致。
        //
        // 这一点必须和生产一致，否则测试是在一个「RLS 被绕过」的环境里跑的 ——
        // 所有隔离测试都会通过，而生产上一行也拦不住。该账号由迁移
        // V20260801_0300__row_level_security.sql 创建，因此必须等 Flyway 先执行；
        // Boot 的 entityManagerFactory 依赖 flyway Bean，顺序天然成立。
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
        registry.add("spring.flyway.placeholders.app_role_password", () -> APP_ROLE_PASSWORD);

        // Redis 同样是随机端口。没有这两行，测试会去连本机 6379 ——
        // 开发机上那里往往真的有一个 deploy/ 起的实例，于是测试会污染开发数据，
        // 而 CI 上则表现为连接超时。两种失败方式都与被测逻辑无关
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** 应用运行时角色名。与迁移中创建的角色一致。 */
    protected static final String APP_ROLE = "thingslink_app";

    /** 应用运行时角色的开发口令。仅测试环境使用。 */
    protected static final String APP_ROLE_PASSWORD = "thingslink";

    /**
     * 放宽控制台 REST 写/读速率，避免功能集成测试在快速连续准备夹具时误触生产限流。
     *
     * <p>限流器是生产保护机制，与功能测试正交；测试关注的是业务契约而非限流节奏。该 Bean 在 Spring
     * 上下文就绪后（Flyway 已建表并 seed 默认策略）、任何用例执行前运行一次，把默认配额的 REST 速率
     * 抬到足以覆盖夹具准备的高值。缓存未暖时生效，后续 {@code resolveTrustedProject} 读到的是放宽后的值。</p>
     *
     * <p>本模块不依赖 spring-jdbc，故用 JDBC 直连共享 PostgreSQL 容器（Flyway 超级用户）执行。</p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    public static class RelaxRestQuotaTestConfiguration {

        /** 速率放宽值：足够覆盖单用例内多次夹具写，同时仍能拦截失控循环（远高于任何单测写次数）。 */
        private static final long RELAXED_PER_SECOND = 1_000_000L;

        /** 分钟速率放宽值。 */
        private static final long RELAXED_PER_MINUTE = 60_000_000L;

        /**
         * 上下文就绪后把默认配额的 REST 速率抬到高值。
         *
         * @return 启动时执行一次的应用运行器
         */
        @Bean
        ApplicationRunner relaxRestQuota() {
            return args -> {
                try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                     Statement statement = connection.createStatement()) {
                    // 只放宽写速率：读速率(默认 20/s)被 S7 配额策略用例断言，不能动。
                    statement.executeUpdate("""
                            UPDATE sys_quota_policy
                               SET rest_api_write_rate_per_second = %d,
                                   rest_api_write_rate_per_minute = %d
                            """.formatted(RELAXED_PER_SECOND, RELAXED_PER_MINUTE));
                } catch (SQLException exception) {
                    // 支持/设备等模块隔离测试的 Flyway 不含 sys_quota_policy（undefined_table），跳过放宽即可。
                    if ("42P01".equals(exception.getSQLState())) {
                        return;
                    }
                    throw new IllegalStateException("放宽测试 REST 配额失败", exception);
                }
            };
        }
    }

    /**
     * 为物模型播种提供 owner 连接；专库测试覆盖此入口，避免业务夹具和运行时连接落入不同数据库。
     * 默认继续使用共享容器，未选择专库的测试不改变隔离或清理约定。
     *
     * @return 调用方负责关闭的 owner 连接
     * @throws SQLException 数据库连接失败时直接终止夹具准备
     */
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * ADR0087：仅供原本就执行全项目重置的测试显式调用，先清无执行空计划收据和原始点，再删项目/版本父行。
     * 使用当前夹具owner连接，专库覆盖继续有效；不授予应用原始表权限，不关闭守卫，也不注册全局清场钩子。
     * 一次最多500个真实完整主键；只清本隔离测试库的原始点，不使用TRUNCATE或drop_chunks。
     */
    protected void clearRawPropertyPointsBeforeAllProjectFixtureReset() {
        try (Connection connection = fixtureOwnerConnection(); Statement statement = connection.createStatement()) {
            // 属性接受事件现在即使没有匹配自动化，也会持久化空计划收据。
            // 仅在调用方明确清空全部项目的专用测试库内，先删除无执行引用的空计划收据。
            // 非空计划或有执行的收据仍由其拥有者清理，不能掩盖执行事实泄漏。
            statement.executeUpdate("""
                    DELETE FROM rule_automation_event_receipt r WHERE r.plan='[]'::jsonb
                      AND NOT EXISTS (SELECT 1 FROM rule_automation_execution e
                        WHERE (e.tenant_id,e.project_id,e.source_event_id)
                            =(r.tenant_id,r.project_id,r.source_event_id))
                    """);
            int deleted;
            do {
                deleted = statement.executeUpdate("""
                        WITH candidates AS (
                            SELECT project_id, device_id, property_key, ts, message_id
                              FROM public.ts_property_point_internal
                             ORDER BY project_id, device_id, property_key, ts, message_id LIMIT 500
                        )
                        DELETE FROM public.ts_property_point_internal p USING candidates c
                         WHERE (p.project_id,p.device_id,p.property_key,p.ts,p.message_id)
                             = (c.project_id,c.device_id,c.property_key,c.ts,c.message_id)
                        """);
            } while (deleted > 0);
        } catch (SQLException failure) {
            throw new IllegalStateException("全项目测试重置前清理原始点失败", failure);
        }
    }

    /**
     * B-X1b 数据面夹具：复用或建立不可变初始版本，并幂等补齐设备 INITIAL 绑定。
     *
     * <p>生产发布链现在会生成 1.0.0；仍有少量隔离测试直接写已发布类型。夹具因此先复用生产事实，缺失时才
     * 自行建立，并只为尚无 INITIAL 历史的目标设备补事实。走迁移超级用户连接绕过 RLS，避免夹具依赖
     * TenantContext 顺序；新建摘要与生产保持 {@code jsonb::text + SHA-256} 一致。</p>
     *
     * @param tenantId 设备租户 @param projectId 项目 @param deviceTypeId 类型 @param deviceId 设备
     * @param snapshotJson 属性/事件/命令完整快照 JSON 文本（须为对象）
     * @return 已复用或新建立的版本 ID，供后续建立非 INITIAL 转换夹具复用
     */
    protected UUID seedThingModelVersion(UUID tenantId, UUID projectId, UUID deviceTypeId, UUID deviceId,
                                         String snapshotJson) {
        try (Connection connection = fixtureOwnerConnection()) {
            UUID versionId = null;
            try (var findVersion = connection.prepareStatement("""
                    SELECT id FROM dev_thing_model_version
                     WHERE project_id = ? AND device_type_id = ? AND version_number = '1.0.0'
                    """)) {
                findVersion.setObject(1, projectId);
                findVersion.setObject(2, deviceTypeId);
                try (var rows = findVersion.executeQuery()) {
                    if (rows.next()) {
                        versionId = rows.getObject(1, UUID.class);
                    }
                }
            }
            if (versionId == null) {
                versionId = UUID.randomUUID();
                try (var insertVersion = connection.prepareStatement("""
                    INSERT INTO dev_thing_model_version
                        (id, tenant_id, project_id, device_type_id, version_number, version_major, version_minor,
                         version_patch, change_level, schema_profile, model_snapshot, schema_digest, digest_algorithm)
                    VALUES (?, ?, ?, ?, '1.0.0', 1, 0, 0, 'MAJOR', 'TC_PROPERTY_COMPOSITE_V1',
                            ?::jsonb,
                            encode(digest(convert_to(?::jsonb::text, 'UTF8'), 'sha256'), 'hex'),
                            'PG_JSONB_TEXT_V1_SHA256')
                        """)) {
                    insertVersion.setObject(1, versionId);
                    insertVersion.setObject(2, tenantId);
                    insertVersion.setObject(3, projectId);
                    insertVersion.setObject(4, deviceTypeId);
                    insertVersion.setString(5, snapshotJson);
                    insertVersion.setString(6, snapshotJson);
                    insertVersion.executeUpdate();
                }
            }
            try (var bindDevice = connection.prepareStatement("""
                    UPDATE dev_device
                       SET thing_model_version_id = COALESCE(thing_model_version_id, ?), updated_at = now()
                     WHERE id = ? AND project_id = ?
                    """)) {
                bindDevice.setObject(1, versionId);
                bindDevice.setObject(2, deviceId);
                bindDevice.setObject(3, projectId);
                if (bindDevice.executeUpdate() != 1) {
                    throw new IllegalStateException("绑定物模型版本时未找到设备");
                }
            }
            try (var insertTransition = connection.prepareStatement("""
                    INSERT INTO dev_device_model_binding_history
                        (id, tenant_id, project_id, device_id, from_model_version_id, to_model_version_id,
                         transition_key, transition_type, effective_at)
                    SELECT ?, ?, ?, ?, NULL, ?, ?, 'INITIAL', now()
                     WHERE NOT EXISTS (
                         SELECT 1 FROM dev_device_model_binding_history
                          WHERE project_id = ? AND device_id = ? AND transition_type = 'INITIAL')
                    """)) {
                insertTransition.setObject(1, UUID.randomUUID());
                insertTransition.setObject(2, tenantId);
                insertTransition.setObject(3, projectId);
                insertTransition.setObject(4, deviceId);
                insertTransition.setObject(5, versionId);
                insertTransition.setObject(6, UUID.randomUUID());
                insertTransition.setObject(7, projectId);
                insertTransition.setObject(8, deviceId);
                insertTransition.executeUpdate();
            }
            return versionId;
        } catch (SQLException exception) {
            throw new IllegalStateException("建立物模型版本与绑定夹具失败", exception);
        }
    }

    /**
     * B-X1b 数据面夹具：为设备追加一次 UPGRADE 转换并切换当前指针。
     *
     * <p>用于验证存量标量省略 modelVersion 在此后必须显式声明（{@code THING_MODEL_VERSION_REQUIRED}），
     * 同时以真实 SQL 钉住 {@code hasNonInitialTransition} 的 {@code transition_type <> 'INITIAL'} 判定。</p>
     *
     * @param tenantId 设备租户 @param projectId 项目 @param deviceTypeId 类型 @param deviceId 设备
     * @param snapshotJson 与新版本 2.0.0 绑定的完整快照 JSON 文本
     */
    protected UUID seedNonInitialTransition(UUID tenantId, UUID projectId, UUID deviceTypeId, UUID deviceId,
                                            String snapshotJson) {
        try (Connection connection = fixtureOwnerConnection()) {
            UUID fromVersionId;
            try (var read = connection.prepareStatement(
                    "SELECT thing_model_version_id FROM dev_device WHERE id = ? AND project_id = ?")) {
                read.setObject(1, deviceId);
                read.setObject(2, projectId);
                try (var rows = read.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException("未找到设备当前绑定版本");
                    }
                    fromVersionId = rows.getObject(1, UUID.class);
                }
            }
            UUID toVersionId = UUID.randomUUID();
            try (var insertVersion = connection.prepareStatement("""
                    INSERT INTO dev_thing_model_version
                        (id, tenant_id, project_id, device_type_id, version_number, version_major, version_minor,
                         version_patch, change_level, schema_profile, model_snapshot, schema_digest, digest_algorithm)
                    VALUES (?, ?, ?, ?, '2.0.0', 2, 0, 0, 'MAJOR', 'TC_PROPERTY_COMPOSITE_V1',
                            ?::jsonb,
                            encode(digest(convert_to(?::jsonb::text, 'UTF8'), 'sha256'), 'hex'),
                            'PG_JSONB_TEXT_V1_SHA256')
                    """)) {
                insertVersion.setObject(1, toVersionId);
                insertVersion.setObject(2, tenantId);
                insertVersion.setObject(3, projectId);
                insertVersion.setObject(4, deviceTypeId);
                insertVersion.setString(5, snapshotJson);
                insertVersion.setString(6, snapshotJson);
                insertVersion.executeUpdate();
            }
            try (var insertTransition = connection.prepareStatement("""
                    INSERT INTO dev_device_model_binding_history
                        (id, tenant_id, project_id, device_id, from_model_version_id, to_model_version_id,
                         transition_key, transition_type, effective_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'UPGRADE', now())
                    """)) {
                insertTransition.setObject(1, UUID.randomUUID());
                insertTransition.setObject(2, tenantId);
                insertTransition.setObject(3, projectId);
                insertTransition.setObject(4, deviceId);
                insertTransition.setObject(5, fromVersionId);
                insertTransition.setObject(6, toVersionId);
                insertTransition.setObject(7, UUID.randomUUID());
                insertTransition.executeUpdate();
            }
            try (var updateBinding = connection.prepareStatement("""
                    UPDATE dev_device SET thing_model_version_id = ?, updated_at = now()
                     WHERE id = ? AND project_id = ? AND thing_model_version_id = ?
                    """)) {
                updateBinding.setObject(1, toVersionId);
                updateBinding.setObject(2, deviceId);
                updateBinding.setObject(3, projectId);
                updateBinding.setObject(4, fromVersionId);
                if (updateBinding.executeUpdate() != 1) {
                    throw new IllegalStateException("升级转换未能切换设备当前版本");
                }
            }
            return toVersionId;
        } catch (SQLException exception) {
            throw new IllegalStateException("建立升级转换夹具失败", exception);
        }
    }
}
