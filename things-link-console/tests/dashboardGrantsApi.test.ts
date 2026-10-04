import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const state = vi.hoisted(() => ({ accessToken: 'token', epoch: 1 }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => state.epoch }))
vi.mock('@/utils/http', () => ({ default: { get: vi.fn() } }))
import request from '@/utils/http'
import {
  fetchGrantUsers,
  fetchDashboardGrant,
  writeDashboardGrantIntent
} from '@/api/dashboard-grants'
import type { GrantWriteIntent } from '@/features/dashboard/grant-model'
const intent: GrantWriteIntent = {
  projectId: 'project',
  dashboardId: 'dashboard',
  appUserId: 'user',
  key: 'unique',
  body: { expectedRevision: '0', status: 'ACTIVE' }
}
describe('看板READ授权API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubEnv('VITE_API_URL', '/')
    vi.stubGlobal('fetch', vi.fn())
    state.epoch = 1
    state.accessToken = 'token'
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
    vi.useRealTimers()
  })
  it('目录20项透明游标；精确用户看板详情保留60025错误', async () => {
    await fetchGrantUsers('project', 'opaque')
    expect(request.get).toHaveBeenLastCalledWith({
      url: '/api/v1/projects/project/end-users',
      params: { limit: 20, cursor: 'opaque' },
      showErrorMessage: false
    })
    vi.mocked(request.get).mockRejectedValueOnce({ code: 60025, status: 404 })
    await expect(fetchDashboardGrant('project', 'user', 'dashboard')).rejects.toMatchObject({
      code: 60025
    })
    expect(request.get).toHaveBeenLastCalledWith({
      url: '/api/v1/projects/project/end-users/user/dashboard-grants/dashboard',
      showErrorMessage: false
    })
  })
  it('一次PUT冻结原key正文，401不刷新或重放且不透传响应正文', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response('{"code":20001,"message":"PRIVATE"}', { status: 401 })
    )
    await expect(writeDashboardGrantIntent(intent)).rejects.toMatchObject({
      code: 20001,
      status: 401,
      outcomeUnknown: false,
      message: '授权请求被拒绝（HTTP 401）。'
    })
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(vi.mocked(fetch).mock.calls[0]).toMatchObject([
      '/api/v1/projects/project/end-users/user/dashboard-grants/dashboard',
      {
        method: 'PUT',
        body: JSON.stringify(intent.body),
        headers: { 'Idempotency-Key': 'unique', Authorization: 'Bearer token' },
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error'
      }
    ])
  })
  it.each([10010, 10014, 60027, 60025])('保留错误码%s，不冒充成功', async (code) => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code }), { status: 409 }))
    await expect(writeDashboardGrantIntent(intent)).rejects.toMatchObject({
      code,
      outcomeUnknown: false
    })
  })
  it('只有200正文为成功；201/204/坏JSON均保持未知', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response('{"revision":"1"}', { status: 200 }))
    await expect(writeDashboardGrantIntent(intent)).resolves.toEqual({ revision: '1' })
    for (const response of [
      new Response('{}', { status: 201 }),
      new Response(null, { status: 204 }),
      new Response('bad', { status: 200 })
    ]) {
      vi.mocked(fetch).mockResolvedValueOnce(response)
      await expect(writeDashboardGrantIntent(intent)).rejects.toMatchObject({
        outcomeUnknown: true
      })
    }
  })
  it('网络与5xx保持未知，不自动重试', async () => {
    vi.mocked(fetch)
      .mockRejectedValueOnce(new Error('private network detail'))
      .mockResolvedValueOnce(new Response('{"code":90000}', { status: 500 }))
    await expect(writeDashboardGrantIntent(intent)).rejects.toMatchObject({ outcomeUnknown: true })
    await expect(writeDashboardGrantIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true,
      code: 90000
    })
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('超4MiB中止响应；身份切换拒绝迟到200', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(' '.repeat(4 * 1024 * 1024 + 1), { status: 200 })
    )
    await expect(writeDashboardGrantIntent(intent)).rejects.toMatchObject({ outcomeUnknown: true })
    expect(vi.mocked(fetch).mock.calls[0]![1]!.signal!.aborted).toBe(true)
    vi.mocked(fetch).mockImplementationOnce(async () => {
      state.epoch++
      return new Response('{}', { status: 200 })
    })
    await expect(writeDashboardGrantIntent(intent)).rejects.toMatchObject({ outcomeUnknown: true })
  })
  it('整个请求超过30秒主动中止，无自动重发', async () => {
    vi.useFakeTimers()
    vi.mocked(fetch).mockImplementation(
      (_url, init) =>
        new Promise((_resolve, reject) =>
          init!.signal!.addEventListener('abort', () => reject(new Error('abort')))
        )
    )
    const result = expect(writeDashboardGrantIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
    await vi.advanceTimersByTimeAsync(30_000)
    await result
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('未登录不发请求', async () => {
    state.accessToken = ''
    await expect(writeDashboardGrantIntent(intent)).rejects.toMatchObject({
      status: 401,
      outcomeUnknown: false
    })
    expect(fetch).not.toHaveBeenCalled()
  })
})
