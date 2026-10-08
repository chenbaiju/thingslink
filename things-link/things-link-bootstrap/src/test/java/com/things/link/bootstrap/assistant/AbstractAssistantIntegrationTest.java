package com.things.link.bootstrap.assistant;

import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** Agent组共用独立容器，隔离旧测试的全项目清场；JVM退出由Ryuk回收，不登记为类级独占资源。 */
@Import(AbstractAssistantIntegrationTest.AssistantDatabaseConfiguration.class)
public abstract class AbstractAssistantIntegrationTest extends AbstractIntegrationTest {
    private static final String DATABASE_NAME="assistant_suite";
    /** 有意遮蔽基类字段，使既有owner夹具与Spring/Flyway统一使用Agent专库。 */
    protected static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse(AbstractIntegrationTest.POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(AbstractIntegrationTest.POSTGRES.getUsername())
            .withPassword(AbstractIntegrationTest.POSTGRES.getPassword());
    static {POSTGRES.start();}
    /** 基类的测试runner直连原共享库，明确替换，避免跨库修改测试配额。 */
    @MockitoBean(enforceOverride=true,name="relaxRestQuota")
    private ApplicationRunner unusedSharedRestQuota;
    @Autowired private JdbcTemplate databaseIdentity;

    @Override protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
    }

    /** 每例实查所有运行池与owner指向专库；应用角色仍无superuser/BYPASSRLS。 */
    @BeforeEach void verifyAssistantDatabaseIdentity() throws SQLException {
        assertThat(POSTGRES.getJdbcUrl()).isNotEqualTo(AbstractIntegrationTest.POSTGRES.getJdbcUrl());
        for(var workload:DatabaseWorkload.values()) {
            try(var ignored=DatabaseWorkloadContext.enter(workload)) {
                var row=databaseIdentity.queryForMap("SELECT current_database(),current_user");
                assertThat(row.get("current_database")).isEqualTo(DATABASE_NAME);
                assertThat(row.get("current_user")).isEqualTo(APP_ROLE);
                assertThat(databaseIdentity.queryForObject(
                        "SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)).isTrue();
            }
        }
        try(var owner=fixtureOwnerConnection();var statement=owner.createStatement();var result=statement.executeQuery("SELECT current_database()")) {
            assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo(DATABASE_NAME);
        }
    }

    /** 在Web服务器及数据源创建前确定专库地址；只改测试落点，不禁用业务组件。 */
    @TestConfiguration(proxyBeanMethods=false)
    static class AssistantDatabaseConfiguration {
        /** Registrar在真实Web上下文可能晚于数据源创建，使用更早的工厂阶段覆盖继承地址。 */
        @Bean static BeanFactoryPostProcessor assistantDatabaseProperties(ConfigurableEnvironment environment) {
            return beanFactory -> environment.getPropertySources().addFirst(new MapPropertySource(
                    "assistantTestDatabase",Map.of("spring.datasource.url",POSTGRES.getJdbcUrl(),
                    "spring.flyway.url",POSTGRES.getJdbcUrl())));
        }
        /** 保留基类既有写速率夹具设置，仅在正确专库执行；读速率及其他配额不变。 */
        @Bean ApplicationRunner relaxAssistantRestQuota() {
            return args->{
                try(Connection connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                    Statement statement=connection.createStatement()) {
                    statement.executeUpdate("UPDATE sys_quota_policy SET rest_api_write_rate_per_second=1000000,rest_api_write_rate_per_minute=60000000");
                }
            };
        }
    }
}
