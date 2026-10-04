import type { AppRouteRecord } from '@/types/router'

/** 运营待审入口；菜单权限只用于呈现，后端每次请求继续复核数据库资格。 */
export const selfHostedEnrollmentRoutes: AppRouteRecord = {
  name: 'SelfHostedEnrollment',
  path: '/self-hosted-enrollment',
  component: '/self-hosted-enrollment/index',
  meta: {
    title: 'menus.selfHostedEnrollment.title',
    icon: 'ri:file-list-3-line',
    keepAlive: false,
    requiredPermissions: ['commercial:adjust']
  }
}
