import { AppRouteRecord } from '@/types/router'

/**
 * OTA菜单前端兜底定义。
 *
 * 日常运行由后端 `MenuCatalog.ota()` 按 `ota:read` / `ota:deploy` 权限下发，
 * 本文件只在 `VITE_ACCESS_MODE=frontend` 时生效；两侧的名称、路径、组件与
 * `meta.title` 必须逐字一致，否则切回兜底模式会少菜单。
 *
 * OTA是部署管理面：读取与部署都只授予 OWNER 与 ADMIN，因此这里也把 roles 收窄到
 * 两者，不把「接口对成员开放」误当成「控制台提供入口」。
 */
export const otaRoutes: AppRouteRecord = {
  name: 'Ota',
  path: '/ota',
  component: '/index/index',
  meta: {
    title: 'menus.ota.title',
    icon: 'ri:upload-cloud-2-line',
    roles: ['OWNER', 'ADMIN']
  },
  children: [
    {
      path: 'firmwares',
      name: 'OtaFirmwares',
      component: '/ota/firmwares',
      meta: {
        title: 'menus.ota.firmwares',
        icon: 'ri:archive-2-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN'],
        authList: [
          { title: '查看信任与类型基线', authMark: 'ota:read' },
          { title: '创建固件草稿', authMark: 'ota:deploy' },
          { title: '上传固件对象', authMark: 'ota:deploy' },
          { title: '提交固件发布', authMark: 'ota:deploy' },
          { title: '退役或撤销固件', authMark: 'ota:deploy' }
        ]
      }
    },
    {
      path: 'campaigns',
      name: 'OtaCampaigns',
      component: '/ota/campaigns',
      meta: {
        title: 'menus.ota.campaigns',
        icon: 'ri:rocket-2-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN'],
        authList: [
          { title: '创建活动', authMark: 'ota:deploy' },
          { title: '排程与启动', authMark: 'ota:deploy' },
          { title: '暂停或恢复活动', authMark: 'ota:deploy' },
          { title: '取消活动', authMark: 'ota:deploy' },
          { title: '放行下一批', authMark: 'ota:deploy' }
        ]
      }
    },
    {
      path: 'jobs',
      name: 'OtaJobs',
      component: '/ota/jobs',
      meta: {
        title: 'menus.ota.jobs',
        icon: 'ri:device-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN']
      }
    },
    {
      path: 'audits',
      name: 'OtaAudits',
      component: '/ota/audits',
      meta: {
        title: 'menus.ota.audits',
        icon: 'ri:history-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN']
      }
    }
  ]
}
