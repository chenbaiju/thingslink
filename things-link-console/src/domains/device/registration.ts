import type { ConsoleDomainRegistration } from '../contract'
import { deviceRoutes } from '@/router/modules/device'

/** 设备域注册；API、store与页面继续通过静态依赖保持各自类型边界。 */
const deviceDomain = {
  id: 'device',
  order: 20,
  routes: [deviceRoutes],
  zhCNMessages: {
    menus: {
      device: {
        title: '设备',
        list: '所有设备',
        groups: '设备组',
        messages: '消息日志',
        types: '设备类型',
        topology: '拓扑管理',
        modbusPoints: 'Modbus 点位'
      }
    }
  }
} satisfies ConsoleDomainRegistration

export default deviceDomain
