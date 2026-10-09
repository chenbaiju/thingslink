import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { effectScope, ref, type EffectScope } from 'vue'
import { fetchSearchDevices } from '@/api/device'
import { useDeviceListPagination } from '@/composables/useDeviceListPagination'

vi.mock('@/api/device', () => ({ fetchSearchDevices: vi.fn() }))
const fetchPage = vi.mocked(fetchSearchDevices)
const scopes: EffectScope[] = []
function setup() {
  const project = ref('project-a')
  const identity = ref('user-a')
  const filters = ref({ keyword: '', statuses: ['ONLINE'] as Array<'ONLINE' | 'OFFLINE'> })
  const scope = effectScope()
  scopes.push(scope)
  const pager = scope.run(() =>
    useDeviceListPagination(
      project,
      () => filters.value,
      () => `${project.value}:${identity.value}`
    )
  )!
  return { pager, project, identity, filters, scope }
}
function deferred() {
  let resolve!: (value: Awaited<ReturnType<typeof fetchSearchDevices>>) => void
  const promise = new Promise<Awaited<ReturnType<typeof fetchSearchDevices>>>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
beforeEach(() => fetchPage.mockReset().mockResolvedValue({ items: [], hasMore: false }))
afterEach(() => scopes.splice(0).forEach((scope) => scope.stop()))

it('翻页替换当前数据，返回上一页使用该页游标，末页禁用继续前进', async () => {
  const { pager } = setup()
  fetchPage.mockResolvedValueOnce({ items: [{ id: 'first' }], nextCursor: 'next', hasMore: true })
  await pager.search()
  expect(fetchPage).toHaveBeenLastCalledWith('project-a', {
    keyword: '',
    statuses: ['ONLINE'],
    deviceTypeIds: undefined,
    cursor: undefined,
    limit: 20
  })
  fetchPage.mockResolvedValueOnce({ items: [{ id: 'last' }], hasMore: false })
  await pager.goToPage(2)
  expect(fetchPage.mock.lastCall?.[1].cursor).toBe('next')
  expect(pager.items.value.map((item) => item.id)).toEqual(['last'])
  expect(pager.currentPage.value).toBe(2)
  expect(pager.hasNext.value).toBe(false)
  await pager.goToPage(3)
  expect(fetchPage).toHaveBeenCalledTimes(2)
  await pager.goToPage(1)
  expect(fetchPage.mock.lastCall?.[1].cursor).toBeUndefined()
  expect(pager.currentPage.value).toBe(1)
})

it('翻页保持已提交的筛选快照，重新查询及改变页大小回到第一页', async () => {
  const { pager, filters } = setup()
  fetchPage.mockResolvedValueOnce({ items: [], hasMore: true, nextCursor: 'next' })
  await pager.search()
  filters.value.keyword = 'new'
  filters.value.statuses.push('OFFLINE')
  await pager.goToPage(2)
  expect(pager.currentPage.value).toBe(2)
  expect(fetchPage.mock.lastCall?.[1].keyword).toBe('')
  expect(fetchPage.mock.lastCall?.[1].statuses).toEqual(['ONLINE'])
  await pager.search(50)
  expect(fetchPage.mock.lastCall?.[1]).toMatchObject({
    keyword: 'new',
    limit: 50,
    cursor: undefined
  })
  expect(pager.currentPage.value).toBe(1)
  expect(pager.pageSize.value).toBe(50)
})

it('离开页面后忽略未完成请求，不恢复设备数据', async () => {
  const { pager, scope } = setup()
  const request = deferred()
  fetchPage.mockReturnValueOnce(request.promise)
  const pending = pager.search()
  scope.stop()
  request.resolve({ items: [{ id: 'late' }], hasMore: false })
  await pending
  expect(pager.items.value).toEqual([])
  expect(pager.loading.value).toBe(false)
})

it('翻页失败保留当前页和可重试游标，不把失败当空列表', async () => {
  const { pager } = setup()
  fetchPage.mockResolvedValueOnce({ items: [{ id: 'first' }], hasMore: true, nextCursor: 'next' })
  await pager.search()
  fetchPage.mockRejectedValueOnce(new Error('offline'))
  await pager.goToPage(2)
  expect(pager.currentPage.value).toBe(1)
  expect(pager.items.value[0].id).toBe('first')
  expect(pager.error.value).toContain('查询失败')
  fetchPage.mockResolvedValueOnce({ items: [{ id: 'last' }], hasMore: false })
  await pager.retry()
  expect(fetchPage.mock.lastCall?.[1].cursor).toBe('next')
  expect(pager.currentPage.value).toBe(2)
  expect(pager.error.value).toBe('')
})

it('重叠查询只接受最新筛选的响应', async () => {
  const { pager, filters } = setup()
  const old = deferred()
  fetchPage.mockReturnValueOnce(old.promise)
  const pending = pager.search()
  filters.value.keyword = 'latest'
  fetchPage.mockResolvedValueOnce({ items: [{ id: 'latest' }], hasMore: false })
  await pager.search()
  old.resolve({ items: [{ id: 'stale' }], hasMore: true, nextCursor: 'stale-cursor' })
  await pending
  expect(pager.items.value[0].id).toBe('latest')
  expect(pager.hasNext.value).toBe(false)
})

it.each(['project', 'identity'] as const)('切换%s清空游标并隔离迟到响应', async (key) => {
  const state = setup()
  const old = deferred()
  fetchPage.mockReturnValueOnce(old.promise)
  const pending = state.pager.search()
  fetchPage.mockResolvedValueOnce({ items: [{ id: 'new-scope' }], hasMore: false })
  state[key].value = 'new-scope'
  await Promise.resolve()
  old.resolve({ items: [{ id: 'old-scope' }], hasMore: true, nextCursor: 'old-cursor' })
  await pending
  expect(state.pager.items.value[0].id).toBe('new-scope')
  expect(fetchPage.mock.lastCall?.[1].cursor).toBeUndefined()
  expect(state.pager.currentPage.value).toBe(1)
})

it('缺少或重复的下一页游标显示失败，不继续无限翻页', async () => {
  const { pager } = setup()
  fetchPage.mockResolvedValueOnce({ items: [], hasMore: true, nextCursor: null })
  await pager.search()
  expect(pager.error.value).toContain('查询失败')
  expect(pager.hasNext.value).toBe(false)
  fetchPage.mockResolvedValueOnce({ items: [{ id: 'first' }], hasMore: true, nextCursor: 'same' })
  await pager.search()
  fetchPage.mockResolvedValueOnce({ items: [{ id: 'second' }], hasMore: true, nextCursor: 'same' })
  await pager.goToPage(2)
  expect(pager.currentPage.value).toBe(1)
  expect(pager.items.value[0].id).toBe('first')
  expect(pager.error.value).toContain('查询失败')
})
