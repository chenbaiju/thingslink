import { AppRouteRecord } from '@/types/router'

/**
 * 告警菜单前端兜底定义；日常运行由后端 MenuCatalog 按 alarm:* 权限下发。
 * 兜底模式只负责页面可达性，不复制后端按钮权限清单。
 */
export const alarmRoutes: AppRouteRecord = {
  name: 'Alarm',
  path: '/alarm',
  component: '/index/index',
  meta: {
    title: 'menus.alarm.title',
    icon: 'ri:alarm-warning-line',
    roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
  },
  children: [
    {
      path: 'rules',
      name: 'AlarmRules',
      component: '/alarm/rules',
      meta: {
        title: 'menus.alarm.rules',
        icon: 'ri:list-settings-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    },
    {
      path: 'history',
      name: 'AlarmHistory',
      component: '/alarm/history',
      meta: {
        title: 'menus.alarm.history',
        icon: 'ri:history-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    },
    {
      path: 'notification-groups',
      name: 'AlarmNotificationGroups',
      component: '/alarm/notification-groups',
      meta: {
        title: 'menus.alarm.notificationGroups',
        icon: 'ri:group-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    },
    {
      path: 'notification-templates',
      name: 'AlarmNotificationTemplates',
      component: '/alarm/notification-templates',
      meta: {
        title: 'menus.alarm.notificationTemplates',
        icon: 'ri:mail-settings-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    }
  ]
}
