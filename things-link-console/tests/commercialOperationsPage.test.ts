import { memoryStorage } from './commercialStorageFixture'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import Page from '@/views/commercial-operations/index.vue'
const mocks = vi.hoisted(() => ({
  user: { info: { userId: 'operator', buttons: ['commercial:adjust'] as string[] } },
  preview: vi.fn(),
  create: vi.fn(),
  recover: vi.fn(),
  revoke: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/commercial', () => ({
  fetchCommercialPreview: mocks.preview,
  createCommercialAdjustment: mocks.create,
  recoverCommercialAdjustment: mocks.recover,
  revokeCommercialAdjustment: mocks.revoke
}))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: mocks.confirm } }))
const container = defineComponent({
  setup(_, { slots }) {
    return () => h('div', [slots.header?.(), slots.default?.()])
  }
})
const button = defineComponent({
  props: ['disabled'],
  setup(p, { slots, attrs }) {
    return () => h('button', { ...attrs, disabled: p.disabled }, slots.default?.())
  }
})
const input = defineComponent({
  props: ['modelValue', 'disabled'],
  emits: ['update:modelValue', 'input'],
  setup(p, { emit, attrs }) {
    return () =>
      h('input', {
        ...attrs,
        value: p.modelValue,
        disabled: p.disabled,
        onInput: (e: Event) => {
          const value = (e.target as HTMLInputElement).value
          emit('update:modelValue', value)
          emit('input', value)
        }
      })
  }
})
const title = defineComponent({
  props: ['title'],
  setup(p) {
    return () => h('div', p.title)
  }
})
function page() {
  return mount(Page, {
    global: {
      stubs: {
        ElCard: container,
        ElForm: container,
        ElFormItem: container,
        ElButton: button,
        ElInput: input,
        ElSelect: container,
        ElOption: true,
        ElAlert: title,
        ElResult: title
      }
    }
  })
}
const tenant = '01900000-0000-7000-8000-000000000001'
const request = {
  dimensionCode: 'DEVICES_MAX',
  amount: '9007199254740993',
  startsAt: '2026-09-20T00:00:00Z',
  endsAt: '2026-10-20T00:00:00Z',
  reason: 'INC-1',
  expectedAssignmentVersion: '1',
  idempotencyKey: 'request-123'
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('localStorage', memoryStorage())
  localStorage.clear()
  mocks.user.info.buttons = ['commercial:adjust']
})
describe('商业运营页面', () => {
  it('无平台权限不渲染审批或调用API', () => {
    mocks.user.info.buttons = []
    const wrapper = page()
    expect(wrapper.text()).toContain('需要平台商业运营权限')
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(mocks.preview).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('刷新恢复原申请并展示精确数量，到期事实不会重新授予', async () => {
    localStorage.setItem(
      'tc-commercial-pending:operator:request-123',
      JSON.stringify({ tenantId: tenant, request })
    )
    mocks.recover.mockResolvedValue({ ...request, tenantId: tenant, id: 'old', status: 'EXPIRED' })
    const wrapper = page()
    expect(wrapper.get('[data-testid="pending-adjustment"]').text()).toContain('9007199254740993')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '查询原申请')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="adjustment-result"]').text()).toContain('已到期')
    expect(mocks.create).not.toHaveBeenCalled()
    expect(localStorage.length).toBe(0)
    wrapper.unmount()
  })
  it('显式核对租户、增量和时间后才提交，取消确认不写入', async () => {
    mocks.preview.mockResolvedValue({
      tenantId: tenant,
      tenantName: '目标客户',
      assignmentVersion: '1',
      dimensions: [{ code: 'DEVICES_MAX', effectiveAmount: '3', unit: 'COUNT' }]
    })
    mocks.confirm.mockRejectedValue('cancel')
    const wrapper = page()
    await wrapper.get('input[placeholder="输入租户的完整 UUID"]').setValue(tenant)
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '读取审批摘要')!
      .trigger('click')
    await flushPromises()
    await wrapper.get('input[placeholder^="完整十进制整数"]').setValue(request.amount)
    await wrapper.get('input[placeholder="2026-10-21T00:00:00Z"]').setValue(request.endsAt)
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '核对并提交审批')!
      .trigger('click')
    await flushPromises()
    expect(mocks.confirm.mock.calls[0][0]).toContain(tenant)
    expect(mocks.confirm.mock.calls[0][0]).toContain(request.amount)
    expect(mocks.create).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
