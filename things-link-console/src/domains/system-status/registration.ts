import type { ConsoleDomainRegistration } from '../contract'
import { systemStatusRoutes } from '@/router/modules/system-status'

/** 系统状态域注册；域标识使用路径一致的kebab-case，翻译键保持既有camelCase合同。 */
const systemStatusDomain = {
  id: 'system-status',
  order: 70,
  routes: [systemStatusRoutes],
  zhCNMessages: {
    menus: {
      systemStatus: {
        title: '系统状态'
      }
    }
  }
} satisfies ConsoleDomainRegistration

export default systemStatusDomain
