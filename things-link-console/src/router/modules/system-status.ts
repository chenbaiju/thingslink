import { AppRouteRecord } from '@/types/router'

/**
 * 系统状态页前端兜底定义；日常运行由后端 MenuCatalog 下发。
 *
 * 状态是平台级事实，不依赖当前项目，因此不设置项目角色。接口仍要求有效登录令牌。
 */
export const systemStatusRoutes: AppRouteRecord = {
  name: 'SystemStatus',
  path: '/system-status',
  component: '/system-status/index',
  meta: {
    title: 'menus.systemStatus.title',
    icon: 'ri:pulse-line',
    keepAlive: false
  }
}
