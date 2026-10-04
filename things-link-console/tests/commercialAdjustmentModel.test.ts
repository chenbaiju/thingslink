import { describe, expect, it, vi } from 'vitest'
import { createCommercialModel } from '@/features/commercial/adjustment-model'
import { filterPlatformPermissions } from '@/router/core/platformPermissionFilter'
import { commercialRoutes } from '@/router/modules/commercial'
const tenant = '01900000-0000-7000-8000-000000000001'
const input = {
  dimensionCode: 'DEVICES_MAX',
  amount: '9007199254740993',
  startsAt: '2026-09-20T00:00:00Z',
  endsAt: '2026-10-20T00:00:00Z',
  reason: 'INC-123 审批'
}
function fixture() {
  const ports = {
    preview: vi.fn().mockResolvedValue({
      tenantId: tenant,
      tenantName: '目标租户',
      assignmentVersion: '9007199254740993',
      dimensions: [{ code: 'DEVICES_MAX', effectiveAmount: '3' }]
    }),
    create: vi.fn().mockImplementation(async (tenantId, request) => ({
      ...request,
      tenantId,
      id: 'fact-id',
      status: 'ACTIVE'
    })),
    recover: vi.fn(),
    revoke: vi.fn().mockResolvedValue({ revoked: true }),
    save: vi.fn(),
    key: vi.fn(() => 'request-key-123')
  }
  return { ports, model: createCommercialModel(ports) }
}
describe('商业审批恢复与权限', () => {
  it('数值和版本超过JS安全整数仍按原字符串提交和展示', async () => {
    const { ports, model } = fixture()
    await model.preview(tenant)
    await model.submit(input)
    expect(ports.create).toHaveBeenCalledWith(
      tenant,
      expect.objectContaining({
        amount: input.amount,
        expectedAssignmentVersion: '9007199254740993'
      })
    )
    expect(model.state.result?.amount).toBe(input.amount)
    expect(ports.save.mock.invocationCallOrder[0]).toBeLessThan(
      ports.create.mock.invocationCallOrder[0]
    )
  })
  it.each(['9223372036854775808', '0', '-1', '1.1', '1e9', '01'])(
    '拒绝非法或越界数量 %s',
    async (amount) => {
      const { ports, model } = fixture()
      await model.preview(tenant)
      await model.submit({ ...input, amount })
      expect(ports.create).not.toHaveBeenCalled()
      expect(model.state.error).toContain('整数')
    }
  )
  it('未知结果先查询，即使404也保留原编号和冻结内容', async () => {
    const { ports, model } = fixture()
    ports.create.mockRejectedValueOnce({ outcomeUnknown: true })
    ports.recover.mockRejectedValue({ code: 50037 })
    await model.preview(tenant)
    await model.submit(input)
    const original = model.state.pending
    await model.retryOriginal()
    expect(ports.create).toHaveBeenCalledTimes(1)
    await model.preview(tenant)
    await model.submit({ ...input, amount: '2' })
    expect(ports.create).toHaveBeenCalledTimes(1)
    await model.recover()
    expect(model.state.pending).toEqual(original)
    await model.retryOriginal()
    expect(ports.create).toHaveBeenLastCalledWith(tenant, original?.request)
    expect(ports.key).toHaveBeenCalledTimes(1)
    expect(model.state.pending).toBeNull()
  })
  it('已有未知结果遇到有效期拒绝仍不丢弃原申请', async () => {
    const { ports, model } = fixture()
    ports.create
      .mockRejectedValueOnce({ outcomeUnknown: true })
      .mockRejectedValueOnce({ code: 50039 })
    ports.recover.mockRejectedValue({ code: 50037 })
    await model.preview(tenant)
    await model.submit(input)
    await model.recover()
    await model.retryOriginal()
    expect(model.state.pending?.request.idempotencyKey).toBe('request-key-123')
  })
  it('首次明确版本冲突要求重读摘要，不能悄悄按旧版本重批', async () => {
    const { ports, model } = fixture()
    ports.create.mockRejectedValue({ code: 50052 })
    await model.preview(tenant)
    await model.submit(input)
    expect(model.state.pending).toBeNull()
    expect(model.state.preview).toBeNull()
    expect(model.state.error).toContain('重新读取')
  })
  it('持久化失败时不发出写请求', async () => {
    const { ports, model } = fixture()
    ports.save.mockImplementation(() => {
      throw new Error('存储失败')
    })
    await model.preview(tenant)
    await model.submit(input)
    expect(ports.create).not.toHaveBeenCalled()
    expect(model.state.pending).toBeNull()
  })
  it('恢复已到期事实，不根据状态重新授予；旧身份响应不得污染新上下文', async () => {
    const { ports, model } = fixture()
    model.restore({
      tenantId: tenant,
      request: { ...input, idempotencyKey: 'old-key-123', expectedAssignmentVersion: '1' }
    })
    ports.recover.mockResolvedValue({
      tenantId: tenant,
      id: 'old-fact',
      idempotencyKey: 'old-key-123',
      status: 'EXPIRED'
    })
    await model.recover()
    expect(model.state.result?.status).toBe('EXPIRED')
    expect(ports.create).not.toHaveBeenCalled()
    let resolve!: (v: unknown) => void
    ports.preview.mockReturnValue(
      new Promise((r) => {
        resolve = r
      })
    )
    const waiting = model.preview(tenant)
    model.invalidate()
    resolve({ tenantId: tenant, assignmentVersion: '1', dimensions: [{}] })
    await waiting
    expect(model.state.preview).toBeNull()
  })
  it('撤销按原ID执行并回读，不新建调整', async () => {
    const { ports, model } = fixture()
    await model.preview(tenant)
    await model.submit(input)
    ports.recover.mockResolvedValue({
      tenantId: tenant,
      id: 'fact-id',
      idempotencyKey: 'request-key-123',
      status: 'CANCELLED'
    })
    await model.revoke('收回临时补偿')
    expect(ports.revoke).toHaveBeenCalledWith(tenant, 'fact-id', '收回临时补偿')
    expect(model.state.result?.status).toBe('CANCELLED')
    expect(ports.create).toHaveBeenCalledTimes(1)
  })
  it('备用菜单不给OWNER替代权限，仅显式平台权限可见', () => {
    expect(filterPlatformPermissions([commercialRoutes], ['project:write', 'quota:read'])).toEqual(
      []
    )
    expect(filterPlatformPermissions([commercialRoutes], ['commercial:adjust'])).toHaveLength(1)
  })
})
