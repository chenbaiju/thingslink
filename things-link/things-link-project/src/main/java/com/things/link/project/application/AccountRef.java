package com.things.link.project.application;

import java.util.UUID;

/**
 * 账号在 project 模块视角下的<b>最小画像</b>。
 *
 * <p>只有三个字段，这是刻意的：project 需要的仅仅是「这一行成员是谁」。
 * 把 iam 的 {@code Account} 整个搬过来会顺带带上 {@code passwordHash}、
 * {@code failedLoginAttempts}、{@code lockedUntil} —— 口令哈希与风控状态出现在
 * 项目成员列表的调用链里，唯一的作用是让它们有机会被写进日志或序列化进响应。
 *
 * <p>端口的返回类型定义在<b>端口这一侧</b>（project.application），
 * 而不是复用 iam 的类型。复用的话依赖方向立刻反过来，整个依赖反转就白做了。
 *
 * @param id          账号 ID
 * @param email       邮箱。成员列表要显示它 —— 显示名可以重复，邮箱是人辨认「这是谁」的唯一依据
 * @param displayName 显示名
 */
public record AccountRef(UUID id, String email, String displayName) {
}
