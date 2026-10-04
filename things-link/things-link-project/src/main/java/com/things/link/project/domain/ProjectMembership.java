package com.things.link.project.domain;

import com.things.link.shared.authz.ProjectRole;

/**
 * 「某个账号 + 他参与的某个项目 + 他在其中的角色」。
 *
 * <p>项目列表页要同时显示项目信息与「我是什么角色」，分两次查询会产生 N+1；
 * 合成一个投影，一条 SQL 取回。
 *
 * @param project 项目
 * @param role    当前账号在该项目中的角色
 */
public record ProjectMembership(Project project, ProjectRole role) {
}
