import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Firmwares from '@/views/ota/firmwares/index.vue'
import { otaRoutes } from '@/router/modules/ota'

const state = vi.hoisted(() => ({ user: {} as any, read: true }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/hooks/core/useAuth', () => ({
  useAuth: () => ({
    hasAuth: (name: string) => name === 'ota:read' && state.read
  })
}))
vi.mock('@/api/device', () => ({ fetchDeviceTypePage: vi.fn() }))
vi.mock('@/api/ota', () => ({
  fetchOtaFirmwares: vi.fn().mockResolvedValue({ items: [], nextCursor: null, hasMore: false }),
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
vi.mock('@/views/ota/firmwares/OtaMetadataDrawer.vue', () => ({
  default: {
    name: 'OtaMetadataDrawer',
    props: ['modelValue', 'projectId', 'authorized'],
    emits: ['update:modelValue'],
    template:
      '<div v-if="modelValue" class="metadata"><span>{{projectId}}</span><button @click="$emit(\'update:modelValue\', false)">关闭元数据</button></div>'
  }
}))
let wrapper: VueWrapper | undefined
function render() {
  wrapper = mount(Firmwares, {
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElButton: { template: '<button><slot/></button>' },
        ElCard: { template: '<div><slot/></div>' },
        ElTable: true,
        ElTableColumn: true,
        ElAlert: { template: '<div><slot name="title"/><slot/></div>' },
        ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot/></div>' },
        ElDrawer: { props: ['modelValue'], template: '<div v-if="modelValue"><slot/></div>' }
      }
    }
  })
}
beforeEach(() => {
  state.read = true
  state.user = reactive({ info: { currentProjectId: 'project-a' } })
})
afterEach(() => wrapper?.unmount())

it('只读入口打开抽屉并传递当前项目/路由许可，抽屉关闭不触发固件动作', async () => {
  render()
  await flushPromises()
  expect(wrapper!.find('.metadata').exists()).toBe(false)
  await wrapper!.get('[data-testid="ota-metadata-open"]').trigger('click')
  const drawer = wrapper!.findComponent({ name: 'OtaMetadataDrawer' })
  expect(drawer.props()).toMatchObject({
    modelValue: true,
    projectId: 'project-a',
    authorized: true
  })
  state.user.info.currentProjectId = 'project-b'
  await flushPromises()
  expect(drawer.props('projectId')).toBe('project-b')
  await wrapper!
    .findAll('button')
    .find((button) => button.text() === '关闭元数据')!
    .trigger('click')
  expect(wrapper!.find('.metadata').exists()).toBe(false)
})

it('没有ota:read时隐藏入口且传递拒绝许可', async () => {
  state.read = false
  render()
  await flushPromises()
  expect(wrapper!.find('[data-testid="ota-metadata-open"]').exists()).toBe(false)
  expect(wrapper!.findComponent({ name: 'OtaMetadataDrawer' }).props('authorized')).toBe(false)
})

it('静态固件菜单保持管理角色，读入口不借用部署权限', () => {
  const route = otaRoutes.children!.find((child) => child.name === 'OtaFirmwares')!
  expect(route.meta.roles).toEqual(['OWNER', 'ADMIN'])
  expect(route.meta.authList?.filter((point) => point.authMark === 'ota:read')).toEqual([
    { title: '查看信任与类型基线', authMark: 'ota:read' }
  ])
  expect(route.meta.authList?.filter((point) => point.authMark === 'ota:deploy')).toHaveLength(4)
})
