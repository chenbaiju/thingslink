import type { AppRouteRecord } from '@/types/router'
export const commercialRoutes: AppRouteRecord = {
  name: 'CommercialOperations',
  path: '/commercial-operations',
  component: '/commercial-operations/index',
  meta: {
    title: 'menus.commercialOperations.title',
    icon: 'ri:shield-user-line',
    keepAlive: false,
    requiredPermissions: ['commercial:adjust']
  }
}
