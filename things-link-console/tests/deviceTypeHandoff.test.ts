import { describe, expect, it, vi } from 'vitest'
import { resolveTypeHandoff, type TypeHandoffScope } from '@/features/device/type-handoff'
import type { DeviceTypeResponse } from '@/api/device'

const id = '11111111-1111-4111-8111-111111111111'
const query = { createTypeId: id, contextProjectId: 'project' }
const initial = (): TypeHandoffScope => ({
  projectId: 'project',
  userId: 'owner',
  tenantId: 'tenant',
  identity: 1,
  canCreate: true
})
const type = {
  id,
  projectId: 'project',
  status: 'PUBLISHED',
  name: '跨页设备类型'
} as DeviceTypeResponse

describe('设备类型到创建表单的可信交接', () => {
  it('通过当前项目单读已发布类型，不依赖首屏目录是否包含类型', async () => {
    const read = vi.fn().mockResolvedValue(type)
    expect(await resolveTypeHandoff(query, initial, read)).toEqual(type)
    expect(read).toHaveBeenCalledExactlyOnceWith('project', id)
  })
  it.each([
    { ...query, contextProjectId: 'other' },
    { ...query, createTypeId: [id] },
    { ...query, createTypeId: '../other' },
    { createTypeId: id }
  ])('拒绝跨项目或非法 URL，不发起读取 %j', async (invalid) => {
    const read = vi.fn()
    expect(await resolveTypeHandoff(invalid, initial, read)).toBeUndefined()
    expect(read).not.toHaveBeenCalled()
  })
  it('只读角色不读取也不打开创建', async () => {
    const read = vi.fn()
    expect(
      await resolveTypeHandoff(query, () => ({ ...initial(), canCreate: false }), read)
    ).toBeUndefined()
    expect(read).not.toHaveBeenCalled()
  })
  it.each([
    { ...type, projectId: 'other' },
    { ...type, id: 'other' },
    { ...type, status: 'DRAFT' }
  ])('不接受项目、实体或发布状态不符的响应 %j', async (response) => {
    expect(
      await resolveTypeHandoff(query, initial, vi.fn().mockResolvedValue(response))
    ).toBeUndefined()
  })
  it.each(['projectId', 'userId', 'tenantId', 'identity', 'canCreate'] as const)(
    '读取中 %s 改变使响应失效',
    async (key) => {
      let scope = initial()
      const read = vi.fn(async () => {
        scope = {
          ...scope,
          [key]: key === 'identity' ? 2 : key === 'canCreate' ? false : 'changed'
        }
        return type
      })
      expect(await resolveTypeHandoff(query, () => scope, read)).toBeUndefined()
    }
  )
  it('后端拒绝/类型被删除保留失败，调用者不能获得伪类型', async () => {
    const failure = new Error('403')
    await expect(
      resolveTypeHandoff(query, initial, vi.fn().mockRejectedValue(failure))
    ).rejects.toBe(failure)
  })
})
