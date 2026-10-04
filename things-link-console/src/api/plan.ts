import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/**
 * 平台套餐目录 API（S14-1c）。
 *
 * 类型全部来自生成的权威契约（`pnpm api:generate`），不手写 DTO。目录是**平台全局只读事实**，
 * 没有租户列、也不属于任何项目，因此这里不传 projectId，也不做任何项目作用域参数。
 *
 * 两个口径必须显式区分：
 * - `saleStatus`/`priceCents` 才是「能不能买、卖多少钱」；
 * - `referencePriceCents` 只是商业架构 §2 的参考价快照，**不是成交价**，在 `NOT_FOR_SALE`
 *   档位上照样有值，任何按钮/下单逻辑都不得把它当成可售价格。
 */
export type PlanResponse = components['schemas']['PlanResponse']

/** 套餐的冻结数值维度；数值恒为确定值，不使用 null 表示不限。 */
export type PlanQuotaDimensionResponse = components['schemas']['PlanQuotaDimensionResponse']

/** 套餐的功能权益；enabled保留目录声明，enforcement明确解释语义；不代表运行权限或额度0。 */
export type PlanEntitlementResponse = components['schemas']['PlanEntitlementResponse']

/**
 * 读取当前生效的四档平台套餐目录。
 *
 * 服务端只要求有效登录令牌（平台全局目录无项目/租户作用域），未认证返回 401。
 */
export function fetchPlanCatalog() {
  return request.get<PlanResponse[]>({
    url: '/api/v1/plans'
  })
}

/**
 * 读取单个套餐当前生效的修订版快照。
 *
 * @param planCode 稳定套餐编码，如 `STANDARD`；未知编码服务端返回 404。
 */
export function fetchPlanDetail(planCode: string) {
  return request.get<PlanResponse>({
    url: `/api/v1/plans/${encodeURIComponent(planCode)}`
  })
}
