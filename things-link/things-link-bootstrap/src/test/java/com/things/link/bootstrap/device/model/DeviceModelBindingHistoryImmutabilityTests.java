package com.things.link.bootstrap.device.model;

import com.things.link.device.application.DeviceIngestionContext;
import com.things.link.device.application.ThingModelVersionBindingService;
import com.things.link.device.application.ThingModelVersionService;
import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.ThingModelVersion;
import com.things.link.device.domain.ThingModelVersionRepository.BindingTransition;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * D-114真实验收：ADR0052的不可变转换事实和十分钟资格不能被APP原位改时刻或删除历史重解释。
 * 版本、INITIAL及UPGRADE均由真实发布服务、设备仓储和绑定服务产生，不mock资格、不手工造历史行。
 * APP写权限立即拒绝，owner直接破坏原事实另由数据库守卫拒绝；合法追加、读取和父实体清理由各自边界验证。
 */
@DisplayName("D-114 转换历史旁路改写与真实旧版本资格")
class DeviceModelBindingHistoryImmutabilityTests extends AbstractIntegrationTest {

    /** 结构化完整JSONB快照，使历史行变化与设备指针、版本不变可分别核对。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** owner有底层表权限时仍须由专属不可变守卫拒绝，不能把其他23514约束当作历史保护。 */
    private static final String IMMUTABILITY_CONSTRAINT = "dev_device_model_binding_history_immutable";
    /** 两版模型都通过生产版本线路与摘要校验，不直接INSERT版本事实。 */
    @Autowired private ThingModelVersionService versionService;
    /** 设备创建复用生产仓储的当前版本选择与INITIAL插入，避免伪造初始历史。 */
    @Autowired private DeviceRepository deviceRepository;
    /** 升级与上行资格均走真实事务服务，不能用测试内复制的时间公式代替业务判定。 */
    @Autowired private ThingModelVersionBindingService bindingService;
    /** 服务事务中确认真实APP角色并检查生成的持久化事实。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 将发布/创建/升级夹具放入真实应用事务，前置失败时整体回滚。 */
    @Autowired private PlatformTransactionManager transactionManager;

    /** owner拥有表写权限也不能原位改写、删除或清空历史，三个入口分别验证。 */
    private enum OwnerWrite {
        /** 整行列赋值覆盖所有历史字段的UPDATE入口。 */ UPDATE,
        /** 父设备仍存在时直接DELETE转换事实。 */ DELETE,
        /** TRUNCATE绕过行级DELETE，须另有语句级拒绝。 */ TRUNCATE
    }

    /** 原APP改时刻反例在语句阶段即42501，事务回滚后旧A仍为30055且全部事实不变。 */
    @Test
    void rewritingCommittedTransitionTimeCannotReopenExpiredHistoryWindow() throws Exception {
        Fixture fixture = fixture(Duration.ofMinutes(11));
        JsonNode before = snapshot(fixture);
        assertExpired(fixture, fixture.receivedAt());
        assertThat(resolve(fixture, "1.0.1", fixture.receivedAt()).eligibility()).isEqualTo(DeviceIngestionContext.Eligibility.CURRENT);
        Instant rewrittenTime = fixture.receivedAt().minusSeconds(30);

        assertApplicationWriteDenied(fixture, application -> execute(application, """
                UPDATE public.dev_device_model_binding_history SET effective_at = ?
                 WHERE project_id = ? AND device_id = ? AND id = ?
                """, Timestamp.from(rewrittenTime), fixture.projectId(), fixture.deviceId(), fixture.upgradeId()));
        assertExpired(fixture, fixture.receivedAt());
        assertThat(resolve(fixture, "1.0.1", fixture.receivedAt()).eligibility()).isEqualTo(DeviceIngestionContext.Eligibility.CURRENT);
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    /** 原APP删除反例立即42501，完整UPGRADE事实及窗口内旧A的HISTORY_ONLY资格均保留。 */
    @Test
    void deletingCommittedTransitionCannotEraseValidHistoryEligibility() throws Exception {
        Fixture fixture = fixture(Duration.ofMinutes(5));
        JsonNode before = snapshot(fixture);
        assertThat(resolve(fixture, "1.0.0", fixture.receivedAt()).eligibility()).isEqualTo(DeviceIngestionContext.Eligibility.HISTORY_ONLY);
        assertApplicationWriteDenied(fixture, application -> execute(application, """
                DELETE FROM public.dev_device_model_binding_history
                 WHERE project_id = ? AND device_id = ? AND id = ?
                """, fixture.projectId(), fixture.deviceId(), fixture.upgradeId()));
        DeviceIngestionContext historical = resolve(fixture, "1.0.0", fixture.receivedAt());
        assertThat(historical.eligibility()).isEqualTo(DeviceIngestionContext.Eligibility.HISTORY_ONLY);
        assertThat(historical.thingModelVersionId()).isEqualTo(fixture.versionA());
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    /** 合法对照证明真实版本线路、RLS及服务资格均可工作；仅可信receivedAt推进才让旧版本自然超窗。 */
    @Test
    void legitimateUpgradeKeepsCurrentAndHistoricalWindowsWithoutChangingFacts() throws Exception {
        Fixture fixture = fixture(Duration.ofMinutes(5));
        JsonNode before = snapshot(fixture);
        DeviceIngestionContext current = resolve(fixture, "1.0.1", fixture.receivedAt());
        DeviceIngestionContext historical = resolve(fixture, "1.0.0", fixture.receivedAt());
        assertThat(current.thingModelVersionId()).isEqualTo(fixture.versionB());
        assertThat(current.eligibility()).isEqualTo(DeviceIngestionContext.Eligibility.CURRENT);
        assertThat(historical.thingModelVersionId()).isEqualTo(fixture.versionA());
        assertThat(historical.eligibility()).isEqualTo(DeviceIngestionContext.Eligibility.HISTORY_ONLY);
        assertExpired(fixture, fixture.transitionAt().plus(Duration.ofMinutes(11)));
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    /** APP保留SELECT/INSERT但失去全部改删/清空权限，PUBLIC也不得通过默认ACL保留旁路能力。 */
    @Test
    void applicationPrivilegesKeepAppendAndReadButRejectMutationAndTruncate() throws Exception {
        Fixture fixture = fixture(Duration.ofMinutes(5));
        try (Connection application = scopedConnection(fixture.projectId())) {
            for (String privilege : new String[]{"SELECT", "INSERT"}) {
                assertThat(text(application, "SELECT has_table_privilege(current_user, 'public.dev_device_model_binding_history', ?)::text", privilege))
                        .as("合法发布/绑定服务需要%s", privilege).isEqualTo("true");
            }
            for (String privilege : new String[]{"UPDATE", "DELETE", "TRUNCATE"}) {
                assertThat(text(application, "SELECT has_table_privilege(current_user, 'public.dev_device_model_binding_history', ?)::text", privilege))
                        .isEqualTo("false");
            }
            assertThat(text(application, "SELECT has_any_column_privilege(current_user, 'public.dev_device_model_binding_history', 'UPDATE')::text"))
                    .isEqualTo("false");
            assertThat(text(application, """
                    SELECT count(*)::text FROM pg_class c,
                           LATERAL aclexplode(COALESCE(c.relacl, acldefault('r', c.relowner))) acl
                     WHERE c.oid = 'public.dev_device_model_binding_history'::regclass AND acl.grantee = 0
                       AND acl.privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE')
                    """)).isEqualTo("0");
            assertThat(text(application, "SELECT count(*)::text FROM public.dev_device_model_binding_history WHERE device_id = ?", fixture.deviceId()))
                    .isEqualTo("2");
        }
        assertApplicationWriteDenied(fixture, application -> execute(application, "TRUNCATE TABLE public.dev_device_model_binding_history"));
    }

    /** owner有底层写权限，整行UPDATE、直接DELETE与TRUNCATE都必须由专属不可变触发器23514拒绝。 */
    @ParameterizedTest
    @EnumSource(OwnerWrite.class)
    void ownerCannotRewriteDeleteOrTruncateCommittedHistory(OwnerWrite operation) throws Exception {
        Fixture fixture = fixture(Duration.ofMinutes(5));
        assertOwnerWriteRejected(fixture, owner -> {
            switch (operation) {
                case UPDATE -> execute(owner, """
                        UPDATE public.dev_device_model_binding_history
                           SET id = ?, tenant_id = ?, project_id = ?, device_id = ?,
                               from_model_version_id = ?, to_model_version_id = ?, transition_key = ?,
                               transition_type = 'ROLLBACK', effective_at = ?, created_at = ?
                         WHERE project_id = ? AND id = ?
                        """, UUID.randomUUID(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                        fixture.versionB(), fixture.versionA(), UUID.randomUUID(), Timestamp.from(fixture.receivedAt()),
                        Timestamp.from(fixture.receivedAt()), fixture.projectId(), fixture.upgradeId());
                case DELETE -> execute(owner, "DELETE FROM public.dev_device_model_binding_history WHERE project_id = ? AND id = ?",
                        fixture.projectId(), fixture.upgradeId());
                // 后续OTA事实外键会让裸TRUNCATE先报0A000；CASCADE只用于到达原语句级守卫。
                // 专项仍要求23514和原约束名，且整个owner工作单元无条件回滚。
                case TRUNCATE -> execute(owner, "TRUNCATE TABLE public.dev_device_model_binding_history CASCADE");
            }
        });
        if (operation == OwnerWrite.UPDATE) {
            // ADR0061把同值UPDATE也定义为改写入口，幂等调用应读取原事实，不能依赖只比较差异的守卫。
            assertOwnerWriteRejected(fixture, owner -> execute(owner, """
                    UPDATE public.dev_device_model_binding_history
                       SET id = id, tenant_id = tenant_id, project_id = project_id, device_id = device_id,
                           from_model_version_id = from_model_version_id, to_model_version_id = to_model_version_id,
                           transition_key = transition_key, transition_type = transition_type,
                           effective_at = effective_at, created_at = created_at
                     WHERE project_id = ? AND id = ?
                    """, fixture.projectId(), fixture.upgradeId()));
        }
        assertThat(resolve(fixture, "1.0.0", fixture.receivedAt()).eligibility()).isEqualTo(DeviceIngestionContext.Eligibility.HISTORY_ONLY);
    }

    /** 父设备软删仍是存在的父实体，不能伪装成物理级联而允许owner直接抹除历史。 */
    @Test
    void ownerCannotDeleteHistoryWhileSoftDeletedDeviceStillExists() throws Exception {
        Fixture fixture = fixture(Duration.ofMinutes(5));
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(execute(application, "UPDATE public.dev_device SET deleted_at = now() WHERE project_id = ? AND id = ?",
                        fixture.projectId(), fixture.deviceId())).isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        JsonNode before = snapshot(fixture);
        assertThat(before.get("device").get("deleted_at").isNull()).isFalse();
        assertThat(before.get("history")).hasSize(2);
        assertOwnerWriteRejected(fixture, owner -> execute(owner,
                "DELETE FROM public.dev_device_model_binding_history WHERE project_id = ? AND device_id = ?",
                fixture.projectId(), fixture.deviceId()));
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    /** APP拒绝必须发生在写语句本身，并撤销同事务先前元信息更新，不依赖COMMIT失败才发现。 */
    private void assertApplicationWriteDenied(Fixture fixture, SqlAction action) throws SQLException {
        JsonNode before = snapshot(fixture);
        Throwable failure;
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(execute(application, "UPDATE public.dev_device SET name = 'APP拒绝应回滚' WHERE project_id = ? AND id = ?",
                        fixture.projectId(), fixture.deviceId())).isEqualTo(1);
                failure = catchThrowable(() -> action.run(application));
            } finally {
                application.rollback();
            }
        }
        assertFailureUnchanged(fixture, before, failure, "42501", null);
    }

    /** owner原生连接具有底层权限但不能绕过触发器；无论误成功还是正确拒绝都回滚测试工作单元。 */
    private void assertOwnerWriteRejected(Fixture fixture, SqlAction action) throws SQLException {
        JsonNode before = snapshot(fixture);
        Throwable failure;
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(text(owner, "SELECT current_user")).isEqualTo(POSTGRES.getUsername());
            owner.setAutoCommit(false);
            try {
                text(owner, "SELECT set_config('lock_timeout', '10s', true)");
                text(owner, "SELECT set_config('statement_timeout', '15s', true)");
                assertThat(execute(owner, "UPDATE public.dev_device SET name = 'owner拒绝应回滚' WHERE project_id = ? AND id = ?",
                        fixture.projectId(), fixture.deviceId())).isEqualTo(1);
                failure = catchThrowable(() -> action.run(owner));
            } finally {
                owner.rollback();
            }
        }
        assertFailureUnchanged(fixture, before, failure, "23514", IMMUTABILITY_CONSTRAINT);
    }

    /** 并列核对真实SQLSTATE、owner专属约束名及完整快照，防止权限错误或其他CHECK掩盖保护缺失。 */
    private void assertFailureUnchanged(Fixture fixture, JsonNode before, Throwable failure, String expectedState,
                                         String expectedConstraint) throws SQLException {
        JsonNode after = snapshot(fixture);
        String sqlState = failure instanceof SQLException postgres ? postgres.getSQLState() : null;
        String constraint = failure instanceof PSQLException postgres && postgres.getServerErrorMessage() != null
                ? postgres.getServerErrorMessage().getConstraint() : null;
        assertSoftly(softly -> {
            softly.assertThat(failure).isInstanceOf(SQLException.class);
            softly.assertThat(sqlState).isEqualTo(expectedState);
            if (expectedConstraint != null) {
                softly.assertThat(constraint).isEqualTo(expectedConstraint);
            }
            softly.assertThat(after).as("拒绝后设备、类型、不可变版本及全部历史必须保持").isEqualTo(before);
        });
    }

    /** 同一实际上行资格端口接受固定服务器接收时刻，测试不sleep、不修改数据库或系统时钟。 */
    private DeviceIngestionContext resolve(Fixture fixture, String versionNumber, Instant receivedAt) {
        return inScope(fixture.tenantId(), fixture.projectId(), () ->
                bindingService.resolveForIngestion(fixture.projectId(), fixture.deviceId(), versionNumber, receivedAt));
    }

    /** 超窗必须是既有30055业务错误，权限、找不到版本或空范围不能冒充旧版本资格拒绝。 */
    private BusinessException assertExpired(Fixture fixture, Instant receivedAt) {
        Throwable failure = catchThrowable(() -> resolve(fixture, "1.0.0", receivedAt));
        assertThat(failure).isInstanceOf(BusinessException.class);
        BusinessException business = (BusinessException) failure;
        assertThat(business.errorCode()).isEqualTo(DeviceErrorCode.THING_MODEL_VERSION_HISTORY_EXPIRED);
        return business;
    }

    /**
     * owner仅创建随机平台租户/项目，类型由APP准备；版本、设备INITIAL与升级都调用生产端口。
     * 先发布A并创建设备，再发布B并升级，确保A→B是真实转换而非手工拼出的历史；receivedAt仅在内存中固定。
     */
    private Fixture fixture(Duration receivedAfterTransition) throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '绑定历史不可变性租户')", tenantId);
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '绑定历史不可变性项目', ?)",
                    projectId, tenantId, "binding_history_" + projectId.toString().replace("-", ""));
        }
        try (Connection application = scopedConnection(projectId)) {
            try {
                assertThat(execute(application, """
                        INSERT INTO public.dev_type
                            (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type, status)
                        VALUES (?, ?, ?, 'history_type', '历史资格类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                        """, typeId, tenantId, projectId)).isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        Fixture fixture = inScope(tenantId, projectId, () -> new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
            JsonNode model = JSON.readTree("{\"properties\":{},\"events\":{},\"commands\":{}}");
            ThingModelVersion versionA = versionService.publish(projectId, typeId, "1.0.0", ThingModelVersion.ChangeLevel.MAJOR, model);
            deviceRepository.create(new Device(deviceId, tenantId, projectId, typeId, null, "history_device", "历史资格设备",
                    null, Device.Status.INACTIVE, null, null, Instant.now()));
            assertThat(jdbcTemplate.queryForObject("SELECT thing_model_version_id FROM public.dev_device WHERE id = ?", UUID.class, deviceId))
                    .isEqualTo(versionA.id());
            ThingModelVersion versionB = versionService.publish(projectId, typeId, "1.0.1", ThingModelVersion.ChangeLevel.PATCH, model);
            Instant transitionAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
            BindingTransition upgrade = bindingService.bind(projectId, deviceId, versionB.id(), UUID.randomUUID(),
                    BindingTransition.TransitionType.UPGRADE, transitionAt);
            assertThat(upgrade.fromVersionId()).isEqualTo(versionA.id());
            assertThat(upgrade.toVersionId()).isEqualTo(versionB.id());
            return new Fixture(tenantId, projectId, typeId, deviceId, versionA.id(), versionB.id(), upgrade.id(),
                    transitionAt, transitionAt.plus(receivedAfterTransition));
        }));
        assertThat(fixture).isNotNull();
        JsonNode state = snapshot(fixture);
        assertThat(state.get("device").get("thing_model_version_id").asString()).isEqualTo(fixture.versionB().toString());
        assertThat(state.get("versions")).hasSize(2);
        assertThat(state.get("history")).hasSize(2);
        assertThat(historyRow(state, fixture.upgradeId()).get("transition_type").asString()).isEqualTo("UPGRADE");
        return fixture;
    }

    /** 查找实际服务返回的转换主键，不依赖随机UUID排序推测哪一行是升级事实。 */
    private JsonNode historyRow(JsonNode snapshot, UUID historyId) {
        for (JsonNode history : snapshot.get("history")) {
            if (history.get("id").asString().equals(historyId.toString())) {
                return history;
            }
        }
        throw new AssertionError("快照中缺少真实转换历史 " + historyId);
    }

    /** 完整应用快照覆盖设备、类型、两个不可变版本及所有历史行，不能只看被攻击的时间列。 */
    private JsonNode snapshot(Fixture fixture) throws SQLException {
        try (Connection connection = scopedConnection(fixture.projectId())) {
            return JSON.readTree(text(connection, """
                    SELECT jsonb_build_object(
                        'device', (SELECT to_jsonb(d) FROM public.dev_device d WHERE d.id = ?),
                        'types', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM public.dev_type t),
                        'versions', (SELECT jsonb_agg(to_jsonb(v) ORDER BY v.id) FROM public.dev_thing_model_version v),
                        'history', COALESCE((SELECT jsonb_agg(to_jsonb(h) ORDER BY h.id)
                            FROM public.dev_device_model_binding_history h), '[]'::jsonb))::text
                    """, fixture.deviceId()));
        }
    }

    /** 真实服务事务借连接前设置项目RLS，完成后清除线程范围；不伪造HTTP授权已被测试。 */
    private <T> T inScope(UUID tenantId, UUID projectId, Supplier<T> action) {
        RlsScopeContext.set(new RlsScope(tenantId, projectId));
        try {
            return action.get();
        } finally {
            RlsScopeContext.clear();
        }
    }

    /** 原生APP物理连接验证身份和同项目范围，显式RC兼容已有守卫，等待上限只作清理兜底。 */
    private Connection scopedConnection(UUID projectId) throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        try {
            assertThat(text(connection, "SELECT current_user")).isEqualTo(APP_ROLE);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            assertThat(text(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString())).isEqualTo(projectId.toString());
            text(connection, "SELECT set_config('lock_timeout', '10s', true)");
            text(connection, "SELECT set_config('statement_timeout', '15s', true)");
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /** 直接写入的失败时点由调用者检查，语句资源执行后关闭，所有身份及时刻通过参数绑定。 */
    private int execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            return statement.executeUpdate();
        }
    }

    /** 单值查询必须返回真实一行，SQL NULL保持为空，不把无范围空结果当成业务事实。 */
    private String text(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** 保留UUID/Timestamp数据库类型，避免字符串日期时区或SQL拼接改变反例条件。 */
    private void parameters(PreparedStatement statement, Object[] arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }

    /** 不可变性测试的一个直接SQL工作单元，异常由调用者在语句边界捕获。 */
    @FunctionalInterface
    private interface SqlAction {
        /** @param connection 已建立的真实APP或owner事务连接 */
        void run(Connection connection) throws SQLException;
    }

    /**
     * 真实A→B转换及固定服务器接收时刻；每例拥有独立项目，不全表清理。
     * @param tenantId 平台归属租户
     * @param projectId RLS隔离项目
     * @param typeId A/B共同所属设备类型
     * @param deviceId 当前已升级到B的设备
     * @param versionA 真实发布1.0.0版本
     * @param versionB 真实发布1.0.1版本
     * @param upgradeId 实际绑定服务返回的UPGRADE历史身份
     * @param transitionAt 实际转换的可信生效时刻
     * @param receivedAt 固定的服务端接收时刻，用于无需等待地验证窗口
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID typeId, UUID deviceId, UUID versionA, UUID versionB,
                           UUID upgradeId, Instant transitionAt, Instant receivedAt) { }
}
