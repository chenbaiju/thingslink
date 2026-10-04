import type { AppRouteRecord } from '@/types/router'
/** 前端菜单模式也必须按/me授予的平台权限过滤；不从项目角色推导。 */
export function filterPlatformPermissions(
  routes: AppRouteRecord[],
  permissions: string[]
): AppRouteRecord[] {
  return routes
    .filter((route) =>
      (route.meta?.requiredPermissions ?? []).every((p) => permissions.includes(p))
    )
    .map((route) => ({
      ...route,
      ...(route.children
        ? { children: filterPlatformPermissions(route.children, permissions) }
        : {})
    }))
}
