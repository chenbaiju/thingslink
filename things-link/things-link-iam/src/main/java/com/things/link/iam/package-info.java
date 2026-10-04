/**
 * 身份与访问控制模块。
 *
 * <p>只负责架构文档 7.2 三类身份中的**第一类：控制台账号**
 * （平台运营者、Owner、管理员、操作员、访客）。另外两类不在本模块：
 * 终端用户账号在 {@code things-link-enduser}，设备身份在 {@code things-link-device}。
 * 把三者混进一张表是这类系统最常见、也最难回退的设计错误。
 *
 * <p>权限模型为 RBAC + 数据范围 ABAC：
 * <pre>
 * Permission = resource:action        例：device:read、alarm:maintain、ota:deploy
 * Scope      = tenant / project / device_group / device
 * Decision   = 角色权限 ∩ 项目成员关系 ∩ 数据范围 ∩ 套餐能力 ∩ 资源状态
 * </pre>
 *
 * <p>Decision 中的「项目成员关系」来自 {@code things-link-project}，
 * 因此 iam → project 是预期中的单向依赖，S1 实现授权判定时声明。
 *
 * <p>已提供账号注册登录、JWT 签发与刷新令牌轮换、项目角色
 * （OWNER/ADMIN/OPERATOR/VIEWER）、菜单树与权限点下发。
 * Console登录持久审计由 G3-AUDIT-1 接线，成功与会话同事务、业务拒绝独立持久化。
 *
 * <p>S1 有一个最容易漏的点：<b>切换项目必须重新拉取权限集合</b>。同一账号
 * 在项目 A 是 OWNER、在项目 B 是 VIEWER 是完全正常的情况。
 */
package com.things.link.iam;
