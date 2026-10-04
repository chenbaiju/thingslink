import type { AppRouteRecord } from '@/types/router'

/** 独立审核入口，前端显示判断不取代发行方服务内的事务授权。 */
export const selfHostedReviewRoutes: AppRouteRecord = {
  name: 'SelfHostedReview',
  path: '/self-hosted-review',
  component: '/self-hosted-review/index',
  meta: {
    title: 'menus.selfHostedReview.title',
    icon: 'ri:shield-check-line',
    keepAlive: false,
    requiredPermissions: ['self_hosted:review']
  }
}
