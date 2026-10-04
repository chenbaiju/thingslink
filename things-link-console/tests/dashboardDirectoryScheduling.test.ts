import { mount, flushPromises } from '@vue/test-utils'
import { it, expect, vi, beforeEach } from 'vitest'
import {
  previewInteractionKey,
  type PreviewInteraction
} from '../src/features/dashboard/preview-interaction'
import type { DesignerReadScope } from '../src/api/designer-read-scope'
const api = vi.hoisted(() => ({ fetch: vi.fn(), create: vi.fn(), close: vi.fn() }))
vi.mock('@/api/dashboard-binding', () => ({
  fetchDesignerDeviceCatalog: api.fetch,
  createDesignerReadScope: api.create
}))
import DesignerDeviceSelector from '../src/views/dashboard/designer/components/DesignerDeviceSelector.vue'
const defaults = {
  projectId: 'project',
  modelVersionId: 'model',
  variableKey: 'devices',
  title: '选择设备',
  multiple: true,
  maxItems: 20,
  pageSize: 20,
  selected: [],
  disabled: false
}
const page = {
  items: [
    { deviceId: 'device', name: '可选设备', deviceStatus: 'ONLINE', currentModelVersionId: 'model' }
  ],
  nextCursor: 'next',
  hasMore: true
}
const scope = () => ({ close: vi.fn(), read: vi.fn() }) as unknown as DesignerReadScope
function fixture(interaction?: PreviewInteraction) {
  return mount(DesignerDeviceSelector, {
    props: defaults,
    global: {
      provide: interaction ? { [previewInteractionKey as symbol]: interaction } : {},
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        }
      }
    }
  })
}
beforeEach(() => {
  vi.clearAllMocks()
  api.fetch.mockResolvedValue(page)
  api.create.mockImplementation(() => ({ close: api.close, read: vi.fn() }))
})
it('多个选择器进入同一provider，每次目录页都借用scope且不自行关闭', async () => {
  const borrowed = scope(),
    controller = new AbortController()
  const run = vi.fn(
    async (_key: string, work: (s: DesignerReadScope, signal: AbortSignal) => Promise<unknown>) =>
      work(borrowed, controller.signal)
  )
  const interaction = { run } as PreviewInteraction
  const first = fixture(interaction),
    second = fixture(interaction)
  await second.setProps({ variableKey: 'another-variable' })
  await first.findAll('button')[0]!.trigger('click')
  await flushPromises()
  await second.findAll('button')[0]!.trigger('click')
  await flushPromises()
  expect(run).toHaveBeenCalledTimes(2)
  expect(run.mock.calls[0]![0]).toBe(run.mock.calls[1]![0])
  expect(api.fetch.mock.calls[0]![4]).toBe(borrowed)
  expect(api.create).not.toHaveBeenCalled()
  await first.findAll('button')[1]!.trigger('click')
  await flushPromises()
  expect(JSON.parse(run.mock.calls[2]![0]).at(-1)).toBe('next')
  expect(api.fetch.mock.calls[2]![2]).toBe('next')
  first.unmount()
  second.unmount()
  expect(borrowed.close).not.toHaveBeenCalled()
})
it('排队期间换查询丢弃旧work，不能让尚未开始的目录消耗读取预算', async () => {
  let work!: (s: DesignerReadScope, signal: AbortSignal) => Promise<unknown>
  let resolve!: (value: unknown) => void, reject!: (error: unknown) => void
  const run = vi.fn((_key: string, callback: typeof work) => {
    work = callback
    return new Promise((yes, no) => {
      resolve = yes
      reject = no
    })
  })
  const wrapper = fixture({ run } as PreviewInteraction)
  await wrapper.findAll('button')[0]!.trigger('click')
  await wrapper.setProps({ modelVersionId: 'new-model' })
  await work(scope(), new AbortController().signal).then(resolve, reject)
  await flushPromises()
  expect(api.fetch).not.toHaveBeenCalled()
  expect(wrapper.text()).not.toContain('读取失败')
  expect(wrapper.findAll('button')[0]!.attributes('disabled')).toBeUndefined()
  wrapper.unmount()
})
it('借用读取在途换代或上游取消均不恢复旧页且不关闭借用scope', async () => {
  let resolve!: (value: unknown) => void
  api.fetch.mockReturnValueOnce(
    new Promise((done) => {
      resolve = done
    })
  )
  const borrowed = scope(),
    controller = new AbortController()
  const interaction = {
    run: async <T>(_key: string, work: (s: DesignerReadScope, signal: AbortSignal) => Promise<T>) =>
      work(borrowed, controller.signal)
  }
  const wrapper = fixture(interaction)
  await wrapper.findAll('button')[0]!.trigger('click')
  await wrapper.setProps({ readGeneration: 2 })
  controller.abort()
  resolve(page)
  await flushPromises()
  expect(wrapper.find('li').exists()).toBe(false)
  expect(borrowed.close).not.toHaveBeenCalled()
  expect(wrapper.findAll('button')[0]!.attributes('disabled')).toBeUndefined()
  wrapper.unmount()
})
it('明确目录错误保持手动重试；权限拒绝仍向父级报告', async () => {
  const borrowed = scope(),
    controller = new AbortController()
  const interaction = {
    run: async <T>(_key: string, work: (s: DesignerReadScope, signal: AbortSignal) => Promise<T>) =>
      work(borrowed, controller.signal)
  }
  const wrapper = fixture(interaction)
  api.fetch.mockRejectedValueOnce(new Error('network'))
  await wrapper.findAll('button')[0]!.trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('读取失败')
  for (const status of [401, 403, 404]) {
    api.fetch.mockRejectedValueOnce({ status })
    await wrapper.findAll('button')[0]!.trigger('click')
    await flushPromises()
  }
  expect(wrapper.emitted('denied')).toHaveLength(3)
  expect(borrowed.close).not.toHaveBeenCalled()
  wrapper.unmount()
})
it('无provider旧用法仍拥有并关闭自己的scope', async () => {
  const wrapper = fixture()
  await wrapper.findAll('button')[0]!.trigger('click')
  await flushPromises()
  expect(api.create).toHaveBeenCalledTimes(1)
  expect(api.close).toHaveBeenCalledTimes(1)
  expect(wrapper.get('li').text()).toContain('可选设备')
  wrapper.unmount()
})
