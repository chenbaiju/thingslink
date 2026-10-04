import type { ConsoleDomainRegistration } from '../contract'
import { ruleRoutes } from '@/router/modules/rule'

/** 规则域注册；注册表不承载规则事实或页面状态。 */
const ruleDomain = {
  id: 'rule',
  order: 50,
  routes: [ruleRoutes],
  zhCNMessages: {
    menus: {
      rule: {
        title: '规则中心',
        executions: '执行记录',
        messages: '消息规则',
        scenes: '手动场景',
        automations: '自动化'
      }
    }
  }
} satisfies ConsoleDomainRegistration

export default ruleDomain
