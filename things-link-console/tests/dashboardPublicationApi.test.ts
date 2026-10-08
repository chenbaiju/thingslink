import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const state = vi.hoisted(() => ({ accessToken: 'token', epoch: 1 }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => state.epoch }))
vi.mock('@/utils/http', () => ({ default: { get: vi.fn() } }))
import { writeDashboardPublicationIntent } from '@/api/dashboard-publication'
import type { PublicationIntent } from '@/features/dashboard/publication-model'
const intent: PublicationIntent = {
  kind: 'PUBLISH',
  projectId: 'project',
  dashboardId: 'dashboard',
  key: 'unique',
  body: { expectedDraftRevision: '7', expectedPublicationRevision: '3' },
  status: 'UNKNOWN'
}
describe('发布API无隐式写重放', () => {
  it('软删除精确单字段long字符串和204，401不重放，200不能当删除成功', async () => {
    const deletion: PublicationIntent = {
      ...intent,
      kind: 'SOFT_DELETE',
      body: { expectedPublicationRevision: '9223372036854775807' }
    }
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 204 }))
    await expect(writeDashboardPublicationIntent(deletion)).resolves.toBeUndefined()
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/projects/project/dashboards/dashboard/soft-delete',
      expect.objectContaining({
        body: '{"expectedPublicationRevision":"9223372036854775807"}',
        headers: expect.objectContaining({ 'Idempotency-Key': 'unique' })
      })
    )
    vi.mocked(fetch).mockResolvedValueOnce(new Response('{"code":20001}', { status: 401 }))
    await expect(writeDashboardPublicationIntent(deletion)).rejects.toMatchObject({
      status: 401,
      outcomeUnknown: false
    })
    vi.mocked(fetch).mockResolvedValueOnce(new Response('{}', { status: 200 }))
    await expect(writeDashboardPublicationIntent(deletion)).rejects.toMatchObject({
      status: 200,
      outcomeUnknown: true
    })
    expect(fetch).toHaveBeenCalledTimes(3)
  })
  it.each(['01', '-1', '9223372036854775808', '1.0', 3 as unknown as string])(
    '删除非法revision%s拒绝发请求',
    async (value) => {
      await expect(
        writeDashboardPublicationIntent({
          ...intent,
          kind: 'SOFT_DELETE',
          body: { expectedPublicationRevision: value }
        })
      ).rejects.toMatchObject({ outcomeUnknown: false })
      expect(fetch).not.toHaveBeenCalled()
    }
  )
  it('软删除不能夹带草稿revision或目标版本', async () => {
    await expect(
      writeDashboardPublicationIntent({ ...intent, kind: 'SOFT_DELETE' })
    ).rejects.toMatchObject({ outcomeUnknown: false })
    await expect(
      writeDashboardPublicationIntent({
        ...intent,
        kind: 'SOFT_DELETE',
        targetVersionId: 'version',
        body: { expectedPublicationRevision: '3' }
      })
    ).rejects.toMatchObject({ outcomeUnknown: false })
    expect(fetch).not.toHaveBeenCalled()
  })
  beforeEach(() => {
    vi.stubEnv('VITE_API_URL', '/')
    vi.stubGlobal('fetch', vi.fn())
    state.epoch = 1
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
    vi.useRealTimers()
  })
  it('一次POST原key正文，401不进入刷新或重放', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response('{"code":20001,"message":"登录失效"}', { status: 401 })
    )
    await expect(writeDashboardPublicationIntent(intent)).rejects.toMatchObject({
      code: 20001,
      status: 401,
      outcomeUnknown: false
    })
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(vi.mocked(fetch).mock.calls[0]).toMatchObject([
      '/api/v1/projects/project/dashboards/dashboard/versions',
      {
        method: 'POST',
        body: JSON.stringify(intent.body),
        headers: { 'Idempotency-Key': 'unique', Authorization: 'Bearer token' },
        redirect: 'error'
      }
    ])
  })
  it('10014保留业务码，不伪装成功正文', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response('{"code":10014,"message":"完成记录"}', { status: 409 })
    )
    await expect(writeDashboardPublicationIntent(intent)).rejects.toMatchObject({
      code: 10014,
      outcomeUnknown: false
    })
  })
  it('5xx与网络异常保持未知，不自动重试', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(new Response('{"code":90000}', { status: 500 }))
      .mockRejectedValueOnce(new Error('reset'))
    await expect(writeDashboardPublicationIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
    await expect(writeDashboardPublicationIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('撤回204无需JSON，回滚只使用精确版本path', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response('{}', { status: 200 }))
    await expect(
      writeDashboardPublicationIntent({
        ...intent,
        kind: 'WITHDRAW',
        body: { expectedPublicationRevision: '3' }
      })
    ).resolves.toBeUndefined()
    await writeDashboardPublicationIntent({
      ...intent,
      kind: 'ROLLBACK',
      targetVersionId: 'version'
    })
    expect(vi.mocked(fetch).mock.calls[1]![0]).toBe(
      '/api/v1/projects/project/dashboards/dashboard/versions/version/rollback'
    )
  })
  it('响应超4MiB中止并保持结果未知', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(' '.repeat(4 * 1024 * 1024 + 1), { status: 201 })
    )
    await expect(writeDashboardPublicationIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
    expect(vi.mocked(fetch).mock.calls[0]![1]!.signal!.aborted).toBe(true)
  })
  it('身份换代拒绝迟到成功', async () => {
    vi.mocked(fetch).mockImplementation(async () => {
      state.epoch++
      return new Response('{}', { status: 201 })
    })
    await expect(writeDashboardPublicationIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
  })
})
