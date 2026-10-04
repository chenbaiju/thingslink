import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createDashboardSharing,
  type SharingContext
} from '../src/features/dashboard/sharing-model'
import { emptyDashboard } from '../src/features/dashboard/designer-model'
const projectId = '11111111-1111-4111-8111-111111111111',
  dashboardId = '22222222-2222-4222-8222-222222222222',
  versionId = '33333333-3333-4333-8333-333333333333',
  shareId = '44444444-4444-4444-8444-444444444444',
  deviceId = '55555555-5555-4555-8555-555555555555',
  modelId = '66666666-6666-4666-8666-666666666666'
const token = 'sh_' + 'A'.repeat(43),
  time = '2026-09-08T00:00:00Z',
  hostCompatibility = { minInclusive: '1.0.0', maxExclusive: '1.0.1' }
const config = {
  available: true,
  hostOrigin: 'https://app.test',
  hostVersion: '1.0.0',
  hostCompatibility
}
const model = {
  versionId: modelId,
  digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256' as const,
  digest: 'a'.repeat(64),
  profile: 'TC_PROPERTY_COMPOSITE_V1' as const
}
const summary = {
  shareId,
  dashboardVersionId: versionId,
  dashboardVersionNumber: '1',
  status: 'ACTIVE',
  refererPolicy: 'HOST_ORIGIN',
  hostCompatibility,
  createdAt: time,
  expiresAt: time,
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
  const context: SharingContext = {
    projectId,
    dashboardId,
    identity: 1,
    available: true,
    canManage: true
  }
  const ports = {
    context: () => context,
    configuration: vi.fn(async (): Promise<unknown> => config),
    list: vi.fn(async (): Promise<unknown> => ({ items: [], hasMore: false, nextCursor: null })),
    create: vi.fn(async (): Promise<unknown> => ({ shareId, secret: token, expiresAt: time })),
    revoke: vi.fn(async (): Promise<unknown> => undefined),
    deviceMetadata: vi.fn(async () => ({ model, properties: [] })),
    newKey: vi.fn(() => 'unique-key'),
    changed: vi.fn()
  }
  return { context, ports, sharing: createDashboardSharing(ports) }
}
async function selected() {
  const result = fixture()
  await result.sharing.open()
  result.sharing.selectVersion({ id: versionId, versionNumber: '1', schema: emptyDashboard() }, '0')
  return result
}
beforeEach(() => vi.clearAllMocks())
describe('分享一次性凭据与范围状态', () => {
  it('静态全版本六字段准确，secret不入快照，仅显式copy回调收到完整链接', async () => {
    const { sharing, ports } = await selected()
    await sharing.create(3600)
    expect(ports.create).toHaveBeenCalledWith({
      projectId,
      dashboardId,
      key: 'unique-key',
      body: {
        dashboardVersionId: versionId,
        expectedDashboardPublicationRevision: '0',
        expiresInSeconds: 3600,
        refererPolicy: 'HOST_ORIGIN',
        hostCompatibility,
        variables: []
      }
    })
    expect(JSON.stringify(sharing.getSnapshot())).not.toContain(token)
    expect(JSON.stringify(ports.changed.mock.calls)).not.toContain(token)
    const write = vi.fn(async () => undefined)
    await sharing.copyLink(write)
    expect(write).toHaveBeenCalledWith(`https://app.test/app/share/${shareId}#token=${token}`)
    sharing.suspend()
    await sharing.copyLink(write)
    expect(write).toHaveBeenCalledTimes(1)
  })
  it('全页声明变量包含默认设备，额外候选去重，精确模型不匹配时不签发', async () => {
    const { sharing, ports } = await selected()
    const schema = {
      ...emptyDashboard(),
      models: [{ key: 'model', ...model }],
      variables: [
        {
          key: 'single',
          type: 'DEVICE_SINGLE',
          title: '单选',
          modelKey: 'model',
          defaultDeviceId: deviceId
        },
        {
          key: 'multi',
          type: 'DEVICE_MULTI',
          title: '多选',
          modelKey: 'model',
          maxItems: 2,
          defaultDeviceIds: [deviceId]
        }
      ],
      pages: [
        { id: 'main', title: '静态首页', components: [] },
        { id: 'second', title: '另一页', components: [] }
      ]
    }
    sharing.selectVersion({ id: versionId, versionNumber: '1', schema }, '1')
    expect(sharing.getSnapshot().pageCount).toBe(2)
    sharing.removeCandidate('single', deviceId)
    expect(sharing.getSnapshot().variables[0]!.deviceIds).toEqual([deviceId])
    sharing.addCandidate('single', shareId)
    sharing.addCandidate('single', shareId)
    await sharing.create(3600)
    expect(ports.deviceMetadata).toHaveBeenCalledTimes(2)
    expect(sharing.getSnapshot().hasSecret).toBe(true)
    sharing.selectVersion({ id: versionId, versionNumber: '1', schema }, '1')
    ports.deviceMetadata.mockResolvedValueOnce({
      model: { ...model, digest: 'b'.repeat(64) },
      properties: []
    })
    await sharing.create(3600)
    expect(ports.create).toHaveBeenCalledTimes(1)
    expect(sharing.getSnapshot().pending).toBeNull()
  })
  it.each([299, 86401, 3600.5])('拒绝非法期限%s，不分配请求键', async (ttl) => {
    const { sharing, ports } = await selected()
    await sharing.create(ttl)
    expect(ports.create).not.toHaveBeenCalled()
    expect(ports.newKey).not.toHaveBeenCalled()
  })
  it('未知后同key重试60052只展示ID，不造链接；撤销后才能明确新建', async () => {
    const { sharing, ports } = await selected()
    ports.create
      .mockRejectedValueOnce({ outcomeUnknown: true })
      .mockRejectedValueOnce({ code: 60052, status: 409, details: [shareId] })
    await sharing.create(3600)
    const original = sharing.getSnapshot().pending
    await sharing.create(7200)
    expect(ports.create).toHaveBeenCalledTimes(1)
    await sharing.retry()
    expect(ports.create.mock.calls[1]).toEqual([original])
    expect(sharing.getSnapshot().recoverShareId).toBe(shareId)
    expect(sharing.getSnapshot().hasSecret).toBe(false)
    await sharing.create(3600)
    expect(ports.create).toHaveBeenCalledTimes(2)
    await sharing.revoke(shareId)
    expect(sharing.getSnapshot().recoverShareId).toBeNull()
    await sharing.create(3600)
    expect(ports.create).toHaveBeenCalledTimes(3)
  })
  it('隐藏时真实创建可能已执行，迟到201丢弃，恢复后403不能清除未知意图', async () => {
    const { sharing, ports, context } = await selected()
    const result = deferred<unknown>()
    ports.create
      .mockImplementationOnce(() => result.promise)
      .mockRejectedValueOnce({ code: 60035, status: 403 })
    const run = sharing.create(3600)
    await Promise.resolve()
    context.available = false
    sharing.suspend()
    result.resolve({ shareId, secret: token, expiresAt: time })
    await run
    expect(sharing.getSnapshot().hasSecret).toBe(false)
    const pending = sharing.getSnapshot().pending
    expect(pending).not.toBeNull()
    context.available = true
    await sharing.open()
    await sharing.retry()
    expect(sharing.getSnapshot().pending).toEqual(pending)
    expect(ports.create.mock.calls[1]).toEqual([pending])
    expect(JSON.stringify(ports.changed.mock.calls)).not.toContain(token)
  })
  it('身份变化重置丢掉旧意图，不向新项目重试；无管理权限无读取', async () => {
    const { sharing, ports, context } = await selected()
    ports.create.mockRejectedValueOnce({ outcomeUnknown: true })
    await sharing.create(3600)
    context.identity++
    context.projectId = shareId
    sharing.reset()
    await sharing.retry()
    expect(ports.create).toHaveBeenCalledTimes(1)
    context.canManage = false
    await sharing.open()
    expect(ports.list).toHaveBeenCalledTimes(1)
  })
  it('配置失败仍读取列表并可撤销；权限失败清旧读视图保留未知键', async () => {
    const { sharing, ports } = await selected()
    ports.configuration.mockRejectedValueOnce({ status: 500 })
    ports.list.mockResolvedValueOnce({ items: [summary], hasMore: false, nextCursor: null })
    await sharing.open()
    expect(sharing.getSnapshot().configuration).toBeNull()
    expect(sharing.getSnapshot().items).toHaveLength(1)
    await sharing.revoke(shareId)
    expect(ports.revoke).toHaveBeenCalledWith(projectId, dashboardId, shareId)
    await sharing.open()
    ports.create.mockRejectedValueOnce({ outcomeUnknown: true })
    await sharing.create(3600)
    ports.configuration.mockRejectedValueOnce({ status: 403, code: 60035 })
    await sharing.open()
    expect(sharing.getSnapshot().scopeDenied).toBe(true)
    expect(sharing.getSnapshot().items).toHaveLength(0)
    expect(sharing.getSnapshot().listLoaded).toBe(false)
    expect(sharing.getSnapshot().versionId).toBeNull()
    expect(sharing.getSnapshot().pending).not.toBeNull()
  })
  it.each([
    { ...config, hostOrigin: 'https://app.test/evil' },
    { ...config, hostCompatibility: { minInclusive: '2.0.0', maxExclusive: '1.0.0' } },
    { ...config, hostVersion: '2.0.0' },
    { available: false, hostOrigin: 'https://app.test', hostVersion: null, hostCompatibility: null }
  ])('非法配置关闭新建但保留列表 %j', async (bad) => {
    const { sharing, ports } = fixture()
    ports.configuration.mockResolvedValueOnce(bad)
    await sharing.open()
    expect(sharing.getSnapshot().configuration).toBeNull()
    expect(sharing.getSnapshot().listLoaded).toBe(true)
  })
  it('规范化合法host大小写和默认443端口，不误拒平台配置', async () => {
    const { sharing, ports } = fixture()
    ports.configuration.mockResolvedValueOnce({ ...config, hostOrigin: 'https://APP.TEST:443' })
    await sharing.open()
    expect(sharing.getSnapshot().configuration?.hostOrigin).toBe('https://app.test')
  })
  it('列表状态以服务端为准；重复撤销同一个ID可恢复，名单不泄露secret', async () => {
    const { sharing, ports } = fixture()
    ports.list.mockResolvedValueOnce({ items: [summary], hasMore: false, nextCursor: null })
    await sharing.open()
    expect(sharing.getSnapshot().items[0]!.status).toBe('ACTIVE')
    ports.revoke.mockRejectedValueOnce({ status: 503 })
    await sharing.revoke(shareId)
    expect(sharing.getSnapshot().revokePending).toBe(shareId)
    await sharing.revoke(shareId)
    expect(ports.revoke).toHaveBeenCalledTimes(2)
    expect(sharing.getSnapshot().items[0]!.status).toBe('REVOKED')
  })
})

it.each([{ status: 403 }, { code: 30001 }])(
  '单路失权%s立即清secret与旧视图，不等待另一读完成',
  async (failure) => {
    const { sharing, ports } = await selected()
    await sharing.create(3600)
    expect(sharing.getSnapshot().hasSecret).toBe(true)
    const pending = deferred<unknown>()
    ports.configuration.mockRejectedValueOnce(failure)
    ports.list.mockImplementationOnce(() => pending.promise)
    const run = sharing.open()
    await Promise.resolve()
    await Promise.resolve()
    expect(sharing.getSnapshot().hasSecret).toBe(false)
    expect(sharing.getSnapshot().scopeDenied).toBe(true)
    expect(sharing.getSnapshot().items).toEqual([])
    const copy = vi.fn(async () => undefined)
    await sharing.copyLink(copy)
    expect(copy).not.toHaveBeenCalled()
    pending.resolve({ items: [summary], hasMore: false, nextCursor: null })
    await run
    expect(sharing.getSnapshot().items).toEqual([])
  }
)

it('60052缺少可证明的shareId仍保留原意图，不当作未创建再分配新键', async () => {
  const { sharing, ports } = await selected()
  ports.create.mockRejectedValueOnce({ code: 60052, status: 409 })
  await sharing.create(3600)
  expect(sharing.getSnapshot().pending).not.toBeNull()
  expect(sharing.getSnapshot().recoverShareId).toBeNull()
  await sharing.create(3600)
  expect(ports.create).toHaveBeenCalledTimes(1)
  expect(ports.newKey).toHaveBeenCalledTimes(1)
})
