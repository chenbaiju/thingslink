package com.things.link.ota.api.support;

import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * OTA HTTP边界的项目角色守卫。
 *
 * <p>这里存在的唯一理由是<b>规则只写一处</b>：{@code ota:deploy}的授予范围由
 * {@link ProjectRole#canManageMembers()}决定，控制器若各自再写一遍
 * {@code role == OWNER || role == ADMIN}，将来新增角色时两边就会分叉——
 * 而下发的按钮与接口的实际放行分叉时，症状是「按钮藏起来了但接口照样放行」，
 * 没有任何人会去点一个看不见的按钮来发现它。
 *
 * <p><b>读取判定的两层口径（与{@code docs/PERMISSIONS.md}一致，不要写成第三种说法）：</b>
 * 权限表把{@code ota:read}只授予OWNER/ADMIN；而S13-4a之前已交付的5个读取端点
 * （固件列表、固件详情、上传会话详情、发布尝试详情、信任域摘要）<b>有意</b>接受任意项目成员，
 * 收窄它们是另一次独立的产品与安全决定，因此本方法按「当前项目成员」判定。
 * 新增读取端点若要与权限表一致，必须在同一次改动里同时调整服务端与授予表，不能只改一边。
 * 未选择项目时角色为{@code null}，项目服务会先拒绝，不会出现「无角色即放行」。
 *
 * <p>本类只做项目角色判定，不替代领域服务内的二次复核，也不代表设备侧刷写或
 * 无signer时的fail-closed被解除。
 */
@Component
public class OtaAuthorization {

    /** 当前项目成员与角色解析。 */
    private final ProjectService projects;

    /**
     * 注入项目服务。
     *
     * @param projects 当前项目成员与角色解析
     */
    public OtaAuthorization(ProjectService projects) {
        this.projects = projects;
    }

    /**
     * 读取守卫：要求当前项目成员资格。
     *
     * @param projectId 项目标识
     * @return 解析出的项目角色，供调用方进一步收窄
     */
    public ProjectRole requireRead(UUID projectId) {
        return projects.requireRoleInProject(projectId);
    }

    /**
     * 写入守卫：判定是否具备OTA部署资格。
     *
     * <p>返回值而不是直接抛异常，是为了让各控制器继续抛出自己模块既有的
     * {@code FORBIDDEN}错误码：错误码属于对外契约，不因规则收敛而改变。
     *
     * @param projectId 项目标识
     * @return 当前角色是否满足 {@link ProjectRole#canManageMembers()}
     */
    public boolean mayDeploy(UUID projectId) {
        return projects.requireRoleInProject(projectId).canManageMembers();
    }
}
