import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/**
 * 认证相关接口。
 *
 * 【请求与响应类型全部来自 OpenAPI 生成物，不手写】
 * 架构文档 11.1：OpenAPI 是唯一契约来源。后端改了字段名，`pnpm api:check`
 * 会在 CI 里变红，而不是等到运行时才发现 undefined。
 *
 * 契约更新流程见 README.md：从仓库根运行唯一入口
 * `python3 scripts/generate-openapi-contracts.py`，由同一候选生成并发布两份生成物。
 */

/** 登录请求体 */
export type LoginRequest = components['schemas']['LoginRequest']

/** 登录响应体 */
export type LoginResponse = components['schemas']['LoginResponse']

/**
 * 登录。
 *
 * 失败时抛 HttpError，其 code 是**业务错误码**而非 HTTP 状态码：
 * 20001 邮箱或口令错误 / 20002 账号已停用 / 20003 账号已锁定。
 * 完整清单见 docs/ERROR_CODES.md。
 *
 * @param body 邮箱与口令
 * @returns 访问令牌与过期时刻
 */
export function fetchLogin(body: LoginRequest, options: { showErrorMessage?: boolean } = {}) {
  return request.post<LoginResponse>({
    url: '/api/v1/auth/login',
    params: body,
    showErrorMessage: options.showErrorMessage
  })
}

/** 注册请求体 */
export type RegisterRequest = components['schemas']['RegisterRequest']

/**
 * 注册。
 *
 * 注册的语义是**创建租户 + 初始账号**，不是「加一个用户」（ADR 0008）。
 * 注册成功只表示账号已创建、验证邮件已触发；邮箱验证完成前后端不会签发会话，
 * 登录接口也会拒绝未验证账号。
 *
 * 失败时抛 HttpError，其 code 是业务错误码：
 * 10001 参数不合法 / 20010 邮箱已被注册 / 20011 口令强度不足 / 10029 注册过于频繁。
 *
 * @param body 邮箱、口令与可选的显示名
 */
export function fetchRegister(body: RegisterRequest) {
  return request.post<void>({
    url: '/api/v1/auth/register',
    params: body
  })
}

/** 切换项目请求体 */
export type SwitchProjectRequest = components['schemas']['SwitchProjectRequest']

/**
 * 切换当前项目。
 *
 * 项目选择是**会话状态**，写在刷新令牌上，因此跨页面刷新存活（ADR 0012）。
 * 服务端会重新签发令牌，新的访问令牌里带上目标项目的 pid —— 那是行级安全的输入，
 * 没有它就一行业务数据也读不到。
 *
 * **旧的访问令牌随之失效**：否则用户切走之后，旧令牌在剩余有效期内仍能操作原项目。
 *
 * 非成员或项目不存在都返回 10004（不区分，否则会变成项目枚举通道）。
 *
 * @param projectId 目标项目 ID；传 null 表示退出项目上下文
 */
export function fetchSwitchProject(projectId: string | null) {
  return request.post<LoginResponse>({
    url: '/api/v1/auth/switch-project',
    params: { projectId }
  })
}

/** 当前登录用户（后端契约） */
export type CurrentUserResponse = components['schemas']['CurrentUserResponse']

/**
 * 取当前登录用户。
 *
 * 后端会在这里**重新校验账号状态与成员关系** —— 令牌签发后无法撤销，
 * 账号被停用或用户被移出租户，只有回库才能发现。前端在应用启动时调用它，
 * 等于每次刷新页面都做一次复核，因此它返回 401 时必须当作已登出处理。
 *
 * 【为什么在这里做字段映射】
 * 后端契约用 accountId / displayName / projectRole，而模板的 store 与菜单过滤
 * 用 userId / userName / roles（数组）。映射写在这一层，好处是 store 与菜单代码
 * 不必改动，而映射逻辑集中在一处、有类型约束 —— 后端改字段名时这里会编译报错，
 * 而不是在运行时悄悄变成 undefined。
 */
export async function fetchGetUserInfo(): Promise<Api.Auth.UserInfo> {
  const me = await request.get<CurrentUserResponse>({ url: '/api/v1/auth/me' })

  return {
    userId: me.accountId ?? '',
    userName: me.displayName ?? '',
    email: me.email ?? '',
    tenantId: me.tenantId ?? '',
    // 模板菜单过滤按数组匹配。这里放的是**项目角色**；tenant_member 已不再有角色列。
    roles: me.projectRole ? [me.projectRole] : [],
    currentProjectId: me.currentProjectId ?? '',
    // 按钮级权限点。后端下发的是 resource:action 形式的标识（架构文档 7.2），
    // 与菜单节点 meta.authList 里的 authMark 同一套命名。
    //
    // 它只决定按钮显不显示，**不构成授权** —— 服务端对每个权限点独立校验。
    // 改这里的值不会让任何人多出实际权限，只会让界面显示出点了必然报错的按钮。
    buttons: me.permissions ?? []
  }
}

/** 邮箱验证请求体 */
export type VerifyEmailRequest = components['schemas']['VerifyEmailRequest']

/** 邮箱验证结果 */
export type VerifyEmailResponse = components['schemas']['VerifyEmailResponse']

/**
 * 验证邮箱。
 *
 * 令牌来自邮件链接里的 `token` 查询参数，由页面读出来后**放进请求体**再提交 ——
 * 不直接以 GET 带 query 打后端：查询串会进服务端访问日志、反向代理日志与浏览器
 * 历史，而这个令牌能改变账号状态。
 *
 * 失败一律是 20021（链接无效 / 用途不符 / 已过期 / 已被使用四种情况后端刻意不区分，
 * 区分开会告诉试探者「这个令牌真实存在过」）。因此调用方只需处理「成功」与
 * 「失败，请重新获取链接」两种结果。
 *
 * @param token 邮件链接中的验证令牌
 * @returns 已验证的邮箱地址
 */
export function fetchVerifyEmail(token: string) {
  return request.post<VerifyEmailResponse>({
    url: '/api/v1/auth/email/verify',
    params: { token },
    // 结果由页面自己渲染成整屏状态，不要再弹一个 toast —— 那会变成
    // 「页面上写着失败、右上角又弹一条一样的话」
    showErrorMessage: false
  })
}

/** 重发验证邮件请求体 */
export type ResendVerificationRequest = components['schemas']['ResendVerificationRequest']

/**
 * 重发验证邮件。
 *
 * **无论该邮箱是否注册、是否已验证，后端一律返回 204。** 这不是偷懒：这个接口不
 * 需要登录，若区分开就成了一个比注册接口（20010）更便宜的账号枚举通道。
 * 因此前端也**不能**据此显示「该邮箱未注册」之类的提示 —— 那等于把后端刻意
 * 隐藏的信息又在界面上还回去。
 *
 * 唯一会失败的是 10029（重发过于频繁）：重发每次都真的往外发一封信，
 * 限流比其他入口都严。
 *
 * @param email 目标邮箱
 */
export function fetchResendVerification(email: string) {
  return request.post<void>({
    url: '/api/v1/auth/email/resend',
    params: { email }
  })
}

/** 找回密码请求体 */
export type ForgotPasswordRequest = components['schemas']['ForgotPasswordRequest']

/**
 * 找回密码：向已验证的注册邮箱发送重置链接。
 *
 * **无论该邮箱是否注册、是否已验证、账号是否被停用，后端一律返回 204。**
 * 因此前端也不能据此显示「该邮箱未注册」之类的提示——这个接口不需要登录，
 * 区分开不仅会泄露「这个邮箱注册过」，还会连带泄露账号状态。
 *
 * 只有已验证的邮箱才会真的收到信（ADR 0013）：未经证实的邮箱能走重置流程，
 * 等于任何人都可以用别人的邮箱注册、再把账号找回到自己手里。
 *
 * 唯一会失败的是 10029（请求过于频繁）。
 *
 * @param email 目标邮箱
 */
export function fetchForgotPassword(email: string) {
  return request.post<void>({
    url: '/api/v1/auth/password/forgot',
    params: { email }
  })
}

/** 重置密码请求体 */
export type ResetPasswordRequest = components['schemas']['ResetPasswordRequest']

/**
 * 用邮件里的一次性令牌设置新口令。
 *
 * **成功后该账号的全部会话都会被撤销**，所以这个接口不返回令牌对，
 * 用户必须用新口令重新登录。这不是遗漏：重置密码的典型场景就是「账号可能已经被
 * 别人拿到了」，只换口令而不踢掉现有会话，攻击者手里的刷新令牌照样能一直续期。
 *
 * 失败是 20021（链接无效/过期/已用过）或 20011（口令强度不足）。
 * 后端刻意**先校验口令强度、再消费令牌**，所以口令太短时那条链接还能再用一次。
 *
 * @param token       邮件链接中的重置令牌
 * @param newPassword 新口令
 */
export function fetchResetPassword(token: string, newPassword: string) {
  return request.post<void>({
    url: '/api/v1/auth/password/reset',
    params: { token, newPassword }
  })
}
