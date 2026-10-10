package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppNotificationContact;
import com.things.link.enduser.domain.AppNotificationContactRepository;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Console按项目配置联系号码，App只读本人；不变更全局身份或发送任何通知。 */
@Service
public class EndUserNotificationContactService {
    private final ProjectService projects;
    private final ProjectLifecycleAccessService lifecycle;
    private final TransactionLocalRlsScope scope;
    private final AppUserRepository users;
    private final AppUserRoleRepository roles;
    private final AppNotificationContactRepository contacts;
    private final AppAccountService accounts;
    private final AuditLogService audit;
    /**
     * @param projects 项目归属与当前成员权限
     * @param lifecycle 项目读写生命周期
     * @param scope 原事务双轴范围
     * @param users 稳定用户锁与租户归属
     * @param roles 项目角色存在性
     * @param contacts 本域联系事实
     * @param accounts App本人资格
     * @param audit 不包含号码的同事务审计
     */
    public EndUserNotificationContactService(ProjectService projects, ProjectLifecycleAccessService lifecycle,
            TransactionLocalRlsScope scope, AppUserRepository users, AppUserRoleRepository roles,
            AppNotificationContactRepository contacts, AppAccountService accounts, AuditLogService audit) {
        this.projects=projects;this.lifecycle=lifecycle;this.scope=scope;this.users=users;this.roles=roles;
        this.contacts=contacts;this.accounts=accounts;this.audit=audit;
    }
    /**
     * 管理员读取本项目已分配账号的配置，归档项目仍可读取。
     * @param projectId 当前项目
     * @param userId 已分配终端用户
     * @return 当前号码及版本；无权限与不可见目标保持既有错误边界
     */
    @Transactional(readOnly = true)
    public AppNotificationContact read(UUID projectId, UUID userId) {
        requireManager(projectId);
        UUID tenantId=projects.requireProjectTenant(projectId);
        scope.establish(tenantId,projectId);
        requireTarget(tenantId,projectId,userId);
        return contacts.read(tenantId,projectId,userId);
    }
    /**
     * 在项目许可及用户锁内更改号码，只写本项目，旧版本冲突不覆盖。
     * @param projectId 当前项目
     * @param userId 目标终端用户
     * @param voice 电话号码或空
     * @param sms 短信号码或空
     * @param revision 读取版本
     * @return 新事实；相同配置不推进版本或制造审计
     */
    @Transactional
    public AppNotificationContact update(UUID projectId, UUID userId, String voice, String sms, long revision) {
        requireManager(projectId);
        UUID tenantId=projects.requireProjectTenant(projectId);
        scope.establish(tenantId,projectId);
        lifecycle.requireActiveForWrite(tenantId,projectId);
        requireManager(projectId);
        users.lockByIdAndTenant(tenantId,userId).orElseThrow(EndUserNotificationContactService::missing);
        requireTarget(tenantId,projectId,userId);
        validateNumber(voice);validateNumber(sms);
        var previous=contacts.read(tenantId,projectId,userId);
        if(previous.revision()!=revision) throw conflict();
        if(Objects.equals(previous.voiceNumber(),voice)&&Objects.equals(previous.smsNumber(),sms))return previous;
        if(revision==Long.MAX_VALUE)throw conflict();
        var next=new AppNotificationContact(voice,sms,revision+1);
        contacts.save(tenantId,projectId,userId,next);
        audit.record(new AuditLogEntry(tenantId,projectId,TenantContext.require().accountId(),
                "app_notification_contact",userId,"END_USER_NOTIFICATION_CONTACT_UPDATED",
                Map.of("revision",Long.toString(next.revision()),"voiceConfigured",voice!=null,"smsConfigured",sms!=null)));
        return next;
    }
    /**
     * App只读可信JWT本人与当前项目的号码，不能传入其他身份。
     * @param tenantId 已确权租户
     * @param projectId 已确权项目
     * @param userId 已确权终端用户
     * @return 当前项目的联系配置，不代表真实渠道已接入
     */
    @Transactional(readOnly = true)
    public AppNotificationContact readSelf(UUID tenantId, UUID projectId, UUID userId) {
        accounts.read(tenantId,projectId,userId);
        return contacts.read(tenantId,projectId,userId);
    }
    /** 号码是私密联系信息，普通目录查看权限不足以读取。 */
    private void requireManager(UUID projectId) {
        if(!projects.requireRoleInProject(projectId).canManageMembers()) {
            throw new BusinessException(EndUserErrorCode.END_USER_MANAGE_FORBIDDEN);
        }
    }
    /** 只承认已分配本项目的同租户身份，停用不会把管理范围扩大到其他项目。 */
    private void requireTarget(UUID tenantId, UUID projectId, UUID userId) {
        if(users.findByIdAndTenant(tenantId,userId).isEmpty()||roles.findByProjectAndUser(projectId,userId).isEmpty())throw missing();
    }
    /** 格式检查不构成运营商验证，不截断或猜测国家码。 */
    private static void validateNumber(String number) {
        if(number!=null&&!number.matches("\\+[1-9][0-9]{6,14}"))throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
    /** 目标不可见时不区分不存在、跨租户与未分配角色。 */
    private static BusinessException missing(){return new BusinessException(EndUserErrorCode.END_USER_ROLE_NOT_FOUND);}
    /** 旧版本不得覆盖其他管理员决定。 */
    private static BusinessException conflict(){return new BusinessException(EndUserErrorCode.NOTIFICATION_CONTACT_CONFLICT);}
}
