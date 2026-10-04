import { AppRouteRecord } from '@/types/router'

/** 任务菜单前端兜底定义；日常运行由后端 MenuCatalog 按 task:* 权限下发。 */
export const taskRoutes: AppRouteRecord = {
  name: 'Task',
  path: '/task',
  component: '/index/index',
  meta: {
    title: 'menus.task.title',
    icon: 'ri:task-line',
    roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
  },
  children: [
    {
      path: 'jobs',
      name: 'TaskJobs',
      component: '/task/jobs',
      meta: {
        title: 'menus.task.jobs',
        icon: 'ri:task-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    }
  ]
}
