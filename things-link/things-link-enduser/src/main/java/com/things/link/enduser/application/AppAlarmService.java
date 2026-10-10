package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppAlarm;
import com.things.link.enduser.domain.AppAlarmQuery;
import com.things.link.enduser.domain.AppAlarmRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.support.query.SignedQueryCursorCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 独立于看板的只读告警用例；角色与设备授权均按查询时事实复验。 */
@Service
public class AppAlarmService {
    private static final String PURPOSE = "APP_ALARM_HISTORY";
    private final AppUserRoleRepository roles;
    private final AppAlarmRepository alarms;
    private final SignedQueryCursorCodec cursors;
    /** @param roles 项目角色 @param alarms 授权告警仓储 @param cursors 身份过滤绑定游标 */
    public AppAlarmService(AppUserRoleRepository roles, AppAlarmRepository alarms, SignedQueryCursorCodec cursors) {
        this.roles=roles; this.alarms=alarms; this.cursors=cursors;
    }
    /**
     * 读取授权事故页；不要求看板、模型版本、通知偏好或推送资格。
     * @param identity 可信令牌身份与项目代次
     * @param query 可选筛选；时间必须成对且在366天内
     * @param cursor 绑定身份/条件的签名游标
     * @param limit 页容量，1至50
     * @return 告警页，失败不能解释成空集合
     */
    @Transactional(readOnly = true)
    public CursorPage<AppAlarm> list(AppAuthenticatedPrincipal identity, AppAlarmQuery query, String cursor, int limit) {
        requireRole(identity);
        validate(query, limit);
        String binding = identity.tenantId()+"|"+identity.projectId()+"|"+identity.appUserId()+"|"+identity.projectGeneration()
                +"|"+query.deviceId()+"|"+query.severity()+"|"+query.conditionState()+"|"+query.from()+"|"+query.to()+"|"+limit;
        var anchor = cursors.decode(cursor, PURPOSE, binding);
        List<AppAlarm> rows = alarms.list(identity.tenantId(), identity.projectId(), identity.appUserId(), query,
                anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null), limit+1);
        if (rows.size()<=limit) return CursorPage.last(rows);
        List<AppAlarm> page = List.copyOf(rows.subList(0,limit));
        AppAlarm last = page.getLast();
        return CursorPage.of(page,cursors.encode(PURPOSE,binding,last.firstConditionAt(),last.id()));
    }
    /**
     * 读取单项授权告警，不区分不存在、设备失权或跨范围。
     * @param identity 可信令牌身份
     * @param id 事故标识
     * @return 公开告警事实；不可见统一404/60060
     */
    @Transactional(readOnly = true)
    public AppAlarm detail(AppAuthenticatedPrincipal identity, UUID id) {
        requireRole(identity);
        return alarms.detail(identity.tenantId(),identity.projectId(),identity.appUserId(),id)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_ALARM_NOT_FOUND));
    }
    /** 失效角色使用访问失效401，不泄漏是否存在目标事故。 */
    private void requireRole(AppAuthenticatedPrincipal identity) {
        var role = roles.findByProjectAndUser(identity.projectId(),identity.appUserId());
        if (role.isEmpty() || role.get().status()!=AppUserRole.Status.ACTIVE)
            throw new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
    }
    /** 只接受闭集和有限时间窗；缺省时间表示全部，不创建隐式时间游标。 */
    private static void validate(AppAlarmQuery query,int limit) {
        if (query==null || limit<1 || limit>50
                || (query.severity()!=null && !Set.of("CRITICAL","MAJOR","MINOR","WARNING","INFO").contains(query.severity()))
                || (query.conditionState()!=null && !Set.of("ACTIVE","CLEARED").contains(query.conditionState()))
                || ((query.from()==null)!=(query.to()==null))) throw invalid();
        if (query.from()!=null && (!query.from().isBefore(query.to()) || query.from().isBefore(Instant.EPOCH)
                || query.to().isAfter(Instant.parse("9999-12-31T23:59:59Z"))
                || Duration.between(query.from(),query.to()).compareTo(Duration.ofDays(366))>0)) throw invalid();
    }
    /** 统一参数错误，不回显签名游标或身份。 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER,"告警筛选参数不合法");
    }
}
