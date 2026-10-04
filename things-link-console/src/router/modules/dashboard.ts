import { AppRouteRecord } from '@/types/router'

/**
 * 概要菜单。
 *
 * 对应架构文档第 2 节后台菜单的第一项。模板自带的 console / analysis /
 * ecommerce 三个演示看板已在 S0-7 删除 —— 它们是后台模板的示例数据，
 * 与本项目的业务毫无关系，留着只会在将来被误当成参考实现。
 *
 * 后续菜单（所有设备、设备类型、告警、任务……）按开发手册的阶段逐个加入。
 * 注意 S1 切到后端权限模式后，菜单树由后端下发，本文件只保留前端兜底。
 */
export const dashboardRoutes: AppRouteRecord = {
  name: 'Dashboard',
  path: '/dashboard',
  component: '/index/index',
  meta: {
    title: 'menus.dashboard.title',
    icon: 'ri:pie-chart-line',
    // 角色名与当前项目角色一致（架构文档 7.2 内置角色），不是模板的 R_SUPER / R_ADMIN。
    // tenant_member 已收敛为租户归属关系，不能再作为菜单授权依据。
    roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
  },
  children: [
    {
      path: 'applications',
      name: 'ApplicationManager',
      component: '/dashboard/applications',
      meta: {
        title: 'menus.dashboard.applications',
        icon: 'ri:apps-line',
        // 页面权限实时读取用户buttons，与后端目录一致，不重复声明未消费的authList。
        keepAlive: false
      }
    },
    {
      path: 'designer',
      name: 'DashboardDesigner',
      component: '/dashboard/designer',
      meta: {
        title: 'menus.dashboard.designer',
        icon: 'ri:layout-masonry-line',
        keepAlive: false
      }
    },
    {
      path: 'overview',
      name: 'Overview',
      component: '/dashboard/overview',
      meta: {
        title: 'menus.dashboard.overview',
        icon: 'ri:home-smile-2-line',
        keepAlive: false,
        // 固定标签页：作为登录后的落点，不允许关闭
        fixedTab: true
      }
    }
  ]
}
