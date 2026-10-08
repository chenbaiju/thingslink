import { describe, expect, it, vi } from 'vitest'
import {
  createApplicationPublication,
  type PublicationContext,
  type PublicationIntent
} from '@/features/application/publication-model'
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const summary = (n: number) => ({
  id: id(n),
  versionNumber: String(n),
  sourceDraftRevision: '7',
  snapshotDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
  snapshotDigest: 'a'.repeat(64),
  publishedAt: '2026-09-08T00:00:00Z'
})
const snapshot = () => ({
  formatVersion: 'tc.application/v1',
  displayName: '应用',
  hostCompatibility: { minInclusive: '1.0.0', maxExclusive: '2.0.0' },
  dashboardRefs: [
    {
      dashboardId: id(201),
      dashboardVersionId: id(202),
      dashboardVersionNumber: '2',
      title: '精确导航',
      schemaVersion: 'tc.dashboard/v1',
      schemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
      schemaDigest: 'b'.repeat(64),
      pages: [{ id: 'main', title: '主页面' }]
    }
  ],
  entryDashboardId: id(201),
  requiredSchemas: ['tc.dashboard/v1'],
  requiredComponents: [] as { kind: string; componentVersion: string }[],
  requiredResources: []
})
const detail = (n: number) => ({ ...summary(n), snapshot: snapshot() })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
function fixture() {
  const context: PublicationContext = {
    projectId: id(100),
    applicationId: id(101),
    identity: 1,
    canRead: true,
    canManage: true,
    available: true,
    draftRevision: '7',
    dirty: false,
    saving: false,
    conflict: false
  }
  const catalog = {
    id: context.applicationId,
    managementName: '应用',
    appKey: 'app_' + 'a'.repeat(32),
    publicationRevision: '3',
    currentVersionId: id(2) as string | null,
    createdAt: '2026-09-08T00:00:00Z',
    updatedAt: '2026-09-08T00:00:00Z'
  }
  const ports = {
    context: () => context,
    detail: vi.fn(async () => ({ ...catalog })),
    list: vi.fn(async (_project: string, _id: string, _cursor?: string) => ({
      items: [summary(2), summary(1)],
      hasMore: false,
      nextCursor: null as string | null
    })),
    version: vi.fn(async (_project: string, _id: string, version: string) =>
      detail(version === id(2) ? 2 : 1)
    ),
    write: vi.fn<(intent: PublicationIntent) => Promise<unknown>>(async () => detail(3)),
    newKey: vi.fn(() => id(200)),
    changed: vi.fn(),
    accessDenied: vi.fn(),
    deleteResourceUnavailable: vi.fn()
  }
  return { context, catalog, ports, model: createApplicationPublication(ports) }
}
describe('应用发布管理意图与权威恢复', () => {
  it.each(['204', 'transport'] as const)(
    '在途%s离线未采纳后恢复404不能清原key或判首写失败',
    async (outcome) => {
      const f = fixture()
      await f.model.open()
      let resolve!: (value: unknown) => void
      let reject!: (error: unknown) => void
      f.ports.write.mockReturnValueOnce(
        new Promise((yes, no) => {
          resolve = yes
          reject = no
        })
      )
      const deleting = f.model.softDelete()
      const first = structuredClone(f.ports.write.mock.calls[0]![0])
      f.context.available = false
      if (outcome === '204') resolve(undefined)
      else reject(new Error('lost'))
      await deleting
      f.context.available = true
      f.ports.write.mockRejectedValueOnce({ code: 60030, status: 404, outcomeUnknown: false })
      await f.model.retry()
      expect(f.ports.write.mock.calls[1]![0]).toEqual(first)
      expect(f.model.getSnapshot()).toMatchObject({ pending: first, deleted: null })
      expect(f.ports.deleteResourceUnavailable).toHaveBeenCalledOnce()
      expect(f.ports.accessDenied).not.toHaveBeenCalled()
      await f.model.softDelete()
      expect(f.ports.newKey).toHaveBeenCalledTimes(1)
      f.ports.write.mockRejectedValueOnce({ code: 10014, status: 409, outcomeUnknown: false })
      await f.model.retry()
      expect(f.model.getSnapshot().deleted?.receipt).toBe('COMPLETION_MARKER')
    }
  )
  it('首个明确400不是传输未知，不冻结新key，也不生成删除终态', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValueOnce({ code: 60043, status: 400, outcomeUnknown: false })
    await f.model.softDelete()
    expect(f.model.getSnapshot()).toMatchObject({ pending: null, catalog: null, deleted: null })
    await f.model.softDelete()
    expect(f.ports.write).toHaveBeenCalledTimes(1)
  })
  it.each([null, id(2)])(
    '任意发布态%s软删除用单CAS进入204独立终态，绝不读已删除历史恢复',
    async (current) => {
      const f = fixture()
      f.catalog.currentVersionId = current
      await f.model.open()
      f.ports.write.mockResolvedValueOnce(undefined)
      await f.model.softDelete()
      expect(f.ports.write.mock.calls[0]![0]).toMatchObject({
        kind: 'SOFT_DELETE',
        body: { expectedPublicationRevision: '3' }
      })
      expect(Object.keys(f.ports.write.mock.calls[0]![0].body)).toEqual([
        'expectedPublicationRevision'
      ])
      expect(f.model.getSnapshot()).toMatchObject({
        deleted: { receipt: 'NO_CONTENT', applicationId: id(101) },
        pending: null,
        catalog: null,
        history: [],
        writing: false
      })
      await f.model.recover()
      await f.model.open()
      await f.model.softDelete()
      expect(f.ports.detail).toHaveBeenCalledTimes(1)
      expect(f.ports.list).toHaveBeenCalledTimes(1)
      expect(f.ports.write).toHaveBeenCalledTimes(1)
    }
  )
  it('未知删除GET60030仅失效业务视图，保key直到409/10014独立终态', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValueOnce({ outcomeUnknown: true })
    await f.model.softDelete()
    const first = structuredClone(f.ports.write.mock.calls[0]![0])
    f.ports.detail.mockRejectedValueOnce({ code: 60030, status: 404 })
    await f.model.recover()
    expect(f.ports.deleteResourceUnavailable).toHaveBeenCalledOnce()
    expect(f.ports.accessDenied).not.toHaveBeenCalled()
    expect(f.model.getSnapshot()).toMatchObject({
      pending: first,
      catalog: null,
      history: [],
      deleted: null
    })
    await f.model.softDelete()
    f.ports.write.mockRejectedValueOnce({ code: 10014, status: 409, outcomeUnknown: false })
    await f.model.retry()
    expect(f.ports.write.mock.calls[1]![0]).toEqual(first)
    expect(f.ports.newKey).toHaveBeenCalledTimes(1)
    expect(f.model.getSnapshot()).toMatchObject({
      deleted: { receipt: 'COMPLETION_MARKER' },
      pending: null
    })
    expect(f.model.getSnapshot().notice).toContain('不重放原204')
    expect(f.ports.detail).toHaveBeenCalledTimes(2)
  })
  it('未知后原键恢复遇60030仍只原意图，首次明确60030则强清', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.write
      .mockRejectedValueOnce({ outcomeUnknown: true })
      .mockRejectedValueOnce({ code: 60030, status: 404, outcomeUnknown: false })
    await f.model.softDelete()
    const pending = f.model.getSnapshot().pending
    await f.model.retry()
    expect(f.model.getSnapshot().pending).toEqual(pending)
    expect(f.ports.deleteResourceUnavailable).toHaveBeenCalledOnce()
    expect(f.ports.accessDenied).not.toHaveBeenCalled()
    f.model.reset()
    await f.model.open()
    f.ports.write.mockRejectedValueOnce({ code: 60030, status: 404, outcomeUnknown: false })
    await f.model.softDelete()
    expect(f.model.getSnapshot().pending).toBeNull()
    expect(f.ports.accessDenied).toHaveBeenCalledWith(60030)
    expect(f.model.getSnapshot().deleted).toBeNull()
  })
  it.each([
    { code: 20001, status: 401 },
    { code: 403, status: 403 },
    { code: 50001, status: 404 }
  ])('UNKNOWN后真实读取失效%s必须销毁原意图，不吞为资源404恢复', async (error) => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValueOnce({ outcomeUnknown: true })
    await f.model.softDelete()
    f.ports.detail.mockRejectedValueOnce(error)
    await f.model.recover()
    expect(f.model.getSnapshot()).toMatchObject({ pending: null, catalog: null, deleted: null })
    expect(f.ports.accessDenied).toHaveBeenCalledWith(error.code)
    expect(f.ports.deleteResourceUnavailable).not.toHaveBeenCalled()
    await f.model.retry()
    expect(f.ports.write).toHaveBeenCalledTimes(1)
  })
  it.each([{ code: 60031, status: 403 }, { code: 50017, status: 403 }, { status: 401 }])(
    '删除原键恢复时写资格明确撤销%s不继续持有恢复键',
    async (error) => {
      const f = fixture()
      await f.model.open()
      f.ports.write.mockRejectedValueOnce({ outcomeUnknown: true }).mockRejectedValueOnce(error)
      await f.model.softDelete()
      await f.model.retry()
      expect(f.model.getSnapshot()).toMatchObject({ pending: null, catalog: null, deleted: null })
      expect(f.ports.accessDenied).toHaveBeenCalled()
    }
  )
  it('单个历史版本60048不能误当整个应用缺失', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValueOnce({ outcomeUnknown: true })
    await f.model.softDelete()
    f.ports.version.mockRejectedValueOnce({ code: 60048, status: 404 })
    await f.model.selectVersion(id(1))
    expect(f.model.getSnapshot()).toMatchObject({
      catalog: f.catalog,
      pending: { kind: 'SOFT_DELETE' }
    })
    expect(f.ports.accessDenied).not.toHaveBeenCalled()
    expect(f.ports.deleteResourceUnavailable).not.toHaveBeenCalled()
  })
  it.each([
    { code: 10010, status: 409 },
    { status: 503, outcomeUnknown: true },
    { code: 10014, status: 500, outcomeUnknown: true },
    { code: 10014, status: 409, outcomeUnknown: true }
  ])('暂不可判定%s只保原键，不冒称完成', async (error) => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValue(error)
    await f.model.softDelete()
    const first = structuredClone(f.ports.write.mock.calls[0]![0])
    f.catalog.publicationRevision = '9'
    await f.model.recover()
    await f.model.retry()
    expect(f.ports.write.mock.calls[1]![0]).toEqual(first)
    expect(f.ports.newKey).toHaveBeenCalledTimes(1)
    expect(f.model.getSnapshot().deleted).toBeNull()
  })
  it.each(['dirty', 'saving', 'conflict'] as const)('%s禁止删除且不静默丢本地', async (flag) => {
    const f = fixture()
    await f.model.open()
    f.context[flag] = true
    await f.model.softDelete()
    expect(f.ports.write).not.toHaveBeenCalled()
    expect(f.ports.newKey).not.toHaveBeenCalled()
  })
  it('CAS60044先重读，晚身份响应和重复点击不能生成终态', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValueOnce({ code: 60044, status: 409, outcomeUnknown: false })
    await f.model.softDelete()
    expect(f.model.getSnapshot()).toMatchObject({ pending: null, deleted: null, catalog: null })
    await f.model.softDelete()
    expect(f.ports.write).toHaveBeenCalledTimes(1)
    await f.model.open()
    const flight = deferred<unknown>()
    f.ports.write.mockReturnValueOnce(flight.promise)
    const deleting = f.model.softDelete()
    await f.model.retry()
    f.context.identity++
    f.model.reset()
    flight.resolve(undefined)
    await deleting
    expect(f.model.getSnapshot().deleted).toBeNull()
    expect(f.ports.write).toHaveBeenCalledTimes(2)
  })
  it('离线迟到204只保UNKNOWN，重新联网可显式原键恢复', async () => {
    const f = fixture()
    await f.model.open()
    const flight = deferred<unknown>()
    f.ports.write.mockReturnValueOnce(flight.promise)
    const deleting = f.model.softDelete()
    f.context.available = false
    flight.resolve(undefined)
    await deleting
    expect(f.model.getSnapshot()).toMatchObject({
      writing: false,
      pending: { status: 'UNKNOWN' },
      deleted: null
    })
    f.context.available = true
    f.ports.write.mockRejectedValueOnce({ code: 10014, status: 409, outcomeUnknown: false })
    await f.model.retry()
    expect(f.model.getSnapshot().deleted?.receipt).toBe('COMPLETION_MARKER')
  })
  it.each(['1.0.0', '1.0.1'])(
    '读取精确组件版本%s且不自动改写发布快照',
    async (componentVersion) => {
      const f = fixture()
      const value = detail(2)
      const withComponent = {
        ...value,
        snapshot: { ...value.snapshot, requiredComponents: [{ kind: 'TEXT', componentVersion }] }
      }
      f.ports.version.mockResolvedValue(withComponent)
      await f.model.open()
      expect(f.model.getSnapshot().error).toBe('')
      expect(f.ports.write).not.toHaveBeenCalled()
    }
  )
  it('发布冻结双revision，成功后只读刷新当前事实', async () => {
    const f = fixture()
    await f.model.open()
    await f.model.publish()
    expect(f.ports.write.mock.calls[0]![0]).toMatchObject({
      kind: 'PUBLISH',
      key: id(200),
      body: { expectedDraftRevision: '7', expectedPublicationRevision: '3' }
    })
    expect(f.model.getSnapshot().pending).toBeNull()
    expect(f.model.getSnapshot().notice).toContain('成功回执')
  })
  it.each(['dirty', 'saving', 'conflict'] as const)('%s阻止发布', async (flag) => {
    const f = fixture()
    await f.model.open()
    f.context[flag] = true
    await f.model.publish()
    expect(f.ports.write).not.toHaveBeenCalled()
    expect(f.model.getSnapshot().error).toContain('草稿')
  })
  it('读取失败不解锁未知写，新编辑不改变同键重试正文', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValue(new Error('network'))
    await f.model.publish()
    const first = structuredClone(f.ports.write.mock.calls[0]![0])
    f.context.draftRevision = '8'
    f.ports.detail.mockRejectedValueOnce(new Error('read failed'))
    await f.model.recover()
    await f.model.withdraw()
    await f.model.publish()
    expect(f.ports.write).toHaveBeenCalledTimes(1)
    await f.model.retry()
    expect(f.ports.write.mock.calls[1]![0]).toEqual(first)
    expect(f.model.getSnapshot().pending?.status).toBe('UNKNOWN')
  })
  it('未知后revision冲突不证明原操作失败，读取事实不解锁', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.write
      .mockRejectedValueOnce(new Error('network'))
      .mockRejectedValueOnce({ code: 60044, status: 409 })
    await f.model.publish()
    await f.model.retry()
    await f.model.recover()
    expect(f.model.getSnapshot().pending?.status).toBe('UNKNOWN')
    await f.model.withdraw()
    expect(f.ports.write).toHaveBeenCalledTimes(2)
  })
  it('10014后必须完整读取事实；读失败保留恢复状态不能再POST', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValue({ code: 10014, status: 409 })
    f.ports.list.mockRejectedValueOnce(new Error('history unavailable'))
    await f.model.publish()
    expect(f.model.getSnapshot().pending?.status).toBe('COMPLETED')
    await f.model.retry()
    expect(f.ports.write).toHaveBeenCalledTimes(1)
    await f.model.recover()
    expect(f.model.getSnapshot().pending).toBeNull()
    expect(f.model.getSnapshot().notice).toContain('不是首次操作回执')
  })
  it('首次明确拒绝清除旧catalog，要求重新读取再开始新意图', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValue({ code: 60044, status: 409 })
    await f.model.publish()
    expect(f.model.getSnapshot().pending).toBeNull()
    expect(f.model.getSnapshot().catalog).toBeNull()
    await f.model.withdraw()
    expect(f.ports.write).toHaveBeenCalledTimes(1)
  })
  it('回滚仅指定版本，撤回只携publicationRevision', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockResolvedValueOnce(detail(1))
    await f.model.rollback(id(1))
    expect(f.ports.write.mock.calls[0]![0]).toMatchObject({
      kind: 'ROLLBACK',
      targetVersionId: id(1),
      body: { expectedPublicationRevision: '3' }
    })
    f.ports.write.mockResolvedValueOnce(undefined)
    await f.model.withdraw()
    expect(f.ports.write.mock.calls[1]![0].body).toEqual({ expectedPublicationRevision: '3' })
    expect(f.context.draftRevision).toBe('7')
  })
  it('单写在途不重复，身份变化和reset丢弃旧回执', async () => {
    const f = fixture()
    await f.model.open()
    const write = deferred<unknown>()
    f.ports.write.mockReturnValue(write.promise)
    const first = f.model.publish()
    await f.model.publish()
    await f.model.retry()
    expect(f.ports.write).toHaveBeenCalledTimes(1)
    f.context.identity++
    f.model.reset()
    write.resolve(detail(3))
    await first
    expect(f.model.getSnapshot().catalog).toBeNull()
    expect(f.model.getSnapshot().notice).toBe('')
  })
  it('失权不发写，迟到详情不能越过reset回填', async () => {
    const f = fixture()
    await f.model.open()
    f.context.canManage = false
    await f.model.publish()
    expect(f.ports.write).not.toHaveBeenCalled()
    const result = deferred<ReturnType<typeof detail>>()
    f.ports.version.mockReturnValue(result.promise)
    const loading = f.model.selectVersion(id(1))
    f.model.reset()
    result.resolve(detail(1))
    await loading
    expect(f.model.getSnapshot().selectedVersion).toBeNull()
  })
  it('下一页保留不透明游标并替换，不无限累积历史', async () => {
    const f = fixture()
    f.ports.list
      .mockResolvedValueOnce({
        items: [summary(3), summary(2)],
        hasMore: true,
        nextCursor: 'opaque+/='
      })
      .mockResolvedValueOnce({ items: [summary(1)], hasMore: false, nextCursor: null })
    await f.model.open()
    await f.model.loadMore()
    expect(f.ports.list.mock.calls[1]![2]).toBe('opaque+/=')
    expect(f.model.getSnapshot().history.map((x) => x.id)).toEqual([id(1)])
  })
  it('详情拒绝摘要漂移，错误来源回执仍保持未知', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.version.mockResolvedValueOnce({ ...detail(1), snapshotDigest: 'b'.repeat(64) })
    await f.model.selectVersion(id(1))
    expect(f.model.getSnapshot().selectedVersion).toBeNull()
    f.ports.write.mockResolvedValueOnce({ ...detail(3), sourceDraftRevision: '8' })
    await f.model.publish()
    expect(f.model.getSnapshot().pending?.status).toBe('UNKNOWN')
  })
  it.each([401, 403, 404, 30001, 50001, 60030])(
    '读取拒绝%s清视图但保留未知原意图，迟到详情不能回填',
    async (code) => {
      const f = fixture()
      await f.model.open()
      await f.model.selectVersion(id(1))
      f.ports.write.mockRejectedValue(new Error('unknown'))
      await f.model.publish()
      const pending = f.model.getSnapshot().pending
      const late = deferred<ReturnType<typeof detail>>()
      f.ports.version.mockReturnValueOnce(late.promise)
      const loading = f.model.selectVersion(id(2))
      f.ports.detail.mockRejectedValueOnce({ code })
      await f.model.recover()
      expect(f.model.getSnapshot()).toMatchObject({
        catalog: null,
        history: [],
        selectedVersion: null,
        nextCursor: null,
        notice: '',
        pending,
        loading: false,
        detailLoading: false
      })
      late.resolve(detail(2))
      await loading
      expect(f.model.getSnapshot().selectedVersion).toBeNull()
      expect(f.model.getSnapshot().pending).toEqual(pending)
    }
  )
  it('下一页读取拒绝清全部旧视图，仍可显式重新读取', async () => {
    const f = fixture()
    f.ports.list.mockResolvedValueOnce({ items: [summary(2)], hasMore: true, nextCursor: 'next' })
    await f.model.open()
    await f.model.selectVersion(id(2))
    f.ports.list.mockRejectedValueOnce({ code: 60030 })
    await f.model.loadMore()
    expect(f.model.getSnapshot()).toMatchObject({
      catalog: null,
      history: [],
      selectedVersion: null,
      loading: false,
      detailLoading: false
    })
    await f.model.open()
    expect(f.model.getSnapshot().catalog).not.toBeNull()
  })
  it('详情失权阻断其他迟到目录读取，但不废弃写在途代次', async () => {
    const f = fixture()
    await f.model.open()
    const read = deferred<typeof f.catalog>()
    f.ports.detail.mockReturnValueOnce(read.promise)
    const refreshing = f.model.open()
    f.ports.version.mockRejectedValueOnce({ code: 403 })
    await f.model.selectVersion(id(1))
    read.resolve({ ...f.catalog })
    await refreshing
    expect(f.model.getSnapshot()).toMatchObject({
      catalog: null,
      history: [],
      loading: false,
      detailLoading: false
    })
    await f.model.open()
    const write = deferred<unknown>()
    f.ports.write.mockReturnValueOnce(write.promise)
    const publishing = f.model.publish()
    f.ports.version.mockRejectedValueOnce({ code: 50001 })
    await f.model.selectVersion(id(1))
    expect(f.model.getSnapshot().writing).toBe(true)
    write.resolve(detail(3))
    await publishing
    expect(f.model.getSnapshot().writing).toBe(false)
    expect(f.model.getSnapshot().pending).toBeNull()
    expect(f.model.getSnapshot().notice).toContain('成功回执')
  })
  it('首次管理写403不等于失去所有历史读取权限', async () => {
    const f = fixture()
    await f.model.open()
    await f.model.selectVersion(id(1))
    f.ports.write.mockRejectedValueOnce({ code: 60031, status: 403 })
    await f.model.publish()
    expect(f.model.getSnapshot().history).toHaveLength(2)
    expect(f.model.getSnapshot().selectedVersion?.id).toBe(id(1))
    expect(f.model.getSnapshot().pending).toBeNull()
  })
})

describe('应用历史快照边界', () => {
  it.each([
    (s: ReturnType<typeof snapshot>) => {
      s.dashboardRefs = []
    },
    (s: ReturnType<typeof snapshot>) => {
      s.entryDashboardId = id(999)
    },
    (s: ReturnType<typeof snapshot>) => {
      s.dashboardRefs.push({ ...s.dashboardRefs[0]! })
    },
    (s: ReturnType<typeof snapshot>) => {
      s.dashboardRefs[0]!.dashboardVersionNumber = '90071992547409930e0'
    },
    (s: ReturnType<typeof snapshot>) => {
      s.requiredSchemas = []
    },
    (s: ReturnType<typeof snapshot>) => {
      s.dashboardRefs[0]!.pages.push({ ...s.dashboardRefs[0]!.pages[0]! })
    },
    (s: ReturnType<typeof snapshot>) => {
      Object.assign(s, { unauthorized: true })
    }
  ])('拒绝损坏快照，不以草稿或看板当前指针补齐', async (mutate) => {
    const f = fixture()
    await f.model.open()
    const result = detail(1)
    mutate(result.snapshot)
    f.ports.version.mockResolvedValueOnce(result)
    await f.model.selectVersion(id(1))
    expect(f.model.getSnapshot().selectedVersion).toBeNull()
    expect(f.model.getSnapshot().error).not.toBe('')
  })
  it('历史目标60048只清目标详情，仍可读当前应用', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.version.mockRejectedValueOnce({ code: 60048, status: 404 })
    await f.model.selectVersion(id(1))
    expect(f.model.getSnapshot().catalog?.id).toBe(f.context.applicationId)
    expect(f.model.getSnapshot().history).toHaveLength(2)
    expect(f.model.getSnapshot().selectedVersion).toBeNull()
  })
  it('Long超安全整数修订与版本保持字符串，回滚保留脏草稿', async () => {
    const f = fixture()
    f.catalog.publicationRevision = '9007199254740993'
    f.context.draftRevision = '9007199254740995'
    f.context.dirty = true
    await f.model.open()
    f.ports.write.mockResolvedValueOnce(detail(1))
    await f.model.rollback(id(1))
    expect(f.ports.write.mock.calls[0]![0].body).toEqual({
      expectedPublicationRevision: '9007199254740993'
    })
    expect(f.context.dirty).toBe(true)
    expect(f.context.draftRevision).toBe('9007199254740995')
  })
})

describe('发布写请求确认不可读', () => {
  it.each([401, 20001, 50001, 60030])('首次%s拒绝清除历史快照', async (code) => {
    const f = fixture()
    await f.model.open()
    await f.model.selectVersion(id(1))
    f.ports.write.mockRejectedValueOnce({
      code,
      status: code === 60030 || code === 50001 ? 404 : 401
    })
    await f.model.publish()
    expect(f.model.getSnapshot()).toMatchObject({
      catalog: null,
      history: [],
      selectedVersion: null
    })
  })
  it('UNKNOWN后确认不可读也清视图，但不证明原请求未执行', async () => {
    const f = fixture()
    await f.model.open()
    await f.model.selectVersion(id(1))
    f.ports.write
      .mockRejectedValueOnce(new Error('lost'))
      .mockRejectedValueOnce({ code: 60030, status: 404 })
    await f.model.publish()
    const first = f.model.getSnapshot().pending
    await f.model.retry()
    expect(f.model.getSnapshot()).toMatchObject({
      catalog: null,
      history: [],
      selectedVersion: null,
      pending: first
    })
  })
})
