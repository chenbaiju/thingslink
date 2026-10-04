package com.things.link.bootstrap.device.model;

import com.things.link.device.application.ThingModelVersionBindingService;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B-X1a 数据库合同与 D-098 平台入口复核：真实 PostgreSQL 验证版本事实及无 HTTP 上下文的项目隔离。 */
@DisplayName("B-X1a 物模型版本数据库合同")
class ThingModelVersionIntegrationTests extends AbstractIntegrationTest {
    /** 数据库事实核验入口。 */ @Autowired private JdbcTemplate jdbcTemplate;
    /** 必须调用 Spring 代理，才能验证 local RLS 设置与仓储查询实际加入同一事务。 */
    @Autowired private ThingModelVersionBindingService bindingService;
    /** 仅记录本用例的随机夹具身份，清理时不删除其他集成测试的数据。 */
    private final List<VersionFixture> fixtures = new ArrayList<>();

    /** 迁移必须建立两张 RLS 业务表和设备当前指针，不能只停留在 Java 领域类型。 */
    @Test void createsVersionAndBindingFactsWithRls() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT tablename FROM pg_tables
                 WHERE schemaname = 'public'
                   AND tablename IN ('dev_thing_model_version', 'dev_device_model_binding_history')
                 ORDER BY tablename
                """, String.class);
        assertThat(tables).containsExactly("dev_device_model_binding_history", "dev_thing_model_version");
        Integer pointerColumns = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'dev_device'
                   AND column_name = 'thing_model_version_id'
                """, Integer.class);
        assertThat(pointerColumns).isEqualTo(1);
        List<Boolean> rls = jdbcTemplate.queryForList("""
                SELECT relrowsecurity FROM pg_class
                 WHERE relname IN ('dev_thing_model_version', 'dev_device_model_binding_history')
                 ORDER BY relname
                """, Boolean.class);
        assertThat(rls).containsOnly(true);
    }

    /** 历史资格必须走稳定关系索引，且发布版本由数据库触发器禁止原地变化。 */
    @Test void installsImmutabilityAndHistoryEligibilityIndexes() {
        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                 WHERE schemaname = 'public' AND indexname IN (
                   'dev_thing_model_version_project_type_published_idx',
                   'dev_device_model_binding_history_project_device_effective_idx',
                   'dev_device_model_binding_history_project_device_from_idx')
                 ORDER BY indexname
                """, String.class);
        assertThat(indexes).hasSize(3);
        Integer triggers = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                 WHERE c.relname = 'dev_thing_model_version'
                   AND t.tgname = 'dev_thing_model_version_immutable_trg' AND NOT t.tgisinternal
                """, Integer.class);
        assertThat(triggers).isEqualTo(1);
    }

    /** D-098：Kafka 平台轮询入口没有 HTTP 身份，现有端口仍须在自身事务中读到已绑定版本。 */
    @Test
    void resolvesPlatformGeneratedVersionWithoutRequestContext() {
        VersionFixture fixture = seedBoundDevice();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            assertNoRequestScope(fixture.deviceId());

            assertThat(bindingService.resolveCurrentVersionForPlatformGenerated(
                    fixture.tenantId(), fixture.projectId(), fixture.deviceId())).isEqualTo("1.0.0");

            assertNoRequestScope(fixture.deviceId());
        }
    }

    /** ADR 0012：现存其他项目不能解析目标设备；失败事务结束后仍可处理下一项目且不遗留请求范围。 */
    @Test
    void rejectsOtherProjectAndClearsScopeAfterFailure() {
        VersionFixture first = seedBoundDevice();
        VersionFixture second = seedBoundDevice();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            assertNoRequestScope(first.deviceId());

            assertThatThrownBy(() -> bindingService.resolveCurrentVersionForPlatformGenerated(
                    second.tenantId(), second.projectId(), first.deviceId()))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.errorCode()).isEqualTo(DeviceErrorCode.THING_MODEL_VERSION_NOT_FOUND));

            assertNoRequestScope(first.deviceId());
            assertThat(bindingService.resolveCurrentVersionForPlatformGenerated(
                    second.tenantId(), second.projectId(), second.deviceId())).isEqualTo("1.0.0");
            assertNoRequestScope(second.deviceId());
            assertThat(bindingService.resolveCurrentVersionForPlatformGenerated(
                    first.tenantId(), first.projectId(), first.deviceId())).isEqualTo("1.0.0");
            assertNoRequestScope(first.deviceId());
        }
    }

    /**
     * 仅用 owner 连接准备独立关系骨架，版本及 INITIAL 历史复用既有夹具；被测查询始终使用应用角色。
     *
     * @return 拥有当前 1.0.0 绑定的项目和设备
     */
    private VersionFixture seedBoundDevice() {
        VersionFixture fixture = new VersionFixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        fixtures.add(fixture);
        UUID typeId = Uuid7.generate();
        JdbcTemplate owner = fixtureJdbc();
        owner.update("INSERT INTO sys_tenant (id, name) VALUES (?, 'D098 版本复核租户')", fixture.tenantId());
        owner.update("""
                INSERT INTO sys_project (id, tenant_id, name, project_key)
                VALUES (?, ?, 'D098 版本复核项目', ?)
                """, fixture.projectId(), fixture.tenantId(),
                "d098" + fixture.projectId().toString().replace("-", ""));
        owner.update("""
                INSERT INTO dev_type
                    (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type, status)
                VALUES (?, ?, ?, 'version_probe', '版本复核类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                """, typeId, fixture.tenantId(), fixture.projectId());
        owner.update("""
                INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name)
                VALUES (?, ?, ?, ?, 'version_probe', '版本复核设备')
                """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), typeId);
        seedThingModelVersion(fixture.tenantId(), fixture.projectId(), typeId, fixture.deviceId(),
                "{\"properties\":{},\"events\":{},\"commands\":{}}");
        return fixture;
    }

    /**
     * 检查调用前后均无请求范围或外层测试事务，并证明后续无范围的应用查询仍然 fail-closed。
     *
     * <p>这里不声称重借到同一物理连接；物理连接复用及 local 设置回滚由支持层专项验证。</p>
     *
     * @param deviceId owner 夹具已提交的真实设备
     */
    private void assertNoRequestScope(UUID deviceId) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT app_current_tenant() IS NULL AND app_current_project() IS NULL
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dev_device WHERE id = ?",
                Integer.class, deviceId)).isZero();
    }

    /** 清理本用例夹具及防御性线程状态；先移除设备引用，再删版本，保持生产 RESTRICT 外键有效。 */
    @AfterEach
    void cleanVersionFixtures() {
        TenantContext.clear();
        JdbcTemplate owner = fixtureJdbc();
        for (VersionFixture fixture : fixtures) {
            owner.update("DELETE FROM dev_device WHERE id = ?", fixture.deviceId());
            owner.update("DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_project WHERE id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
        }
    }

    /** @return 仅供夹具准备和清理的隔离容器 owner 连接，不能注入被测服务 */
    private JdbcTemplate fixtureJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /** 单用例随机身份；每个项目使用独立租户，覆盖跨租户项目组合而不依赖全表清空。 */
    private record VersionFixture(UUID tenantId, UUID projectId, UUID deviceId) {
    }
}
