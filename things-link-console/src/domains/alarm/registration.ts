import type { ConsoleDomainRegistration } from '../contract'
import { alarmRoutes } from '@/router/modules/alarm'

/** 告警域注册；这里只声明兜底菜单与文案，服务端权限仍是最终授权边界。 */
const alarmDomain = {
  id: 'alarm',
  order: 30,
  routes: [alarmRoutes],
  zhCNMessages: {
    menus: {
      alarm: {
        title: '告警',
        rules: '告警规则',
        history: '告警历史',
        notificationGroups: '通知组',
        notificationTemplates: '通知模板'
      }
    }
  }
} satisfies ConsoleDomainRegistration

export default alarmDomain
