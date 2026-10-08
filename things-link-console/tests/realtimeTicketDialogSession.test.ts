import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, reactive, ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({
  user: {} as {
    isLogin: boolean
    info: { currentProjectId: string; userId: string; tenantId: string; buttons: string[] }
  },
  issue: vi.fn(),
  metadata: vi.fn(),
  catalog: {} as Record<string, unknown>,
  readClose: vi.fn()
}))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => 1 }))
vi.mock('@/composables/usePagedDeviceCatalog', () => ({
  usePagedDeviceCatalog: () => mocks.catalog
}))
vi.mock('@/api/dashboard-binding', () => ({
  fetchBindingMetadata: mocks.metadata,
  createDesignerReadScope: () => ({ close: mocks.readClose })
}))
vi.mock('@/api/realtime-ticket', () => ({ issueRealtimeTicket: mocks.issue }))
import Dialog from '@/views/device/messages/components/RealtimeTicketDialog.vue'
const id = '11111111-1111-4111-8111-111111111111'
const credential = `tcrt1.${id}.${'a'.repeat(43)}`
const issued = () => ({
  ticketId: id,
  credential,
  protocol: 'WS',
  expiresAt: new Date(Date.now() + 300000).toISOString(),
  remainingMs: 300000,
  endpoint: '/api/open/v1/realtime/ws',
  subprotocol: 'tc-realtime-v1'
})
const Select = defineComponent({
  name: 'TestSelect',
  props: ['modelValue', 'disabled'],
  emits: ['update:modelValue', 'change'],
  template: '<div><slot /></div>'
})
function mountDialog() {
  return mount(Dialog, {
    global: {
      stubs: {
        ElDialog: { template: '<section><slot /><slot name="footer" /></section>' },
        ElSelect: Select,
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElInput: { props: ['modelValue'], template: '<input :value="modelValue" />' },
        ElAlert: { props: ['title'], template: '<p>{{ title }}</p>' },
        ElForm: { template: '<div><slot /></div>' },
        ElFormItem: { template: '<div><slot /></div>' },
        ElRadioGroup: { template: '<div><slot /></div>' },
        ElRadioButton: { template: '<span><slot /></span>' },
        ElDescriptions: { template: '<div><slot /></div>' },
        ElDescriptionsItem: { template: '<div><slot /></div>' },
        ElOption: true
      }
    }
  })
}
async function submit(wrapper: ReturnType<typeof mountDialog>) {
  const selects = wrapper.findAllComponents(Select)
  selects[0].vm.$emit('update:modelValue', id)
  selects[0].vm.$emit('change', id)
  await flushPromises()
  selects[1].vm.$emit('update:modelValue', ['temperature'])
  await flushPromises()
  const button = wrapper.findAll('button').find((button) => button.text() === '签发一次性票据')!
  expect(button.attributes('disabled')).toBeUndefined()
  await button.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.user = reactive({
    isLogin: true,
    info: { currentProjectId: id, userId: 'owner', tenantId: 'tenant', buttons: ['device:read'] }
  })
  mocks.catalog = {
    devices: ref([{ id, name: '设备', deviceKey: 'device' }]),
    devicesLoading: ref(false),
    deviceHasMore: ref(false),
    searchDevices: vi.fn(async () => undefined),
    loadMoreDevices: vi.fn(async () => undefined)
  }
  mocks.metadata.mockResolvedValue({
    model: { versionId: id },
    properties: [{ key: 'temperature', dataType: 'NUMBER' }]
  })
  mocks.issue.mockResolvedValue(issued())
})
afterEach(() => {
  vi.useRealTimers()
})
it.each(['offline', 'user', 'project'] as const)(
  '组件%s事件清除已签发秘密并请求关闭',
  async (reason) => {
    const wrapper = mountDialog()
    try {
      await submit(wrapper)
      expect(wrapper.get<HTMLInputElement>('input[aria-label="本次短期秘密"]').element.value).toBe(
        credential
      )
      if (reason === 'offline') window.dispatchEvent(new Event('offline'))
      if (reason === 'user') mocks.user.info.userId = 'another-user'
      if (reason === 'project')
        mocks.user.info.currentProjectId = '22222222-2222-4222-8222-222222222222'
      await flushPromises()
      expect(wrapper.find('input[aria-label="本次短期秘密"]').exists()).toBe(false)
      expect(wrapper.emitted('close')).toHaveLength(1)
      expect(mocks.issue).toHaveBeenCalledTimes(1)
    } finally {
      wrapper.unmount()
    }
  }
)
it('组件离线清除在途请求，迟到的首次秘密不能恢复到窗口', async () => {
  let resolve!: (value: ReturnType<typeof issued>) => void
  mocks.issue.mockReturnValue(
    new Promise((done) => {
      resolve = done
    })
  )
  const wrapper = mountDialog()
  try {
    await submit(wrapper)
    const signal = mocks.issue.mock.calls[0][3] as AbortSignal
    window.dispatchEvent(new Event('offline'))
    expect(signal.aborted).toBe(true)
    resolve(issued())
    await flushPromises()
    expect(wrapper.find('input[aria-label="本次短期秘密"]').exists()).toBe(false)
    expect(wrapper.emitted('close')).toHaveLength(1)
  } finally {
    wrapper.unmount()
  }
})
