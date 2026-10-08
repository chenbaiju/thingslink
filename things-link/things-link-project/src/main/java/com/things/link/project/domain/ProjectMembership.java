package com.things.link.project.domain;

import com.things.link.shared.authz.ProjectRole;
import com.things.link.project.domain.plan.PlanIdentity;

/**
 * 「某个账号 + 他参与的某个项目 + 他在其中的角色」。
 *
 * <p>项目列表页要同时显示项目信息与「我是什么角色」，分两次查询会产生 N+1；
 * 合成一个投影，一条 SQL 取回。
 *
 * @param project 项目
 * @param role    当前账号在该项目中的角色
 * @param subscribedPlan 项目归属租户的订阅档位；仅有效租户成员可见，缺失不代表免费版
 */
public record ProjectMembership(Project project, ProjectRole role, PlanIdentity subscribedPlan) {

    /** 创建、编辑和授权查询不读取订阅，保留原有投影构造方式。 */
    public ProjectMembership(Project project, ProjectRole role) {
        this(project, role, null);
    }
}
