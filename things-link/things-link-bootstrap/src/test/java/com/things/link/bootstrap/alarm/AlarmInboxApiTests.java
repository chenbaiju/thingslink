package com.things.link.bootstrap.alarm;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.alarm.application.AlarmInboxService;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** ADR0093/S12-P0-6a：真实签名JWT、项目RLS与事务验证个人已读不改变共享告警事实。 */
@AutoConfigureMockMvc
@Import(AlarmInboxApiTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"INBOX_POSTGRES"})
class AlarmInboxApiTests extends AbstractIntegrationTest {
    /** 全局领取器不得与通知阅读的并发夹具争用持久事实。 */
    private static final PostgreSQLContainer<?> INBOX_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("alarm_inbox_api").withUsername("thingslink").withPassword("thingslink");
    /** 静态启动使Flyway和APP池始终绑定同一独占库。 */
    private static final String DATABASE_URL = startDatabase();
    /** JSON只用于真实HTTP信封解析。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 不运行共享数据库专用的配额放宽runner。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedQuotaRunner;
    /** 当前片不外发任何通知。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotifications;
    /** 不领取无关任务。 */
    @MockitoBean(enforceOverride = true)
    private TaskSchedulingScanner unusedTasks;
    /** 不修改命令事实。 */
    @MockitoBean(enforceOverride = true)
    private DeviceCommandTimeoutScanner unusedCommands;
    /** 不运行无关聚合回补。 */
    @MockitoBean(enforceOverride = true)
    private PropertyAggregateBackfillScanner unusedBackfill;
    /** HTTP边界保留真实安全链与Controller装配。 */
    @Autowired private MockMvc mvc;
    /** 使用生产签名器签发真实Fixture身份，不用mock Authentication绕过过滤器。 */
    @Autowired private TokenIssuer tokens;
    /** APP池确认真实角色与数据库。 */
    @Autowired private JdbcTemplate jdbc;
    /** 真实事务服务补证配额过滤之前已归档时的业务层只读边界，不绕过HTTP主断言。 */
    @Autowired private AlarmInboxService inbox;
    /** 仅暂停真实许可已取得后的代码，不替换锁或事务。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 默认不暂停；单例测试恢复后不会影响其他请求。 */
    private Runnable afterPermit = () -> { };
    /** 受控线程在每例结束等待退出，避免悬挂HTTP调用。 */
    private final List<ExecutorService> executors = new ArrayList<>();

    /** 确认APP角色与专库，挂钩保持原SHARE锁语义。 */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("alarm_inbox_api");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            afterPermit.run();
            return result;
        }).when(target).requireActiveForWrite(any(), any());
    }

    /** 账号互不影响、重复和去重幂等，VIEWER个人已读不写业务ACK/CLEAR或投递。 */
    @Test
    void viewerReadsOnlyOwnReceiptAndKeepsSharedAlarmUnchanged() throws Exception {
        Fixture f = seed();
        List<String> facts = alarmFacts(f);
        assertThat(count(f, false)).isEqualTo(1);
        assertThat(count(f, true)).isEqualTo(1);
        JsonNode changed = ok(mark(f, true, List.of(f.eventId(), f.eventId())));
        assertThat(changed.get("markedCount").asInt()).isEqualTo(1);
        assertThat(ok(mark(f, true, List.of(f.eventId()))).get("markedCount").asInt()).isZero();
        assertThat(count(f, true)).isZero();
        assertThat(count(f, false)).isEqualTo(1);
        JsonNode page = ok(page(f, true, null, 20));
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("read").asBoolean()).isTrue();
        assertThat(alarmFacts(f)).isEqualTo(facts);
        try (Connection c = owner(); PreparedStatement q = c.prepareStatement(
                "SELECT tenant_id,account_id FROM alarm_notification_read WHERE project_id=?")) {
            q.setObject(1, f.projectId());
            try (var r = q.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getObject(1, UUID.class)).isEqualTo(f.tenantId());
                assertThat(r.getObject(2, UUID.class)).isEqualTo(f.viewerId());
            }
        }
    }

    /** A先生成旧时间/UUID但迟提交；读B绝不能用水位把从未展示的A吞掉。 */
    @Test
    void lateCommitRemainsUnreadAfterNewerEventWasRead() throws Exception {
        Fixture f = seed();
        UUID late = Uuid7.generate();
        try (Connection a = owner()) {
            a.setAutoCommit(false);
            event(a, f, late, Instant.now().minusSeconds(180), "ACTIVATED");
            assertThat(ok(page(f, false, null, 20)).get("items").size()).isEqualTo(1);
            ok(mark(f, false, List.of(f.eventId())));
            a.commit();
        }
        JsonNode items = ok(page(f, false, null, 20)).get("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).get("read").asBoolean()).isTrue();
        assertThat(items.get(1).get("eventId").asString()).isEqualTo(late.toString());
        assertThat(items.get(1).get("read").asBoolean()).isFalse();
        assertThat(count(f, false)).isEqualTo(1);
    }

    /** 窗口隐藏旧/未来事件及非ACTIVATED，真实历史事实不因此删除。 */
    @Test
    void windowAndEventTypeFilterDoNotDestroyHistory() throws Exception {
        Fixture f = seed();
        UUID old = Uuid7.generate();
        UUID future = Uuid7.generate();
        UUID pending = Uuid7.generate();
        try (Connection c = owner()) {
            event(c, f, old, Instant.now().minusSeconds(31L * 86400), "ACTIVATED");
            event(c, f, future, Instant.now().plusSeconds(86400), "ACTIVATED");
            event(c, f, pending, Instant.now().minusSeconds(90), "PENDING");
        }
        List<String> before = alarmFacts(f);
        assertThat(ok(page(f, false, null, 100)).get("items").size()).isEqualTo(1);
        error(mark(f, false, List.of(f.eventId(), old)), 409, 40012);
        error(mark(f, false, List.of(future)), 409, 40012);
        error(mark(f, false, List.of(f.eventId(), pending)), 404, 40011);
        assertThat(count(f, false)).isEqualTo(1);
        assertThat(receipts(f)).isZero();
        assertThat(alarmFacts(f)).isEqualTo(before);
    }

    /** 跨项目/不存在事件整批拒绝，合法前缀不能已读；正文账号字段也不能代选受众。 */
    @Test
    void mixedForeignOrMissingIdsRejectWholeBatch() throws Exception {
        Fixture f = seed();
        Fixture other = seed();
        error(mark(f, false, List.of(f.eventId(), other.eventId())), 404, 40011);
        error(mark(f, false, List.of(f.eventId(), Uuid7.generate())), 404, 40011);
        assertThat(receipts(f)).isZero();
        assertThat(receipts(other)).isZero();
        MvcResult injected = mvc.perform(post(path(f) + "/read").header(HttpHeaders.AUTHORIZATION, token(f, true))
                .contentType(MediaType.APPLICATION_JSON).content("{\"eventIds\":[\"" + f.eventId()
                        + "\"],\"accountId\":\"" + f.ownerId() + "\"}")).andReturn();
        ok(injected);
        assertThat(count(f, false)).isEqualTo(1);
        assertThat(count(f, true)).isZero();
    }

    /** 请求数组、空元素、畸形游标及页大小均在持久写入前拒绝。 */
    @Test
    void validatesRequestBoundsAndCursor() throws Exception {
        Fixture f = seed();
        for (String json : List.of("{\"eventIds\":[]}", "{\"eventIds\":[null]}", "{}")) {
            MvcResult response = mvc.perform(post(path(f) + "/read").header(HttpHeaders.AUTHORIZATION, token(f, false))
                    .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
            error(response, 400, 40010);
        }
        error(mark(f, false, java.util.Collections.nCopies(101, f.eventId())), 400, 40010);
        error(page(f, false, "not-a-cursor", 20), 400, 40010);
        error(page(f, false, null, 0), 400, 40010);
        error(page(f, false, null, 101), 400, 40010);
        assertThat(receipts(f)).isZero();
    }

    /** capped计数不冒充精确数，分页仍能访问100条之外的有效事件且不重复。 */
    @Test
    void capsCountAndPaginatesWithStableWindow() throws Exception {
        Fixture f = seed();
        try (Connection c = owner()) {
            for (int i = 0; i < 104; i++) event(c, f, Uuid7.generate(), Instant.now().minusSeconds(120 + i), "ACTIVATED");
        }
        assertThat(count(f, false)).isEqualTo(100);
        JsonNode first = ok(page(f, false, null, 100));
        JsonNode second = ok(page(f, false, first.get("nextCursor").asString(), 100));
        assertThat(first.get("items").size()).isEqualTo(100);
        assertThat(second.get("items").size()).isEqualTo(5);
        assertThat(first.get("windowStart")).isEqualTo(second.get("windowStart"));
        assertThat(first.get("windowEnd")).isEqualTo(second.get("windowEnd"));
        List<String> ids = new ArrayList<>();
        first.get("items").forEach(item -> ids.add(item.get("eventId").asString()));
        second.get("items").forEach(item -> ids.add(item.get("eventId").asString()));
        assertThat(ids).doesNotHaveDuplicates();
    }

    /** 成员移除阻断读取/写入，重入沿用该全局账号回执；归档仅可读取。 */
    @Test
    void removalRejoinAndArchivePreservePersonalFacts() throws Exception {
        Fixture f = seed();
        assertThat(count(f, true)).isEqualTo(1);
        // 先观察真实ACTIVE读取产生计量事实，避免测试配置关闭记录而掩盖归档READ拒绝。
        assertThat(usageFacts(f)).isEqualTo(1);
        ok(mark(f, true, List.of(f.eventId())));
        try (Connection c = owner()) { execute(c, "UPDATE sys_project_member SET status='DISABLED' WHERE project_id=? AND account_id=?", f.projectId(), f.viewerId()); }
        error(page(f, true, null, 20), 401, 20020);
        error(mark(f, true, List.of(f.eventId())), 401, 20020);
        try (Connection c = owner()) {
            execute(c, "UPDATE sys_project_member SET status='ACTIVE' WHERE project_id=? AND account_id=?", f.projectId(), f.viewerId());
            execute(c, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
        }
        int beforeArchivedReads = usageFacts(f);
        assertThat(count(f, true)).isZero();
        JsonNode archivedPage = ok(page(f, true, null, 20));
        assertThat(archivedPage.get("items").size()).isEqualTo(1);
        assertThat(archivedPage.get("items").get(0).get("eventId").asString()).isEqualTo(f.eventId().toString());
        assertThat(archivedPage.get("items").get(0).get("read").asBoolean()).isTrue();
        JsonNode projects = ok(mvc.perform(get("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, token(f, true))).andReturn());
        assertThat(projects.size()).isEqualTo(1);
        assertThat(projects.get(0).get("id").asString()).isEqualTo(f.projectId().toString());
        assertThat(projects.get(0).get("status").asString()).isEqualTo("ARCHIVED");
        // 归档READ继续返回真实数据，但不能伪造ACTIVE项目才允许写入的日计量事实。
        assertThat(usageFacts(f)).isEqualTo(beforeArchivedReads);
        // 先已归档的请求由既有日配额安全链fail-closed；不能假设它一定到达业务许可检查。
        error(mark(f, false, List.of(f.eventId())), 429, 10029);
        TenantContext.set(new TenantScope(f.tenantId(), f.projectId(), f.ownerId()));
        try {
            assertThatThrownBy(() -> inbox.markRead(f.projectId(), List.of(f.eventId())))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.errorCode().code()).isEqualTo(50017));
        } finally {
            TenantContext.clear();
        }
        assertThat(receipts(f)).isEqualTo(1);
    }

    /** 缺认证不查询个人事实；项目删除即不可再创建回执。 */
    @Test
    void requiresAuthenticationAndRejectsDeletedProject() throws Exception {
        Fixture f = seed();
        assertThat(mvc.perform(get(path(f))).andReturn().getResponse().getStatus()).isEqualTo(401);
        try (Connection c = owner()) { execute(c, "UPDATE sys_project SET status='DELETING',deleted_at=clock_timestamp() WHERE id=?", f.projectId()); }
        error(page(f, true, null, 20), 401, 20020);
        error(mark(f, true, List.of(f.eventId())), 401, 20020);
        assertThat(receipts(f)).isZero();
    }

    /** 同账号并发重放只能保存一条回执，不把唯一键竞争转换成500。 */
    @Test
    void concurrentDuplicateMarksAreIdempotent() throws Exception {
        Fixture f = seed();
        ExecutorService pool = pool();
        CountDownLatch go = new CountDownLatch(1);
        Future<MvcResult> a = pool.submit(() -> { await(go); return mark(f, true, List.of(f.eventId())); });
        Future<MvcResult> b = pool.submit(() -> { await(go); return mark(f, true, List.of(f.eventId())); });
        go.countDown();
        assertThat(ok(a.get(10, TimeUnit.SECONDS)).get("markedCount").asInt()
                + ok(b.get(10, TimeUnit.SECONDS)).get("markedCount").asInt()).isEqualTo(1);
        assertThat(receipts(f)).isEqualTo(1);
    }

    /** COMMIT阶段拒绝必须回滚整批回执；读取/未读和共享事故随后可恢复。 */
    @Test
    void commitFailureRollsBackAllReceiptsAndAllowsRetry() throws Exception {
        Fixture f = seed();
        UUID second = Uuid7.generate();
        List<UUID> batch = List.of(f.eventId(), second);
        try (Connection c = owner()) {
            event(c, f, second, Instant.now().minusSeconds(90), "ACTIVATED");
            execute(c, "CREATE FUNCTION public.alarm_inbox_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture commit failure' USING ERRCODE='23514'; END $$");
            execute(c, "CREATE CONSTRAINT TRIGGER alarm_inbox_test_reject AFTER INSERT ON alarm_notification_read DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.alarm_inbox_test_reject()");
        }
        try {
            assertThat(mark(f, false, batch).getResponse().getStatus()).isEqualTo(500);
            assertThat(receipts(f)).isZero();
            assertThat(count(f, false)).isEqualTo(2);
        } finally {
            try (Connection c = owner()) {
                execute(c, "DROP TRIGGER alarm_inbox_test_reject ON alarm_notification_read");
                execute(c, "DROP FUNCTION public.alarm_inbox_test_reject()");
            }
        }
        assertThat(ok(mark(f, false, batch)).get("markedCount").asInt()).isEqualTo(2);
        assertThat(receipts(f)).isEqualTo(2);
        assertThat(count(f, false)).isZero();
    }

    /** 项目锁先归档时，已通过HTTP预检的标记在锁后必须拒绝并保持零回执。 */
    @Test
    void archiveWinningProjectLockPreventsReceipt() throws Exception {
        Fixture f = seed();
        try (Connection c = owner()) {
            c.setAutoCommit(false);
            execute(c, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
            Future<MvcResult> waiting = pool().submit(() -> mark(f, true, List.of(f.eventId())));
            awaitDatabaseLockWait();
            c.commit();
            error(waiting.get(10, TimeUnit.SECONDS), 403, 50017);
        }
        assertThat(receipts(f)).isZero();
    }

    /** HTTP入口先读到有效成员，排他锁中移除先提交时，应用锁后角色复核仍必须拒绝。 */
    @Test
    void removalDuringProjectLockWaitPreventsReceipt() throws Exception {
        Fixture f = seed();
        try (Connection c = owner()) {
            c.setAutoCommit(false);
            try (PreparedStatement q = c.prepareStatement("SELECT id FROM sys_project WHERE id=? FOR UPDATE")) {
                q.setObject(1, f.projectId());
                try (var row = q.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            execute(c, "UPDATE sys_project_member SET status='DISABLED' WHERE project_id=? AND account_id=?", f.projectId(), f.viewerId());
            Future<MvcResult> waiting = pool().submit(() -> mark(f, true, List.of(f.eventId())));
            awaitDatabaseLockWait();
            c.commit();
            error(waiting.get(10, TimeUnit.SECONDS), 404, 50001);
        }
        assertThat(receipts(f)).isZero();
    }

    /** 标记先取得SHARE时，归档只能在回执提交后完成，不能越过许可写入。 */
    @Test
    void receiptWinningPermitCommitsBeforeArchive() throws Exception {
        Fixture f = seed();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        afterPermit = () -> { locked.countDown(); await(release); };
        Future<MvcResult> mark = pool().submit(() -> mark(f, true, List.of(f.eventId())));
        try {
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            Future<?> archive = pool().submit(() -> { try (Connection c = owner()) { execute(c, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId()); } return null; });
            awaitDatabaseLockWait();
            assertThat(archive.isDone()).isFalse();
            release.countDown();
            ok(mark.get(10, TimeUnit.SECONDS));
            archive.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(receipts(f)).isEqualTo(1);
    }

    /** 首次创建真实跨租户VIEWER及一条激活事件，不依赖模板Demo数据或无效业务外键。 */
    private Fixture seed() throws SQLException {
        Fixture f = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        try (Connection c = owner()) {
            c.setAutoCommit(false);
            execute(c, "INSERT INTO sys_tenant(id,name) VALUES (?,'通知OWNER租户'),(?,'通知协作者租户')", f.tenantId(), f.viewerTenantId());
            execute(c, "INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) VALUES (?,?,'{noop}unused','通知OWNER',clock_timestamp()),(?,?,'{noop}unused','通知VIEWER',clock_timestamp())", f.ownerId(), f.ownerId()+"@example.com", f.viewerId(), f.viewerId()+"@example.com");
            execute(c, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?),(?,?,?)", Uuid7.generate(), f.tenantId(), f.ownerId(), Uuid7.generate(), f.viewerTenantId(), f.viewerId());
            execute(c, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'通知项目','sh-1',?)", f.projectId(), f.tenantId(), "inbox_"+f.projectId().toString().replace("-", ""));
            execute(c, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER'),(?,?,?,'VIEWER')", Uuid7.generate(), f.projectId(), f.ownerId(), Uuid7.generate(), f.projectId(), f.viewerId());
            execute(c, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES (?,?,?,'inbox_type','通知设备','DIRECT','STANDARD','WIFI','PUBLISHED')", f.typeId(), f.tenantId(), f.projectId());
            execute(c, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,'inbox_device','通知设备','OFFLINE')", f.deviceId(), f.tenantId(), f.projectId(), f.typeId());
            execute(c, "INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity) VALUES (?,?,?,'通知规则','HIGH_TEMPERATURE',?,'temperature','GT',30,'LT',25,'MAJOR')", f.ruleId(), f.tenantId(), f.projectId(), f.deviceId());
            execute(c, "INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,condition_state,ack_state,first_condition_at,activated_at,last_received_at,last_value) VALUES (?,?,?,?,'DEVICE',?,'HIGH_TEMPERATURE','MAJOR','ACTIVE','UNACKNOWLEDGED',clock_timestamp(),clock_timestamp(),clock_timestamp(),31)", f.instanceId(), f.tenantId(), f.projectId(), f.ruleId(), f.deviceId());
            event(c, f, f.eventId(), Instant.now().minusSeconds(60), "ACTIVATED");
            c.commit();
        }
        return f;
    }

    /** 源事件在caller连接提交，允许真实迟提交反例；PENDING也满足原不可变事件约束。 */
    private void event(Connection c, Fixture f, UUID id, Instant time, String type) throws SQLException {
        execute(c, "INSERT INTO alarm_event(id,tenant_id,project_id,instance_id,event_type,source_message_id,trace_id,value,received_at,condition_state,ack_state) VALUES (?,?,?,?,?,?,'inbox-fixture',31,?,?,'UNACKNOWLEDGED')", id, f.tenantId(), f.projectId(), f.instanceId(), type, id, time, type.equals("PENDING") ? "PENDING" : "ACTIVE");
    }

    /** 真实JWT签名；项目代次为新夹具0，跨租户VIEWER仍使用自己的租户声明。 */
    private String token(Fixture f, boolean viewer) {
        return "Bearer " + tokens.issue(new AuthenticatedPrincipal(viewer ? f.viewerId() : f.ownerId(),
                viewer ? f.viewerTenantId() : f.tenantId(), f.projectId())).value();
    }

    /** 固定当前项目REST路径。 */
    private String path(Fixture f) { return "/api/v1/projects/" + f.projectId() + "/alarm-notifications"; }

    /** 透过真实Controller参数绑定与安全链读取分页。 */
    private MvcResult page(Fixture f, boolean viewer, String cursor, int limit) throws Exception {
        var request = get(path(f)).header(HttpHeaders.AUTHORIZATION, token(f, viewer)).param("limit", Integer.toString(limit));
        if (cursor != null) request.param("cursor", cursor);
        return mvc.perform(request).andReturn();
    }

    /** 明确IDs，不提交账号字段。 */
    private MvcResult mark(Fixture f, boolean viewer, List<UUID> ids) throws Exception {
        return mvc.perform(post(path(f) + "/read").header(HttpHeaders.AUTHORIZATION, token(f, viewer))
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(java.util.Map.of("eventIds", ids)))).andReturn();
    }

    /** 计数接口的100是99+信号。 */
    private int count(Fixture f, boolean viewer) throws Exception {
        return ok(mvc.perform(get(path(f) + "/unread-count").header(HttpHeaders.AUTHORIZATION, token(f, viewer))).andReturn())
                .get("unreadCount").asInt();
    }

    /** 成功信封只能由精确200产生，不能把业务错误JSON当数据读取。 */
    private JsonNode ok(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** 稳定错误码核对同时覆盖HTTP类别。 */
    private void error(MvcResult result, int status, int code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(code);
    }

    /** 精确项目观察，不把邻居回执混入断言。 */
    private int receipts(Fixture f) throws SQLException {
        try (Connection c = owner(); PreparedStatement q = c.prepareStatement("SELECT count(*) FROM alarm_notification_read WHERE project_id=?")) {
            q.setObject(1, f.projectId());
            try (var r = q.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }

    /** 独占项目的真实REST计量行证明记录器启用，并检查归档读取不会新增计费事实。 */
    private int usageFacts(Fixture f) throws SQLException {
        try (Connection c = owner(); PreparedStatement q = c.prepareStatement(
                "SELECT count(*) FROM sys_usage_fact WHERE project_id=? AND metric='REST_API_CALL'")) {
            q.setObject(1, f.projectId());
            try (var r = q.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }

    /** 事故与事件全行字节观察确保阅读不发生ACK/CLEAR或隐式删除。 */
    private List<String> alarmFacts(Fixture f) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection c = owner()) {
            for (String table : List.of("alarm_instance", "alarm_event", "alarm_notification_delivery")) {
                try (PreparedStatement q = c.prepareStatement("SELECT row_to_json(r)::text FROM " + table + " r WHERE project_id=? ORDER BY id")) {
                    q.setObject(1, f.projectId());
                    try (var r = q.executeQuery()) { while (r.next()) result.add(table + ':' + r.getString(1)); }
                }
            }
        }
        return result;
    }

    /** 真实数据库锁等待屏障；不以sleep或Future未完成推断竞争已建立。 */
    private void awaitDatabaseLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try (Connection c = owner(); PreparedStatement q = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname='alarm_inbox_api' AND wait_event_type='Lock'")) {
            while (System.nanoTime() < deadline) {
                try (var r = q.executeQuery()) { r.next(); if (r.getInt(1) > 0) return; }
                Thread.sleep(10);
            }
        }
        throw new AssertionError("未形成预期真实项目锁竞争");
    }

    /** 并发例创建的线程池必须在AfterEach归还。 */
    private ExecutorService pool() { ExecutorService result = Executors.newFixedThreadPool(2); executors.add(result); return result; }

    /** 等待预算低于生产五秒事务上限，避免测试无限挂起。 */
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("并发屏障超时"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    /** owner仅创建/观察本类隔离事实与故障注入，不替代被测APP连接。 */
    private Connection owner() throws SQLException { return DriverManager.getConnection(DATABASE_URL, "thingslink", "thingslink"); }

    /** 时间参数显式转换，语句设置五秒预算。 */
    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement q = c.prepareStatement(sql)) {
            q.setQueryTimeout(5);
            for (int i = 0; i < args.length; i++) q.setObject(i + 1, args[i] instanceof Instant time ? java.sql.Timestamp.from(time) : args[i]);
            q.executeUpdate();
        }
    }

    /** 专库启动早于Spring属性注册。 */
    private static String startDatabase() { INBOX_POSTGRES.start(); return INBOX_POSTGRES.getJdbcUrl(); }

    /** 只释放本类线程和上下文，不清共享开发资源。 */
    @AfterEach
    void clear() throws Exception {
        afterPermit = () -> { };
        for (ExecutorService pool : executors) { pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        executors.clear(); TenantContext.clear(); RlsScopeContext.clear();
    }

    /** 本类只有领域阅读写入，关闭无关全局队列并将Flyway/APP绑同一物理数据库。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 两个入口使用同一独占库；不偷换APP运行身份。 */
        @Bean DynamicPropertyRegistrar inboxDatabase() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                // 保留生产默认的真实日计量链，归档READ与WRITE必须分别验证退化语义。
                registry.add("things-link.quota.daily-usage-recording-enabled", () -> "true");
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }
    }

    /** 一项目两租户账号，用来显式识别账号租户与项目owner tenant。 */
    private record Fixture(UUID tenantId, UUID viewerTenantId, UUID projectId, UUID ownerId, UUID viewerId,
                           UUID typeId, UUID deviceId, UUID ruleId, UUID instanceId, UUID eventId) { }
}
