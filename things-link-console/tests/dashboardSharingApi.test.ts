import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const state = vi.hoisted(() => ({ accessToken: 'token', epoch: 1 }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => state.epoch }))
vi.mock('@/utils/http', () => ({ default: { get: vi.fn() } }))
import { createDashboardShareIntent, revokeDashboardShare } from '@/api/dashboard-sharing'
import type { ShareCreateIntent } from '@/features/dashboard/sharing-model'
const intent: ShareCreateIntent = {
  projectId: 'project',
  dashboardId: 'dashboard',
  key: 'unique',
  body: {
    dashboardVersionId: 'version',
    expectedDashboardPublicationRevision: '3',
    expiresInSeconds: 3600,
    refererPolicy: 'HOST_ORIGIN',
    hostCompatibility: { minInclusive: '1.0.0', maxExclusive: '1.0.1' },
    variables: []
  }
}
describe('分享API无隐式写重放', () => {
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
    await expect(createDashboardShareIntent(intent)).rejects.toMatchObject({
      code: 20001,
      status: 401,
      outcomeUnknown: false
    })
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(vi.mocked(fetch).mock.calls[0]).toMatchObject([
      '/api/v1/projects/project/dashboards/dashboard/shares',
      {
        method: 'POST',
        body: JSON.stringify(intent.body),
        headers: { 'Idempotency-Key': 'unique', Authorization: 'Bearer token' },
        redirect: 'error'
      }
    ])
  })
  it('60052仅保留规范单一shareId，不透出错误message/secret', async () => {
    const id = '00000000-0000-0000-0000-000000000001'
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: 60052, details: [id], message: 'sensitive' }), {
        status: 409
      })
    )
    await expect(createDashboardShareIntent(intent)).rejects.toMatchObject({
      code: 60052,
      details: [id],
      outcomeUnknown: false
    })
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: 60052, details: ['sensitive'], message: 'sensitive' }), {
        status: 409
      })
    )
    await expect(createDashboardShareIntent(intent)).rejects.toMatchObject({
      details: undefined,
      message: '分享请求被拒绝，请核对权限、版本和范围后恢复。'
    })
  })
  it('5xx与网络异常保持未知，不自动重试', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(new Response('{"code":90000}', { status: 500 }))
      .mockRejectedValueOnce(new Error('reset'))
    await expect(createDashboardShareIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
    await expect(createDashboardShareIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('撤销204无正文、无创建键，不触发secret恢复', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }))
    await expect(revokeDashboardShare('project', 'dashboard', 'share')).resolves.toBeUndefined()
    expect(vi.mocked(fetch).mock.calls[0]).toMatchObject([
      '/api/v1/projects/project/dashboards/dashboard/shares/share/revoke',
      { method: 'POST', body: undefined }
    ])
    expect(vi.mocked(fetch).mock.calls[0]![1]!.headers).not.toHaveProperty('Idempotency-Key')
  })
  it('响应超4MiB中止并保持结果未知', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(' '.repeat(4 * 1024 * 1024 + 1), { status: 201 })
    )
    await expect(createDashboardShareIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
    expect(vi.mocked(fetch).mock.calls[0]![1]!.signal!.aborted).toBe(true)
  })
  it('身份换代拒绝迟到成功', async () => {
    vi.mocked(fetch).mockImplementation(async () => {
      state.epoch++
      return new Response('{}', { status: 201 })
    })
    await expect(createDashboardShareIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
  })
})
