import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Panel from '@/views/project/end-users/EndUserNotificationContact.vue'
import * as api from '@/api/end-users'
import { HttpError } from '@/utils/http/error'
const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/end-users', () => ({
  fetchEndUserNotificationContact: vi.fn(),
  updateEndUserNotificationContact: vi.fn()
}))
const contact = { voiceNumber: '+8613800000001', smsNumber: null, revision: '9007199254740993' }
let panel: VueWrapper
function render(allowWrite = true) {
  panel = mount(Panel, {
    props: { projectId: 'p', appUserId: 'a', allowWrite },
    global: {
      stubs: {
        ElForm: { template: '<form><slot /></form>' },
        ElFormItem: { template: '<div><slot /></div>' },
        ElInput: {
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue'],
          template:
            '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />'
        },
        ElButton: {
          props: ['disabled'],
          template: '<button type="button" :disabled="disabled"><slot /></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' }
      }
    }
  })
}
const button = (name: string) => panel.findAll('button').find((b) => b.text() === name)!
beforeEach(() => {
  vi.resetAllMocks()
  state.user = reactive({ info: { buttons: ['enduser:manage'] } })
  vi.mocked(api.fetchEndUserNotificationContact).mockResolvedValue(contact)
})
afterEach(() => panel?.unmount())
it('preserves exact revision and saves once before showing confirmed numbers', async () => {
  let resolve!: (value: any) => void
  vi.mocked(api.updateEndUserNotificationContact).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r
      })
  )
  render()
  await flushPromises()
  await panel.findAll('input')[1].setValue('+8613800000002')
  await button('保存接收号码').trigger('click')
  await button('保存接收号码').trigger('click')
  expect(api.updateEndUserNotificationContact).toHaveBeenCalledExactlyOnceWith('p', 'a', {
    voiceNumber: contact.voiceNumber,
    expectedRevision: contact.revision,
    smsNumber: '+8613800000002'
  })
  expect(panel.text()).not.toContain('接收号码已保存。')
  resolve({ ...contact, smsNumber: '+8613800000002', revision: '9007199254740994' })
  await flushPromises()
  expect(panel.text()).toContain('接收号码已保存。')
})
it('unknown outcome only rereads, and read failure does not look unconfigured', async () => {
  render()
  await flushPromises()
  vi.mocked(api.updateEndUserNotificationContact).mockRejectedValue(
    new HttpError('untrusted', 60062)
  )
  vi.mocked(api.fetchEndUserNotificationContact).mockRejectedValue(new Error('network'))
  await panel.findAll('input')[0].setValue('')
  await button('保存接收号码').trigger('click')
  await flushPromises()
  expect(api.updateEndUserNotificationContact).toHaveBeenCalledTimes(1)
  expect(api.fetchEndUserNotificationContact).toHaveBeenCalledTimes(2)
  expect(panel.text()).toContain('读取失败')
  expect(panel.findAll('input')[0].element.value).toBe('')
  expect(button('保存接收号码').attributes('disabled')).toBeDefined()
})
it('project switch drops a late write and loads only new scope', async () => {
  let resolve!: (value: any) => void
  vi.mocked(api.updateEndUserNotificationContact).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r
      })
  )
  render()
  await flushPromises()
  await panel.findAll('input')[0].setValue('')
  await button('保存接收号码').trigger('click')
  vi.mocked(api.fetchEndUserNotificationContact).mockResolvedValue({
    ...contact,
    voiceNumber: null,
    revision: '0'
  })
  await panel.setProps({ projectId: 'next' })
  await flushPromises()
  resolve({ ...contact, revision: '9007199254740994' })
  await flushPromises()
  expect(panel.findAll('input')[0].element.value).toBe('')
  expect(panel.text()).not.toContain('已保存')
})
it('readonly blocks edits and revoked permission clears private content', async () => {
  render(false)
  await flushPromises()
  expect(panel.findAll('input')[0].attributes('disabled')).toBeDefined()
  state.user.info.buttons = []
  await flushPromises()
  expect(panel.findAll('input')).toHaveLength(0)
  expect(api.updateEndUserNotificationContact).not.toHaveBeenCalled()
})
it('rejects local format and invalid read receipts without allowing write', async () => {
  render()
  await flushPromises()
  await panel.findAll('input')[0].setValue('13800000001')
  expect(button('保存接收号码').attributes('disabled')).toBeDefined()
  vi.mocked(api.fetchEndUserNotificationContact).mockResolvedValue({
    ...contact,
    revision: '9223372036854775808'
  })
  await panel.setProps({ appUserId: 'b' })
  await flushPromises()
  expect(panel.text()).toContain('读取失败')
  expect(api.updateEndUserNotificationContact).not.toHaveBeenCalled()
})
