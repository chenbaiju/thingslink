import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, reactive } from 'vue'
import { useWorkspaceDeviceContext } from '@/composables/useWorkspaceDeviceContext'
import { fetchDeviceDetail } from '@/api/device'
import { invalidateIdentity } from '@/utils/http/identity-scope'

const mocks = vi.hoisted(() => ({ user: {} as any, route: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('vue-router', () => ({ useRoute: () => mocks.route }))
vi.mock('@/api/device', () => ({ fetchDeviceDetail: vi.fn() }))
const deviceId = '11111111-1111-4111-8111-111111111111'
const projectId = '22222222-2222-4222-8222-222222222222'
const Harness = defineComponent({
  setup: () => useWorkspaceDeviceContext('rule:manage'),
  template: '<div>{{device?.name}} {{error}}</div>'
})
beforeEach(() => {
  vi.resetAllMocks()
  mocks.user = reactive({
    isLogin: true,
    info: { userId: 'u', currentProjectId: projectId, buttons: ['device:read', 'rule:manage'] }
  })
  mocks.route = reactive({ query: { deviceId, contextProjectId: projectId } })
  vi.mocked(fetchDeviceDetail).mockResolvedValue({ id: deviceId, name: '已核对设备' })
})
describe('工作区来源设备', () => {
  it('只按当前项目单读并提供预选，不触发业务写入', async () => {
    const page = mount(Harness)
    await flushPromises()
    expect(fetchDeviceDetail).toHaveBeenCalledExactlyOnceWith(projectId, deviceId)
    expect(page.text()).toContain('已核对设备')
    page.unmount()
  })
  it.each(['wrong-project', 'no-read', 'no-manage', 'invalid', 'logout'])(
    '%s 不请求设备',
    async (kind) => {
      if (kind === 'wrong-project') mocks.route.query.contextProjectId = 'other'
      if (kind === 'no-read') mocks.user.info.buttons = ['rule:manage']
      if (kind === 'no-manage') mocks.user.info.buttons = ['device:read']
      if (kind === 'invalid') mocks.route.query.deviceId = ['unexpected']
      if (kind === 'logout') mocks.user.isLogin = false
      const page = mount(Harness)
      await flushPromises()
      expect(fetchDeviceDetail).not.toHaveBeenCalled()
      expect(page.vm.device).toBeUndefined()
      expect(page.vm.error).not.toBe('')
      page.unmount()
    }
  )
  it('无来源设备不请求，也不显示错误', async () => {
    mocks.route.query = {}
    const page = mount(Harness)
    await flushPromises()
    expect(fetchDeviceDetail).not.toHaveBeenCalled()
    expect(page.vm.error).toBe('')
    page.unmount()
  })
  it.each(['project', 'tenant', 'identity', 'permission'])('%s 变更丢弃迟响应', async (kind) => {
    let resolve!: (value: any) => void
    vi.mocked(fetchDeviceDetail).mockReturnValue(
      new Promise((done) => {
        resolve = done
      })
    )
    const page = mount(Harness)
    if (kind === 'project') mocks.user.info.currentProjectId = 'other'
    if (kind === 'tenant') {
      mocks.route.query = {}
      mocks.user.info.tenantId = 'other'
    }
    if (kind === 'permission') mocks.user.info.buttons = []
    if (kind === 'identity') {
      mocks.route.query = {}
      invalidateIdentity()
    }
    resolve({ id: deviceId, name: '过期设备' })
    await flushPromises()
    expect(page.vm.device).toBeUndefined()
    expect(page.text()).not.toContain('过期设备')
    page.unmount()
  })
  it('404失败可显式重试，响应ID不匹配不可预选', async () => {
    vi.mocked(fetchDeviceDetail).mockRejectedValueOnce(new Error('404'))
    const page = mount(Harness)
    await flushPromises()
    expect(page.vm.error).toContain('无法读取')
    await page.vm.reload()
    expect(page.vm.device?.id).toBe(deviceId)
    vi.mocked(fetchDeviceDetail).mockResolvedValueOnce({ id: 'wrong' })
    await page.vm.reload()
    expect(page.vm.device).toBeUndefined()
    expect(page.vm.error).toContain('无法读取')
    page.unmount()
  })
})
