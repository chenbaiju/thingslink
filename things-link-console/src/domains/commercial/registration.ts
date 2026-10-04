import type { ConsoleDomainRegistration } from '../contract'
import { commercialRoutes } from '@/router/modules/commercial'
export default {
  id: 'commercial',
  order: 76,
  routes: [commercialRoutes],
  zhCNMessages: { menus: { commercialOperations: { title: '商业运营' } } }
} satisfies ConsoleDomainRegistration
