import { beforeEach, describe, expect, it, vi } from 'vitest'

const get = vi.hoisted(() => vi.fn())
vi.mock('@/utils/http', () => ({ default: { get } }))

import { fetchProjectQuota } from '@/api/quota'

/**
 * 项目配额 API 客户端（S14-2c）。
 *
 * 路径保持不变：套餐摘要复用了既有项目配额接口，没有为它新增平台级入口，也没有让前端
 * 传 tenantId。这里钉住这两条，避免将来把它改成平台权限或项目作用域之外的读取面。
 */
describe('项目配额 API 路径', () => {
  beforeEach(() => {
    get.mockReset()
    get.mockResolvedValue({})
  })

  it('走既有项目配额路径，不接受也不传递 tenantId', async () => {
    await fetchProjectQuota('01a0afe9-ae7b-77b2-90ec-ce002632d5d2')
    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith({
      url: '/api/v1/projects/01a0afe9-ae7b-77b2-90ec-ce002632d5d2/quota'
    })
  })
})
