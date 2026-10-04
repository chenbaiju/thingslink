import type { ConsoleDomainRegistration } from '../contract'
import { otaRoutes } from '@/router/modules/ota'

/**
 * OTA域注册。
 *
 * 顺序取 45：位于任务（40）与规则（50）之间，既不改动既有域的 order，
 * 也让OTA在导航上紧跟设备闭环相关功能。路由与文案都由本文件声明，
 * 与后端 `MenuCatalog` 的两份定义靠人工同步。
 */
const otaDomain = {
  id: 'ota',
  order: 45,
  routes: [otaRoutes],
  zhCNMessages: {
    menus: {
      ota: {
        title: 'OTA升级',
        firmwares: '固件管理',
        campaigns: '灰度活动',
        jobs: '设备作业',
        audits: '审计时间线'
      }
    }
  }
} satisfies ConsoleDomainRegistration

export default otaDomain
