import { AppRouteRecord } from '@/types/router'

/**
 * 规则中心菜单前端兜底定义；日常运行由后端 MenuCatalog 按 rule:read 权限下发。
 * 兜底模式只负责页面可达性，不复制后端按钮权限清单。
 *
 * 消息规则管理仅向 OWNER/ADMIN 开放；执行记录保留场景和上行两个 Tab。
 */
export const ruleRoutes: AppRouteRecord = {
  name: 'Rule',
  path: '/rule',
  component: '/index/index',
  meta: {
    title: 'menus.rule.title',
    icon: 'ri:flow-chart',
    roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
  },
  children: [
    {
      path: 'automations',
      name: 'RuleAutomations',
      component: '/rule/automations',
      meta: {
        title: 'menus.rule.automations',
        icon: 'ri:flashlight-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN']
      }
    },
    {
      path: 'scenes',
      name: 'RuleScenes',
      component: '/rule/scenes',
      meta: {
        title: 'menus.rule.scenes',
        icon: 'ri:play-circle-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN']
      }
    },
    {
      path: 'messages',
      name: 'MessageRules',
      component: '/rule/messages',
      meta: {
        title: 'menus.rule.messages',
        icon: 'ri:code-box-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN']
      }
    },
    {
      path: 'executions',
      name: 'RuleExecutions',
      component: '/rule/executions',
      meta: {
        title: 'menus.rule.executions',
        icon: 'ri:history-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    }
  ]
}
