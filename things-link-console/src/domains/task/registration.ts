import type { ConsoleDomainRegistration } from '../contract'
import { taskRoutes } from '@/router/modules/task'

/** 任务域注册；注册顺序只控制导航展示，不表达调度优先级。 */
const taskDomain = {
  id: 'task',
  order: 40,
  routes: [taskRoutes],
  zhCNMessages: {
    menus: {
      task: {
        title: '任务',
        jobs: '任务调度'
      }
    }
  }
} satisfies ConsoleDomainRegistration

export default taskDomain
