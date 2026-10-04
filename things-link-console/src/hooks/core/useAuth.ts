/**
 * useAuth - 权限验证管理
 *
 * 提供统一的权限验证功能，支持前端和后端两种权限模式。
 * 用于控制页面按钮、操作等功能的显示和访问权限。
 *
 * ## 主要功能
 *
 * 1. 权限检查 - 检查用户是否拥有指定的权限点
 * 2. 双模式支持 - 自动适配前端模式和后端模式的权限验证
 * 3. 前端模式 - 从用户信息中获取按钮权限点列表（如 ['member:invite']）
 * 4. 后端模式 - 从路由 meta 配置中获取权限点列表（如 [{ authMark: 'member:invite' }]）
 *
 * ## 使用示例
 *
 * ```typescript
 * const { hasAuth } = useAuth()
 *
 * // 检查是否有添加成员权限
 * if (hasAuth('member:invite')) {
 *   // 显示添加成员按钮
 * }
 *
 * // 在模板中使用
 * <el-button v-if="hasAuth('member:update_role')">修改角色</el-button>
 * <el-button v-if="hasAuth('member:remove')">移除成员</el-button>
 * ```
 *
 * @module useAuth
 * @author Things Link Team
 */

import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useUserStore } from '@/store/modules/user'
import { useAppMode } from '@/hooks/core/useAppMode'
import type { AppRouteRecord } from '@/types/router'

type AuthItem = NonNullable<AppRouteRecord['meta']['authList']>[number]

const userStore = useUserStore()

export const useAuth = () => {
  const route = useRoute()
  const { isFrontendMode } = useAppMode()
  const { info } = storeToRefs(userStore)

  // 前端按钮权限点（例如：['member:invite', 'member:remove']）
  const frontendAuthList = info.value?.buttons ?? []

  // 后端路由 meta 配置的权限点列表（例如：[{ authMark: 'member:invite' }]）
  const backendAuthList: AuthItem[] = Array.isArray(route.meta.authList)
    ? (route.meta.authList as AuthItem[])
    : []

  /**
   * 检查是否拥有某权限点（前后端模式通用）
   * @param auth 权限点标识
   * @returns 是否有权限
   */
  const hasAuth = (auth: string): boolean => {
    // 前端模式
    if (isFrontendMode.value) {
      return frontendAuthList.includes(auth)
    }

    // 后端模式
    return backendAuthList.some((item) => item?.authMark === auth)
  }

  return {
    hasAuth
  }
}
