import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Dialog from '@/views/ota/firmwares/OtaReleaseDownloadDialog.vue'
import { createOtaReleaseDownload, fetchOtaFirmwareLifecycle } from '@/api/ota'
import { fetchProjects } from '@/api/project'
import { HttpError } from '@/utils/http/error'
import { invalidateIdentity } from '@/utils/http/identity-scope'

const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/ota', () => ({
  createOtaReleaseDownload: vi.fn(),
  fetchOtaFirmwareLifecycle: vi.fn()
}))
vi.mock('@/api/project', () => ({ fetchProjects: vi.fn() }))
const projectId = '11111111-1111-4111-8111-111111111111'
const firmwareId = '22222222-2222-4222-8222-222222222222'
const otherId = '33333333-3333-4333-8333-333333333333'
const lifecycle = {
  firmwareId,
  status: 'READY',
  revision: '3',
  deprecation: null,
  revocation: null
}
const project = { id: projectId, status: 'ACTIVE', myRole: 'OWNER' }
const url = 'https://storage.example/fixed?X-Amz-Signature=synthetic-capability'
const response = (overrides = {}) => ({
  firmwareId,
  downloadUrl: url,
  expiresAt: new Date(Date.now() + 60_000).toISOString(),
  ...overrides
})
let wrapper: VueWrapper | undefined
let clicks: HTMLAnchorElement[]
const button = () => wrapper!.get('[data-testid="ota-release-download-issue"]')
async function issue() {
  await button().trigger('click')
  await flushPromises()
}
function deferred() {
  let resolve!: (v: any) => void
  const promise = new Promise<any>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
function render(
  props: Partial<{
    modelValue: boolean
    projectId: string
    firmwareId: string
    authorized: boolean
  }> = {}
) {
  wrapper = mount(Dialog, {
    props: {
      modelValue: true,
      projectId,
      firmwareId,
      authorized: true,
      ...props,
      'onUpdate:modelValue': (v: boolean) => {
        if (wrapper) void wrapper.setProps({ modelValue: v })
      }
    },
    global: {
      stubs: {
        ElDialog: {
          props: ['modelValue', 'title'],
          template:
            '<section v-if="modelValue" role="dialog" :aria-label="title"><slot/><slot name="footer"/></section>'
        },
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot/></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' }
      }
    }
  })
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
  vi.setSystemTime(new Date('2026-10-06T20:00:00Z'))
  state.user = reactive({
    info: {
      currentProjectId: projectId,
      userId: 'owner',
      tenantId: 'tenant',
      roles: ['OWNER'],
      buttons: ['ota:deploy']
    }
  })
  clicks = []
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement
  ) {
    clicks.push(this)
  })
  vi.mocked(fetchOtaFirmwareLifecycle).mockResolvedValue({ ...lifecycle } as any)
  vi.mocked(fetchProjects).mockResolvedValue([{ ...project }])
  vi.mocked(createOtaReleaseDownload).mockImplementation(async () => response())
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.restoreAllMocks()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

it('进入只读取资格，显式点击重新读取ACTIVE/READY并单次申领，地址不进入DOM或存储', async () => {
  const local = vi.fn(),
    session = vi.fn()
  vi.stubGlobal('localStorage', { setItem: local })
  vi.stubGlobal('sessionStorage', { setItem: session })
  render()
  await flushPromises()
  expect(createOtaReleaseDownload).not.toHaveBeenCalled()
  expect(fetchOtaFirmwareLifecycle).toHaveBeenCalledExactlyOnceWith(projectId, firmwareId)
  await issue()
  expect(fetchOtaFirmwareLifecycle).toHaveBeenCalledTimes(2)
  expect(fetchProjects).toHaveBeenCalledTimes(2)
  expect(createOtaReleaseDownload).toHaveBeenCalledOnce()
  expect(createOtaReleaseDownload).toHaveBeenCalledWith(projectId, firmwareId, expect.any(String))
  expect(clicks).toHaveLength(1)
  expect(clicks[0]!.href === url).toBe(true)
  expect(clicks[0]!.isConnected).toBe(false)
  expect(clicks[0]!.referrerPolicy).toBe('no-referrer')
  expect(clicks[0]!.rel).toBe('noopener noreferrer')
  expect(clicks[0]!.target).toBe('_blank')
  expect(clicks[0]!.download).toBe(`firmware-${firmwareId}.bin`)
  expect(wrapper!.html().includes('synthetic-capability')).toBe(false)
  expect(wrapper!.find('a').exists()).toBe(false)
  expect(local).not.toHaveBeenCalled()
  expect(session).not.toHaveBeenCalled()
  expect(wrapper!.text()).toContain('不代表设备升级资格')
  expect(button().text()).toBe('重新申领并下载')
})

it.each(['DRAFT', 'VERIFYING', 'CANCELLED', 'DEPRECATED', 'REVOKED'])(
  '%s固件不提供申领',
  async (status) => {
    vi.mocked(fetchOtaFirmwareLifecycle).mockResolvedValue({ ...lifecycle, status } as any)
    render()
    await flushPromises()
    expect(button().attributes('disabled')).toBeDefined()
    expect(createOtaReleaseDownload).not.toHaveBeenCalled()
  }
)
it.each(['ARCHIVED', 'DELETING', 'DELETED'])('%s项目不提供申领', async (status) => {
  vi.mocked(fetchProjects).mockResolvedValue([{ ...project, status }])
  render()
  await flushPromises()
  expect(button().attributes('disabled')).toBeDefined()
})
it.each([
  { firmwareId: otherId },
  { revision: null },
  { deprecation: {} },
  { downloadUrl: url },
  { revocation: undefined }
])('公开生命周期必须匹配身份与闭集字段 %j', async (change) => {
  vi.mocked(fetchOtaFirmwareLifecycle).mockResolvedValue({ ...lifecycle, ...change } as any)
  render()
  await flushPromises()
  expect(button().attributes('disabled')).toBeDefined()
})
it('读失败只能刷新；申领前状态变动阻止POST', async () => {
  vi.mocked(fetchProjects).mockRejectedValueOnce(new Error('PRIVATE_ERROR'))
  render()
  await flushPromises()
  expect(button().attributes('disabled')).toBeDefined()
  expect(wrapper!.text()).not.toContain('PRIVATE_ERROR')
  await wrapper!.get('[data-testid="ota-release-download-refresh"]').trigger('click')
  await flushPromises()
  vi.mocked(fetchProjects).mockResolvedValue([{ ...project, status: 'ARCHIVED' }])
  await issue()
  expect(createOtaReleaseDownload).not.toHaveBeenCalled()
})
it('当前服务端项目角色为低权限时阻止申领', async () => {
  vi.mocked(fetchProjects).mockResolvedValue([{ ...project, myRole: 'VIEWER' }])
  render()
  await flushPromises()
  expect(button().attributes('disabled')).toBeDefined()
})
it.each(['unknown', 'pending'])(
  '%s保留原键，只在显式重试时发送，完成墓碑后显式换新键',
  async (kind) => {
    vi.mocked(createOtaReleaseDownload).mockRejectedValueOnce(
      kind === 'pending'
        ? new HttpError('PRIVATE_ERROR', 10010)
        : new HttpError('PRIVATE_ERROR', 500, { outcomeUnknown: true })
    )
    render()
    await flushPromises()
    await issue()
    const original = vi.mocked(createOtaReleaseDownload).mock.calls[0]![2]
    expect(button().text()).toBe('使用原键重试')
    await vi.advanceTimersByTimeAsync(10_000)
    expect(createOtaReleaseDownload).toHaveBeenCalledOnce()
    vi.mocked(createOtaReleaseDownload).mockRejectedValueOnce(new HttpError('PRIVATE_ERROR', 10014))
    await issue()
    expect(vi.mocked(createOtaReleaseDownload).mock.calls[1]![2]).toBe(original)
    expect(button().text()).toBe('重新申领并下载')
    expect(wrapper!.text()).toContain('原签发已完成')
    expect(wrapper!.text()).not.toContain('PRIVATE_ERROR')
    expect(clicks).toHaveLength(0)
    await issue()
    expect(vi.mocked(createOtaReleaseDownload).mock.calls[2]![2] === original).toBe(false)
    expect(clicks).toHaveLength(1)
  }
)
it('在途资格读取及签发都防止双发，成功后再次点击产生新键', async () => {
  render()
  await flushPromises()
  const read = deferred()
  vi.mocked(fetchProjects).mockReturnValueOnce(read.promise)
  await button().trigger('click')
  await button().trigger('click')
  await flushPromises()
  expect(createOtaReleaseDownload).not.toHaveBeenCalled()
  const signing = deferred()
  vi.mocked(createOtaReleaseDownload).mockReturnValueOnce(signing.promise)
  read.resolve([{ ...project }])
  await flushPromises()
  await button().trigger('click')
  expect(createOtaReleaseDownload).toHaveBeenCalledOnce()
  const key = vi.mocked(createOtaReleaseDownload).mock.calls[0]![2]
  signing.resolve(response())
  await flushPromises()
  await issue()
  expect(createOtaReleaseDownload).toHaveBeenCalledTimes(2)
  expect(vi.mocked(createOtaReleaseDownload).mock.calls[1]![2] === key).toBe(false)
})
it.each([
  'close',
  'project',
  'tenant',
  'user',
  'epoch',
  'roles',
  'buttons',
  'firmware',
  'authorization'
])('%s变化立即清理并拒绝迟到地址', async (change) => {
  render()
  await flushPromises()
  const signing = deferred()
  vi.mocked(createOtaReleaseDownload).mockReturnValueOnce(signing.promise)
  await button().trigger('click')
  await flushPromises()
  if (change === 'close') await wrapper!.setProps({ modelValue: false })
  else if (change === 'project') state.user.info.currentProjectId = otherId
  else if (change === 'tenant') state.user.info.tenantId = 'tenant-b'
  else if (change === 'user') state.user.info.userId = 'user-b'
  else if (change === 'epoch') invalidateIdentity()
  else if (change === 'roles') state.user.info.roles = ['VIEWER']
  else if (change === 'buttons') state.user.info.buttons = []
  else if (change === 'firmware') await wrapper!.setProps({ firmwareId: otherId })
  else await wrapper!.setProps({ authorized: false })
  await flushPromises()
  signing.resolve(response())
  await flushPromises()
  expect(clicks).toHaveLength(0)
  expect(wrapper!.find('[role="dialog"]').exists()).toBe(false)
  expect(wrapper!.html().includes('synthetic-capability')).toBe(false)
})
it('关闭后再次进入不继承未知签发键', async () => {
  vi.mocked(createOtaReleaseDownload).mockRejectedValueOnce(
    new HttpError('unknown', 500, { outcomeUnknown: true })
  )
  render()
  await flushPromises()
  await issue()
  const old = vi.mocked(createOtaReleaseDownload).mock.calls[0]![2]
  await wrapper!.setProps({ modelValue: false })
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  await issue()
  expect(vi.mocked(createOtaReleaseDownload).mock.calls[1]![2] === old).toBe(false)
})
it('新关闭代次拒绝旧资格读取，不使新窗口可申领', async () => {
  const old = deferred()
  vi.mocked(fetchProjects).mockReturnValueOnce(old.promise)
  render()
  await flushPromises()
  await wrapper!.setProps({ modelValue: false })
  vi.mocked(fetchProjects).mockResolvedValue([{ ...project, status: 'ARCHIVED' }])
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  old.resolve([{ ...project }])
  await flushPromises()
  expect(button().attributes('disabled')).toBeDefined()
})
it.each([
  { firmwareId: otherId },
  { expiresAt: null },
  { expiresAt: '2026-02-30T20:00:00Z' },
  { expiresAt: 'invalid' },
  { secret: 'PRIVATE_SECRET' },
  { expiresAt: undefined },
  { downloadUrl: 'https://user:pass@storage.example/file' },
  { downloadUrl: 'https://@storage.example/file' },
  { downloadUrl: 'https://storage.example/file#' },
  { downloadUrl: 'https://storage.example/file#fragment' },
  { downloadUrl: 'https://storage.example/\nfile' },
  { downloadUrl: 'javascript:alert(1)' },
  { downloadUrl: 'http://storage.example/file' },
  { downloadUrl: 'http://localhost.evil/file' },
  { downloadUrl: 'http://127.1/file' },
  { downloadUrl: 'http://2130706433/file' },
  { downloadUrl: 'https:/storage.example/file' }
])('无效/多字段票据丢弃且不下载 %j', async (change) => {
  vi.mocked(createOtaReleaseDownload).mockResolvedValue(response(change) as any)
  render()
  await flushPromises()
  await issue()
  expect(clicks).toHaveLength(0)
  expect(wrapper!.find('[data-testid="ota-release-download-expiry"]').exists()).toBe(false)
  expect(wrapper!.text()).toContain('签发响应无法确认')
  expect(wrapper!.html().includes('PRIVATE_SECRET')).toBe(false)
})
it.each(['http://localhost:9000/file', 'http://127.0.0.1:9000/file', 'http://[::1]:9000/file'])(
  '只接受精确本机开发回环%s',
  async (downloadUrl) => {
    vi.mocked(createOtaReleaseDownload).mockResolvedValue(response({ downloadUrl }))
    render()
    await flushPromises()
    await issue()
    expect(clicks).toHaveLength(1)
  }
)
it('已到期响应不下载，60秒客户端截止清票据且刷新不取消到期；只能显式重申领', async () => {
  vi.mocked(createOtaReleaseDownload).mockResolvedValueOnce(
    response({ expiresAt: new Date(Date.now() - 1).toISOString() })
  )
  render()
  await flushPromises()
  await issue()
  expect(clicks).toHaveLength(0)
  expect(button().text()).toBe('重新申领并下载')
  vi.mocked(createOtaReleaseDownload).mockResolvedValueOnce(
    response({ expiresAt: new Date(Date.now() + 120_000).toISOString() })
  )
  await issue()
  expect(clicks).toHaveLength(1)
  await wrapper!.get('[data-testid="ota-release-download-refresh"]').trigger('click')
  await flushPromises()
  await vi.advanceTimersByTimeAsync(60_000)
  expect(wrapper!.find('[data-testid="ota-release-download-expiry"]').exists()).toBe(false)
  expect(wrapper!.text()).toContain('本次地址已到期')
  expect(createOtaReleaseDownload).toHaveBeenCalledTimes(2)
  await issue()
  expect(createOtaReleaseDownload).toHaveBeenCalledTimes(3)
})
it('明确拒绝固定显示且要求刷新；组件不输出错误原文', async () => {
  const log = vi.spyOn(console, 'error').mockImplementation(() => {})
  vi.mocked(createOtaReleaseDownload).mockRejectedValueOnce(
    new HttpError('PRIVATE_SECRET', 70024, { details: [url], data: url })
  )
  render()
  await flushPromises()
  await issue()
  expect(wrapper!.text()).toContain('服务端未授权本次下载')
  expect(wrapper!.text()).not.toContain('PRIVATE_SECRET')
  expect(button().attributes('disabled')).toBeDefined()
  expect(log).not.toHaveBeenCalled()
})
it('浏览器点击失败不泄露票据，不把已成功签发当结果未知', async () => {
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {
    throw new Error(url)
  })
  render()
  await flushPromises()
  await issue()
  expect(wrapper!.text()).toContain('本次签发已完成')
  expect(button().text()).toBe('重新申领并下载')
  expect(wrapper!.html().includes('synthetic-capability')).toBe(false)
})

it.each(['roles', 'buttons', 'project', 'user', 'tenant', 'authorization', 'firmware', 'closed'])(
  '首次%s条件不满足时不读取或签发',
  async (condition) => {
    const props: any = {}
    if (condition === 'roles') state.user.info.roles = ['VIEWER']
    if (condition === 'buttons') state.user.info.buttons = []
    if (condition === 'project') state.user.info.currentProjectId = otherId
    if (condition === 'user') state.user.info.userId = ''
    if (condition === 'tenant') state.user.info.tenantId = ''
    if (condition === 'authorization') props.authorized = false
    if (condition === 'firmware') props.firmwareId = 'invalid'
    if (condition === 'closed') props.modelValue = false
    render(props)
    await flushPromises()
    expect(fetchProjects).not.toHaveBeenCalled()
    expect(fetchOtaFirmwareLifecycle).not.toHaveBeenCalled()
    expect(createOtaReleaseDownload).not.toHaveBeenCalled()
  }
)
it('已签发票据遇到权限变化立即清理到期事实，关闭不留定时任务', async () => {
  render()
  await flushPromises()
  await issue()
  expect(wrapper!.find('[data-testid="ota-release-download-expiry"]').exists()).toBe(true)
  state.user.info.buttons = []
  await flushPromises()
  expect(wrapper!.find('[data-testid="ota-release-download-expiry"]').exists()).toBe(false)
  expect(vi.getTimerCount()).toBe(0)
  await vi.advanceTimersByTimeAsync(120_000)
  expect(createOtaReleaseDownload).toHaveBeenCalledOnce()
})
it('URI不接受反斜杠规范化的地址', async () => {
  vi.mocked(createOtaReleaseDownload).mockResolvedValue(
    response({ downloadUrl: 'https://storage.example\\file' })
  )
  render()
  await flushPromises()
  await issue()
  expect(clicks).toHaveLength(0)
  expect(wrapper!.text()).toContain('签发响应无法确认')
})
it('5xx未知标记优先于不一致完成码，仍须用原键显式重试', async () => {
  vi.mocked(createOtaReleaseDownload).mockRejectedValueOnce(
    new HttpError('opaque', 10014, { outcomeUnknown: true })
  )
  render()
  await flushPromises()
  await issue()
  const original = vi.mocked(createOtaReleaseDownload).mock.calls[0]![2]
  expect(button().text()).toBe('使用原键重试')
  await issue()
  expect(vi.mocked(createOtaReleaseDownload).mock.calls[1]![2]).toBe(original)
})
