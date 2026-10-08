import type { AppRouteRecord } from '@/types/router'
/** 展示分组只消费已授权菜单，不注册新路由，也不授予任何权限。 */
const groups = [
  {
    id: 'workbench',
    title: '工作台',
    icon: 'ri:home-smile-2-line',
    matches: (path: string) => ['/dashboard/workbench', '/dashboard/overview'].includes(path)
  },
  {
    id: 'device',
    title: '设备开发',
    icon: 'ri:cpu-line',
    matches: (path: string) => path.startsWith('/device/') || path.startsWith('/ota/')
  },
  {
    id: 'rules',
    title: '规则与自动化',
    icon: 'ri:git-branch-line',
    matches: (path: string) => path.startsWith('/rule/') || path.startsWith('/task/')
  },
  {
    id: 'operations',
    title: '运行与告警',
    icon: 'ri:alarm-warning-line',
    matches: (path: string) => path.startsWith('/alarm/')
  },
  {
    id: 'applications',
    title: '应用与看板',
    icon: 'ri:layout-grid-line',
    matches: (path: string) => path.startsWith('/dashboard/') || path === '/project/end-users'
  },
  {
    id: 'project',
    title: '项目与资源',
    icon: 'ri:folder-settings-line',
    matches: (path: string) => path.startsWith('/project/')
  },
  { id: 'platform', title: '账号与平台工具', icon: 'ri:settings-3-line', matches: () => true }
]
export function workspaceNavigation(routes: readonly AppRouteRecord[]): AppRouteRecord[] {
  const leaves: AppRouteRecord[] = []
  const visit = (items: readonly AppRouteRecord[]) =>
    items.forEach((item) => {
      if (item.meta.isHide) return
      if (item.children?.length) visit(item.children)
      else if (item.component && item.component !== '/index/index') leaves.push(item)
    })
  visit(routes)
  const used = new Set<AppRouteRecord>()
  return groups.flatMap((group) => {
    const children = leaves.filter((item) => !used.has(item) && group.matches(item.path))
    children.forEach((item) => used.add(item))
    return children.length
      ? [
          {
            name: `Workspace-${group.id}`,
            path: `/_workspace/${group.id}`,
            component: '/index/index',
            meta: { title: group.title, icon: group.icon },
            children
          }
        ]
      : []
  })
}
export function activeWorkspace(
  routes: readonly AppRouteRecord[],
  path: string
): AppRouteRecord | undefined {
  return routes.find((group) => group.children?.some((child) => child.path === path))
}
