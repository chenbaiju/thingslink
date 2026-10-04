import { AppRouteRecord } from '@/types/router'

/** 设备菜单前端兜底定义；真实运行时由后端 MenuCatalog 按项目权限下发。 */
export const deviceRoutes: AppRouteRecord = {
  name: 'Device',
  path: '/device',
  component: '/index/index',
  meta: {
    title: 'menus.device.title',
    icon: 'ri:cpu-line',
    roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
  },
  children: [
    {
      path: 'list',
      name: 'Devices',
      component: '/device/index',
      meta: {
        title: 'menus.device.list',
        icon: 'ri:device-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    },
    {
      path: 'groups',
      name: 'DeviceGroups',
      component: '/device/groups',
      meta: {
        title: 'menus.device.groups',
        icon: 'ri:folder-settings-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    },
    {
      path: 'messages',
      name: 'DeviceMessages',
      component: '/device/messages',
      meta: {
        title: 'menus.device.messages',
        icon: 'ri:file-list-3-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    },
    {
      path: 'types',
      name: 'DeviceTypes',
      component: '/device/types',
      meta: {
        title: 'menus.device.types',
        icon: 'ri:list-settings-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    },
    {
      path: 'topology',
      name: 'DeviceTopology',
      component: '/device/topology',
      meta: {
        title: 'menus.device.topology',
        icon: 'ri:node-tree',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    },
    {
      path: 'modbus-points',
      name: 'DeviceModbusPoints',
      component: '/device/modbus-points',
      meta: {
        title: 'menus.device.modbusPoints',
        icon: 'ri:plug-line',
        keepAlive: false,
        roles: ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER']
      }
    }
  ]
}
