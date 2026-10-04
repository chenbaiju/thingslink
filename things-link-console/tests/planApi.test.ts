import { beforeEach, describe, expect, it, vi } from 'vitest'

const get = vi.hoisted(() => vi.fn())
vi.mock('@/utils/http', () => ({ default: { get } }))

import { fetchPlanCatalog, fetchPlanDetail } from '@/api/plan'

/**
 * 套餐目录 API 客户端只做两件事：拼对平台全局路径、把类型交给生成的契约。
 *
 * 这里钉住路径与「不带项目/租户作用域参数」这两条：目录是平台全局事实，
 * 一旦被改成项目作用域路径，平台目录就会在未选项目时不可读。
 */
describe('套餐目录 API 路径', () => {
  beforeEach(() => {
    get.mockReset()
    get.mockResolvedValue([])
  })

  it('列表走平台全局 /api/v1/plans，不带项目作用域参数', async () => {
    await fetchPlanCatalog()
    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith({ url: '/api/v1/plans' })
  })

  it('详情按编码拼路径并做编码，未知编码交给服务端 404', async () => {
    await fetchPlanDetail('PROFESSIONAL')
    expect(get).toHaveBeenCalledWith({ url: '/api/v1/plans/PROFESSIONAL' })

    await fetchPlanDetail('A/B')
    expect(get).toHaveBeenLastCalledWith({ url: '/api/v1/plans/A%2FB' })
  })
})
