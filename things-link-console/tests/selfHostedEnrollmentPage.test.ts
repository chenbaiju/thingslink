import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import Page from '@/views/self-hosted-enrollment/index.vue'

const mocks = vi.hoisted(() => ({
  user: { info: { userId: 'operator-1', buttons: ['commercial:adjust'] as string[] } },
  receive: vi.fn(),
  queue: vi.fn()
}))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/self-hosted-enrollment', () => ({
  receiveEnrollment: mocks.receive,
  fetchPendingEnrollments: mocks.queue
}))

const container = defineComponent({
  setup(_, { slots }) {
    return () => h('div', [slots.header?.(), slots.default?.()])
  }
})
const button = defineComponent({
  props: ['disabled'],
  setup(props, { slots, attrs }) {
    return () => h('button', { ...attrs, disabled: props.disabled }, slots.default?.())
  }
})
const alert = defineComponent({
  props: ['title'],
  setup(props) {
    return () => h('div', props.title)
  }
})
const select = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  setup(props, { emit, attrs }) {
    return () =>
      h(
        'select',
        {
          ...attrs,
          value: props.modelValue,
          onChange: (event: Event) =>
            emit('update:modelValue', (event.target as HTMLSelectElement).value)
        },
        [
          h('option', { value: '' }, '请选择'),
          h('option', { value: 'OFFLINE' }, '离线'),
          h('option', { value: 'ONLINE' }, '联网')
        ]
      )
  }
})

function page() {
  return mount(Page, {
    global: {
      stubs: {
        ElCard: container,
        ElAlert: alert,
        ElResult: { template: '<div>需要受控运营身份</div>' },
        ElSelect: select,
        ElOption: true,
        ElButton: button,
        ElTable: container,
        ElTableColumn: true
      }
    }
  })
}
const pending = (suffix: number) => ({
  requestId: `request-${suffix}`,
  deploymentId: `deployment-${suffix}`,
  claimedTenantId: `tenant-${suffix}`,
  firstChannel: 'OFFLINE',
  receivedAt: '2026-09-28T00:00:00Z'
})
beforeEach(() => {
  vi.clearAllMocks()
  mocks.user.info.buttons = ['commercial:adjust']
  mocks.queue.mockResolvedValue([])
})

describe('自部署待审申请页面', () => {
  it('无运营资格时不查询队列，也不显示导入动作', async () => {
    mocks.user.info.buttons = []
    const wrapper = page()
    await flushPromises()
    expect(wrapper.text()).toContain('需要受控运营身份')
    expect(wrapper.find('[data-testid="enrollment-file"]').exists()).toBe(false)
    expect(mocks.queue).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('拒绝超长申请；仅发送原始字节和显式来源，结果仍显示待审', async () => {
    const wrapper = page()
    await flushPromises()
    const input = wrapper.get<HTMLInputElement>('[data-testid="enrollment-file"]')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [{ name: 'bad.tcshreq', size: 252 }]
    })
    await input.trigger('change')
    expect(wrapper.text()).toContain('不超过 251 字节')
    expect(mocks.receive).not.toHaveBeenCalled()

    const bytes = new ArrayBuffer(100)
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [{ name: 'valid.tcshreq', size: 100, arrayBuffer: async () => bytes }]
    })
    await input.trigger('change')
    await wrapper.get('select').setValue('OFFLINE')
    mocks.receive.mockResolvedValue({ ...pending(1), tenantId: 'tenant-1', status: 'PENDING' })
    await wrapper
      .findAll('button')
      .find((item) => item.text().includes('接收待审申请'))!
      .trigger('click')
    await flushPromises()
    expect(mocks.receive).toHaveBeenCalledWith(bytes, 'OFFLINE')
    expect(wrapper.get('[data-testid="enrollment-result"]').text()).toContain(
      '同封套重复提交 · 待审'
    )
    expect(wrapper.get('[data-testid="enrollment-result"]').text()).toContain('未核验')
    expect(wrapper.text()).not.toContain('签发授权')
    wrapper.unmount()
  })

  it('按最后一行使用成对游标翻页，冲突失败不显示成功事实', async () => {
    const firstPage = Array.from({ length: 25 }, (_, index) => pending(index + 1))
    mocks.queue.mockResolvedValueOnce(firstPage).mockResolvedValueOnce([pending(26)])
    const wrapper = page()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((item) => item.text().includes('下一页'))!
      .trigger('click')
    await flushPromises()
    expect(mocks.queue).toHaveBeenNthCalledWith(2, 25, firstPage[24])
    expect(wrapper.text()).toContain('第 2 页')

    const input = wrapper.get<HTMLInputElement>('[data-testid="enrollment-file"]')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [{ name: 'valid.tcshreq', size: 100, arrayBuffer: async () => new ArrayBuffer(100) }]
    })
    await input.trigger('change')
    await wrapper.get('select').setValue('ONLINE')
    mocks.receive.mockRejectedValue(new Error('申请身份冲突'))
    await wrapper
      .findAll('button')
      .find((item) => item.text().includes('接收待审申请'))!
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="enrollment-result"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('申请身份冲突')
    wrapper.unmount()
  })
})
