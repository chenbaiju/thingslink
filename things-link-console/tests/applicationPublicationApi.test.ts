import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const state = vi.hoisted(() => ({ accessToken: 'token', epoch: 1 }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => state.epoch }))
vi.mock('@/utils/http', () => ({ default: { get: vi.fn() } }))
import request from '@/utils/http'
import {
  fetchApplicationPublicationCatalog,
  fetchApplicationPublicationHistory,
  fetchApplicationPublicationVersion,
  writeApplicationPublicationIntent
} from '@/api/application-publication'
import type { PublicationIntent } from '@/features/application/publication-model'
const intent: PublicationIntent = {
  kind: 'PUBLISH',
  projectId: 'project',
  applicationId: 'application',
  key: 'unique',
  body: { expectedDraftRevision: '7', expectedPublicationRevision: '3' },
  status: 'UNKNOWN'
}
describe('发布API无隐式写重放', () => {
  beforeEach(() => {
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
  it('一次POST原key正文，401不进入刷新或重放', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response('{"code":20001,"message":"登录失效"}', { status: 401 })
    )
    await expect(writeApplicationPublicationIntent(intent)).rejects.toMatchObject({
      code: 20001,
      status: 401,
      outcomeUnknown: false
    })
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(vi.mocked(fetch).mock.calls[0]).toMatchObject([
      '/api/v1/projects/project/applications/application/versions',
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
    await expect(writeApplicationPublicationIntent(intent)).rejects.toMatchObject({
      code: 10014,
      outcomeUnknown: false
    })
  })
  it('5xx与网络异常保持未知，不自动重试', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(new Response('{"code":90000}', { status: 500 }))
      .mockRejectedValueOnce(new Error('reset'))
    await expect(writeApplicationPublicationIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
    await expect(writeApplicationPublicationIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('撤回204无需JSON，回滚只使用精确版本path', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response('{}', { status: 200 }))
    await expect(
      writeApplicationPublicationIntent({
        ...intent,
        kind: 'WITHDRAW',
        body: { expectedPublicationRevision: '3' }
      })
    ).resolves.toBeUndefined()
    await writeApplicationPublicationIntent({
      ...intent,
      kind: 'ROLLBACK',
      targetVersionId: 'version'
    })
    expect(vi.mocked(fetch).mock.calls[1]![0]).toBe(
      '/api/v1/projects/project/applications/application/versions/version/rollback'
    )
  })
  it('响应超4MiB中止并保持结果未知', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(' '.repeat(4 * 1024 * 1024 + 1), { status: 201 })
    )
    await expect(writeApplicationPublicationIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
    expect(vi.mocked(fetch).mock.calls[0]![1]!.signal!.aborted).toBe(true)
  })
  it('身份换代拒绝迟到成功', async () => {
    vi.mocked(fetch).mockImplementation(async () => {
      state.epoch++
      return new Response('{}', { status: 201 })
    })
    await expect(writeApplicationPublicationIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
  })
  it('目录、分页及精确历史路径不带写幂等键', async () => {
    vi.mocked(request.get).mockClear()
    await fetchApplicationPublicationCatalog('p/q', 'a/b')
    await fetchApplicationPublicationHistory('p/q', 'a/b', 'opaque+/=')
    await fetchApplicationPublicationVersion('p/q', 'a/b', 'v/x')
    expect(request.get).toHaveBeenNthCalledWith(1, {
      url: '/api/v1/projects/p%2Fq/applications/a%2Fb',
      showErrorMessage: false
    })
    expect(request.get).toHaveBeenNthCalledWith(2, {
      url: '/api/v1/projects/p%2Fq/applications/a%2Fb/versions',
      params: { limit: 20, cursor: 'opaque+/=' },
      showErrorMessage: false
    })
    expect(request.get).toHaveBeenNthCalledWith(3, {
      url: '/api/v1/projects/p%2Fq/applications/a%2Fb/versions/v%2Fx',
      showErrorMessage: false
    })
  })
  it('无登录不发送写请求', async () => {
    state.accessToken = ''
    await expect(writeApplicationPublicationIntent(intent)).rejects.toMatchObject({
      status: 401,
      outcomeUnknown: false
    })
    expect(fetch).not.toHaveBeenCalled()
  })
  it('状态不匹配与成功正文不可解析都保持未知', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(new Response('{}', { status: 200 }))
      .mockResolvedValueOnce(new Response('not-json', { status: 201 }))
    await expect(writeApplicationPublicationIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
    await expect(writeApplicationPublicationIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
  })
  it('正文流停滞也在30秒中止，不等待无限reader', async () => {
    vi.useFakeTimers()
    const cancel = vi.fn()
    vi.mocked(fetch).mockResolvedValue(
      new Response(new ReadableStream({ cancel }), { status: 201 })
    )
    const pending = writeApplicationPublicationIntent(intent)
    const rejected = expect(pending).rejects.toMatchObject({ outcomeUnknown: true })
    await vi.advanceTimersByTimeAsync(30_001)
    await rejected
    expect(fetch).toHaveBeenCalledOnce()
    expect(cancel).toHaveBeenCalledOnce()
  })
  it('显式重试仍发送同key及完全相同正文', async () => {
    vi.mocked(fetch)
      .mockRejectedValueOnce(new Error('reset'))
      .mockResolvedValueOnce(new Response('{}', { status: 201 }))
    await expect(writeApplicationPublicationIntent(intent)).rejects.toMatchObject({
      outcomeUnknown: true
    })
    await writeApplicationPublicationIntent(intent)
    const first = vi.mocked(fetch).mock.calls[0]![1]!,
      second = vi.mocked(fetch).mock.calls[1]![1]!
    expect(second.body).toBe(first.body)
    expect(second.headers).toEqual(first.headers)
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})
