import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  isStrictJsonNumber,
  type StrictJsonObject
} from '@things-link/client-contracts/dashboard/v1'
const state = vi.hoisted(() => ({ accessToken: 'test-token', epoch: 1 }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => state.epoch }))
import {
  createDesignerReadScope,
  fetchBindingMetadata,
  fetchDesignerDeviceCatalog,
  fetchPreviewCurrent
} from '@/api/dashboard-binding'
const deviceId = '11111111-1111-4111-8111-111111111111'
const versionId = '22222222-2222-4222-8222-222222222222'
const response = (body: string, status = 200) =>
  new Response(body, { status, headers: { 'content-type': 'application/json' } })
const metadata = () => ({
  devices: [{ deviceId, status: 'AVAILABLE', currentModelVersionId: versionId }],
  models: [
    {
      versionId,
      digest: 'a'.repeat(64),
      digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
      profile: 'TC_PROPERTY_COMPOSITE_V1',
      properties: [{ propertyKey: 'temperature', dataType: 'NUMBER' }]
    }
  ]
})
let clock = 0
describe('Console设计器有界数据读取', () => {
  beforeEach(() => {
    clock += 61_000
    vi.spyOn(performance, 'now').mockImplementation(() => clock)
    vi.stubEnv('VITE_API_URL', '/')
    state.epoch = 1
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })
  it('拒绝把其他设备模型拼装成本次绑定', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response(JSON.stringify(metadata())))
    expect((await fetchBindingMetadata('project', deviceId)).model.versionId).toBe(versionId)
    const wrong = metadata()
    wrong.devices[0].deviceId = versionId
    vi.mocked(fetch).mockResolvedValueOnce(response(JSON.stringify(wrong)))
    await expect(fetchBindingMetadata('project', deviceId)).rejects.toThrow()
  })
  it('外借完整轮经目录和元数据读取后仍可读取当前值，不被子API提前关闭', async () => {
    const scope = createDesignerReadScope({ kind: 'FULL' })
    vi.mocked(fetch)
      .mockResolvedValueOnce(response(JSON.stringify(metadata())))
      .mockResolvedValueOnce(
        response(JSON.stringify({ items: [], nextCursor: null, hasMore: false }))
      )
      .mockResolvedValueOnce(response('{"devices":[]}'))
    try {
      await fetchBindingMetadata(deviceId, deviceId, scope)
      await fetchDesignerDeviceCatalog(deviceId, versionId, undefined, 20, scope)
      await fetchPreviewCurrent(deviceId, { devices: [] }, scope)
      expect(fetch).toHaveBeenCalledTimes(3)
      expect(vi.mocked(fetch).mock.calls.every(([, init]) => !init?.signal?.aborted)).toBe(true)
    } finally {
      scope.close()
    }
  })
  it('保留大整数原文并使用Console精确端点', async () => {
    vi.mocked(fetch).mockResolvedValue(response('{"devices":[],"probe":9007199254740993}'))
    const scope = createDesignerReadScope()
    try {
      const result = (await fetchPreviewCurrent(
        'project',
        { devices: [] },
        scope
      )) as StrictJsonObject
      expect(isStrictJsonNumber(result.probe)).toBe(true)
      expect(result.probe).toHaveProperty('lexical', '9007199254740993')
      expect(vi.mocked(fetch).mock.calls[0][0]).toBe(
        '/api/v1/projects/project/devices/current-value-snapshots/query'
      )
      expect(vi.mocked(fetch).mock.calls[0][1]?.redirect).toBe('error')
    } finally {
      scope.close()
    }
  })
  it('单响应超4MiB立即中断，不继续解析', async () => {
    vi.mocked(fetch).mockResolvedValue(response(' '.repeat(4 * 1024 * 1024) + '{}'))
    const scope = createDesignerReadScope()
    try {
      await expect(scope.read('/read')).rejects.toThrow('字节')
      expect(vi.mocked(fetch).mock.calls[0][1]?.signal?.aborted).toBe(true)
    } finally {
      scope.close()
    }
  })
  it('实际接收的服务失败正文也消耗整轮8MiB', async () => {
    vi.mocked(fetch).mockImplementation(async () =>
      response(' '.repeat(3 * 1024 * 1024 - 2) + '{}', 503)
    )
    const scope = createDesignerReadScope()
    try {
      await expect(scope.read('/read')).rejects.toThrow('不可用')
      await expect(scope.read('/read')).rejects.toThrow('不可用')
      await expect(scope.read('/read')).rejects.toThrow('字节')
      expect(fetch).toHaveBeenCalledTimes(3)
    } finally {
      scope.close()
    }
  })
  it('固定30秒截止不因第二个请求重新计时，身份换代也拒绝读取', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance'] })
    const scope = createDesignerReadScope()
    await vi.advanceTimersByTimeAsync(30_000)
    await expect(scope.read('/read')).rejects.toThrow()
    expect(fetch).not.toHaveBeenCalled()
    scope.close()
    const changed = createDesignerReadScope()
    state.epoch++
    await expect(changed.read('/read')).rejects.toThrow()
    changed.close()
  })

  it('绑定元数据保留权威边界的原始精度，不经原生浮点舍入', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          response(
            JSON.stringify(metadata()).replace(
              '"dataType":"NUMBER"',
              '"dataType":"NUMBER","minimumValue":0,"maximumValue":9007199254740993'
            )
          )
        )
    )
    const result = await fetchBindingMetadata('project', deviceId)
    expect(result.properties[0]).toMatchObject({
      minimumValue: { kind: 'NUMBER', lexical: '0' },
      maximumValue: { kind: 'NUMBER', lexical: '9007199254740993' }
    })
    vi.unstubAllGlobals()
  })
  it('目录使用Console独立入口、精确模型及分页，保留共享身份预算', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      response(
        JSON.stringify({
          items: [
            { deviceId, name: '设备', deviceStatus: 'ONLINE', currentModelVersionId: versionId }
          ],
          nextCursor: null,
          hasMore: false
        })
      )
    )
    const result = await fetchDesignerDeviceCatalog(deviceId, versionId, 'opaque/+=', 2)
    expect(result.items[0]!.deviceId).toBe(deviceId)
    const [url, init] = vi.mocked(fetch).mock.calls[0]!
    expect(url).toContain(`/projects/${deviceId}/devices/catalog?`)
    expect(String(url)).toContain(`modelVersionId=${versionId}`)
    expect(String(url)).toContain('cursor=opaque%2F%2B%3D')
    expect(init).toMatchObject({
      method: 'GET',
      cache: 'no-store',
      redirect: 'error',
      headers: { Authorization: 'Bearer test-token' }
    })
  })
  it.each(['wrong-model', 'duplicate', 'wrong-cursor', 'extra'])(
    '目录拒绝不可信分页响应：%s',
    async (kind) => {
      const row = {
        deviceId,
        name: '设备',
        deviceStatus: 'ONLINE',
        currentModelVersionId: versionId
      }
      const body: Record<string, unknown> = { items: [row], nextCursor: null, hasMore: false }
      if (kind === 'wrong-model') row.currentModelVersionId = deviceId
      if (kind === 'duplicate') body.items = [row, row]
      if (kind === 'wrong-cursor') body.nextCursor = 'stale'
      if (kind === 'extra') body.secret = 'unexpected'
      vi.mocked(fetch).mockResolvedValueOnce(response(JSON.stringify(body)))
      await expect(fetchDesignerDeviceCatalog(deviceId, versionId)).rejects.toThrow('精确模型合同')
    }
  )
  it.each([401, 403, 404])('明确拒绝%s带状态立即取消正文，无需等待无界错误流', async (status) => {
    const cancel = vi.fn()
    vi.mocked(fetch).mockResolvedValueOnce(new Response(new ReadableStream({ cancel }), { status }))
    await expect(fetchDesignerDeviceCatalog(deviceId, versionId)).rejects.toMatchObject({ status })
    expect(cancel).toHaveBeenCalledTimes(1)
  })
})
