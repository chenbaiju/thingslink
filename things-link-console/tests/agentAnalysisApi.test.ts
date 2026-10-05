import { beforeEach, expect, it, vi } from 'vitest'
const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/utils/http', () => ({ default: http }))
import {
  readAnalysisAvailability,
  readAnalysisCallByKey,
  postAnalysisRun
} from '@/api/assistant-analysis'
import { newAnalysisKey } from '@/features/agent/analysis-intent'

beforeEach(() => vi.resetAllMocks())
it.each([
  'INTERNAL_TRANSPORT_DISABLED',
  'MODEL_ADMISSION_PENDING',
  'PROJECT_MODEL_CONFIGURATION_DISABLED'
])('状态 %s 只读取固定GET，不携带Key、正文或自动发送', async (reason) => {
  const value = { businessAvailable: false, reason }
  http.get.mockResolvedValue(value)
  const signal = new AbortController().signal
  expect(await readAnalysisAvailability('a/b', signal)).toEqual(value)
  expect(http.get).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/a%2Fb/assistant/analysis-runs/status',
    signal,
    showErrorMessage: false
  })
  expect(http.post).not.toHaveBeenCalled()
})
it.each([
  null,
  {},
  { businessAvailable: true, reason: 'MODEL_ADMISSION_PENDING' },
  { businessAvailable: false, reason: 'NEW_REASON' },
  { businessAvailable: false, reason: 'REVIEWED_CONFIGURATION_AVAILABLE' },
  { businessAvailable: true, reason: 'INTERNAL_TRANSPORT_DISABLED' },
  { businessAvailable: true, reason: 'PROJECT_MODEL_CONFIGURATION_DISABLED' },
  { businessAvailable: 'false', reason: 'MODEL_ADMISSION_PENDING' },
  { businessAvailable: false, reason: 'MODEL_ADMISSION_PENDING', apiKey: 'private-response' }
])('拒绝未定义响应 %j，固定错误不回显内容', async (value) => {
  http.get.mockResolvedValue(value)
  await expect(readAnalysisAvailability('p', new AbortController().signal)).rejects.toThrow(
    'INVALID_ANALYSIS_AVAILABILITY'
  )
  expect(http.get).toHaveBeenCalledTimes(1)
  expect(http.post).not.toHaveBeenCalled()
})
it('读取失败不自动重试或降级为调用', async () => {
  http.get.mockRejectedValue(new Error('unavailable'))
  await expect(readAnalysisAvailability('p', new AbortController().signal)).rejects.toThrow()
  expect(http.get).toHaveBeenCalledTimes(1)
  expect(http.post).not.toHaveBeenCalled()
})

function call() {
  const now = Date.now()
  return {
    id: newAnalysisKey(),
    status: 'UNKNOWN',
    createdAt: new Date(now).toISOString(),
    deadline: new Date(now + 60_000).toISOString(),
    expiresAt: new Date(now + 86_400_000).toISOString(),
    dispatchedAt: null,
    finishedAt: null
  }
}
it('原键放在单个头而非URL，只读取七字段元数据', async () => {
  const key = newAnalysisKey(),
    value = call(),
    signal = new AbortController().signal
  http.get.mockResolvedValue(value)
  expect(await readAnalysisCallByKey('a/b', key, signal)).toEqual(value)
  expect(http.get).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/a%2Fb/assistant/analysis-runs/by-key',
    headers: { 'Idempotency-Key': key },
    signal,
    showErrorMessage: false
  })
  expect(http.post).not.toHaveBeenCalled()
})
it.each([
  { status: 'NEW_STATUS' },
  { id: 'not-an-id' },
  { summary: 'private text' },
  { createdAt: 'yesterday' },
  { finishedAt: undefined },
  { dispatchedAt: 0 },
  { expiresAt: '2020-01-01T00:00:00Z' }
])('拒绝意外恢复响应，仅抛固定错误', async (patch) => {
  http.get.mockResolvedValue({ ...call(), ...patch })
  await expect(
    readAnalysisCallByKey('p', newAnalysisKey(), new AbortController().signal)
  ).rejects.toThrow('INVALID_ANALYSIS_CALL')
  expect(http.post).not.toHaveBeenCalled()
})
it.each([
  'not-a-key',
  '00000000-0000-4000-8000-000000000000',
  '00000000-0000-7000-8000-000000000000'
])('无效或过期键不联网', async (key) => {
  await expect(readAnalysisCallByKey('p', key, new AbortController().signal)).rejects.toThrow()
  expect(http.get).not.toHaveBeenCalled()
})

function intent() {
  return {
    version: 1 as const,
    key: newAnalysisKey(),
    accountId: '00000000-0000-4000-8000-000000000001',
    tenantId: '00000000-0000-4000-8000-000000000002',
    projectId: '00000000-0000-4000-8000-000000000003',
    request: {
      deviceId: '00000000-0000-4000-8000-000000000004',
      expectedModelVersionId: '00000000-0000-4000-8000-000000000005',
      propertyKeys: ['temperature'],
      template: 'STATUS_SUMMARY' as const
    }
  }
}
it('单次提交只有封闭原请求和独立键头，期限60秒且不携带身份或值', async () => {
  const original = intent(),
    signal = new AbortController().signal
  const response = { call: null, category: 'UNAVAILABLE', result: null }
  http.post.mockResolvedValue(response)
  expect(await postAnalysisRun(original, signal)).toEqual(response)
  expect(http.post).toHaveBeenCalledExactlyOnceWith({
    url: `/api/v1/projects/${original.projectId}/assistant/analysis-runs`,
    data: original.request,
    headers: { 'Idempotency-Key': original.key },
    signal,
    timeout: 60_000,
    showErrorMessage: false
  })
  original.request.propertyKeys.push('changed')
  expect(http.post.mock.calls[0][0].data.propertyKeys).toEqual(['temperature'])
  expect(http.get).not.toHaveBeenCalled()
})
it.each(['request', 'scope', 'key', 'aborted'])('提交%s非法则零网络', async (mode) => {
  const original = intent(),
    controller = new AbortController()
  if (mode === 'request') Object.assign(original.request, { apiKey: 'private' })
  if (mode === 'scope') original.projectId = 'invalid'
  if (mode === 'key') original.key = 'invalid'
  if (mode === 'aborted') controller.abort()
  await expect(postAnalysisRun(original, controller.signal)).rejects.toThrow()
  expect(http.post).not.toHaveBeenCalled()
})
it.each(['error', 'invalid', 'aborted'])('发送后%s不自动重发且不返回不可信正文', async (mode) => {
  const controller = new AbortController()
  http.post.mockImplementation(async () => {
    if (mode === 'error') throw new Error('unknown')
    if (mode === 'aborted') controller.abort()
    return { call: null, category: 'UNAVAILABLE', result: 'private' }
  })
  await expect(postAnalysisRun(intent(), controller.signal)).rejects.toThrow()
  expect(http.post).toHaveBeenCalledTimes(1)
})

it('受审配对只来自GET并复制值，不能从状态自动提交', async () => {
  const value = { businessAvailable: true, reason: 'REVIEWED_CONFIGURATION_AVAILABLE' }
  http.get.mockResolvedValue(value)
  const result = await readAnalysisAvailability('p', new AbortController().signal)
  expect(result).toEqual(value)
  value.businessAvailable = false
  expect(result.businessAvailable).toBe(true)
  expect(http.post).not.toHaveBeenCalled()
})
it('取消后的状态响应不保留可用资格', async () => {
  const controller = new AbortController()
  http.get.mockImplementation(async () => {
    controller.abort()
    return { businessAvailable: true, reason: 'REVIEWED_CONFIGURATION_AVAILABLE' }
  })
  await expect(readAnalysisAvailability('p', controller.signal)).rejects.toThrow()
  expect(http.post).not.toHaveBeenCalled()
})
