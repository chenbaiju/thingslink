package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUserAssignment;
import com.things.link.enduser.domain.AppUserDevice;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectService.ProjectRoutingContext;
import com.things.link.shared.page.CursorPage;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 终端用户查询用例：项目用户列表、设备绑定概览（S11-1b）。
 *
 * <h2>读授权与写授权不同</h2>
 * 写操作（预置/分配/停用/恢复/改角色）要求 {@code ProjectRole.canManageMembers()}
 * （OWNER / ADMIN）。本服务的读操作只要<b>是项目成员</b>即可 —— 与成员列表同理，
 * 「这个项目里有谁」是协作前提，四种角色都能看（{@code enduser:read} 授予全部四角色）。
 *
 * <h2>列表只展示当前项目赋值</h2>
 * {@code app_user} 是租户级身份，但控制台「用户」列表只应出现<b>已分配本项目角色</b>的
 * 用户，而不是整个租户目录 —— 否则会把其他项目的成员（以及它们跨项目的角色关系）泄露
 * 出来。驱动表因此是 {@code app_user_role}（项目轴），再内联 {@code app_user} 取身份字段。
 */
@Service
public class EndUserQueryService {

    /** 控制台成员身份与项目权威路由入口。 */
    private final ProjectService projectService;
    /** 项目终端用户赋值只读仓储。 */
    private final AppUserRoleRepository appUserRoleRepository;
    /** 项目终端用户设备关系只读仓储。 */
    private final AppUserDeviceRepository appUserDeviceRepository;
    /** S12-2a1e 以项目权威路由建立完整事务局部 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;

    /**
     * 创建终端用户查询服务。
     *
     * @param projectService 控制台成员身份与项目权威路由入口
     * @param appUserRoleRepository 项目终端用户赋值只读仓储
     * @param appUserDeviceRepository 项目终端用户设备关系只读仓储
     * @param transactionLocalRlsScope 事务局部RLS完整范围组件
     */
    public EndUserQueryService(ProjectService projectService,
                               AppUserRoleRepository appUserRoleRepository,
                               AppUserDeviceRepository appUserDeviceRepository,
                               TransactionLocalRlsScope transactionLocalRlsScope) {
        this.projectService = projectService;
        this.appUserRoleRepository = appUserRoleRepository;
        this.appUserDeviceRepository = appUserDeviceRepository;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
    }

    /**
     * 列出当前项目内已分配角色的终端用户（游标分页）。
     *
     * @param projectId 项目 ID
     * @param cursor    上一页游标；null 表示首页
     * @param limit     单页数量
     * @return 一页终端用户角色赋值投影，按分配时刻降序
     */
    @Transactional(readOnly = true)
    public CursorPage<AppUserAssignment> list(UUID projectId, String cursor, int limit) {
        requireMembership(projectId);
        ProjectRoutingContext routing = projectService.requireRoutingContext(projectId);
        transactionLocalRlsScope.establish(routing.tenantId(), projectId);
        return appUserRoleRepository.findAssignmentsByProject(projectId, cursor, limit);
    }

    /**
     * 列出某终端用户在本项目中的设备绑定概览。
     *
     * <p>只陈述 {@code app_user_device} 里的授权事实（设备 ID + 关系角色 + 状态），不解析
     * 设备名 —— 设备名属 device 模块，S11-2b 再拼。S11-3 之前该表由绑定流程填充，返回
     * 空列表属正常。目标用户不存在或跨租户时同样返回空列表：概览端点只陈述「可见的绑定」，
     * 不泄露某个 ID 在别的租户是否存在。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @return 该用户在本项目的设备关系，按建立时间正序
     */
    @Transactional(readOnly = true)
    public List<AppUserDevice> listDeviceBindings(UUID projectId, UUID appUserId) {
        requireMembership(projectId);
        ProjectRoutingContext routing = projectService.requireRoutingContext(projectId);
        transactionLocalRlsScope.establish(routing.tenantId(), projectId);
        return appUserDeviceRepository.findByProjectAndUser(projectId, appUserId);
    }

    /** 要求调用者是项目成员（任意角色）。非成员返回 404，不泄露项目是否存在。 */
    private void requireMembership(UUID projectId) {
        projectService.requireRoleInProject(projectId);
    }

}
