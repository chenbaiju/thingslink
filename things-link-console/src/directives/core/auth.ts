/**
 * v-auth 权限指令
 *
 * 适用于后端权限控制模式，基于权限点控制 DOM 元素的显示和隐藏。
 * 如果用户没有对应权限，元素将从 DOM 中移除。
 *
 * ## 主要功能
 *
 * - 权限验证 - 根据路由 meta 中的权限点列表验证用户权限
 * - DOM 控制 - 无权限时自动移除元素，而非隐藏
 * - 响应式更新 - 权限变化时自动更新元素状态
 *
 * ## 使用示例
 *
 * ```vue
 * <!-- 只有拥有 'member:invite' 权限的用户才能看到添加成员按钮 -->
 * <el-button v-auth="'member:invite'">添加成员</el-button>
 *
 * <!-- 只有拥有 'member:update_role' 权限的用户才能看到改角色按钮 -->
 * <el-button v-auth="'member:update_role'">修改角色</el-button>
 *
 * <!-- 只有拥有 'member:remove' 权限的用户才能看到移除成员按钮 -->
 * <el-button v-auth="'member:remove'">移除成员</el-button>
 * ```
 *
 * ## 注意事项
 *
 * - 该指令会直接移除 DOM 元素，而不是使用 v-if 隐藏
 * - 权限点列表从当前路由的 meta.authList 中获取
 *
 * @module directives/auth
 * @author Things Link Team
 */

import { router } from '@/router'
import { App, Directive, DirectiveBinding } from 'vue'

export type AuthDirective = Directive<HTMLElement, string>

function checkAuthPermission(el: HTMLElement, binding: DirectiveBinding<string>): void {
  // 获取当前路由的按钮权限点列表
  const authList = (router.currentRoute.value.meta.authList as Array<{ authMark: string }>) || []

  // 检查是否有对应的权限点标识
  const hasPermission = authList.some((item) => item.authMark === binding.value)

  // 如果没有权限，移除元素
  if (!hasPermission) {
    removeElement(el)
  }
}

function removeElement(el: HTMLElement): void {
  if (el.parentNode) {
    el.parentNode.removeChild(el)
  }
}

const authDirective: AuthDirective = {
  mounted: checkAuthPermission,
  updated: checkAuthPermission
}

export function setupAuthDirective(app: App): void {
  app.directive('auth', authDirective)
}
