import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, h, inject, provide, reactive } from 'vue'
import Panel from '@/views/project/end-users/EndUserDevices.vue'
import * as api from '@/api/end-users'
import { HttpError } from '@/utils/http/error'
const state = vi.hoisted(() => ({ user: {} as any, confirm: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: state.confirm } }))
vi.mock('@/api/end-users', () => ({ fetchEndUserDevices: vi.fn(), unbindEndUserDevice: vi.fn() }))
const binding = {
  deviceId: 'd',
  relationRole: 'PRIMARY',
  status: 'ACTIVE',
  createdAt: '2026-10-01T00:00:00Z'
}
let panel: VueWrapper
function render() {
  panel = mount(Panel, {
    props: { projectId: 'p', appUserId: 'a', allowWrite: true },
    global: {
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot/></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' },
        ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' },
        ElTable: {
          props: ['data'],
          setup(props: any, { slots }: any) {
            provide(
              'rows',
              computed(() => props.data)
            )
            return () => h('div', props.data.length ? slots.default?.() : slots.empty?.())
          }
        },
        ElTableColumn: {
          props: ['prop'],
          setup(props: any, { slots }: any) {
            const rows = inject<any>('rows')
            return () =>
              h(
                'div',
                rows.value.map((row: any) =>
                  slots.default ? slots.default({ row }) : h('span', String(row[props.prop] || ''))
                )
              )
          }
        }
      }
    }
  })
}
function button(name: string) {
  return panel.findAll('button').find((b) => b.text() === name)!
}
beforeEach(() => {
  vi.resetAllMocks()
  state.user = reactive({ info: { userId: 'u', tenantId: 't', buttons: ['enduser:manage'] } })
  state.confirm.mockResolvedValue('confirm')
  vi.mocked(api.fetchEndUserDevices).mockResolvedValue([binding])
})
afterEach(() => panel?.unmount())
it('shows device relationship and closed history without inferring a project role', async () => {
  vi.mocked(api.fetchEndUserDevices).mockResolvedValue([{ ...binding, status: 'CLOSED' }])
  render()
  await flushPromises()
  expect(panel.text()).toContain('CLOSED')
  expect(button('解绑设备').attributes('disabled')).toBeDefined()
  await button('解绑设备').trigger('click')
  expect(api.unbindEndUserDevice).not.toHaveBeenCalled()
})
it('readonly members can read relationships without management controls', async () => {
  state.user.info.buttons = []
  render()
  await flushPromises()
  expect(api.fetchEndUserDevices).toHaveBeenCalledWith('p', 'a')
  expect(panel.text()).toContain('PRIMARY')
  expect(panel.findAll('button').map((b) => b.text())).not.toContain('解绑设备')
})
it('confirms only one unbind and rereads the closed relationship', async () => {
  let done!: (value: string) => void
  state.confirm.mockReturnValue(
    new Promise((resolve) => {
      done = resolve
    })
  )
  render()
  await flushPromises()
  await button('解绑设备').trigger('click')
  await button('解绑设备').trigger('click')
  expect(state.confirm).toHaveBeenCalledTimes(1)
  expect(api.unbindEndUserDevice).not.toHaveBeenCalled()
  vi.mocked(api.fetchEndUserDevices).mockResolvedValue([{ ...binding, status: 'CLOSED' }])
  done('confirm')
  await flushPromises()
  expect(api.unbindEndUserDevice).toHaveBeenCalledExactlyOnceWith('p', 'a', 'd')
  expect(panel.text()).toContain('CLOSED')
})
it('unknown unbind results read facts without repeating DELETE', async () => {
  render()
  await flushPromises()
  vi.mocked(api.unbindEndUserDevice).mockRejectedValue(
    new HttpError('network', -1, { outcomeUnknown: true })
  )
  vi.mocked(api.fetchEndUserDevices).mockResolvedValue([{ ...binding, status: 'CLOSED' }])
  await button('解绑设备').trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('解绑结果未知')
  expect(panel.text()).toContain('CLOSED')
  expect(api.unbindEndUserDevice).toHaveBeenCalledTimes(1)
})
it('rejects a stale confirmation after switching users', async () => {
  let done!: (value: string) => void
  state.confirm.mockReturnValue(
    new Promise((resolve) => {
      done = resolve
    })
  )
  render()
  await flushPromises()
  await button('解绑设备').trigger('click')
  await panel.setProps({ appUserId: 'b' })
  done('confirm')
  await flushPromises()
  expect(api.unbindEndUserDevice).not.toHaveBeenCalled()
})
it('revoked write eligibility during confirmation prevents the write', async () => {
  let done!: (value: string) => void
  state.confirm.mockReturnValue(
    new Promise((resolve) => {
      done = resolve
    })
  )
  render()
  await flushPromises()
  await button('解绑设备').trigger('click')
  await panel.setProps({ allowWrite: false })
  done('confirm')
  await flushPromises()
  expect(api.unbindEndUserDevice).not.toHaveBeenCalled()
})
it('failed or malformed reads clear relationships instead of presenting an empty success', async () => {
  render()
  await flushPromises()
  vi.mocked(api.fetchEndUserDevices).mockResolvedValue([{ ...binding, relationRole: 'OWNER' }])
  await button('刷新设备关系').trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('读取失败')
  expect(panel.text()).not.toContain('PRIMARY')
})
it('ignores a previous user relationship response', async () => {
  let done!: (value: any) => void
  vi.mocked(api.fetchEndUserDevices).mockReturnValueOnce(
    new Promise((resolve) => {
      done = resolve
    })
  )
  render()
  vi.mocked(api.fetchEndUserDevices).mockResolvedValue([])
  await panel.setProps({ appUserId: 'b' })
  await flushPromises()
  done([binding])
  await flushPromises()
  expect(panel.text()).not.toContain('PRIMARY')
  expect(panel.text()).toContain('暂无设备关系')
})
