import type { ConsoleDomainRegistration } from '../contract'
import { projectRoutes } from '@/router/modules/project'

/** 项目域注册；项目选择和权限事实仍由现有会话及后端菜单链处理。 */
const projectDomain = {
  id: 'project',
  order: 60,
  routes: [projectRoutes],
  zhCNMessages: {
    menus: {
      project: {
        title: '项目',
        list: '项目列表',
        members: '项目成员',
        settings: '项目设置',
        apiKeys: 'API Key',
        webhooks: 'Webhook'
      }
    }
  }
} satisfies ConsoleDomainRegistration

export default projectDomain
