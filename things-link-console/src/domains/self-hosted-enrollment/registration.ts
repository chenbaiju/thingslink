import type { ConsoleDomainRegistration } from '../contract'
import { selfHostedEnrollmentRoutes } from '@/router/modules/self-hosted-enrollment'
import { selfHostedReviewRoutes } from '@/router/modules/self-hosted-review'

export default {
  id: 'self-hosted-enrollment',
  order: 77,
  routes: [selfHostedEnrollmentRoutes, selfHostedReviewRoutes],
  zhCNMessages: {
    menus: {
      selfHostedEnrollment: { title: '自部署授权申请' },
      selfHostedReview: { title: '自部署申请审核' }
    }
  }
} satisfies ConsoleDomainRegistration
