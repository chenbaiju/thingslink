import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { reactive, ref, nextTick } from 'vue'
import Page from '@/views/alarm/rules/index.vue'
import { fetchAlarmRules, fetchCreateAlarmRule } from '@/api/alarm'
import { fetchDeviceDetail } from '@/api/device'

const mocks = vi.hoisted(() => ({
  user: {} as any,
  route: { query: {} as Record<string, string> }
}))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('vue-router', async () => ({
  ...(await vi.importActual<typeof import('vue-router')>('vue-router')),
  useRoute: () => mocks.route
}))
vi.mock('@/hooks/core/useAuth', () => ({
  useAuth: () => ({ hasAuth: (permission: string) => mocks.user.info.buttons.includes(permission) })
}))
vi.mock('@/api/device', () => ({ fetchDeviceDetail: vi.fn() }))
vi.mock('@/api/alarm', () => ({ fetchAlarmRules: vi.fn(), fetchCreateAlarmRule: vi.fn() }))
vi.mock('@/composables/usePagedDeviceCatalog', () => ({
  usePagedDeviceCatalog: () => ({
    devices: ref([]),
    devicesLoading: ref(false),
    loadDevices: vi.fn().mockResolvedValue(undefined),
    ensureDevices: vi.fn().mockResolvedValue(undefined),
    searchDevices: vi.fn(),
    onDevicePopupScroll: vi.fn()
  })
}))
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: vi.fn() },
  ElMessage: { success: vi.fn() }
}))
const id = '11111111-1111-4111-8111-111111111111'
beforeEach(() => {
  vi.resetAllMocks()
  mocks.user = reactive({
    isLogin: true,
    info: {
      userId: 'u',
      currentProjectId: 'a',
      buttons: ['device:read', 'alarm:read', 'alarm:manage']
    }
  })
  mocks.route = reactive({ query: {} })
  vi.mocked(fetchAlarmRules).mockResolvedValue({ items: [] })
  vi.mocked(fetchDeviceDetail).mockResolvedValue({ id, name: '设备A' })
})
it('来源设备通过复核后点击创建才进入表单，不自动提交', async () => {
  mocks.route.query = { deviceId: id, contextProjectId: 'a' }
  const page = shallowMount(Page, {
    global: {
      stubs: {
        ElCard: true,
        ElDialog: true,
        ElTable: true,
        ElTableColumn: true,
        ElAlert: true,
        ElForm: true,
        ElFormItem: true
      },
      directives: { loading: () => {} }
    }
  })
  await flushPromises()
  const state = (page.vm as any).$.setupState
  expect(state.formVisible).toBe(false)
  expect(state.form.deviceId).toBe('')
  state.openCreate()
  expect(state.form.deviceId).toBe(id)
  expect(fetchCreateAlarmRule).not.toHaveBeenCalled()
  mocks.user.info.currentProjectId = 'b'
  await nextTick()
  expect(state.formVisible).toBe(false)
  expect(state.form.deviceId).toBe('')
  page.unmount()
})
it('切换项目后旧告警规则列表迟到不得覆盖新项目', async () => {
  let resolve!: (value: any) => void
  const previous = new Promise((done) => {
    resolve = done
  })
  vi.mocked(fetchAlarmRules).mockImplementation((project) =>
    project === 'a' ? (previous as any) : Promise.resolve({ items: [{ id: 'new' }] })
  )
  const page = shallowMount(Page, {
    global: {
      stubs: {
        ElCard: true,
        ElDialog: true,
        ElTable: true,
        ElTableColumn: true,
        ElAlert: true,
        ElForm: true,
        ElFormItem: true
      },
      directives: { loading: () => {} }
    }
  })
  mocks.user.info.currentProjectId = 'b'
  await flushPromises()
  resolve({ items: [{ id: 'private-old' }] })
  await flushPromises()
  expect((page.vm as any).$.setupState.items).toEqual([{ id: 'new' }])
  page.unmount()
})
it('无告警读取权限不发列表请求', async () => {
  mocks.user.info.buttons = []
  const page = shallowMount(Page, {
    global: {
      stubs: {
        ElCard: true,
        ElDialog: true,
        ElTable: true,
        ElTableColumn: true,
        ElAlert: true,
        ElForm: true,
        ElFormItem: true
      },
      directives: { loading: () => {} }
    }
  })
  await flushPromises()
  expect(fetchAlarmRules).not.toHaveBeenCalled()
  page.unmount()
})
