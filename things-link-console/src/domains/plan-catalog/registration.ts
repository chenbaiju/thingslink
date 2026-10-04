import type { ConsoleDomainRegistration } from '../contract'
import { planCatalogRoutes } from '@/router/modules/plan-catalog'

/** 套餐与权益目录域注册；与 system-status 同一口径：平台级只读页，无需项目角色。 */
const planCatalogDomain = {
  id: 'plan-catalog',
  order: 75,
  routes: [planCatalogRoutes],
  zhCNMessages: {
    menus: {
      planCatalog: {
        title: '套餐与权益'
      }
    }
  }
} satisfies ConsoleDomainRegistration

export default planCatalogDomain
