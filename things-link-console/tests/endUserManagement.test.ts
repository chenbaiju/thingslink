import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Page from '@/views/project/end-users/index.vue'
import * as api from '@/api/end-users'
import { fetchProjects } from '@/api/project'
import { HttpError } from '@/utils/http/error'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const state = vi.hoisted(() => ({ user: {} as any, confirm: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: state.confirm } }))
vi.mock('@/api/project', () => ({ fetchProjects: vi.fn() }))
vi.mock('@/api/end-users', () => ({
  fetchEndUsers: vi.fn(),
  lookupEndUser: vi.fn(),
  provisionEndUser: vi.fn(),
  assignEndUserRole: vi.fn(),
  updateEndUserRole: vi.fn(),
  setEndUserRoleStatus: vi.fn()
}))
const account = { id: 'a', username: 'alice', displayName: '用户甲', status: 'ACTIVE' }
const assigned = { ...account, role: 'OBSERVER', roleStatus: 'ACTIVE' }
let page: VueWrapper
function render() {
  page = mount(Page, {
    global: {
      stubs: {
        EndUserDevices: true,
        EndUserDashboardGrants: true,
        ElCard: { template: '<section><slot/></section>' },
        ElForm: { template: '<form><slot/></form>' },
        ElFormItem: { template: '<div><slot/></div>' },
        ElInput: {
          props: ['modelValue', 'type', 'disabled'],
          emits: ['update:modelValue'],
          template:
            '<input :type="type" :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />'
        },
        ElSelect: {
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue'],
          template:
            '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><slot/></select>'
        },
        ElOption: {
          props: ['value', 'label'],
          template: '<option :value="value">{{label}}</option>'
        },
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot/></button>'
        },
        ElTable: {
          props: ['data'],
          template: '<div>{{JSON.stringify(data)}}<slot name="empty" v-if="!data.length"/></div>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' },
        ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' }
      }
    }
  })
}
function button(name: string) {
  return page.findAll('button').find((b) => b.text() === name)!
}
async function search() {
  await page.find('[aria-label="精确用户名"]').setValue('alice')
  await button('查找账号').trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  state.user = reactive({
    info: { currentProjectId: 'p', userId: 'u', tenantId: 't', buttons: ['enduser:manage'] }
  })
  state.confirm.mockResolvedValue('confirm')
  vi.mocked(fetchProjects).mockResolvedValue([
    { id: 'p', status: 'ACTIVE' },
    { id: 'q', status: 'ACTIVE' }
  ])
  vi.mocked(api.fetchEndUsers).mockResolvedValue({ items: [], hasMore: false })
  vi.mocked(api.lookupEndUser).mockResolvedValue(account)
  vi.mocked(api.provisionEndUser).mockResolvedValue(account)
})
afterEach(() => page?.unmount())

it.each(['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'])(
  'renders directory for %s while management follows server permission',
  async (role) => {
    state.user.info.roles = [role]
    state.user.info.buttons = ['OWNER', 'ADMIN'].includes(role) ? ['enduser:manage'] : []
    render()
    await flushPromises()
    expect(api.fetchEndUsers).toHaveBeenCalledWith('p', undefined)
    expect(page.find('[aria-label="精确用户名"]').exists()).toBe(['OWNER', 'ADMIN'].includes(role))
  }
)
it('distinguishes an empty directory from a failed read and clears previous rows', async () => {
  render()
  await flushPromises()
  expect(page.text()).toContain('尚无已分配')
  vi.mocked(api.fetchEndUsers).mockResolvedValue({ items: [assigned] })
  await button('刷新目录').trigger('click')
  await flushPromises()
  expect(page.text()).toContain('alice')
  vi.mocked(api.fetchEndUsers).mockRejectedValue(new Error('offline'))
  await button('刷新目录').trigger('click')
  await flushPromises()
  expect(page.text()).toContain('目录读取失败')
  expect(page.text()).not.toContain('alice')
})
it('provisions only once, clears the password and never silently assigns a role', async () => {
  let finish!: (value: any) => void
  vi.mocked(api.provisionEndUser).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  render()
  await flushPromises()
  await page.find('[aria-label="精确用户名"]').setValue('alice')
  await page.find('[aria-label="初始口令"]').setValue('secret123')
  await button('预置账号').trigger('click')
  await button('预置账号').trigger('click')
  expect(api.provisionEndUser).toHaveBeenCalledTimes(1)
  expect((page.find('[aria-label="初始口令"]').element as HTMLInputElement).value).toBe('')
  finish(account)
  await flushPromises()
  expect(page.text()).toContain('尚未分配项目角色')
  expect(api.assignEndUserRole).not.toHaveBeenCalled()
})
it('recovers an uncertain provision by explicit exact lookup without repeating the POST', async () => {
  vi.mocked(api.provisionEndUser).mockRejectedValue(new Error('lost response'))
  render()
  await flushPromises()
  await page.find('[aria-label="精确用户名"]').setValue('alice')
  await page.find('[aria-label="初始口令"]').setValue('secret123')
  await button('预置账号').trigger('click')
  await flushPromises()
  expect(page.text()).toContain('预置结果未知')
  expect(api.lookupEndUser).not.toHaveBeenCalled()
  await button('查找账号').trigger('click')
  await flushPromises()
  expect(page.text()).toContain('当前账号：alice')
  expect(api.provisionEndUser).toHaveBeenCalledTimes(1)
})
it('shows duplicate username rejection and allows exact lookup without password recovery', async () => {
  vi.mocked(api.provisionEndUser).mockRejectedValue(new HttpError('用户名已存在', 60003))
  render()
  await flushPromises()
  await page.find('[aria-label="精确用户名"]').setValue('alice')
  await page.find('[aria-label="初始口令"]').setValue('secret123')
  await button('预置账号').trigger('click')
  await flushPromises()
  expect(page.text()).toContain('用户名已存在')
  expect(page.text()).not.toContain('预置结果未知')
  await search()
  expect(page.text()).toContain('尚未分配')
})
it('requires confirmation to assign then reads the authoritative project role', async () => {
  render()
  await flushPromises()
  await search()
  vi.mocked(api.lookupEndUser).mockResolvedValue(assigned)
  await button('分配项目角色').trigger('click')
  await flushPromises()
  expect(state.confirm).toHaveBeenCalledTimes(1)
  expect(api.assignEndUserRole).toHaveBeenCalledExactlyOnceWith('p', 'a', 'OBSERVER')
  expect(api.lookupEndUser).toHaveBeenCalledTimes(2)
  expect(page.text()).toContain('项目角色状态：ACTIVE')
})
it('unknown assignment reads facts without another write and retains actionable notice', async () => {
  vi.mocked(api.assignEndUserRole).mockRejectedValue(
    new HttpError('network', -1, { outcomeUnknown: true })
  )
  render()
  await flushPromises()
  await search()
  vi.mocked(api.lookupEndUser).mockResolvedValue(assigned)
  await button('分配项目角色').trigger('click')
  await flushPromises()
  expect(page.text()).toContain('操作结果未知')
  expect(api.assignEndUserRole).toHaveBeenCalledTimes(1)
  expect(page.text()).toContain('项目角色状态：ACTIVE')
})
it('suspends and restores only the selected project role and warns about closed bindings', async () => {
  vi.mocked(api.lookupEndUser).mockResolvedValue(assigned)
  render()
  await flushPromises()
  await search()
  vi.mocked(api.lookupEndUser).mockResolvedValue({ ...assigned, roleStatus: 'DISABLED' })
  await button('停用项目角色').trigger('click')
  await flushPromises()
  expect(state.confirm.mock.calls[0][0]).toContain('恢复角色不会重新打开')
  expect(api.setEndUserRoleStatus).toHaveBeenCalledWith('p', 'a', 'suspend')
  vi.mocked(api.lookupEndUser).mockResolvedValue(assigned)
  await button('恢复项目角色').trigger('click')
  await flushPromises()
  expect(api.setEndUserRoleStatus).toHaveBeenCalledWith('p', 'a', 'restore')
})
it('identity changes cancel an old confirmation and clear credentials before writing', async () => {
  let confirm!: (value: string) => void
  state.confirm.mockReturnValue(
    new Promise((resolve) => {
      confirm = resolve
    })
  )
  render()
  await flushPromises()
  await search()
  await button('分配项目角色').trigger('click')
  state.user.info.currentProjectId = 'q'
  await flushPromises()
  confirm('confirm')
  await flushPromises()
  expect(api.assignEndUserRole).not.toHaveBeenCalled()
  expect(page.text()).not.toContain('当前账号：alice')
})
it('rejects an old lookup response after identity epoch changes', async () => {
  let finish!: (value: any) => void
  vi.mocked(api.lookupEndUser).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  render()
  await flushPromises()
  await page.find('[aria-label="精确用户名"]').setValue('alice')
  await button('查找账号').trigger('click')
  invalidateIdentity()
  await flushPromises()
  finish(account)
  await flushPromises()
  expect(page.text()).not.toContain('当前账号：alice')
})
it('ARCHIVED remains readable but prevents provisioning and project role writes', async () => {
  vi.mocked(fetchProjects).mockResolvedValue([{ id: 'p', status: 'ARCHIVED' }])
  render()
  await flushPromises()
  await search()
  expect(button('分配项目角色').attributes('disabled')).toBeDefined()
  await button('分配项目角色').trigger('click')
  expect(api.assignEndUserRole).not.toHaveBeenCalled()
})
it('uses cursor history and resets pagination on project change', async () => {
  vi.mocked(api.fetchEndUsers).mockResolvedValueOnce({
    items: [assigned],
    nextCursor: 'next',
    hasMore: true
  })
  render()
  await flushPromises()
  await button('下一页').trigger('click')
  await flushPromises()
  expect(api.fetchEndUsers).toHaveBeenLastCalledWith('p', 'next')
  state.user.info.currentProjectId = 'q'
  await flushPromises()
  expect(api.fetchEndUsers).toHaveBeenLastCalledWith('q', undefined)
  expect(page.text()).toContain('第1页')
})

it('changes only an existing project role via PATCH and rereads the selected identity', async () => {
  vi.mocked(api.lookupEndUser).mockResolvedValue(assigned)
  render()
  await flushPromises()
  await search()
  await page.find('[aria-label="目标项目角色"]').setValue('MAINTAINER')
  vi.mocked(api.lookupEndUser).mockResolvedValue({ ...assigned, role: 'MAINTAINER' })
  await button('修改项目角色').trigger('click')
  await flushPromises()
  expect(api.updateEndUserRole).toHaveBeenCalledExactlyOnceWith('p', 'a', 'MAINTAINER')
  expect(api.assignEndUserRole).not.toHaveBeenCalled()
  expect(page.text()).toContain('本项目角色：MAINTAINER')
})

it('rejects a mismatched exact lookup identity instead of offering assignment to another account', async () => {
  vi.mocked(api.lookupEndUser).mockResolvedValue({ ...account, id: 'b', username: 'bob' })
  render()
  await flushPromises()
  await search()
  expect(page.text()).toContain('查找失败')
  expect(page.find('.end-user-selection').exists()).toBe(false)
  expect(api.assignEndUserRole).not.toHaveBeenCalled()
})

it('同一用户角色回读期间保留授权子面板而禁用写，回读失败才清旧资格', async () => {
  vi.mocked(api.lookupEndUser).mockResolvedValue(assigned)
  render()
  await flushPromises()
  await search()
  const original = page.findComponent({ name: 'EndUserDashboardGrants' })
  const uid = (original.vm as any).$?.uid
  let done!: (row: any) => void
  vi.mocked(api.lookupEndUser).mockReturnValueOnce(
    new Promise((resolve) => {
      done = resolve
    })
  )
  await button('停用项目角色').trigger('click')
  await flushPromises()
  expect(page.findComponent({ name: 'EndUserDashboardGrants' }).exists()).toBe(true)
  expect((page.findComponent({ name: 'EndUserDashboardGrants' }).vm as any).$?.uid).toBe(uid)
  expect(page.findComponent({ name: 'EndUserDashboardGrants' }).props('allowWrite')).toBe(false)
  done({ ...assigned, roleStatus: 'DISABLED' })
  await flushPromises()
  expect((page.findComponent({ name: 'EndUserDashboardGrants' }).vm as any).$?.uid).toBe(uid)
  vi.mocked(api.lookupEndUser).mockRejectedValueOnce(new Error('failed'))
  await button('恢复项目角色').trigger('click')
  await flushPromises()
  expect(page.findComponent({ name: 'EndUserDashboardGrants' }).exists()).toBe(false)
  expect(page.text()).toContain('最新角色读取失败')
})
