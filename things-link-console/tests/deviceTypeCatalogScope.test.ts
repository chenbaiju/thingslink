import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, h, inject, provide, reactive } from 'vue'
import Types from '@/views/device/types/index.vue'
import { fetchDeviceTypePage, fetchDeviceTypeDetail } from '@/api/device'
const state = vi.hoisted(() => ({
  user: {} as any,
  push: vi.fn(),
  allowed: true,
  route: { query: {} as Record<string, string> },
  recent: vi.fn()
}))
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRouter: () => ({ push: state.push }),
  useRoute: () => reactive(state.route)
}))
vi.mock('@/utils/http/error', () => ({ HttpError: class extends Error {} }))
vi.mock('@/utils/workbench-recent', () => ({ recordRecentResource: state.recent }))
vi.mock('@/api/device', () => ({ fetchDeviceTypePage: vi.fn(), fetchDeviceTypeDetail: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/hooks/core/useAuth', () => ({ useAuth: () => ({ hasAuth: () => state.allowed }) }))
vi.mock('@/components/ConsoleTableAction.vue', () => ({
  default: { props: ['label'], template: '<button>{{label}}</button>' }
}))
vi.mock('@/views/device/types/ProductCredentialDialog.vue', () => ({
  default: {
    props: ['modelValue', 'typeId'],
    template: '<div v-if="modelValue" class="credential">{{typeId}}</div>'
  }
}))
let wrapper: VueWrapper
const row = {
  id: 'current',
  projectId: 'project-a',
  name: 'CURRENT',
  typeKey: 'current',
  status: 'PUBLISHED',
  deviceKind: 'DIRECT',
  payloadProtocol: 'STANDARD',
  networkType: 'WIFI'
}
function deferred() {
  let resolve!: (v: any) => void
  const promise = new Promise<any>((r) => (resolve = r))
  return { promise, resolve }
}
function render() {
  wrapper = mount(Types, {
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElCard: { template: '<div><slot/></div>' },
        ElButton: { template: '<button><slot/></button>' },
        ElTag: { template: '<span><slot/></span>' },
        ElEmpty: true,
        ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot/></div>' },
        ElTable: {
          props: ['data'],
          setup(props: any, { slots }: any) {
            provide(
              'rows',
              computed(() => props.data)
            )
            return () => h('div', slots.default?.())
          }
        },
        ElTableColumn: {
          props: ['prop'],
          setup(props: any, { slots }: any) {
            const rows = inject<any>('rows')
            return () =>
              h(
                'div',
                rows.value.map((row: any) =>
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
  vi.resetAllMocks()
  vi.stubGlobal('ElMessage', { warning: vi.fn() })
  state.allowed = true
  state.route.query = {}
  vi.mocked(fetchDeviceTypeDetail).mockResolvedValue(row as any)
  state.user = reactive({
    info: {
      currentProjectId: 'project-a',
      userId: 'owner',
      tenantId: 'tenant',
      roles: ['OWNER'],
      buttons: ['device:read', 'device:create']
    }
  })
  vi.mocked(fetchDeviceTypePage).mockResolvedValue({ items: [row as any], hasMore: false })
})
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
})
it('新项目类型先清空，迟到旧目录不能回填', async () => {
  const old = deferred()
  vi.mocked(fetchDeviceTypePage).mockReturnValueOnce(old.promise)
  render()
  state.user.info.currentProjectId = 'project-b'
  await flushPromises()
  expect(wrapper.text()).toContain('CURRENT')
  old.resolve({ items: [{ ...row, name: 'OLD_PROJECT' }], hasMore: true })
  await flushPromises()
  expect(wrapper.text()).not.toContain('OLD_PROJECT')
})
it('项目切换关闭产品凭据，OPERATOR没有产品入口', async () => {
  render()
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '产品凭据')!
    .trigger('click')
  expect(wrapper.find('.credential').exists()).toBe(true)
  state.user.info.currentProjectId = 'project-b'
  await flushPromises()
  expect(wrapper.find('.credential').exists()).toBe(false)
  state.user.info.roles = ['OPERATOR']
  await flushPromises()
  expect(wrapper.findAll('button').some((b) => b.text() === '产品凭据')).toBe(false)
})

it('物模型工作区读取单条事实并记录最近访问，不显示继续创建设备按钮', async () => {
  render()
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === 'CURRENT')!
    .trigger('click')
  await flushPromises()
  expect(fetchDeviceTypeDetail).toHaveBeenCalledWith('project-a', 'current')
  expect(state.recent).toHaveBeenCalledWith(
    { userId: 'owner', tenantId: 'tenant', projectId: 'project-a' },
    { kind: 'type', id: 'current', label: 'CURRENT' }
  )
  expect(wrapper.text()).not.toContain('继续创建设备')
})
it('草稿与只读角色不出现继续创建入口', async () => {
  vi.mocked(fetchDeviceTypeDetail).mockResolvedValue({ ...row, status: 'DRAFT' } as any)
  render()
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === 'CURRENT')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('继续创建设备')
  vi.mocked(fetchDeviceTypeDetail).mockResolvedValue(row as any)
  state.allowed = false
  state.user.info.buttons = ['device:read']
  await wrapper
    .findAll('button')
    .find((b) => b.text() === 'CURRENT')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('继续创建设备')
})
it('旧项目迟到工作区响应不恢复类型和创建入口', async () => {
  const pending = deferred()
  vi.mocked(fetchDeviceTypeDetail).mockReturnValueOnce(pending.promise)
  render()
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === 'CURRENT')!
    .trigger('click')
  state.user.info.currentProjectId = 'project-b'
  await flushPromises()
  pending.resolve(row)
  await flushPromises()
  expect(wrapper.find('.device-types__workspace').exists()).toBe(false)
})
it('跨项目类型响应不可显示也不可继续创建', async () => {
  vi.mocked(fetchDeviceTypeDetail).mockResolvedValue({ ...row, projectId: 'other' } as any)
  render()
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === 'CURRENT')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('继续创建设备')
  expect(wrapper.text()).toContain('重试读取')
})

it('首页最近访问按项目验证resourceId并单读，跨项目拒绝', async () => {
  const id = '11111111-1111-4111-8111-111111111111'
  state.route.query = { resourceId: id, contextProjectId: 'project-a' }
  vi.mocked(fetchDeviceTypeDetail).mockResolvedValue({ ...row, id } as any)
  render()
  await flushPromises()
  expect(fetchDeviceTypeDetail).toHaveBeenCalledWith('project-a', id)
  vi.mocked(fetchDeviceTypeDetail).mockClear()
  reactive(state.route).query = { resourceId: id, contextProjectId: 'other' }
  await flushPromises()
  expect(fetchDeviceTypeDetail).not.toHaveBeenCalled()
  expect(wrapper.find('.device-types__workspace').exists()).toBe(false)
})
