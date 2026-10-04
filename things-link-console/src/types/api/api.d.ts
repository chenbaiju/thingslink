/**
 * API 接口类型定义模块
 *
 * 提供所有后端接口的类型定义
 *
 * ## 主要功能
 *
 * - 通用类型（分页参数、响应结构等）
 * - 认证类型（登录、用户信息等）
 * - 系统管理类型（用户、角色等）
 * - 全局命名空间声明
 *
 * ## 使用场景
 *
 * - API 请求参数类型约束
 * - API 响应数据类型定义
 * - 接口文档类型同步
 *
 * ## 注意事项
 *
 * - 在 .vue 文件使用需要在 eslint.config.mjs 中配置 globals: { Api: 'readonly' }
 * - 使用全局命名空间，无需导入即可使用
 *
 * ## 使用方式
 *
 * ```typescript
 * const params: Api.Auth.LoginParams = { userName: 'admin', password: '123456' }
 * const response: Api.Auth.UserInfo = await fetchUserInfo()
 * ```
 *
 * @module types/api/api
 * @author Things Link Team
 */

declare namespace Api {
  /** 通用类型 */
  namespace Common {
    /** 分页参数 */
    interface PaginationParams {
      /** 当前页码 */
      current: number
      /** 每页条数 */
      size: number
      /** 总条数 */
      total: number
    }

    /** 通用搜索参数 */
    type CommonSearchParams = Pick<PaginationParams, 'current' | 'size'>

    /** 分页响应基础结构 */
    interface PaginatedResponse<T = any> {
      records: T[]
      current: number
      size: number
      total: number
    }

    /** 启用状态 */
    type EnableStatus = '1' | '2'
  }

  /** 认证类型 */
  namespace Auth {
    /*
     * 模板自带的 LoginParams / LoginResponse 已删除。
     *
     * 它们是手写的、且与后端契约不符（userName 而非 email，token 而非 accessToken，
     * 还有一个前端根本拿不到的 refreshToken —— 刷新令牌在 HttpOnly Cookie 里，
     * 见 ADR 0010）。登录相关的类型一律取自 OpenAPI 生成物，见 src/api/auth.ts：
     * 后端改了字段名，pnpm api:check 会在 CI 里变红，手写类型不会。
     */

    /** 用户信息 */
    interface UserInfo {
      /** 权限点，形如 resource:action（架构文档 7.2）。S1 权限接口完成后填充 */
      buttons: string[]
      /**
       * 当前选中的项目 ID；未选择时是空串。
       *
       * 它决定行级安全能看到哪些数据（ADR 0012）。访问令牌只存在内存里，
       * 页面一刷新就没了，但项目选择记在刷新令牌上，所以能靠 /me 还原。
       */
      currentProjectId: string
      /** 项目角色数组。保留数组形状只是为了适配后台模板的菜单过滤 */
      roles: string[]
      /** 账号 ID。是 UUID 字符串，不是数字 —— 主键类型见 ADR 0004 */
      userId: string
      userName: string
      email: string
      /** 当前租户 ID */
      tenantId: string
      avatar?: string
    }
  }

  /** 系统管理类型 */
  namespace SystemManage {
    /** 用户列表 */
    type UserList = Api.Common.PaginatedResponse<UserListItem>

    /** 用户列表项 */
    interface UserListItem {
      id: number
      avatar: string
      status: string
      userName: string
      userGender: string
      nickName: string
      userPhone: string
      userEmail: string
      userRoles: string[]
      createBy: string
      createTime: string
      updateBy: string
      updateTime: string
    }

    /** 用户搜索参数 */
    type UserSearchParams = Partial<
      Pick<UserListItem, 'id' | 'userName' | 'userGender' | 'userPhone' | 'userEmail' | 'status'> &
        Api.Common.CommonSearchParams
    >

    /** 角色列表 */
    type RoleList = Api.Common.PaginatedResponse<RoleListItem>

    /** 角色列表项 */
    interface RoleListItem {
      roleId: number
      roleName: string
      roleCode: string
      description: string
      enabled: boolean
      createTime: string
    }

    /** 角色搜索参数 */
    type RoleSearchParams = Partial<
      Pick<RoleListItem, 'roleId' | 'roleName' | 'roleCode' | 'description' | 'enabled'> &
        Api.Common.CommonSearchParams & {
          startTime: string | null
          endTime: string | null
        }
    >
  }
}
