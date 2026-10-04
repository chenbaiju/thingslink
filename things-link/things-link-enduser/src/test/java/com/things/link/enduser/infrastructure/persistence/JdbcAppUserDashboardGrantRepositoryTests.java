package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.AppUserDashboardGrant;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 运行访问冻结§4.2：有界稳定键集分页及不可信游标防线；SQL/RLS真实执行另由Bootstrap验收。 */
class JdbcAppUserDashboardGrantRepositoryTests {
    /** 只观察参数与SQL结构，不以mock冒充数据库RLS或排序证据。 */
    private JdbcTemplate jdbc;
    /** 被测分页仓储。 */
    private JdbcAppUserDashboardGrantRepository repository;
    /** 完整范围第一轴。 */
    private UUID tenant;
    /** 完整范围第二轴。 */
    private UUID project;
    /** 查询仅限当前用户，不按App角色自动扩展范围。 */
    private UUID user;

    /** 每例新SQL记录器，非法请求必须在任何数据库调用前拒绝。 */
    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        repository = new JdbcAppUserDashboardGrantRepository(jdbc);
        tenant = UUID.randomUUID();
        project = UUID.randomUUID();
        user = UUID.randomUUID();
    }

    /** limit+1只用于探测后页，返回游标取最后一条实际交付记录而不是探测行。 */
    @Test
    void firstPageUsesStableDashboardCursorAndBoundedLookahead() {
        AppUserDashboardGrant first = grant("00000000-0000-0000-0000-000000000001");
        AppUserDashboardGrant second = grant("00000000-0000-0000-0000-000000000002");
        stubRows(List.of(first, second));
        CursorPage<AppUserDashboardGrant> page = repository.list(tenant, project, user, null, 1);
        assertThat(page.items()).containsExactly(first);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(Cursor.encode(first.dashboardId().toString()));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), JdbcAppUserDashboardGrantRepositoryTests.<RowMapper<AppUserDashboardGrant>>matcher(),
                arguments.capture());
        assertThat(sql.getValue()).contains("tenant_id=? AND project_id=? AND app_user_id=?", "ORDER BY dashboard_id ASC LIMIT ?")
                .doesNotContain("JOIN", "OFFSET", "dashboard_id >");
        assertThat(arguments.getValue()).containsExactly(tenant, project, user, 2);
    }

    /** 游标解码为UUID绑定参数，不能拼进SQL；已到末页不返回伪后续游标。 */
    @Test
    void continuationUsesTypedExclusiveKeyAndReturnsLastPage() {
        UUID after = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        AppUserDashboardGrant grant = grant("00000000-0000-0000-0000-00000000000b");
        stubRows(List.of(grant));
        CursorPage<AppUserDashboardGrant> page = repository.list(tenant, project, user, Cursor.encode(after.toString()), 200);
        assertThat(page.items()).containsExactly(grant);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), JdbcAppUserDashboardGrantRepositoryTests.<RowMapper<AppUserDashboardGrant>>matcher(),
                arguments.capture());
        assertThat(sql.getValue()).contains("AND dashboard_id > ?", "ORDER BY dashboard_id ASC LIMIT ?")
                .doesNotContain(after.toString(), "JOIN", "OFFSET");
        assertThat(arguments.getValue()).containsExactly(tenant, project, user, after, 201);
    }

    /** 没有授权历史时返回真实空末页，而不制造默认ACTIVE grant。 */
    @Test
    void emptyPageHasNoContinuation() {
        stubRows(List.of());
        assertThat(repository.list(tenant, project, user, null, 50)).isEqualTo(CursorPage.last(List.of()));
    }

    /** 当前授权只限定候选ID的ACTIVE/READ集合，完整范围和全部ID均使用绑定参数。 */
    @Test
    void activeIntersectionUsesOnlyBoundedCandidateIds() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(List.of(second));
        assertThat(repository.findActiveDashboardIds(tenant, project, user, List.of(first, second)))
                .isEqualTo(Set.of(second));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), eq(UUID.class), arguments.capture());
        assertThat(sql.getValue()).contains("tenant_id=? AND project_id=? AND app_user_id=?",
                        "status='ACTIVE' AND permission='READ'", "dashboard_id IN (", "?,?")
                .doesNotContain(first.toString(), second.toString(), "JOIN", "OFFSET");
        assertThat(arguments.getValue()).containsExactly(tenant, project, user, first, second);
    }

    /** 空候选不用扫描用户授权；超过5项、重复和null内部候选均在SQL前拒绝。 */
    @Test
    void emptyOrInvalidActiveCandidatesNeverQueryAllUserGrants() {
        assertThat(repository.findActiveDashboardIds(tenant, project, user, List.of())).isEmpty();
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> repository.findActiveDashboardIds(tenant, project, user, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findActiveDashboardIds(tenant, project, user, List.of(id, id)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findActiveDashboardIds(tenant, project, user, Arrays.asList(id, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findActiveDashboardIds(tenant, project, user,
                List.of(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    /** 上下界均由仓储防御，不能仅依赖Controller保证数据库查询预算。 */
    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 201, Integer.MAX_VALUE})
    void invalidLimitDoesNotReachDatabase(int limit) {
        assertInvalid(() -> repository.list(tenant, project, user, null, limit));
        verifyNoInteractions(jdbc);
    }

    /** 原始或非法Base64，以及填充/空游标均不接受为首页或SQL片段。 */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-a-cursor", "00000000-0000-0000-0000-00000000000a",
            "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
    void invalidOpaqueCursorDoesNotReachDatabase(String cursor) {
        assertInvalid(() -> repository.list(tenant, project, user, cursor, 50));
        verifyNoInteractions(jdbc);
    }

    /** 即使Base64合法，内部UUID缩写、大小写别名、空白和SQL载荷仍统一拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = {"0-0-0-0-1", "00000000-0000-0000-0000-00000000000A",
            "00000000-0000-0000-0000-00000000000a ", "1'; DROP TABLE app_user_dashboard;--"})
    void nonCanonicalCursorPayloadDoesNotReachDatabase(String payload) {
        assertInvalid(() -> repository.list(tenant, project, user, Cursor.encode(payload), 50));
        verifyNoInteractions(jdbc);
    }

    /** JDBC返回值仅提供分页算法输入，不替换生产UUID游标解析与参数绑定。 */
    private void stubRows(List<AppUserDashboardGrant> rows) {
        doReturn(rows).when(jdbc).query(anyString(), JdbcAppUserDashboardGrantRepositoryTests.<RowMapper<AppUserDashboardGrant>>matcher(),
                any(Object[].class));
    }

    /** 泛型matcher固定RowMapper类型，避免JdbcTemplate重载产生模糊调用或原始类型警告。 */
    private static <T> T matcher() {
        return any();
    }

    /** 分页错误保持公共10001，与grant业务语法60026不同。 */
    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
    }

    /** 仅以稳定DashboardID区分测试行，不借状态、更新时间改变排序语义。 */
    private AppUserDashboardGrant grant(String dashboardId) {
        UUID actor = UUID.randomUUID();
        return new AppUserDashboardGrant(UUID.randomUUID(), tenant, project, user, UUID.fromString(dashboardId),
                AppUserDashboardGrant.Status.ACTIVE, 1, Instant.EPOCH, Instant.EPOCH, null, actor, actor, null);
    }
}
