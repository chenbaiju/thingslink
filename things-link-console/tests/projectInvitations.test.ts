import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, defineComponent, h, inject, provide, reactive, type Ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  fetchProjectInvitations: vi.fn(),
  fetchMyProjectInvitations: vi.fn(),
  acceptProjectInvitation: vi.fn(),
  resendProjectInvitation: vi.fn(),
  revokeProjectInvitation: vi.fn()
}))
const messages = vi.hoisted(() => ({ confirm: vi.fn(), success: vi.fn(), emit: vi.fn() }))
vi.mock('@/api/project-invitations', () => api)
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: messages.confirm },
  ElMessage: { success: messages.success }
}))
vi.mock('@/utils/sys', () => ({ mittBus: { emit: messages.emit } }))
const user = reactive({ info: { userId: 'recipient', tenantId: 'home' } })
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
import ProjectInvitations from '@/components/ProjectInvitations.vue'

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
const table = defineComponent({
  props: ['data'],
  setup(p, { slots }) {
    provide(
      'rows',
      computed(() => p.data)
    )
    return () => h('div', slots.default?.())
  }
})
const column = defineComponent({
  props: ['prop'],
  setup(p, { slots }) {
    const rows = inject<Ref<Record<string, string>[]>>('rows')!
    return () =>
      h(
        'div',
        rows.value.map((row) => h('span', slots.default?.({ row }) ?? row[p.prop]))
      )
  }
})
const row = {
  id: 'invite-1',
  projectId: 'project-1',
  projectName: '测试项目',
  targetEmail: 'recipient@example.test',
  role: 'VIEWER',
  status: 'PENDING',
  revision: 1,
  expiresAt: '2026-10-07T12:00:00Z',
  createdAt: '2026-09-30T12:00:00Z',
  deliveryChannel: 'INBOX',
  deliveryStatus: 'INBOX',
  code: 'c'.repeat(43)
}
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => {
  vi.clearAllMocks()
  user.info.userId = 'recipient'
  api.fetchMyProjectInvitations.mockResolvedValue({ items: [{ ...row }], nextCursor: null })
  api.fetchProjectInvitations.mockResolvedValue({
    items: [{ ...row, code: null }],
    nextCursor: null
  })
  messages.confirm.mockResolvedValue('confirm')
})
afterEach(() => wrapper?.unmount())
function page(projectId?: string) {
  wrapper = mount(ProjectInvitations, {
    props: { projectId },
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElCard: { template: '<section><slot name="header"/><slot/></section>' },
        ElButton: button,
        ElTable: table,
        ElTableColumn: column,
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' },
        ElEmpty: true
      }
    }
  })
  return wrapper
}
async function click(text: string) {
  await wrapper!
    .findAll('button')
    .find((b) => b.text() === text)!
    .trigger('click')
  await flushPromises()
}

describe('项目邀请实际页面行为', () => {
  it('收件人仅显式确认后接受，随后刷新项目目录与邀请状态', async () => {
    page()
    await flushPromises()
    expect(api.acceptProjectInvitation).not.toHaveBeenCalled()
    api.fetchMyProjectInvitations.mockResolvedValue({
      items: [{ ...row, status: 'ACCEPTED', code: null }]
    })
    await click('确认接受')
    expect(api.acceptProjectInvitation).toHaveBeenCalledExactlyOnceWith('invite-1', row.code)
    expect(messages.emit).toHaveBeenCalledWith('projectsChanged')
    expect(wrapper!.text()).toContain('已接受')
    expect(wrapper!.text()).not.toContain(row.code)
  })
  it('管理页展示渠道并只提供重发撤回，不能代替收件人接受', async () => {
    page('project-1')
    await flushPromises()
    expect(wrapper!.text()).not.toContain('确认接受')
    await click('重发')
    expect(api.resendProjectInvitation).toHaveBeenCalledExactlyOnceWith('project-1', 'invite-1')
    await click('撤回')
    expect(api.revokeProjectInvitation).toHaveBeenCalledExactlyOnceWith('project-1', 'invite-1')
    expect(api.acceptProjectInvitation).not.toHaveBeenCalled()
  })
  it('身份变更取消旧请求并拒绝迟到的前任收件人数据', async () => {
    let resolve!: (value: unknown) => void
    api.fetchMyProjectInvitations.mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r
        })
    )
    page()
    const signal = api.fetchMyProjectInvitations.mock.calls[0][1] as AbortSignal
    api.fetchMyProjectInvitations.mockResolvedValue({ items: [] })
    user.info.userId = 'another'
    await flushPromises()
    expect(signal.aborted).toBe(true)
    resolve({ items: [{ ...row }] })
    await flushPromises()
    expect(wrapper!.text()).not.toContain('recipient@example.test')
  })
  it('等待确认时阻止重复点击，取消后不调用接受', async () => {
    let reject!: (reason: unknown) => void
    messages.confirm.mockImplementation(
      () =>
        new Promise((_, r) => {
          reject = r
        })
    )
    page()
    await flushPromises()
    await wrapper!
      .findAll('button')
      .find((b) => b.text() === '确认接受')!
      .trigger('click')
    await wrapper!
      .findAll('button')
      .find((b) => b.text() === '确认接受')!
      .trigger('click')
    expect(messages.confirm).toHaveBeenCalledTimes(1)
    reject('cancel')
    await flushPromises()
    expect(api.acceptProjectInvitation).not.toHaveBeenCalled()
  })
  it('使用服务端游标翻页，失败保留明确错误和刷新入口', async () => {
    api.fetchMyProjectInvitations.mockResolvedValueOnce({
      items: [{ ...row }],
      nextCursor: 'signed-next'
    })
    page()
    await flushPromises()
    api.fetchMyProjectInvitations.mockRejectedValueOnce(new Error('unavailable'))
    await click('下一页')
    expect(api.fetchMyProjectInvitations.mock.calls[1][0]).toBe('signed-next')
    expect(wrapper!.text()).toContain('邀请读取失败')
  })
})
