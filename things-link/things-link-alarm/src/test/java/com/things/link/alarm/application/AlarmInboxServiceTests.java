package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmInboxEvent;
import com.things.link.alarm.domain.AlarmInboxItem;
import com.things.link.alarm.domain.AlarmInboxPage;
import com.things.link.alarm.domain.AlarmInboxRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR0093：验证个人回执权限、原子前检、固定窗口和游标；提交乱序由真实PG整合验证。 */
class AlarmInboxServiceTests {
    /** 固定数据库时刻，明确不使用测试进程当前时间。 */
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");
    /** 当前项目。 */
    private final UUID projectId = UUID.randomUUID();
    /** 项目真实租户，故意与账号自己的租户不同。 */
    private final UUID ownerTenant = UUID.randomUUID();
    /** 认证账号。 */
    private final UUID accountId = UUID.randomUUID();
    /** 显式事件身份。 */
    private final UUID eventId = UUID.randomUUID();
    /** 阅读事实替身。 */
    private AlarmInboxRepository repository;
    /** 成员授权替身。 */
    private ProjectService projects;
    /** 持续写许可替身。 */
    private ProjectLifecycleAccessService lifecycle;
    /** 被测用例。 */
    private AlarmInboxService service;

    /** 所有测试默认VIEWER，证明个人阅读不隐含维护权限。 */
    @BeforeEach
    void setUp() {
        repository = mock(AlarmInboxRepository.class);
        projects = mock(ProjectService.class);
        lifecycle = mock(ProjectLifecycleAccessService.class);
        service = new AlarmInboxService(repository, projects, lifecycle);
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, accountId));
        when(projects.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        when(projects.requireProjectTenant(projectId)).thenReturn(ownerTenant);
        when(repository.databaseTime()).thenReturn(NOW);
    }

    /** 清理线程身份，避免下一测试继承账号范围。 */
    @AfterEach
    void clearIdentity() {
        TenantContext.clear();
    }

    /** 项目冻结前后都必须验证身份；回执用项目归属租户而非协作者自己的租户。 */
    @Test
    void viewerMarksUsingOwnerTenantAfterLockedMembershipRecheck() {
        when(repository.findActivatedEvents(ownerTenant, projectId, List.of(eventId)))
                .thenReturn(List.of(new AlarmInboxEvent(eventId, NOW)));
        when(repository.insertReads(ownerTenant, projectId, accountId, List.of(eventId), NOW)).thenReturn(1);
        assertThat(service.markRead(projectId, List.of(eventId))).isEqualTo(1);
        InOrder order = inOrder(projects, lifecycle, repository);
        order.verify(projects).requireRoleInProject(projectId);
        order.verify(projects).requireProjectTenant(projectId);
        order.verify(lifecycle).requireActiveForWrite(ownerTenant, projectId);
        order.verify(projects).requireRoleInProject(projectId);
        order.verify(repository).databaseTime();
        order.verify(repository).findActivatedEvents(ownerTenant, projectId, List.of(eventId));
        order.verify(repository).insertReads(ownerTenant, projectId, accountId, List.of(eventId), NOW);
    }

    /** 归档失败早于事件和参数判别；该顺序不能泄漏不可见事件身份。 */
    @Test
    void frozenPermissionPrecedesInvalidBatchAndEventAccess() {
        BusinessException failure = new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        doThrow(failure).when(lifecycle).requireActiveForWrite(ownerTenant, projectId);
        assertThatThrownBy(() -> service.markRead(projectId, null)).isSameAs(failure);
        verifyNoInteractions(repository);
    }

    /** 等待项目锁期间移除成员，锁后必须拒绝整个请求。 */
    @Test
    void removedMemberFailsAfterLockBeforeDatabaseFacts() {
        when(projects.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER)
                .thenThrow(new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
        assertError(() -> service.markRead(projectId, List.of(eventId)), ProjectErrorCode.PROJECT_NOT_FOUND.code());
        verify(lifecycle).requireActiveForWrite(ownerTenant, projectId);
        verifyNoInteractions(repository);
    }

    /** 空数组、空元素和原始数组过量即使去重后很小仍应拒绝。 */
    @Test
    void rejectsInvalidRawBatchesBeforeLookingUpEvents() {
        assertError(() -> service.markRead(projectId, null), 40010);
        assertError(() -> service.markRead(projectId, List.of()), 40010);
        assertError(() -> service.markRead(projectId, Arrays.asList(eventId, null)), 40010);
        assertError(() -> service.markRead(projectId, Collections.nCopies(101, eventId)), 40010);
        verifyNoInteractions(repository);
    }

    /** 去重仍保留明确ID，不把重复标记误报为错误。 */
    @Test
    void deduplicatesExplicitIdsAndAllowsAlreadyReadResult() {
        when(repository.findActivatedEvents(ownerTenant, projectId, List.of(eventId)))
                .thenReturn(List.of(new AlarmInboxEvent(eventId, NOW)));
        assertThat(service.markRead(projectId, List.of(eventId, eventId))).isZero();
        verify(repository).insertReads(ownerTenant, projectId, accountId, List.of(eventId), NOW);
    }

    /** 一项不可见时不允许写入其他合法项。 */
    @Test
    void missingEventRejectsWholeBatchBeforeInsert() {
        UUID missing = UUID.randomUUID();
        when(repository.findActivatedEvents(ownerTenant, projectId, List.of(eventId, missing)))
                .thenReturn(List.of(new AlarmInboxEvent(eventId, NOW)));
        assertError(() -> service.markRead(projectId, List.of(eventId, missing)), 40011);
        verify(repository, never()).insertReads(any(), any(), any(), anyList(), any());
    }

    /** 事件合法但过窗口必须提示刷新，不能提交前半批或称其不存在。 */
    @Test
    void expiredEventRejectsWholeBatchBeforeInsert() {
        when(repository.findActivatedEvents(ownerTenant, projectId, List.of(eventId)))
                .thenReturn(List.of(new AlarmInboxEvent(eventId, NOW.minus(Duration.ofDays(30)).minusNanos(1))));
        assertError(() -> service.markRead(projectId, List.of(eventId)), 40012);
        verify(repository, never()).insertReads(any(), any(), any(), anyList(), any());
    }

    /** 未来时间不落入当前数据库窗口，也不能被标记。 */
    @Test
    void futureEventRequiresRefresh() {
        when(repository.findActivatedEvents(ownerTenant, projectId, List.of(eventId)))
                .thenReturn(List.of(new AlarmInboxEvent(eventId, NOW.plusNanos(1))));
        assertError(() -> service.markRead(projectId, List.of(eventId)), 40012);
    }

    /** 三十天闭区间的起点与终点均合法，时钟每请求只取一次。 */
    @Test
    void acceptsBothClosedWindowBoundaries() {
        UUID second = UUID.randomUUID();
        List<UUID> ids = List.of(eventId, second);
        when(repository.findActivatedEvents(ownerTenant, projectId, ids)).thenReturn(List.of(
                new AlarmInboxEvent(eventId, NOW.minus(Duration.ofDays(30))), new AlarmInboxEvent(second, NOW)));
        service.markRead(projectId, ids);
        verify(repository).insertReads(ownerTenant, projectId, accountId, ids, NOW);
        verify(repository, times(1)).databaseTime();
    }

    /** 角标仅请求当前数据库窗口，不为精确总数另作无界查询。 */
    @Test
    void unreadSummaryUsesAuthenticatedAccountAndDatabaseWindow() {
        when(repository.countUnread(ownerTenant, projectId, accountId, NOW.minus(Duration.ofDays(30)), NOW)).thenReturn(100);
        assertThat(service.unreadCount(projectId)).isEqualTo(100);
        verify(repository, times(1)).databaseTime();
        verifyNoInteractions(lifecycle);
    }

    /** 存储失败必须传播，界面应显示未知而不是误报零未读。 */
    @Test
    void unreadStorageFailureDoesNotBecomeZero() {
        when(repository.countUnread(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("不可用"));
        assertThatThrownBy(() -> service.unreadCount(projectId)).isInstanceOf(IllegalStateException.class);
    }

    /** 首页保留已读项并多查一条判断后页，查询动作没有阅读副作用。 */
    @Test
    void pageKeepsReadItemsAndUsesOneDatabaseTime() {
        when(repository.page(eq(ownerTenant), eq(projectId), eq(accountId), any(), any(), any(), any(), eq(2)))
                .thenReturn(List.of(item(NOW, true), item(NOW.minusSeconds(1), false)));
        AlarmInboxPage page = service.page(projectId, null, 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().read()).isTrue();
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(page.windowEnd()).isEqualTo(NOW);
        assertThat(page.windowStart()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        verify(repository, times(1)).databaseTime();
        verify(repository, never()).insertReads(any(), any(), any(), anyList(), any());
    }

    /** 后页固定首页窗口，不被后续请求时间滑动而改变。 */
    @Test
    void nextPageRetainsInitialWindowAndPosition() {
        String cursor = cursor(NOW, NOW.minusSeconds(10), projectId, accountId);
        when(repository.databaseTime()).thenReturn(NOW.plusSeconds(60));
        AlarmInboxPage page = service.page(projectId, cursor, 20);
        assertThat(page.windowEnd()).isEqualTo(NOW);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        verify(repository).page(ownerTenant, projectId, accountId, NOW.minus(Duration.ofDays(30)), NOW,
                NOW.minusSeconds(10), eventId, 21);
    }

    /** 五分钟恰好边界仍合法，超过一纳秒必须明确刷新。 */
    @Test
    void cursorExpiresOnlyAfterFiveMinutes() {
        String cursor = cursor(NOW, NOW, projectId, accountId);
        when(repository.databaseTime()).thenReturn(NOW.plusSeconds(300));
        service.page(projectId, cursor, 20);
        clearInvocations(repository);
        when(repository.databaseTime()).thenReturn(NOW.plusSeconds(300).plusNanos(1));
        assertError(() -> service.page(projectId, cursor, 20), 40012);
        verify(repository, never()).page(any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    /** 畸形格式、未来窗口、窗口外位置和身份串用都拒绝，不返回其他账号页面。 */
    @Test
    void rejectsMalformedFutureOutOfWindowAndCrossIdentityCursors() {
        List<String> invalid = List.of("", "%%%", "a".repeat(1025),
                cursor(NOW.plusSeconds(1), NOW, projectId, accountId),
                cursor(NOW, NOW.plusSeconds(1), projectId, accountId),
                cursor(NOW, NOW.minus(Duration.ofDays(31)), projectId, accountId),
                cursor(NOW, NOW, UUID.randomUUID(), accountId),
                cursor(NOW, NOW, projectId, UUID.randomUUID()));
        for (String cursor : invalid) assertError(() -> service.page(projectId, cursor, 20), 40010);
        verify(repository, never()).page(any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    /** 无效分页大小不访问告警数据库；范围由服务保护内部调用。 */
    @Test
    void invalidPageSizeNeverQueriesFacts() {
        assertError(() -> service.page(projectId, null, 0), 40010);
        assertError(() -> service.page(projectId, null, 101), 40010);
        verifyNoInteractions(repository);
    }

    /** 不可见项目即使传坏游标也先按项目不可见拒绝。 */
    @Test
    void projectReadAuthorizationPrecedesCursorParsing() {
        when(projects.requireRoleInProject(projectId)).thenThrow(new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
        assertError(() -> service.page(projectId, "%%%", 20), ProjectErrorCode.PROJECT_NOT_FOUND.code());
        assertError(() -> service.unreadCount(projectId), ProjectErrorCode.PROJECT_NOT_FOUND.code());
        verifyNoInteractions(repository);
    }

    /** @param time 显示时刻 @param read 个人已读状态 @return 不带敏感字段的事件投影 */
    private AlarmInboxItem item(Instant time, boolean read) {
        return new AlarmInboxItem(eventId, UUID.randomUUID(), time, AlarmRule.Severity.CRITICAL, "温度过高", read);
    }

    /** @param end 初页窗口 @param time 末行位置 @param project 绑定项目 @param account 绑定账号 @return 测试游标 */
    private String cursor(Instant end, Instant time, UUID project, UUID account) {
        String value = "1|" + project + "|" + account + "|" + end + "|" + time + "|" + eventId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** @param action 受保护操作 @param code ADR0093冻结业务码 */
    private static void assertError(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode().code()).isEqualTo(code));
    }
}
