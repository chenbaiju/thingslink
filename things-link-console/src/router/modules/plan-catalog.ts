import { AppRouteRecord } from '@/types/router'

/**
 * 套餐与权益目录页前端兜底定义；日常运行由后端 MenuCatalog 下发（S14-6e）。
 *
 * 目录是平台全局售卖事实，不依赖当前项目，因此不设置项目角色；接口仍要求有效登录令牌。
 */
export const planCatalogRoutes: AppRouteRecord = {
  name: 'PlanCatalog',
  path: '/plan-catalog',
  component: '/plan-catalog/index',
  meta: {
    title: 'menus.planCatalog.title',
    icon: 'ri:price-tag-3-line',
    keepAlive: false
  }
}
