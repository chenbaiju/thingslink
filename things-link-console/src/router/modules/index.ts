import type { AppRouteRecord } from '@/types/router'
import { consoleDomainRegistry } from '@/domains/registry'

/**
 * 模块化动态路由。
 *
 * S0-7 清空了后台模板自带的示例模块（template / widgets / examples / system /
 * article / result / safeguard / help），只保留概要与异常页两个骨架。
 * 删除的是 90 个演示页面，它们与本项目业务无关，留着会持续产生两类成本：
 * 类型检查与 lint 要为它们买单，以及将来有人把示例代码当成本项目的参考实现。
 *
 * 业务菜单按开发手册的阶段逐个加回：S1 项目（已加）与项目成员、S2 设备类型、
 * S3 所有设备、S5 设备组与消息日志、S6 告警、S7 任务。X-02c2 后新增业务域
 * 通过 src/domains/<domain>/registration.ts 自动进入本数组，不再修改中心清单。
 *
 * ⚠️ 本文件与后端的 MenuCatalog 是两份等价定义，靠人工同步。日常走后端下发那份，
 * 这里只在 VITE_ACCESS_MODE=frontend 时生效。加菜单时两边都要改。
 */
export const routeModules: AppRouteRecord[] = [...consoleDomainRegistry.routes]
