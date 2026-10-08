import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Panel from '@/components/ProjectRecycleBin.vue'
import * as api from '@/api/project'
import { ElMessageBox, ElTable, ElTableColumn } from 'element-plus'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const state = vi.hoisted(() => ({ user: {} as any, confirm: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/project', () => ({ fetchProjectRecycleBin: vi.fn(), fetchRestoreProject: vi.fn() }))
vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessageBox: { confirm: state.confirm }
}))
const row = {
  id: 'p',
  name: '测试删除项目',
  deletedAt: '2026-10-01T00:00:00Z',
  restoreDeadline: '2026-10-31T00:00:00Z',
  timezone: 'Asia/Shanghai',
  restorable: true
}
let panel: VueWrapper
function render() {
  panel = mount(Panel, {
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElTable,
        ElTableColumn,
        ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot/></div>' },
        ProjectRetentionExport: true,
        ElCard: { template: '<section><slot name="header"/><slot/></section>' },
        ElButton: {
          props: ['disabled', 'loading'],
          template: '<button :disabled="disabled || loading"><slot/></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' },
        ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' }
      }
    }
  })
}
function restore() {
  return panel.findAll('button').find((b) => b.text() === '恢复项目')!
}
beforeEach(() => {
  vi.resetAllMocks()
  state.user = reactive({ info: { userId: 'u', tenantId: 't', currentProjectId: '' } })
  vi.mocked(api.fetchProjectRecycleBin).mockResolvedValue([row])
  vi.mocked(api.fetchRestoreProject).mockResolvedValue({ id: 'p' })
  state.confirm.mockResolvedValue('confirm')
})
afterEach(() => panel?.unmount())
it('reads account-scoped recycle bin without a selected project and confirms restore', async () => {
  render()
  await flushPromises()
  expect(panel.text()).toContain(row.name)
  expect(panel.text()).toContain('恢复截止')
  expect(panel.text()).toContain(row.timezone)
  await restore().trigger('click')
  await flushPromises()
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('旧设备凭据'),
    '恢复项目',
    expect.any(Object)
  )
  expect(api.fetchRestoreProject).toHaveBeenCalledExactlyOnceWith('p')
  expect(panel.emitted('changed')).toHaveLength(1)
  expect(api.fetchProjectRecycleBin).toHaveBeenCalledTimes(2)
})
it('uses server eligibility to disable expired rows', async () => {
  vi.mocked(api.fetchProjectRecycleBin).mockResolvedValue([{ ...row, restorable: false }])
  render()
  await flushPromises()
  expect(restore().attributes('disabled')).toBeDefined()
  expect(panel.text()).toContain('已超过恢复期限')
  await restore().trigger('click')
  expect(api.fetchRestoreProject).not.toHaveBeenCalled()
})
it('guards double-click while confirmation is pending', async () => {
  let done!: (v: any) => void
  state.confirm.mockReturnValue(
    new Promise((resolve) => {
      done = resolve
    })
  )
  render()
  await flushPromises()
  await restore().trigger('click')
  await restore().trigger('click')
  expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
  done('confirm')
  await flushPromises()
  expect(api.fetchRestoreProject).toHaveBeenCalledTimes(1)
})
it('never writes after canceled confirmation', async () => {
  state.confirm.mockRejectedValue('cancel')
  render()
  await flushPromises()
  await restore().trigger('click')
  await flushPromises()
  expect(api.fetchRestoreProject).not.toHaveBeenCalled()
})
it('rereads after an uncertain write without automatically retrying', async () => {
  vi.mocked(api.fetchRestoreProject).mockRejectedValue(new Error('lost response'))
  render()
  await flushPromises()
  await restore().trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('勿直接重复提交')
  expect(api.fetchProjectRecycleBin).toHaveBeenCalledTimes(2)
  expect(api.fetchRestoreProject).toHaveBeenCalledTimes(1)
  expect(panel.emitted('changed')).toHaveLength(1)
})
it('does not present read failures as an empty recycle bin', async () => {
  vi.mocked(api.fetchProjectRecycleBin).mockRejectedValue(new Error('offline'))
  render()
  await flushPromises()
  expect(panel.text()).toContain('回收站读取失败')
  expect(panel.text()).not.toContain('回收站暂无项目')
})
it('discards stale account rows and pending confirmation on identity change', async () => {
  let done!: (v: any) => void
  state.confirm.mockReturnValue(
    new Promise((resolve) => {
      done = resolve
    })
  )
  render()
  await flushPromises()
  await restore().trigger('click')
  vi.mocked(api.fetchProjectRecycleBin).mockResolvedValue([])
  invalidateIdentity()
  await flushPromises()
  expect(panel.text()).not.toContain(row.name)
  done('confirm')
  await flushPromises()
  expect(api.fetchRestoreProject).not.toHaveBeenCalled()
})
it('ignores a previous account response arriving last', async () => {
  let done!: (v: any) => void
  vi.mocked(api.fetchProjectRecycleBin).mockReturnValueOnce(
    new Promise((resolve) => {
      done = resolve
    })
  )
  render()
  vi.mocked(api.fetchProjectRecycleBin).mockResolvedValue([])
  state.user.info.userId = 'other'
  await flushPromises()
  done([row])
  await flushPromises()
  expect(panel.text()).not.toContain(row.name)
})
