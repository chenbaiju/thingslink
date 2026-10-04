import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/**
 * 项目接口。
 *
 * 【类型来自 OpenAPI 生成物，不手写】
 * 架构文档 11.1：OpenAPI 是唯一契约来源。后端改了字段名，`pnpm api:check`
 * 会在 CI 里变红，而不是等到运行时才发现 undefined。
 */

/** 项目（后端契约） */
export type ProjectResponse = components['schemas']['ProjectResponse']

/** 创建项目请求体 */
export type CreateProjectRequest = components['schemas']['CreateProjectRequest']

/** 编辑项目请求体。只能改名称，不能改区域 */
export type UpdateProjectRequest = components['schemas']['UpdateProjectRequest']

/**
 * 取我参与的全部项目。
 *
 * 结果**跨租户**：既有自己创建的，也有被邀请加入的、属于别人租户的项目
 * （ADR 0012：协作边界是项目，不是租户）。
 *
 * 每项都带 `myRole`，前端据此决定显示哪些操作——但那只影响界面，
 * **不构成授权**：服务端对每个操作独立校验。
 */
export function fetchProjects() {
  return request.get<ProjectResponse[]>({
    url: '/api/v1/projects'
  })
}

/**
 * 创建项目。创建者自动成为该项目的 OWNER。
 *
 * @param body 项目名称
 */
export function fetchCreateProject(body: CreateProjectRequest) {
  return request.post<ProjectResponse>({
    url: '/api/v1/projects',
    params: body
  })
}

/**
 * 编辑项目名称。
 *
 * 区域不在请求体里：项目区域创建后绑定死，后续跨区域迁移不能做成普通编辑。
 */
export function fetchUpdateProject(projectId: string, body: UpdateProjectRequest) {
  return request.patch<ProjectResponse>({
    url: `/api/v1/projects/${projectId}`,
    params: body
  })
}

/**
 * 删除项目。
 *
 * 只允许项目 OWNER，且项目里只能剩 OWNER 一人。若还有其他成员，服务端返回 50015；
 * 前端的确认弹窗只是防误点，真正约束在后端。
 */
export function fetchDeleteProject(projectId: string) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}`
  })
}

/** 项目成员（后端契约） */
export type ProjectMemberResponse = components['schemas']['ProjectMemberResponse']

/** 邀请成员请求体 */
export type InviteMemberRequest = components['schemas']['InviteMemberRequest']

/** 项目内角色。OWNER 只能由创建项目产生，不在可分配的选项里 */
export type AssignableRole = 'ADMIN' | 'OPERATOR' | 'VIEWER'

/**
 * 列出某个项目的成员。
 *
 * 项目 ID 走路径，**不取令牌里的当前项目**：这样「管理项目 B 的成员」不必先切到
 * 项目 B（切换项目会整页重载）。这不放松任何约束——服务端的判定是
 * `findRole(路径里的 projectId, 当前账号)`，与令牌里写的是什么无关。
 *
 * 不是该项目成员时返回 **404 而不是 403**，因此界面上「没权限」与「项目不存在」
 * 看起来一样。这是刻意的：403 等于承认项目存在，会变成项目枚举通道。
 */
export function fetchProjectMembers(projectId: string) {
  return request.get<ProjectMemberResponse[]>({
    url: `/api/v1/projects/${projectId}/members`
  })
}

/**
 * 兼容入口：按邮箱直接添加一个**已注册**账号，成功后成员关系立即生效。
 *
 * 历史函数名保留以兼容已有调用方与测试夹具；不创建待接受邀请。
 * Console 成员页使用 project-invitations.ts 的 createProjectInvitation，
 * 由收件人完成邮箱验证并显式接受后加入项目。
 */
export function fetchInviteMember(projectId: string, body: InviteMemberRequest) {
  return request.post<ProjectMemberResponse>({
    url: `/api/v1/projects/${projectId}/members`,
    params: body
  })
}

/**
 * 修改成员角色。
 *
 * 不能改 OWNER，也不能改自己，更不能把人设成 OWNER——服务端分别返回
 * 50013 / 50014 / 50012。前端把这些按钮禁掉只是为了少一次往返，**不构成授权**。
 */
export function fetchUpdateMemberRole(projectId: string, accountId: string, role: AssignableRole) {
  return request.patch<void>({
    url: `/api/v1/projects/${projectId}/members/${accountId}`,
    params: { role }
  })
}

/**
 * 转让项目所有权。
 *
 * 只有当前 OWNER 可以调用。目标必须已经是项目成员；转让后目标成为 OWNER，
 * 原 OWNER 降为 ADMIN。它不是普通改角色，因此单独成接口。
 */
export function fetchTransferOwnership(projectId: string, accountId: string) {
  return request.post<void>({
    url: `/api/v1/projects/${projectId}/members/${accountId}/transfer-owner`
  })
}

/**
 * 把成员移出项目。
 *
 * **只解除与项目的关联，账号本身不会被删除**——对方还能登录，还能看到自己参与的
 * 其他项目。确认弹窗里必须把这句话说清楚，否则操作者会以为自己在删人。
 */
export function fetchRemoveMember(projectId: string, accountId: string) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/members/${accountId}`
  })
}

/**
 * 当前登录账号主动退出项目。
 *
 * 只解除自己的项目成员绑定，不删除账号。OWNER 不能退出，需要先转让所有权或删除项目。
 */
export function fetchLeaveProject(projectId: string) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/members/me`
  })
}
