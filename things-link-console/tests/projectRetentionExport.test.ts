import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Panel from '@/components/ProjectRetentionExport.vue'
import * as api from '@/api/project-exports'
import { HttpError } from '@/utils/http/error'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/project-exports', () => ({
  fetchLatestProjectExport: vi.fn(),
  requestProjectExport: vi.fn(),
  fetchProjectExport: vi.fn(),
  downloadProjectExport: vi.fn()
}))
const job = {
  id: 'j',
  projectId: 'p',
  projectGeneration: 1,
  status: 'QUEUED',
  attemptCount: 0,
  requestedAt: '2026-10-01T00:00:00Z'
}
let panel: VueWrapper
function render() {
  panel = mount(Panel, {
    props: { projectId: 'p' },
    global: {
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot/></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' }
      }
    }
  })
}
function button(label: string) {
  return panel.findAll('button').find((b) => b.text() === label)!
}
beforeEach(() => {
  vi.resetAllMocks()
  state.user = reactive({ info: { userId: 'u', tenantId: 't' } })
  vi.mocked(api.fetchLatestProjectExport).mockResolvedValue(undefined)
  vi.mocked(api.requestProjectExport).mockResolvedValue(job)
  vi.mocked(api.fetchProjectExport).mockResolvedValue({ ...job, status: 'SUCCEEDED' })
})
afterEach(() => {
  panel?.unmount()
  vi.useRealTimers()
  vi.restoreAllMocks()
})
it('retrieves latest terminal task on mount without creating any job', async () => {
  vi.mocked(api.fetchLatestProjectExport).mockResolvedValue({ ...job, status: 'SUCCEEDED' })
  render()
  await flushPromises()
  expect(panel.text()).toContain('已生成')
  expect(api.requestProjectExport).not.toHaveBeenCalled()
  expect(api.downloadProjectExport).not.toHaveBeenCalled()
})
it('shows no task for 204 and never presents read failure as an empty task', async () => {
  render()
  await flushPromises()
  expect(panel.text()).toContain('尚无本人申请')
  vi.mocked(api.fetchLatestProjectExport).mockRejectedValue(new Error('offline'))
  await button('刷新最新任务').trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('读取失败')
  expect(panel.text()).not.toContain('尚无本人申请')
})
it('polls a fixed pending task via GET and stops after terminal status', async () => {
  vi.useFakeTimers()
  render()
  await flushPromises()
  await button('申请留存导出').trigger('click')
  await flushPromises()
  expect(button('申请留存导出').attributes('disabled')).toBeDefined()
  await vi.advanceTimersByTimeAsync(5000)
  await flushPromises()
  expect(api.fetchProjectExport).toHaveBeenCalledExactlyOnceWith('p', 'j')
  await vi.advanceTimersByTimeAsync(10_000)
  expect(api.fetchProjectExport).toHaveBeenCalledTimes(1)
  expect(api.requestProjectExport).toHaveBeenCalledTimes(1)
})
it('rereads after an uncertain request without automatic POST retry', async () => {
  render()
  await flushPromises()
  vi.mocked(api.requestProjectExport).mockRejectedValue(new Error('lost response'))
  vi.mocked(api.fetchLatestProjectExport).mockResolvedValue(job)
  await button('申请留存导出').trigger('click')
  await flushPromises()
  expect(api.requestProjectExport).toHaveBeenCalledTimes(1)
  expect(panel.text()).toContain('先读取最新任务')
  expect(panel.text()).toContain('排队中')
})
it('blocks duplicate submit while the first request is unresolved', async () => {
  let done!: (v: any) => void
  vi.mocked(api.requestProjectExport).mockReturnValue(
    new Promise((resolve) => {
      done = resolve
    })
  )
  render()
  await flushPromises()
  await button('申请留存导出').trigger('click')
  await button('申请留存导出').trigger('click')
  expect(api.requestProjectExport).toHaveBeenCalledTimes(1)
  done(job)
  await flushPromises()
})
it('gets a short-lived capability only on click without persistent URL storage', async () => {
  vi.mocked(api.fetchLatestProjectExport).mockResolvedValue({ ...job, status: 'SUCCEEDED' })
  vi.mocked(api.downloadProjectExport).mockResolvedValue({
    url: 'https://private.example/export?signature=test',
    expiresAt: '2026-10-01T00:05:00Z'
  })
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  const storage = vi.spyOn(Storage.prototype, 'setItem')
  render()
  await flushPromises()
  expect(api.downloadProjectExport).not.toHaveBeenCalled()
  await button('下载留存包').trigger('click')
  await flushPromises()
  expect(api.downloadProjectExport).toHaveBeenCalledExactlyOnceWith('p', 'j')
  expect(click).toHaveBeenCalledTimes(1)
  expect(panel.html()).not.toContain('signature=test')
  expect(storage).not.toHaveBeenCalled()
})
it('clears previous identity task and rejects stale download capabilities', async () => {
  vi.mocked(api.fetchLatestProjectExport).mockResolvedValue({ ...job, status: 'SUCCEEDED' })
  let done!: (v: any) => void
  vi.mocked(api.downloadProjectExport).mockReturnValue(
    new Promise((resolve) => {
      done = resolve
    })
  )
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  render()
  await flushPromises()
  await button('下载留存包').trigger('click')
  vi.mocked(api.fetchLatestProjectExport).mockResolvedValue(undefined)
  invalidateIdentity()
  await flushPromises()
  done({ url: 'https://private.example/old' })
  await flushPromises()
  expect(click).not.toHaveBeenCalled()
  expect(panel.text()).not.toContain('任务ID：j')
})
it('rejects a latest task belonging to another project', async () => {
  vi.mocked(api.fetchLatestProjectExport).mockResolvedValue({ ...job, projectId: 'other' })
  render()
  await flushPromises()
  expect(panel.text()).toContain('读取失败')
  expect(panel.text()).not.toContain('任务ID')
})
it('stops old polling when leaving the component', async () => {
  vi.useFakeTimers()
  vi.mocked(api.fetchLatestProjectExport).mockResolvedValue(job)
  render()
  await flushPromises()
  panel.unmount()
  await vi.advanceTimersByTimeAsync(10_000)
  expect(api.fetchProjectExport).not.toHaveBeenCalled()
})

it('reports configured storage quota rejection without claiming an unknown result or rerequesting', async () => {
  render()
  await flushPromises()
  vi.mocked(api.requestProjectExport).mockRejectedValue(
    new HttpError('存储额度事实暂不可用', 50019)
  )
  await button('申请留存导出').trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('导出存储额度暂不可用')
  expect(panel.text()).not.toContain('结果未确认')
  expect(api.fetchLatestProjectExport).toHaveBeenCalledTimes(1)
  expect(api.requestProjectExport).toHaveBeenCalledTimes(1)
})
