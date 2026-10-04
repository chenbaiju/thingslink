import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  previewProjectInvitation: vi.fn(),
  registerWithProjectInvitation: vi.fn()
}))
const actions = vi.hoisted(() => ({ push: vi.fn(), success: vi.fn() }))
vi.mock('@/api/project-invitations', () => api)
vi.mock('element-plus', () => ({ ElMessage: { success: actions.success } }))
const route = reactive({
  params: { invitationId: '00000000-0000-4000-8000-000000000001' },
  query: { code: 'c'.repeat(43) }
})
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: actions.push }) }))
const user = reactive({ isLogin: false })
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
import Entry from '@/views/auth/project-invitation/index.vue'

const input = defineComponent({
  props: ['modelValue', 'readonly', 'type'],
  emits: ['update:modelValue'],
  setup:
    (p, { emit }) =>
    () =>
      h('input', {
        value: p.modelValue,
        readOnly: p.readonly !== undefined && p.readonly !== false,
        type: p.type || 'text',
        onInput: (event: Event) =>
          emit('update:modelValue', (event.target as HTMLInputElement).value)
      })
})
const button = defineComponent({
  props: ['disabled', 'loading'],
  emits: ['click'],
  setup:
    (p, { slots, emit }) =>
    () =>
      h(
        'button',
        { disabled: !!p.disabled || !!p.loading, onClick: () => emit('click') },
        slots.default?.()
      )
})
const checkbox = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  setup:
    (p, { slots, emit }) =>
    () =>
      h('label', [
        h('input', {
          type: 'checkbox',
          checked: p.modelValue,
          onChange: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).checked)
        }),
        slots.default?.()
      ])
})
const invitation = {
  id: route.params.invitationId,
  projectName: '测试项目',
  targetEmail: 'bound@example.test',
  role: 'VIEWER',
  expiresAt: '2026-10-07T12:00:00Z'
}
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => {
  vi.clearAllMocks()
  user.isLogin = false
  route.params.invitationId = invitation.id
  route.query.code = 'c'.repeat(43)
  api.previewProjectInvitation.mockResolvedValue({ ...invitation })
  api.registerWithProjectInvitation.mockResolvedValue(undefined)
})
afterEach(() => wrapper?.unmount())
function page() {
  wrapper = mount(Entry, {
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElCard: { template: '<section><slot/></section>' },
        ElForm: { template: '<form><slot/></form>' },
        ElFormItem: { template: '<label><slot/></label>' },
        ElInput: input,
        ElButton: button,
        ElCheckbox: checkbox,
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' }
      }
    }
  })
  return wrapper
}

describe('邀请注册链接入口', () => {
  it('锁定绑定邮箱并保持注册后仍需邮箱验证和显式接受', async () => {
    page()
    await flushPromises()
    const email = wrapper!.get('input[type="text"]')
    expect((email.element as HTMLInputElement).readOnly).toBe(true)
    expect((email.element as HTMLInputElement).value).toBe('bound@example.test')
    await email.setValue('attacker@example.test')
    for (const field of wrapper!.findAll('input[type="password"]'))
      await field.setValue('a-long-test-password')
    await wrapper!.get('input[type="checkbox"]').setValue(true)
    await wrapper!
      .findAll('button')
      .find((b) => b.text() === '按此邮箱注册')!
      .trigger('click')
    await flushPromises()
    expect(api.registerWithProjectInvitation).toHaveBeenCalledExactlyOnceWith({
      invitationId: invitation.id,
      code: 'c'.repeat(43),
      email: 'bound@example.test',
      password: 'a-long-test-password'
    })
    expect(wrapper!.text()).toContain('完成验证并登录后，在个人中心确认接受邀请')
    expect(actions.push).not.toHaveBeenCalled()
  })
  it('缺码不发预览请求，也不展示注册字段', async () => {
    route.query.code = ''
    page()
    await flushPromises()
    expect(api.previewProjectInvitation).not.toHaveBeenCalled()
    expect(wrapper!.text()).toContain('邀请不可用')
    expect(wrapper!.find('input').exists()).toBe(false)
  })
  it('登录用户只前往自己的收件箱，不因打开链接自动接受', async () => {
    user.isLogin = true
    page()
    await flushPromises()
    expect(wrapper!.find('input[type="password"]').exists()).toBe(false)
    await wrapper!
      .findAll('button')
      .find((b) => b.text() === '到个人中心确认接受')!
      .trigger('click')
    expect(actions.push).toHaveBeenCalledWith('/system/user-center')
    expect(api.registerWithProjectInvitation).not.toHaveBeenCalled()
  })
  it('链接切换后旧预览不能覆盖当前邀请', async () => {
    let resolve!: (value: unknown) => void
    api.previewProjectInvitation.mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r
        })
    )
    page()
    const signal = api.previewProjectInvitation.mock.calls[0][1] as AbortSignal
    api.previewProjectInvitation.mockResolvedValue({
      ...invitation,
      targetEmail: 'next@example.test'
    })
    route.params.invitationId = '00000000-0000-4000-8000-000000000002'
    await flushPromises()
    expect(signal.aborted).toBe(true)
    resolve(invitation)
    await flushPromises()
    expect((wrapper!.get('input[type="text"]').element as HTMLInputElement).value).toBe(
      'next@example.test'
    )
  })
})
