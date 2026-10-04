import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import Page from '@/views/self-hosted-review/index.vue'

const mocks = vi.hoisted(() => ({
  user: { info: { userId: 'reviewer-one', buttons: ['self_hosted:review'] as string[] } },
  detail: vi.fn(),
  attest: vi.fn()
}))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/self-hosted-review', () => ({
  fetchReviewDetail: mocks.detail,
  submitReview: mocks.attest
}))

const container = defineComponent({
  setup(_, { slots }) {
    return () => h('div', [slots.header?.(), slots.default?.()])
  }
})
const input = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  setup(props, { emit, attrs }) {
    return () =>
      h('input', {
        ...attrs,
        value: props.modelValue,
        onInput: (event: Event) =>
          emit('update:modelValue', (event.target as HTMLInputElement).value)
      })
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
        [h('option', { value: '' }, '请选择'), h('option', { value: 'FREE' }, '免费版')]
      )
  }
})
const checkbox = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  setup(props, { emit, attrs, slots }) {
    return () =>
      h('label', {}, [
        h('input', {
          ...attrs,
          type: 'checkbox',
          checked: props.modelValue,
          onChange: (event: Event) =>
            emit('update:modelValue', (event.target as HTMLInputElement).checked)
        }),
        slots.default?.()
      ])
  }
})
const button = defineComponent({
  props: ['disabled'],
  setup(props, { slots, attrs }) {
    return () => h('button', { ...attrs, disabled: props.disabled }, slots.default?.())
  }
})
const detail = {
  requestId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  deploymentId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  claimedTenantId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  publicKeySha256: '1'.repeat(64),
  requestSha256: '2'.repeat(64),
  revisionId: 'revision-1',
  revisionSha256: '3'.repeat(64),
  attestationCount: 0
}
function page() {
  return mount(Page, {
    global: {
      stubs: {
        ElCard: container,
        ElAlert: { props: ['title'], template: '<div>{{ title }}</div>' },
        ElResult: { template: '<div>需要独立审核资格</div>' },
        ElInput: input,
        ElSelect: select,
        ElOption: true,
        ElCheckbox: checkbox,
        ElButton: button
      }
    }
  })
}
const action = (wrapper: ReturnType<typeof page>, label: string) =>
  wrapper.findAll('button').find((item) => item.text().includes(label))!

beforeEach(() => {
  vi.clearAllMocks()
  mocks.user.info.buttons = ['self_hosted:review']
  mocks.detail.mockResolvedValue(detail)
  mocks.attest.mockResolvedValue({
    requestId: detail.requestId,
    attestationCount: 1,
    readyForIssuanceReview: true
  })
})

describe('自部署独立审核页面', () => {
  it('运营资格不继承审核权，且不调用审核 API', async () => {
    mocks.user.info.buttons = ['commercial:adjust']
    const wrapper = page()
    await flushPromises()
    expect(wrapper.text()).toContain('需要独立审核资格')
    expect(mocks.detail).not.toHaveBeenCalled()
    expect(mocks.attest).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('只用服务端摘要及人工证据构造审核输入，不宣称签发', async () => {
    const wrapper = page()
    await wrapper.get('[data-testid="review-request-id"]').setValue(detail.requestId)
    await action(wrapper, '读取申请摘要').trigger('click')
    await flushPromises()
    expect(mocks.detail).toHaveBeenCalledWith(detail.requestId)
    expect(wrapper.text()).toContain('声明租户（未核验）')
    expect(action(wrapper, '记录独立审核事实').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="review-organization"]').setValue('CASE/2026-01')
    await wrapper.get('[data-testid="review-evidence"]').setValue('4'.repeat(64))
    await wrapper.get('[data-testid="review-tier"]').setValue('FREE')
    await wrapper.get('[data-testid="review-confirm"] input').setValue(true)
    await action(wrapper, '记录独立审核事实').trigger('click')
    await flushPromises()
    expect(mocks.attest).toHaveBeenCalledWith(detail.requestId, {
      deploymentId: detail.deploymentId,
      tenantId: detail.claimedTenantId,
      publicKeySha256: detail.publicKeySha256,
      requestSha256: detail.requestSha256,
      organizationReference: 'CASE/2026-01',
      evidenceSha256: '4'.repeat(64),
      tier: 'FREE',
      revisionId: detail.revisionId,
      revisionSha256: detail.revisionSha256
    })
    expect(wrapper.text()).toContain('不构成授权签发或现场激活')
    expect(wrapper.text()).toContain('审核记录已具备')
    expect(action(wrapper, '记录独立审核事实').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('审核冲突不伪装成功，要求重新读取摘要并人工核对', async () => {
    mocks.attest.mockRejectedValue(new Error('申请审核事实冲突'))
    const wrapper = page()
    await wrapper.get('[data-testid="review-request-id"]').setValue(detail.requestId)
    await action(wrapper, '读取申请摘要').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="review-organization"]').setValue('CASE/2026-01')
    await wrapper.get('[data-testid="review-evidence"]').setValue('4'.repeat(64))
    await wrapper.get('[data-testid="review-tier"]').setValue('FREE')
    await wrapper.get('[data-testid="review-confirm"] input').setValue(true)
    await action(wrapper, '记录独立审核事实').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('重新读取摘要及审核进度后人工核对')
    expect(wrapper.find('[data-testid="review-progress"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="review-detail"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
