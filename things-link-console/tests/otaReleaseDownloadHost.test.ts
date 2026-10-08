import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, h, inject, provide, reactive } from 'vue'
import Firmwares from '@/views/ota/firmwares/index.vue'
import { fetchOtaFirmwares } from '@/api/ota'
const state = vi.hoisted(() => ({ user: {} as any, deploy: true }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/hooks/core/useAuth', () => ({
  useAuth: () => ({ hasAuth: (name: string) => name === 'ota:deploy' && state.deploy })
}))
vi.mock('@/api/device', () => ({ fetchDeviceTypePage: vi.fn() }))
vi.mock('@/api/ota', () => ({
  fetchOtaFirmwares: vi.fn(),
  cancelOtaFirmware: vi.fn(),
  cancelOtaUpload: vi.fn(),
  createOtaFirmware: vi.fn(),
  createOtaPublication: vi.fn(),
  createOtaUpload: vi.fn(),
  deprecateOtaFirmware: vi.fn(),
  fetchLatestThingModelVersion: vi.fn(),
  fetchOtaFirmwareLifecycle: vi.fn(),
  fetchOtaPublicationHistory: vi.fn(),
  fetchOtaUploadHistory: vi.fn(),
  fetchOtaUpload: vi.fn(),
  revokeOtaFirmware: vi.fn(),
  sha256Hex: vi.fn(),
  uploadOtaContent: vi.fn(),
  OtaUploadError: class extends Error {}
}))
vi.mock('@/views/ota/firmwares/OtaMetadataDrawer.vue', () => ({ default: { template: '<div/>' } }))
vi.mock('@/views/ota/firmwares/OtaReleaseDownloadDialog.vue', () => ({
  default: {
    name: 'OtaReleaseDownloadDialog',
    props: ['modelValue', 'projectId', 'firmwareId', 'authorized'],
    emits: ['update:modelValue'],
    template:
      '<div v-if="modelValue" class="download"><button @click="$emit(\'update:modelValue\', false)">关闭下载</button></div>'
  }
}))
vi.mock('@/views/ota/firmwares/OtaReleaseProofDialog.vue', () => ({
  default: {
    name: 'OtaReleaseProofDialog',
    props: ['modelValue', 'projectId', 'firmwareId', 'authorized'],
    template: '<div v-if="modelValue" class="proof"/>'
  }
}))
let wrapper: VueWrapper | undefined
const projectId = '11111111-1111-4111-8111-111111111111',
  id = '22222222-2222-4222-8222-222222222222'
function render() {
  wrapper = mount(Firmwares, {
    global: {
      directives: { loading: () => {} },
      stubs: {
        ConsoleTableAction: { props: ['label'], template: '<button>{{label}}</button>' },
        ElButton: { template: '<button><slot/></button>' },
        ElCard: { template: '<div><slot/></div>' },
        ElAlert: { template: '<div><slot name="title"/><slot/></div>' },
        ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot/></div>' },
        ElDrawer: { props: ['modelValue'], template: '<div v-if="modelValue"><slot/></div>' },
        ElDropdown: { template: '<div/>' },
        ElTag: { template: '<span><slot/></span>' },
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
beforeEach(() => {
  vi.clearAllMocks()
  state.deploy = true
  state.user = reactive({ info: { currentProjectId: projectId } })
  vi.mocked(fetchOtaFirmwares).mockResolvedValue({
    items: [{ id, projectId, status: 'READY', revision: '3' }],
    nextCursor: null,
    hasMore: false
  })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})
it('READY及部署许可提供下载入口，传入精确身份；关闭不触发旧固件动作', async () => {
  render()
  await flushPromises()
  expect(wrapper!.find('.download').exists()).toBe(false)
  await wrapper!.get('[data-testid="ota-release-download-open"]').trigger('click')
  const dialog = wrapper!.findComponent({ name: 'OtaReleaseDownloadDialog' })
  expect(dialog.props()).toEqual({ modelValue: true, projectId, firmwareId: id, authorized: true })
  await wrapper!.get('.download button').trigger('click')
  expect(wrapper!.find('.download').exists()).toBe(false)
  state.user.info.currentProjectId = 'other-project'
  await flushPromises()
  expect(dialog.props('projectId')).toBe('other-project')
})
it.each(['DRAFT', 'VERIFYING', 'CANCELLED', 'DEPRECATED', 'REVOKED'])(
  '%s行没有下载入口',
  async (status) => {
    vi.mocked(fetchOtaFirmwares).mockResolvedValue({
      items: [{ id, projectId, status, revision: '3' }],
      nextCursor: null,
      hasMore: false
    })
    render()
    await flushPromises()
    expect(wrapper!.find('[data-testid="ota-release-download-open"]').exists()).toBe(false)
  }
)
it('缺少部署许可不提供下载入口且挂载拒绝许可', async () => {
  state.deploy = false
  render()
  await flushPromises()
  expect(wrapper!.find('[data-testid="ota-release-download-open"]').exists()).toBe(false)
  expect(wrapper!.findComponent({ name: 'OtaReleaseDownloadDialog' }).props('authorized')).toBe(
    false
  )
})

it.each(['OWNER', 'ADMIN'])('%s的READY固件提供独立证明入口并绑定精确身份', async (role) => {
  state.user.info.roles = [role]
  render()
  await flushPromises()
  expect(wrapper!.find('.proof').exists()).toBe(false)
  await wrapper!.get('[data-testid="ota-release-proof-open"]').trigger('click')
  expect(wrapper!.findComponent({ name: 'OtaReleaseProofDialog' }).props()).toEqual({
    modelValue: true,
    projectId,
    firmwareId: id,
    authorized: true
  })
  expect(wrapper!.find('.download').exists()).toBe(false)
})
it.each(['OPERATOR', 'VIEWER'])('%s即使残留按钮许可也不显示证明入口', async (role) => {
  state.user.info.roles = [role]
  render()
  await flushPromises()
  expect(wrapper!.find('[data-testid="ota-release-proof-open"]').exists()).toBe(false)
})
it.each(['DRAFT', 'DEPRECATED', 'REVOKED'])('%s不显示证明入口', async (status) => {
  state.user.info.roles = ['OWNER']
  vi.mocked(fetchOtaFirmwares).mockResolvedValue({
    items: [{ id, projectId, status }],
    hasMore: false
  } as any)
  render()
  await flushPromises()
  expect(wrapper!.find('[data-testid="ota-release-proof-open"]').exists()).toBe(false)
})
