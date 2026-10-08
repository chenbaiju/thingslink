import { describe, expect, it, vi } from 'vitest'
import {
  createDashboardPublication,
  type PublicationContext,
  type PublicationIntent
} from '@/features/dashboard/publication-model'
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const summary = (n: number) => ({
  id: id(n),
  versionNumber: String(n),
  sourceDraftRevision: '7',
  schemaVersion: 'tc.dashboard/v1',
  schemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
  schemaDigest: 'a'.repeat(64),
  publishedAt: '2026-09-08T00:00:00Z'
})
const detail = (n: number) => ({
  ...summary(n),
  schema: {
    schemaVersion: 'tc.dashboard/v1',
    presentation: { mode: 'RESPONSIVE_GRID' },
    pages: [{ id: 'main', title: '主页面', components: [] }]
  },
  requiredComponents: [],
  requiredResources: []
})
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
    dashboardId: id(101),
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
    id: context.dashboardId,
    managementName: '看板',
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
    changed: vi.fn()
  }
  return { context, catalog, ports, model: createDashboardPublication(ports) }
}
describe('看板发布管理意图与权威恢复', () => {
  it.each(['204', 'transport'] as const)(
    'A共享回归：在途%s离线未采纳，联网恢复60034仍保持原key',
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
      f.ports.write.mockRejectedValueOnce({ code: 60034, status: 404, outcomeUnknown: false })
      await f.model.retry()
      expect(f.ports.write.mock.calls[1]![0]).toEqual(first)
      expect(f.model.getSnapshot()).toMatchObject({ pending: first, deleted: null })
      await f.model.softDelete()
      expect(f.ports.newKey).toHaveBeenCalledTimes(1)
      f.ports.write.mockRejectedValueOnce({ code: 10014, status: 409, outcomeUnknown: false })
      await f.model.retry()
      expect(f.model.getSnapshot().deleted?.receipt).toBe('COMPLETION_MARKER')
    }
  )
  it.each([
    { code: 60034, status: 404 },
    { code: 10002, status: 400 }
  ])('A共享回归保留首个明确拒绝%s，不误冻原意图', async (error) => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValueOnce({ ...error, outcomeUnknown: false })
    await f.model.softDelete()
    expect(f.model.getSnapshot()).toMatchObject({ pending: null, catalog: null, deleted: null })
    await f.model.softDelete()
    expect(f.ports.write).toHaveBeenCalledTimes(1)
  })
  it('不合合同的500完成码不能冒称终态或换新键', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValue({ code: 10014, status: 500, outcomeUnknown: true })
    await f.model.softDelete()
    await f.model.retry()
    expect(f.model.getSnapshot()).toMatchObject({
      deleted: null,
      pending: { kind: 'SOFT_DELETE', status: 'UNKNOWN' }
    })
    expect(f.ports.newKey).toHaveBeenCalledTimes(1)
  })
  it.each([null, id(2)])(
    '任何发布态%s软删除204进入终态，不读取已删除目录和历史',
    async (current) => {
      const f = fixture()
      f.catalog.currentVersionId = current
      await f.model.open()
      await f.model.selectVersion(id(1))
      f.ports.write.mockResolvedValueOnce(undefined)
      await f.model.softDelete()
      expect(f.ports.write.mock.calls[0]![0]).toMatchObject({
        kind: 'SOFT_DELETE',
        key: id(200),
        body: { expectedPublicationRevision: '3' }
      })
      expect(Object.keys(f.ports.write.mock.calls[0]![0].body)).toEqual([
        'expectedPublicationRevision'
      ])
      expect(f.model.getSnapshot()).toMatchObject({
        deleted: { receipt: 'NO_CONTENT', dashboardId: id(101) },
        catalog: null,
        history: [],
        selectedVersion: null,
        pending: null,
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
  it('未知删除即使读取404仍仅原键恢复10014，不当作原204回执', async () => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValueOnce({ outcomeUnknown: true })
    await f.model.softDelete()
    const first = structuredClone(f.ports.write.mock.calls[0]![0])
    f.ports.detail.mockRejectedValueOnce({ code: 60034, status: 404 })
    await f.model.recover()
    expect(f.model.getSnapshot()).toMatchObject({ catalog: null, pending: first, deleted: null })
    await f.model.publish()
    await f.model.softDelete()
    expect(f.ports.newKey).toHaveBeenCalledTimes(1)
    f.ports.write.mockRejectedValueOnce({ code: 10014, status: 409 })
    await f.model.retry()
    expect(f.ports.write.mock.calls[1]![0]).toEqual(first)
    expect(f.model.getSnapshot()).toMatchObject({
      pending: null,
      deleted: { receipt: 'COMPLETION_MARKER' }
    })
    expect(f.model.getSnapshot().notice).toContain('不重放原204')
    expect(f.ports.detail).toHaveBeenCalledTimes(2)
  })
  it.each([{ code: 10010, status: 409 }, { code: 50017, status: 503 }, { outcomeUnknown: true }])(
    '删除暂不可判定%s原键和body被冻结',
    async (error) => {
      const f = fixture()
      await f.model.open()
      f.ports.write.mockRejectedValue(error)
      await f.model.softDelete()
      const first = structuredClone(f.ports.write.mock.calls[0]![0])
      f.catalog.publicationRevision = '99'
      await f.model.recover()
      await f.model.retry()
      expect(f.ports.write.mock.calls[1]![0]).toEqual(first)
      expect(f.ports.newKey).toHaveBeenCalledTimes(1)
      expect(f.model.getSnapshot().deleted).toBeNull()
    }
  )
  it.each(['dirty', 'saving', 'conflict'] as const)(
    '%s必须先处理，不静默删除本地草稿',
    async (flag) => {
      const f = fixture()
      await f.model.open()
      f.context[flag] = true
      await f.model.softDelete()
      expect(f.ports.write).not.toHaveBeenCalled()
      expect(f.ports.newKey).not.toHaveBeenCalled()
    }
  )
  it.each([
    { code: 60040, status: 409 },
    { code: 60035, status: 403 },
    { code: 50017, status: 403 }
  ])('首次明确拒绝%s不生成删除终态，需要重读CAS', async (error) => {
    const f = fixture()
    await f.model.open()
    f.ports.write.mockRejectedValueOnce(error)
    await f.model.softDelete()
    expect(f.model.getSnapshot()).toMatchObject({ deleted: null, pending: null, catalog: null })
    await f.model.softDelete()
    expect(f.ports.write).toHaveBeenCalledTimes(1)
  })
  it('在途删除双发被拒，身份换代后的204不能删除新资源', async () => {
    const f = fixture()
    await f.model.open()
    const write = deferred<unknown>()
    f.ports.write.mockReturnValueOnce(write.promise)
    const deleting = f.model.softDelete()
    await f.model.retry()
    await f.model.softDelete()
    f.context.identity++
    f.model.reset()
    write.resolve(undefined)
    await deleting
    expect(f.ports.write).toHaveBeenCalledTimes(1)
    expect(f.model.getSnapshot().deleted).toBeNull()
  })
  it('离线迟到204保持未知且释放在途标记，联网后原键可显式恢复', async () => {
    const f = fixture()
    await f.model.open()
    const write = deferred<unknown>()
    f.ports.write.mockReturnValueOnce(write.promise)
    const deleting = f.model.softDelete()
    f.context.available = false
    write.resolve(undefined)
    await deleting
    expect(f.model.getSnapshot()).toMatchObject({
      writing: false,
      pending: { kind: 'SOFT_DELETE', status: 'UNKNOWN' },
      deleted: null
    })
    f.context.available = true
    f.ports.write.mockRejectedValueOnce({ code: 10014, status: 409 })
    await f.model.retry()
    expect(f.model.getSnapshot().deleted?.receipt).toBe('COMPLETION_MARKER')
    expect(f.ports.newKey).toHaveBeenCalledTimes(1)
  })
  it('迟到历史详情不能复活删除终态，错误200正文只能未知', async () => {
    const f = fixture()
    await f.model.open()
    const read = deferred<unknown>()
    f.ports.version.mockReturnValueOnce(read.promise as Promise<ReturnType<typeof detail>>)
    const selecting = f.model.selectVersion(id(1))
    f.ports.write.mockResolvedValueOnce({})
    await f.model.softDelete()
    expect(f.model.getSnapshot().deleted).toBeNull()
    f.ports.write.mockResolvedValueOnce(undefined)
    await f.model.retry()
    read.resolve(detail(1))
    await selecting
    expect(f.model.getSnapshot()).toMatchObject({
      catalog: null,
      history: [],
      selectedVersion: null,
      deleted: { receipt: 'NO_CONTENT' }
    })
  })
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
      .mockRejectedValueOnce({ code: 60040, status: 409 })
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
    f.ports.write.mockRejectedValue({ code: 60040, status: 409 })
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
    f.ports.version.mockResolvedValueOnce({ ...detail(1), schemaDigest: 'b'.repeat(64) })
    await f.model.selectVersion(id(1))
    expect(f.model.getSnapshot().selectedVersion).toBeNull()
    f.ports.write.mockResolvedValueOnce({ ...detail(3), sourceDraftRevision: '8' })
    await f.model.publish()
    expect(f.model.getSnapshot().pending?.status).toBe('UNKNOWN')
  })
  it.each([401, 403, 404, 30001, 50001, 60034])(
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
    f.ports.list.mockRejectedValueOnce({ code: 60034 })
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
    f.ports.write.mockRejectedValueOnce({ code: 60035, status: 403 })
    await f.model.publish()
    expect(f.model.getSnapshot().history).toHaveLength(2)
    expect(f.model.getSnapshot().selectedVersion?.id).toBe(id(1))
    expect(f.model.getSnapshot().pending).toBeNull()
  })
})
