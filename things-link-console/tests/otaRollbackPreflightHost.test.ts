import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, h, inject, provide, reactive } from 'vue'
import Jobs from '@/views/ota/jobs/index.vue'
import { fetchOtaCampaigns, fetchOtaCampaignJobs, fetchOtaCampaignJob } from '@/api/ota'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/ota', () => ({
  fetchOtaCampaigns: vi.fn(),
  fetchOtaCampaignJobs: vi.fn(),
  fetchOtaCampaignJob: vi.fn()
}))
vi.mock('@/views/ota/jobs/OtaRollbackPreflightPanel.vue', () => ({
  default: {
    name: 'OtaRollbackPreflightPanel',
    props: ['active', 'projectId', 'campaignId', 'jobId'],
    template: '<div class="preflight">{{projectId}}/{{campaignId}}/{{jobId}}</div>'
  }
}))
let wrapper: VueWrapper | undefined
const projectId = '11111111-1111-4111-8111-111111111111',
  campaignId = '22222222-2222-4222-8222-222222222222',
  jobId = '33333333-3333-4333-8333-333333333333',
  otherId = '44444444-4444-4444-8444-444444444444'
const row = { id: jobId, status: 'RECOVERY_REQUIRED', deviceId: 'device-list', stateVersion: '1' }
function deferred() {
  let resolve!: (v: any) => void
  const promise = new Promise<any>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
function render() {
  wrapper = mount(Jobs, {
    global: {
      directives: { loading: () => {} },
      stubs: {
        ConsoleTableAction: { props: ['label'], template: '<button>{{label}}</button>' },
        ConsoleFilterBar: { template: '<div><slot name="field0"/></div>' },
        ElCard: { template: '<div><slot/></div>' },
        ElButton: { template: '<button><slot/></button>' },
        ElAlert: { template: '<div><slot name="title"/><slot/></div>' },
        ElDrawer: {
          props: ['modelValue', 'title'],
          emits: ['update:modelValue'],
          template:
            '<section v-if="modelValue" role="dialog" :aria-label="title"><button class="close" @click="$emit(\'update:modelValue\', false)">关闭</button><slot/></section>'
        },
        ElDescriptions: { template: '<div class="description"><slot/></div>' },
        ElDescriptionsItem: { template: '<p><slot/></p>' },
        ElDivider: { template: '<h3><slot/></h3>' },
        ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' },
        ElTag: { template: '<span><slot/></span>' },
        ElSelect: {
          props: ['modelValue'],
          emits: ['update:modelValue', 'change'],
          template:
            '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\')"><option value=""/><slot/></select>'
        },
        ElOption: {
          props: ['value', 'label'],
          template: '<option :value="value">{{label}}</option>'
        },
        ElTable: {
          props: ['data'],
          setup(props: any, { slots }: any) {
            provide(
              'rows',
              computed(() => props.data)
            )
            return () => h('section', props.data?.length ? slots.default?.() : slots.empty?.())
          }
        },
        ElTableColumn: {
          props: ['prop'],
          setup(props: any, { slots }: any) {
            const rows = inject<any>('rows')
            return () =>
              h(
                'div',
                (rows.value ?? []).map((row: any) =>
                  slots.default ? slots.default({ row }) : h('span', String(row[props.prop] ?? ''))
                )
              )
          }
        }
      }
    }
  })
}
async function select(id = campaignId) {
  await wrapper!.get('select').setValue(id)
  await flushPromises()
}
async function open(index = 0) {
  await wrapper!.findAll('[data-testid="ota-job-detail-open"]')[index]!.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.clearAllMocks()
  state.user = reactive({
    info: {
      currentProjectId: projectId,
      userId: 'owner',
      tenantId: 'tenant',
      roles: ['OWNER'],
      buttons: ['ota:read']
    }
  })
  vi.mocked(fetchOtaCampaigns).mockResolvedValue({
    items: [{ id: campaignId }, { id: otherId }],
    nextCursor: null,
    hasMore: false
  } as any)
  vi.mocked(fetchOtaCampaignJobs).mockResolvedValue({
    items: [row],
    nextCursor: null,
    hasMore: false
  } as any)
  vi.mocked(fetchOtaCampaignJob).mockResolvedValue({
    ...row,
    deviceId: 'current-detail',
    transitions: []
  } as any)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})
it('只在作业详情挂载当前三轴观察，关闭清详情和观察身份', async () => {
  render()
  await flushPromises()
  expect(wrapper!.find('.preflight').exists()).toBe(false)
  await select()
  await open()
  expect(wrapper!.findComponent({ name: 'OtaRollbackPreflightPanel' }).props()).toEqual({
    active: true,
    projectId,
    campaignId,
    jobId
  })
  expect(fetchOtaCampaignJob).toHaveBeenCalledExactlyOnceWith(projectId, campaignId, jobId)
  await wrapper!.get('[role="dialog"] .close').trigger('click')
  expect(wrapper!.find('.preflight').exists()).toBe(false)
})
it('快速换作业，旧详情不能覆盖新详情或传给新观察', async () => {
  vi.mocked(fetchOtaCampaignJobs).mockResolvedValue({
    items: [row, { ...row, id: otherId }],
    nextCursor: null,
    hasMore: false
  } as any)
  const old = deferred()
  vi.mocked(fetchOtaCampaignJob)
    .mockReturnValueOnce(old.promise)
    .mockResolvedValueOnce({ ...row, id: otherId, deviceId: 'new-detail' } as any)
  render()
  await flushPromises()
  await select()
  await open()
  await open(1)
  old.resolve({ ...row, deviceId: 'old-detail' })
  await flushPromises()
  expect(wrapper!.get('.description').text()).toContain('new-detail')
  expect(wrapper!.get('.description').text()).not.toContain('old-detail')
  expect(wrapper!.findComponent({ name: 'OtaRollbackPreflightPanel' }).props('jobId')).toBe(otherId)
})
it.each(['close', 'campaign', 'project', 'tenant', 'user', 'epoch', 'buttons', 'roles'])(
  '%s改变关闭观察，旧详情不复活',
  async (change) => {
    const old = deferred()
    vi.mocked(fetchOtaCampaignJob).mockReturnValueOnce(old.promise)
    render()
    await flushPromises()
    await select()
    await open()
    if (change === 'close') await wrapper!.get('[role="dialog"] .close').trigger('click')
    else if (change === 'campaign') await select(otherId)
    else if (change === 'project') state.user.info.currentProjectId = otherId
    else if (change === 'tenant') state.user.info.tenantId = 'tenant-b'
    else if (change === 'user') state.user.info.userId = 'user-b'
    else if (change === 'epoch') invalidateIdentity()
    else if (change === 'buttons') state.user.info.buttons = []
    else state.user.info.roles = ['VIEWER']
    await flushPromises()
    old.resolve({ ...row, deviceId: 'stale-detail' })
    await flushPromises()
    expect(wrapper!.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper!.find('.preflight').exists()).toBe(false)
  }
)
it('活动切换丢弃旧作业列表响应', async () => {
  const old = deferred()
  vi.mocked(fetchOtaCampaignJobs)
    .mockReturnValueOnce(old.promise)
    .mockResolvedValueOnce({
      items: [{ ...row, id: otherId, deviceId: 'new-list' }],
      nextCursor: null,
      hasMore: false
    } as any)
  render()
  await flushPromises()
  await select()
  await select(otherId)
  old.resolve({ items: [{ ...row, deviceId: 'old-list' }], nextCursor: null, hasMore: false })
  await flushPromises()
  expect(wrapper!.text()).toContain('new-list')
  expect(wrapper!.text()).not.toContain('old-list')
})
