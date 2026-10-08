import { AppRouteRecord } from '@/types/router'

/**
 * 项目菜单（前端兜底）。
 *
 * ⚠️ 这份与后端 `MenuCatalog.project()` 是两份等价定义，由menuCatalogContract跨语言快照检查。
 * 日常运行在 backend 模式，走的是后端下发的那份；本文件只在
 * `VITE_ACCESS_MODE=frontend`（后端不可用时的离线开发）下生效。
 *
 * 不一致的症状很隐蔽：平时看不出来，只有切回兜底时才发现菜单少了几项或点不开。
 * 加菜单时务必两边一起改。
 *
 * 不设 roles：所有登录账号都能创建项目、都能看到自己参与的项目（ADR 0012）。
 * 这个入口发生在选定项目之前，那时还没有项目角色可言。
 */
export const projectRoutes: AppRouteRecord = {
  name: 'Project',
  path: '/project',
  component: '/index/index',
  meta: {
    title: 'menus.project.title',
    icon: 'ri:folder-3-line'
  },
  children: [
    {
      path: 'webhooks',
      name: 'ProjectWebhooks',
      component: '/project/webhooks',
      meta: {
        title: 'menus.project.webhooks',
        icon: 'ri:link',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN']
      }
    },
    {
      path: 'api-keys',
      name: 'ProjectApiKeys',
      component: '/project/api-keys',
      meta: {
        title: 'menus.project.apiKeys',
        icon: 'ri:key-2-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN']
      }
    },
    {
      path: 'list',
      name: 'ProjectList',
      component: '/project/list',
      meta: {
        title: 'menus.project.list',
        icon: 'ri:list-check',
        keepAlive: false
      }
    },
    {
      path: 'create',
      name: 'ProjectCreate',
      component: '/project/create',
      meta: { title: 'menus.project.add', icon: 'ri:add-box-line', keepAlive: false }
    },
    {
      path: 'recycle-bin',
      name: 'ProjectRecycleBin',
      component: '/project/recycle-bin',
      meta: { title: 'menus.project.recycleBin', icon: 'ri:delete-bin-line', keepAlive: false }
    },
    {
      path: 'members',
      name: 'ProjectMembers',
      component: '/project/members',
      meta: {
        title: 'menus.project.members',
        icon: 'ri:team-line',
        keepAlive: false,
        /*
         * 这条兜底路由**没有** authList，而后端下发的那份有三个按钮权限点。
         *
         * 这不是漏写：兜底模式的存在意义是「后端不可用时前端仍能独立开发调试」，
         * 那时没有任何权限点来源，写死一份反而会造出「本地能点、连上后端就消失」
         * 的假象。真实的按钮控制永远走 backend 模式。
         */
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    },
    {
      path: 'end-users',
      name: 'ProjectEndUsers',
      component: '/project/end-users',
      meta: {
        title: 'menus.project.endUsers',
        icon: 'ri:user-settings-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    },
    {
      path: 'settings',
      name: 'ProjectSettings',
      component: '/project/settings',
      meta: {
        title: 'menus.project.settings',
        icon: 'ri:settings-3-line',
        keepAlive: false,
        // S7-2 的页面没有写操作。四种项目角色都可读取当前项目贡献与租户共享池余量，
        // 但后端不会返回其他项目的事实或账单聚合（架构文档 8.1.1）。
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    }
  ]
}
