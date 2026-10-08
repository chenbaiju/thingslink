import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Dialog from '@/views/device/types/ProductCredentialDialog.vue'
import { fetchDeviceTypeDetail, generateProductCredential } from '@/api/device'
import { fetchProjects } from '@/api/project'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const state = vi.hoisted(() => ({ user: {} as any, confirm: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/device', () => ({
  fetchDeviceTypeDetail: vi.fn(),
  generateProductCredential: vi.fn()
}))
vi.mock('@/api/project', () => ({ fetchProjects: vi.fn() }))
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: (...args: any[]) => state.confirm(...args) }
}))
const projectId = '11111111-1111-4111-8111-111111111111',
  typeId = '22222222-2222-4222-8222-222222222222',
  other = '33333333-3333-4333-8333-333333333333',
  plain = 'b'.repeat(64)
const type = {
  id: typeId,
  projectId,
  typeKey: 'example',
  name: '实例类型',
  deviceKind: 'DIRECT' as const,
  payloadProtocol: 'STANDARD' as const,
  networkType: 'WIFI' as const,
  version: 1,
  status: 'PUBLISHED' as const,
  productKey: null,
  createdAt: '2026-10-06T00:00:00Z'
}
const response = { deviceTypeId: typeId, productKey: 'public_key', productSecret: plain }
let wrapper: VueWrapper
function deferred() {
  let resolve!: (v: any) => void
  const promise = new Promise<any>((r) => (resolve = r))
  return { promise, resolve }
}
const button = (name: string) => wrapper.findAll('button').find((b) => b.text() === name)!
function render() {
  wrapper = mount(Dialog, {
    props: {
      modelValue: true,
      projectId,
      typeId,
      'onUpdate:modelValue': (v: boolean) => void wrapper.setProps({ modelValue: v })
    },
    global: {
      stubs: {
        ElDialog: {
          props: ['modelValue'],
          template: '<div v-if="modelValue"><slot/><slot name="footer"/></div>'
        },
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot/></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' },
        ElInput: {
          props: ['modelValue', 'type'],
          template: '<input :value="modelValue" :type="type"/>'
        }
      }
    }
  })
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('localStorage', { setItem: vi.fn() })
  vi.stubGlobal('sessionStorage', { setItem: vi.fn() })
  state.user = reactive({
    info: { roles: ['OWNER'], buttons: ['device:update'], userId: 'owner', tenantId: 'tenant' }
  })
  state.confirm.mockResolvedValue('confirm')
  vi.mocked(fetchDeviceTypeDetail).mockResolvedValue({ ...type })
  vi.mocked(fetchProjects).mockResolvedValue([{ id: projectId, status: 'ACTIVE' }])
  vi.mocked(generateProductCredential).mockResolvedValue({ ...response })
})
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
})
it.each(['OWNER', 'ADMIN'])(
  '%s显式确认后仅本次密码输入显示，刷新清除且不会持久化',
  async (role) => {
    state.user.info.roles = [role]
    render()
    await flushPromises()
    expect(generateProductCredential).not.toHaveBeenCalled()
    await button('生成产品凭据').trigger('click')
    await flushPromises()
    expect(state.confirm).toHaveBeenCalledOnce()
    expect(generateProductCredential).toHaveBeenCalledExactlyOnceWith(projectId, typeId)
    expect(wrapper.find('input').element.value).toBe(plain)
    expect(wrapper.find('input').attributes('type')).toBe('password')
    expect(wrapper.text()).not.toContain(plain)
    expect(localStorage.setItem).not.toHaveBeenCalled()
    expect(sessionStorage.setItem).not.toHaveBeenCalled()
    await button('核对类型').trigger('click')
    await flushPromises()
    expect(wrapper.find('input').exists()).toBe(false)
  }
)
it.each(['OPERATOR', 'VIEWER'])('%s没有生成入口，仍可核对公开类型', async (role) => {
  state.user.info.roles = [role]
  render()
  await flushPromises()
  expect(wrapper.find('[data-testid="product-generate"]').exists()).toBe(false)
  expect(fetchDeviceTypeDetail).toHaveBeenCalledOnce()
})
it.each([{ status: 'ARCHIVED' }, { status: 'DELETED' }, { status: 'unknown' }])(
  '非活动项目不可生成：%j',
  async (project) => {
    vi.mocked(fetchProjects).mockResolvedValue([{ id: projectId, ...project } as any])
    render()
    await flushPromises()
    expect(button('生成产品凭据').attributes('disabled')).toBeDefined()
  }
)
it.each([
  { ...type, status: 'DRAFT' },
  { ...type, deviceKind: 'SUB_DEVICE' }
])('不支持类型不能生成：%j', async (value) => {
  vi.mocked(fetchDeviceTypeDetail).mockResolvedValue(value as any)
  render()
  await flushPromises()
  expect(button('生成产品凭据').attributes('disabled')).toBeDefined()
})
it('确认框及网络期间连续点击只发一次', async () => {
  const confirm = deferred(),
    pending = deferred()
  state.confirm.mockReturnValue(confirm.promise)
  vi.mocked(generateProductCredential).mockReturnValue(pending.promise)
  render()
  await flushPromises()
  await button('生成产品凭据').trigger('click')
  await button('生成产品凭据').trigger('click')
  expect(state.confirm).toHaveBeenCalledOnce()
  expect(generateProductCredential).not.toHaveBeenCalled()
  confirm.resolve('confirm')
  await flushPromises()
  await button('生成产品凭据').trigger('click')
  expect(generateProductCredential).toHaveBeenCalledOnce()
  pending.resolve(response)
  await flushPromises()
})
it('取消确认不提交、不宣称结果未知', async () => {
  state.confirm.mockRejectedValue('cancel')
  render()
  await flushPromises()
  await button('生成产品凭据').trigger('click')
  await flushPromises()
  expect(generateProductCredential).not.toHaveBeenCalled()
  expect(wrapper.text()).not.toContain('上次结果未确认')
})
it.each([401, 500, 'network'])('失败%s不自行补签，必须刷新公开状态再显式确认轮换', async (code) => {
  vi.mocked(generateProductCredential).mockRejectedValueOnce({ code, message: 'PRIVATE_SECRET' })
  render()
  await flushPromises()
  await button('生成产品凭据').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('上次结果未确认')
  expect(wrapper.text()).not.toContain('PRIVATE_SECRET')
  expect(button('重新确认轮换').attributes('disabled')).toBeDefined()
  expect(generateProductCredential).toHaveBeenCalledOnce()
  vi.mocked(fetchDeviceTypeDetail).mockResolvedValue({ ...type, productKey: response.productKey })
  await button('核对类型').trigger('click')
  await flushPromises()
  expect(generateProductCredential).toHaveBeenCalledOnce()
  await button('重新确认轮换').trigger('click')
  await flushPromises()
  expect(state.confirm.mock.calls[1][2].confirmButtonText).toBe('确认轮换')
  expect(generateProductCredential).toHaveBeenCalledTimes(2)
})
it.each([
  { ...response, deviceTypeId: other },
  { ...response, productSecret: 'bad' },
  { ...response, productSecretHash: plain }
])('拒绝非法一次性响应：%j', async (value) => {
  vi.mocked(generateProductCredential).mockResolvedValue(value as any)
  render()
  await flushPromises()
  await button('生成产品凭据').trigger('click')
  await flushPromises()
  expect(wrapper.find('input').exists()).toBe(false)
  expect(wrapper.text()).toContain('上次结果未确认')
})
it('关闭立即清秘密，重开不能查回', async () => {
  render()
  await flushPromises()
  await button('生成产品凭据').trigger('click')
  await flushPromises()
  await button('关闭').trigger('click')
  await flushPromises()
  expect(wrapper.find('input').exists()).toBe(false)
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  expect(wrapper.find('input').exists()).toBe(false)
  expect(generateProductCredential).toHaveBeenCalledOnce()
})
it('发送后关闭丢弃迟到秘密，重开在途时也不能重复提交', async () => {
  const pending = deferred()
  vi.mocked(generateProductCredential).mockReturnValue(pending.promise)
  render()
  await flushPromises()
  await button('生成产品凭据').trigger('click')
  await flushPromises()
  await button('关闭').trigger('click')
  await flushPromises()
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  expect(button('重新确认轮换').attributes('disabled')).toBeDefined()
  pending.resolve(response)
  await flushPromises()
  expect(wrapper.find('input').exists()).toBe(false)
  expect(wrapper.text()).toContain('上次结果未确认')
  expect(generateProductCredential).toHaveBeenCalledOnce()
  expect(button('重新确认轮换').attributes('disabled')).toBeDefined()
})
it('确认期间换身份不提交；已显示秘密身份换代即清除', async () => {
  const confirm = deferred()
  state.confirm.mockReturnValue(confirm.promise)
  render()
  await flushPromises()
  await button('生成产品凭据').trigger('click')
  expect(state.confirm).toHaveBeenCalledOnce()
  invalidateIdentity()
  confirm.resolve('confirm')
  await flushPromises()
  expect(generateProductCredential).not.toHaveBeenCalled()
  state.confirm.mockResolvedValue('confirm')
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  await button('生成产品凭据').trigger('click')
  await flushPromises()
  expect(wrapper.find('input').exists()).toBe(true)
  invalidateIdentity()
  await flushPromises()
  expect(wrapper.find('input').exists()).toBe(false)
})
it.each([
  { ...type, projectId: other },
  { ...type, productSecret: plain }
])('公开类型越界或携带秘密时拒绝生成', async (value) => {
  vi.mocked(fetchDeviceTypeDetail).mockResolvedValue(value as any)
  render()
  await flushPromises()
  expect(wrapper.text()).toContain('类型核对失败')
  expect(button('生成产品凭据').attributes('disabled')).toBeDefined()
})

it.each(['type', 'project', 'account', 'tenant', 'role'])(
  '显示秘密后%s变化立即清除并关闭',
  async (kind) => {
    render()
    await flushPromises()
    await button('生成产品凭据').trigger('click')
    await flushPromises()
    expect(wrapper.find('input').exists()).toBe(true)
    if (kind === 'type') await wrapper.setProps({ typeId: other })
    else if (kind === 'project') await wrapper.setProps({ projectId: other })
    else if (kind === 'account') state.user.info.userId = 'other'
    else if (kind === 'tenant') state.user.info.tenantId = 'other'
    else state.user.info.roles = ['VIEWER']
    await flushPromises()
    expect(wrapper.find('input').exists()).toBe(false)
    expect((wrapper.props() as { modelValue: boolean }).modelValue).toBe(false)
  }
)
