import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Firmwares from '@/views/ota/firmwares/index.vue'
import { fetchOtaFirmwares, createOtaPublication, cancelOtaFirmware } from '@/api/ota'
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
  default: { template: '<div/>' }
}))
vi.mock('@/views/ota/firmwares/OtaTrustImportDialog.vue', () => ({
  default: {
    name: 'OtaTrustImportDialog',
    props: ['modelValue', 'projectId', 'authorized'],
    emits: ['update:modelValue'],
    template:
      '<div v-if="modelValue" class="trust-import"><button @click="$emit(\'update:modelValue\', false)">关闭导入</button></div>'
  }
}))
let wrapper: VueWrapper | undefined
const projectId = '11111111-1111-4111-8111-111111111111'
function render() {
  wrapper = mount(Firmwares, {
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElButton: { template: '<button><slot/></button>' },
        ElCard: { template: '<div><slot/></div>' },
        ElAlert: { template: '<div><slot name="title"/><slot/></div>' },
        ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot/></div>' },
        ElDrawer: { props: ['modelValue'], template: '<div v-if="modelValue"><slot/></div>' },
        ElTable: { template: '<div><slot name="empty"/></div>' },
        ElDropdown: { template: '<div/>' }
      }
    }
  })
}
beforeEach(() => {
  vi.clearAllMocks()
  state.deploy = true
  state.user = reactive({ info: { currentProjectId: projectId } })
  vi.mocked(fetchOtaFirmwares).mockResolvedValue({ items: [], hasMore: false, nextCursor: null })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})
it('部署许可入口挂载精确项目，关闭不触发旧固件动作，切项目更新scope', async () => {
  render()
  await flushPromises()
  expect(wrapper!.find('.trust-import').exists()).toBe(false)
  await wrapper!.get('[data-testid="ota-trust-import-open"]').trigger('click')
  const dialog = wrapper!.findComponent({ name: 'OtaTrustImportDialog' })
  expect(dialog.props()).toEqual({ modelValue: true, projectId, authorized: true })
  await wrapper!.get('.trust-import button').trigger('click')
  expect(wrapper!.find('.trust-import').exists()).toBe(false)
  expect(createOtaPublication).not.toHaveBeenCalled()
  expect(cancelOtaFirmware).not.toHaveBeenCalled()
  state.user.info.currentProjectId = 'other-project'
  await flushPromises()
  expect(dialog.props('projectId')).toBe('other-project')
})
it('缺少部署许可不提供导入入口，组件得到当前拒绝许可', async () => {
  state.deploy = false
  render()
  await flushPromises()
  expect(wrapper!.find('[data-testid="ota-trust-import-open"]').exists()).toBe(false)
  expect(wrapper!.findComponent({ name: 'OtaTrustImportDialog' }).props('authorized')).toBe(false)
})
