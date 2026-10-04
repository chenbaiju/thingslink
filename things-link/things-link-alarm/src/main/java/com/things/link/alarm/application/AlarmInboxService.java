package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmErrorCode;
import com.things.link.alarm.domain.AlarmInboxEvent;
import com.things.link.alarm.domain.AlarmInboxItem;
import com.things.link.alarm.domain.AlarmInboxPage;
import com.things.link.alarm.domain.AlarmInboxRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** ADR0093：个人阅读事实独立于告警ACK，窗口与阅读时刻只使用数据库实际时钟。 */
@Service
public class AlarmInboxService {
    /** 三十天只限定展示范围，不能被误作回执的自动存储回收策略。 */
    private static final Duration WINDOW = Duration.ofDays(30);
    /** 浏览位置五分钟后必须刷新，避免长期持有过时窗口。 */
    private static final Duration CURSOR_LIFETIME = Duration.ofMinutes(5);
    /** 回执与不可变事件持久化端口。 */
    private final AlarmInboxRepository repository;
    /** 成员与项目真实租户公开端口。 */
    private final ProjectService projectService;
    /** 原事务ACTIVE共享许可，阻止冻结后的回执写入。 */
    private final ProjectLifecycleAccessService lifecycle;

    /** @param repository 阅读事实端口 @param projectService 成员端口 @param lifecycle 原事务持续许可 */
    public AlarmInboxService(AlarmInboxRepository repository, ProjectService projectService,
                              ProjectLifecycleAccessService lifecycle) {
        this.repository = repository;
        this.projectService = projectService;
        this.lifecycle = lifecycle;
    }

    /**
     * @param projectId 当前项目 @param cursor 可空浏览位置 @param limit 每页1至100条
     * @return 全部窗口事件及个人已读标志，不将分页过程视为阅读动作
     */
    @Transactional(readOnly = true)
    public AlarmInboxPage page(UUID projectId, String cursor, int limit) {
        projectService.requireRoleInProject(projectId);
        UUID ownerTenant = projectService.requireProjectTenant(projectId);
        UUID accountId = TenantContext.require().accountId();
        if (limit < 1 || limit > 100) throw invalid();
        Instant now = repository.databaseTime();
        Position position = decode(cursor, projectId, accountId, now);
        Instant end = position == null ? now : position.windowEnd();
        Instant start = end.minus(WINDOW);
        List<AlarmInboxItem> rows = repository.page(ownerTenant, projectId, accountId, start, end,
                position == null ? null : position.time(), position == null ? null : position.id(), limit + 1);
        boolean hasMore = rows.size() > limit;
        List<AlarmInboxItem> items = hasMore ? rows.subList(0, limit) : rows;
        String next = null;
        if (hasMore) {
            AlarmInboxItem last = items.getLast();
            next = encode(projectId, accountId, end, last.receivedAt(), last.eventId());
        }
        return new AlarmInboxPage(items, next, hasMore, start, end);
    }

    /** @param projectId 当前项目 @return 0至100，100表示99+而不是精确全量总数 */
    @Transactional(readOnly = true)
    public int unreadCount(UUID projectId) {
        projectService.requireRoleInProject(projectId);
        UUID ownerTenant = projectService.requireProjectTenant(projectId);
        UUID accountId = TenantContext.require().accountId();
        Instant now = repository.databaseTime();
        return repository.countUnread(ownerTenant, projectId, accountId, now.minus(WINDOW), now);
    }

    /**
     * @param projectId 当前项目 @param eventIds 原始数组1至100个非空事件身份
     * @return 当次新增回执数量，已读合法事件幂等返回零
     */
    @Transactional
    public int markRead(UUID projectId, List<UUID> eventIds) {
        projectService.requireRoleInProject(projectId);
        UUID ownerTenant = projectService.requireProjectTenant(projectId);
        lifecycle.requireActiveForWrite(ownerTenant, projectId);
        // VIEWER只能写自己的阅读事实；锁后重验成员，避免等待项目锁期间被移除后继续写入。
        projectService.requireRoleInProject(projectId);
        UUID accountId = TenantContext.require().accountId();
        if (eventIds == null || eventIds.isEmpty() || eventIds.size() > 100
                || eventIds.stream().anyMatch(value -> value == null)) throw invalid();
        List<UUID> distinct = eventIds.stream().distinct().toList();
        Instant now = repository.databaseTime();
        List<AlarmInboxEvent> events = repository.findActivatedEvents(ownerTenant, projectId, distinct);
        if (events.size() != distinct.size()) throw new BusinessException(AlarmErrorCode.INBOX_EVENT_NOT_FOUND);
        Instant start = now.minus(WINDOW);
        if (events.stream().anyMatch(event -> event.receivedAt().isBefore(start) || event.receivedAt().isAfter(now))) {
            throw new BusinessException(AlarmErrorCode.INBOX_WINDOW_EXPIRED);
        }
        // 整批可见性与窗口校验先完成；任何无效ID都不能留下前半批回执。
        return repository.insertReads(ownerTenant, projectId, accountId, distinct, now);
    }

    /** @return 参数或浏览位置错误；不复用告警状态维护的错误码 */
    private static BusinessException invalid() {
        return new BusinessException(AlarmErrorCode.INBOX_INVALID);
    }

    /**
     * @param cursor 原样回传的位置 @param projectId 当前项目 @param accountId 当前账号 @param now 数据库实际时刻
     * @return 已检查身份与窗口的浏览位置；首页返回空
     */
    private static Position decode(String cursor, UUID projectId, UUID accountId, Instant now) {
        if (cursor == null) return null;
        Position position;
        try {
            if (cursor.isBlank() || cursor.length() > 1024) throw new IllegalArgumentException("游标长度不合法");
            String[] fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 6 || !fields[0].equals("1")
                    || !UUID.fromString(fields[1]).equals(projectId)
                    || !UUID.fromString(fields[2]).equals(accountId)) throw new IllegalArgumentException("游标身份不符");
            position = new Position(Instant.parse(fields[3]), Instant.parse(fields[4]), UUID.fromString(fields[5]));
            if (position.windowEnd().isAfter(now) || position.time().isAfter(position.windowEnd())
                    || position.time().isBefore(position.windowEnd().minus(WINDOW))) {
                throw new IllegalArgumentException("游标窗口不合法");
            }
        } catch (RuntimeException exception) {
            throw invalid();
        }
        if (position.windowEnd().plus(CURSOR_LIFETIME).isBefore(now)) {
            throw new BusinessException(AlarmErrorCode.INBOX_WINDOW_EXPIRED);
        }
        return position;
    }

    /**
     * @param projectId 当前项目 @param accountId 当前账号 @param end 初页窗口末端
     * @param time 当前页末行时间 @param id 当前页末行身份 @return 不透明浏览位置
     */
    private static String encode(UUID projectId, UUID accountId, Instant end, Instant time, UUID id) {
        String value = "1|" + projectId + "|" + accountId + "|" + end + "|" + time + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** @param windowEnd 初页数据库窗口末端 @param time 上页末行时间 @param id 上页末行身份 */
    private record Position(Instant windowEnd, Instant time, UUID id) {
    }
}
