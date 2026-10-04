import type { ConsoleDomainRegistration } from '../contract'
import { dashboardRoutes } from '@/router/modules/dashboard'

/** 概要与看板设计器复用dashboard域，不重复注册根路由。 */
const dashboardDomain = {
  id: 'dashboard',
  order: 10,
  routes: [dashboardRoutes],
  zhCNMessages: {
    menus: {
      dashboard: {
        title: '概要',
        overview: '概要',
        designer: '看板设计器',
        applications: '应用管理'
      }
    }
  }
} satisfies ConsoleDomainRegistration

export default dashboardDomain
