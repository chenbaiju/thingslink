import { describe, expect, it, vi } from 'vitest'
import { createDashboardGrants, type GrantContext } from '../src/features/dashboard/grant-model'
const projectId = '11111111-1111-4111-8111-111111111111',
  dashboardId = '22222222-2222-4222-8222-222222222222',
  appUserId = '33333333-3333-4333-8333-333333333333',
  otherId = '44444444-4444-4444-8444-444444444444',
  time = '2026-09-08T00:00:00Z'
const user = {
  id: appUserId,
  username: 'alice',
  displayName: '用户',
  status: 'ACTIVE',
  role: 'OBSERVER',
  roleStatus: 'ACTIVE',
  assignedAt: time
}
const fact = {
  appUserId,
  dashboardId,
  permission: 'READ',
  status: 'ACTIVE',
  revision: '1',
  createdAt: time,
  updatedAt: time,
  revokedAt: null
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
function fixture() {
  const context: GrantContext = {
    projectId,
    dashboardId,
    identity: 1,
    available: true,
    canManage: true
  }
  const ports = {
    context: () => context,
    users: vi.fn(
      async (): Promise<unknown> => ({ items: [user], hasMore: false, nextCursor: null })
    ),
    detail: vi.fn(async (): Promise<unknown> => fact),
    write: vi.fn(async (): Promise<unknown> => fact),
    newKey: vi.fn(() => 'key'),
    changed: vi.fn()
  }
  return { context, ports, grants: createDashboardGrants(ports) }
}
async function missing() {
  const f = fixture()
  await f.grants.open()
  f.ports.detail.mockRejectedValueOnce({ code: 60025, status: 404 })
  await f.grants.selectUser(appUserId)
  return f
}
describe('用户READ授权事实与恢复', () => {
  it('60025不声称确定未授权，仅当前有效目录用户显式首次ACTIVE0，成功后读当前事实', async () => {
    const { grants, ports } = await missing()
    expect(grants.getSnapshot().error).toContain('可能')
    expect(grants.getSnapshot().missingEligible).toBe(true)
    await grants.change('REVOKED')
    expect(ports.write).not.toHaveBeenCalled()
    await grants.change('ACTIVE')
    expect(ports.write).toHaveBeenCalledWith({
      projectId,
      dashboardId,
      appUserId,
      key: 'key',
      body: { expectedRevision: '0', status: 'ACTIVE' }
    })
    expect(grants.getSnapshot().grant).toEqual(fact)
    expect(grants.getSnapshot().pending).toBeNull()
  })
  it('撤销与重授严格推进同一条记录，不让状态相同请求制造新写', async () => {
    const { grants, ports } = fixture()
    await grants.open()
    await grants.selectUser(appUserId)
    await grants.change('ACTIVE')
    expect(ports.write).not.toHaveBeenCalled()
    const revoked = { ...fact, status: 'REVOKED', revision: '2', revokedAt: time }
    ports.write.mockResolvedValueOnce(revoked)
    ports.detail.mockResolvedValueOnce(revoked)
    await grants.change('REVOKED')
    expect(ports.write.mock.calls[0]).toEqual([
      {
        projectId,
        dashboardId,
        appUserId,
        key: 'key',
        body: { expectedRevision: '1', status: 'REVOKED' }
      }
    ])
    ports.write.mockResolvedValueOnce({ ...fact, revision: '3' })
    ports.detail.mockResolvedValueOnce({ ...fact, revision: '3' })
    await grants.change('ACTIVE')
    expect(grants.getSnapshot().grant?.revision).toBe('3')
  })
  it.each([
    { status: 'SUSPENDED' },
    { roleStatus: 'SUSPENDED' },
    { role: null, roleStatus: null, assignedAt: null }
  ])('用户或角色%s不可写，即使grant历史ACTIVE', async (patch) => {
    const { grants, ports } = fixture()
    ports.users.mockResolvedValueOnce({
      items: [{ ...user, ...patch }],
      hasMore: false,
      nextCursor: null
    })
    await grants.open()
    await grants.selectUser(appUserId)
    expect(grants.getSnapshot().grant?.status).toBe('ACTIVE')
    await grants.change('REVOKED')
    expect(ports.write).not.toHaveBeenCalled()
  })
  it('首次明确60027清旧事实，必须显式刷新后决定，不能自动改revision重写', async () => {
    const { grants, ports } = await missing()
    ports.write.mockRejectedValueOnce({ code: 60027, status: 409 })
    const reads = ports.detail.mock.calls.length
    await grants.change('ACTIVE')
    expect(grants.getSnapshot().pending).toBeNull()
    expect(grants.getSnapshot().grant).toBeNull()
    expect(grants.getSnapshot().missingEligible).toBe(false)
    expect(ports.detail).toHaveBeenCalledTimes(reads)
    await grants.change('ACTIVE')
    expect(ports.write).toHaveBeenCalledTimes(1)
    await grants.refresh()
    expect(grants.getSnapshot().grant).toEqual(fact)
  })
  it('未知后10014须GET成功才解锁，60025不变首次授予入口', async () => {
    const { grants, ports } = await missing()
    ports.write
      .mockRejectedValueOnce({ outcomeUnknown: true })
      .mockRejectedValueOnce({ code: 10014, status: 409 })
    await grants.change('ACTIVE')
    const intent = grants.getSnapshot().pending?.intent
    ports.detail.mockRejectedValueOnce({ code: 60025, status: 404 })
    await grants.retry()
    expect(ports.write.mock.calls[1]).toEqual([intent])
    expect(grants.getSnapshot().pending?.status).toBe('COMPLETED')
    expect(grants.getSnapshot().missingEligible).toBe(false)
    await grants.change('ACTIVE')
    expect(ports.write).toHaveBeenCalledTimes(2)
    await grants.refresh()
    expect(grants.getSnapshot().pending).toBeNull()
  })
  it('隐藏迟到200不回填，恢复后403不能否定旧写；目录页未含原用户不妨碍同意图恢复', async () => {
    const { grants, ports, context } = await missing()
    const result = deferred<unknown>()
    ports.write
      .mockImplementationOnce(() => result.promise)
      .mockRejectedValueOnce({ code: 60025, status: 403 })
    const run = grants.change('ACTIVE')
    context.available = false
    grants.suspend()
    result.resolve(fact)
    await run
    expect(grants.getSnapshot().grant).toBeNull()
    const pending = grants.getSnapshot().pending
    context.available = true
    ports.users.mockResolvedValueOnce({
      items: [{ ...user, id: otherId }],
      hasMore: true,
      nextCursor: 'next'
    })
    await grants.open()
    expect(grants.getSnapshot().selectedUser).toBeNull()
    await grants.retry()
    expect(ports.write.mock.calls[1]).toEqual([pending!.intent])
    expect(grants.getSnapshot().pending).toEqual(pending)
    await grants.refresh()
    expect(ports.detail).toHaveBeenLastCalledWith(projectId, appUserId, dashboardId)
    expect(grants.getSnapshot().pending).not.toBeNull()
  })
  it('已明确读到原用户停用，即使翻页不再出现也不发送未知重试写', async () => {
    const { grants, ports } = await missing()
    ports.write.mockRejectedValueOnce({ outcomeUnknown: true })
    await grants.change('ACTIVE')
    ports.users.mockResolvedValueOnce({
      items: [{ ...user, status: 'SUSPENDED' }],
      hasMore: true,
      nextCursor: 'next'
    })
    await grants.open()
    ports.users.mockResolvedValueOnce({
      items: [{ ...user, id: otherId }],
      hasMore: false,
      nextCursor: null
    })
    await grants.loadMore()
    expect(grants.getSnapshot().retryBlocked).toBe(true)
    await grants.retry()
    expect(ports.write).toHaveBeenCalledTimes(1)
  })
  it.each([{ code: 30001 }, { code: 401 }, { code: 60035, status: 403 }])(
    '读取失权%s立即清除事实并保留未知键，旧200围栏不回填',
    async (error) => {
      const { grants, ports } = await missing()
      ports.write.mockRejectedValueOnce({ outcomeUnknown: true })
      await grants.change('ACTIVE')
      const pending = grants.getSnapshot().pending
      const old = deferred<unknown>()
      ports.detail.mockImplementationOnce(() => old.promise)
      const detail = grants.refresh()
      ports.users.mockRejectedValueOnce(error)
      await grants.open()
      expect(grants.getSnapshot().users).toEqual([])
      expect(grants.getSnapshot().grant).toBeNull()
      expect(grants.getSnapshot().pending).toEqual(pending)
      old.resolve(fact)
      await detail
      expect(grants.getSnapshot().grant).toBeNull()
    }
  )
  it('身份变更不能向新scope重试旧用户，失去管理权限不发目录请求', async () => {
    const { grants, ports, context } = await missing()
    ports.write.mockRejectedValueOnce({ outcomeUnknown: true })
    await grants.change('ACTIVE')
    context.identity++
    grants.reset()
    await grants.retry()
    expect(ports.write).toHaveBeenCalledTimes(1)
    context.canManage = false
    await grants.open()
    expect(ports.users).toHaveBeenCalledTimes(1)
  })
  it.each([
    { appUserId: otherId },
    { dashboardId: otherId },
    { permission: 'WRITE' },
    { revision: '0' },
    { revokedAt: time },
    { createdAt: '2026-02-30T00:00:00Z' }
  ])('拒绝响应身份与字段错配%s，不开放写入', async (patch) => {
    const { grants, ports } = fixture()
    await grants.open()
    ports.detail.mockResolvedValueOnce({ ...fact, ...patch })
    await grants.selectUser(appUserId)
    expect(grants.getSnapshot().grant).toBeNull()
    expect(grants.getSnapshot().missingEligible).toBe(false)
    await grants.change('ACTIVE')
    expect(ports.write).not.toHaveBeenCalled()
  })
})

it('合法未设置显示名不会使既有看板用户目录整体失败', async () => {
  const { grants, ports } = fixture()
  ports.users.mockResolvedValueOnce({
    items: [{ ...user, displayName: null }],
    hasMore: false,
    nextCursor: null
  })
  await grants.open()
  await grants.selectUser(appUserId)
  expect(grants.getSnapshot().error).toBe('')
  expect(grants.getSnapshot().selectedUser?.displayName).toBeNull()
  expect(grants.getSnapshot().grant).toEqual(fact)
})
