import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import {
  countResourcePages,
  countWorkbenchResource,
  workbenchResources
} from '@/utils/workbench-resources'
import Resources from '@/views/dashboard/workbench/WorkbenchResources.vue'
import request from '@/utils/http'

vi.mock('@/utils/http', () => ({ default: { get: vi.fn() } }))
let wrapper: VueWrapper | undefined
beforeEach(() => vi.resetAllMocks())
afterEach(() => wrapper?.unmount())

it('counts the complete directory, deduplicating resources across cursor pages', async () => {
  const read = vi
    .fn()
    .mockResolvedValueOnce({ items: [{ id: '1' }, { id: '2' }], nextCursor: 'next', hasMore: true })
    .mockResolvedValueOnce({ items: [{ id: '2' }, { id: '3' }], nextCursor: null, hasMore: false })
  expect(await countResourcePages(read, () => true)).toBe(3)
  expect(read.mock.calls).toEqual([[undefined], ['next']])
})

it('rejects incomplete or looping pagination and never returns a partial total', async () => {
  const read = vi.fn().mockResolvedValue({ items: [{ id: '1' }], nextCursor: 'repeat' })
  await expect(countResourcePages(read, () => true)).rejects.toThrow('游标重复')
  await expect(
    countResourcePages(
      async () => ({ items: [], hasMore: true }),
      () => true
    )
  ).rejects.toThrow('续页游标')
  await expect(
    countResourcePages(
      async () => ({}),
      () => true
    )
  ).rejects.toThrow('缺少条目')
})

it('stops continuing pages when the identity changes during a request', async () => {
  let current = true
  const read = vi.fn(async () => {
    current = false
    return { items: [{ id: '1' }], nextCursor: 'next' }
  })
  await expect(countResourcePages(read, () => current)).rejects.toThrow('身份已改变')
  expect(read).toHaveBeenCalledTimes(1)
})

it('reads every supported project directory including complete array responses', async () => {
  vi.mocked(request.get).mockImplementation(async ({ url }) =>
    url.endsWith('/device-groups') || url.endsWith('/task-jobs')
      ? [{ id: '1' }, { id: '2' }]
      : { items: [{ id: '1' }, { id: '2' }] }
  )
  for (const resource of workbenchResources) {
    expect(await countWorkbenchResource(resource, 'project-a', () => true)).toBe(2)
  }
  expect(request.get).toHaveBeenCalledTimes(12)
  expect(
    vi.mocked(request.get).mock.calls.every(([args]) => args.url.includes('/projects/project-a/'))
  ).toBe(true)
})

function render(paths: string[]) {
  wrapper = mount(Resources, {
    props: { projectId: 'a', scopeKey: 'user-a', allowedPaths: paths },
    global: { stubs: { ArtSvgIcon: true } }
  })
  return wrapper
}

it('only reads permitted directories and distinguishes a real zero from a failed count', async () => {
  vi.mocked(request.get)
    .mockResolvedValueOnce({ items: [] })
    .mockRejectedValueOnce(new Error('offline'))
  const view = render(['/device/types', '/alarm/rules'])
  await flushPromises()
  expect(view.findAll('li')).toHaveLength(2)
  expect(view.get('[aria-label="设备类型数量"]').text()).toBe('0')
  expect(view.get('[aria-label="告警规则数量"]').text()).toBe('暂不可用')
  expect(request.get).toHaveBeenCalledTimes(2)
})

it('ignores old project results and clears rows immediately when read permissions are lost', async () => {
  let release!: (value: unknown) => void
  vi.mocked(request.get)
    .mockReturnValueOnce(
      new Promise((resolve) => {
        release = resolve
      })
    )
    .mockResolvedValueOnce({ items: [{ id: 'new' }] })
  const view = render(['/device/types'])
  await view.setProps({ projectId: 'b', scopeKey: 'user-b' })
  await flushPromises()
  release({ items: [{ id: 'old-1' }, { id: 'old-2' }] })
  await flushPromises()
  expect(view.get('[aria-label="设备类型数量"]').text()).toBe('1')
  await view.setProps({ allowedPaths: [] })
  await flushPromises()
  expect(view.findAll('li')).toHaveLength(0)
  expect(request.get).toHaveBeenCalledTimes(2)
})
