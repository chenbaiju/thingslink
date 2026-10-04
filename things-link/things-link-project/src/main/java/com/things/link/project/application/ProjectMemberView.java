package com.things.link.project.application;

import com.things.link.shared.authz.ProjectRole;

import java.time.Instant;
import java.util.UUID;

/**
 * 成员列表的一行：项目里的成员记录，补齐了「他是谁」。
 *
 * <p>由 {@code project_member} 的行 + {@link AccountRef} 拼成。拼接发生在
 * application 层而不是 SQL 里，因为账号表属于 iam —— project 的仓储不该 join 它。
 * 代价是多一次批量查询，换来的是模块边界不被一条 join 悄悄穿透。
 *
 * @param accountId   账号 ID。移除成员与改角色都用它定位
 * @param email       邮箱。显示名可以重复，邮箱才是人辨认「这是谁」的唯一依据
 * @param displayName 显示名
 * @param role        项目内角色
 * @param joinedAt    加入项目的时刻
 */
public record ProjectMemberView(
        UUID accountId,
        String email,
        String displayName,
        ProjectRole role,
        Instant joinedAt) {
}
