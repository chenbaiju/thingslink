package com.things.link.project.domain;

import com.things.link.shared.authz.ProjectRole;

import java.time.Instant;
import java.util.UUID;

/**
 * 项目成员记录，即 {@code project_member} 的一行。
 *
 * <p>与 {@link ProjectMembership} 的区别值得写清楚，两者名字太像：
 * <ul>
 *   <li>{@code ProjectMembership} 是<b>「我参与的项目」</b>的投影 —— 项目 + 我的角色，
 *       用于项目列表页，账号是隐含的（就是当前用户）</li>
 *   <li>本类是<b>「这个项目有谁」</b>的一行 —— 账号 + 他的角色，
 *       用于成员管理页，项目是隐含的</li>
 * </ul>
 *
 * <p>本类<b>不含邮箱与显示名</b>：那些是 iam 的数据，由
 * {@code AccountDirectory} 在 application 层补齐。放进来就得让 project 的仓储去
 * join account 表，而那正是模块边界不该被跨过的地方。
 *
 * @param id        成员记录 ID
 * @param accountId 账号 ID
 * @param role      项目内角色
 * @param createdAt 加入时刻
 */
public record ProjectMember(
        UUID id,
        UUID accountId,
        ProjectRole role,
        Instant createdAt) {
}
