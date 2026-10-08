import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Dialog from '@/views/ota/firmwares/OtaReleaseProofDialog.vue'
import { fetchOtaRelease, fetchOtaFirmwareLifecycle } from '@/api/ota'
import { fetchProjects } from '@/api/project'
import { HttpError } from '@/utils/http/error'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/ota', () => ({ fetchOtaRelease: vi.fn(), fetchOtaFirmwareLifecycle: vi.fn() }))
vi.mock('@/api/project', () => ({ fetchProjects: vi.fn() }))
const projectId = '11111111-1111-4111-8111-111111111111'
const firmwareId = '22222222-2222-4222-8222-222222222222'
const otherId = '33333333-3333-4333-8333-333333333333'
const response = {
  firmwareId,
  publicationId: otherId,
  manifestBase64: 'e30=',
  signatureBase64: 'YWJj',
  publicKeySpkiBase64: 'ZGVm',
  signatureProfile: 'TC_OTA_ED25519_V1' as const,
  keyFingerprint: 'a'.repeat(64),
  artifactSha256: 'b'.repeat(64),
  artifactSize: '512',
  releaseCreatedAt: '2026-10-07T00:00:00Z'
}
let wrapper: VueWrapper | undefined
const content = () => wrapper!.find('[data-testid="ota-release-proof-content"]')
function render(props = {}) {
  wrapper = mount(Dialog, {
    props: {
      modelValue: true,
      projectId,
      firmwareId,
      authorized: true,
      ...props,
      'onUpdate:modelValue': (value: boolean) => {
        if (wrapper) void wrapper.setProps({ modelValue: value })
      }
    },
    global: {
      stubs: {
        ElDialog: {
          props: ['modelValue'],
          template: '<section v-if="modelValue"><slot/><slot name="footer"/></section>'
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
function deferred() {
  let resolve!: (value: any) => void
  const promise = new Promise<any>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
beforeEach(() => {
  vi.resetAllMocks()
  state.user = reactive({
    info: {
      currentProjectId: projectId,
      userId: 'owner',
      tenantId: 'tenant',
      roles: ['OWNER'],
      buttons: ['ota:deploy']
    }
  })
  vi.mocked(fetchOtaFirmwareLifecycle).mockResolvedValue({
    firmwareId,
    status: 'READY',
    revision: '3',
    deprecation: null,
    revocation: null
  })
  vi.mocked(fetchProjects).mockResolvedValue([{ id: projectId, status: 'ACTIVE', myRole: 'OWNER' }])
  vi.mocked(fetchOtaRelease).mockResolvedValue({ ...response })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.unstubAllGlobals()
})
it('关闭时不请求，打开按当前资格读取并显示精确原始公开材料', async () => {
  render({ modelValue: false })
  expect(fetchOtaRelease).not.toHaveBeenCalled()
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  expect(fetchOtaRelease).toHaveBeenCalledExactlyOnceWith(projectId, firmwareId)
  expect(content().text()).toContain('512 字节')
  expect(content().text()).toContain(response.artifactSha256)
  expect(wrapper!.get('textarea[aria-label="Manifest Base64"]').element).toHaveProperty(
    'value',
    'e30='
  )
  expect(wrapper!.text()).toContain('不代表设备验签通过')
  expect(wrapper!.text()).toContain('公钥本身不是设备信任来源')
})
it.each(['ADMIN', 'OWNER'])('%s在当前项目管理角色下可读取', async (role) => {
  state.user.info.roles = [role]
  vi.mocked(fetchProjects).mockResolvedValue([
    { id: projectId, status: 'ACTIVE', myRole: role as 'OWNER' }
  ])
  render()
  await flushPromises()
  expect(content().exists()).toBe(true)
})
it.each(['OPERATOR', 'VIEWER'])('%s不请求管理证明', async (role) => {
  state.user.info.roles = [role]
  render()
  await flushPromises()
  expect(fetchOtaRelease).not.toHaveBeenCalled()
  expect(wrapper!.emitted('update:modelValue')).toEqual([[false]])
})
it.each([{ authorized: false }, { projectId: otherId }, { firmwareId: 'bad-id' }])(
  '非法/越界入口不请求: %j',
  async (props) => {
    render(props)
    await flushPromises()
    expect(fetchOtaFirmwareLifecycle).not.toHaveBeenCalled()
  }
)
it.each(['DRAFT', 'DEPRECATED', 'REVOKED'])('%s固件不能依据历史READY展示证明', async (status) => {
  vi.mocked(fetchOtaFirmwareLifecycle).mockResolvedValue({ firmwareId, status } as any)
  render()
  await flushPromises()
  expect(fetchOtaRelease).not.toHaveBeenCalled()
  expect(content().exists()).toBe(false)
})
it.each([
  { status: 'SUSPENDED', myRole: 'OWNER' },
  { status: 'ACTIVE', myRole: 'VIEWER' }
])('项目当前状态/角色不符不发起证明读取', async (project) => {
  vi.mocked(fetchProjects).mockResolvedValue([{ id: projectId, ...project } as any])
  render()
  await flushPromises()
  expect(fetchOtaRelease).not.toHaveBeenCalled()
})
it.each([
  [70021, '不存在或当前不可见'],
  [70022, '当前不可读取'],
  [70024, '当前角色无权']
])('错误%s清除旧证明并允许显式刷新', async (code, message) => {
  render()
  await flushPromises()
  expect(content().exists()).toBe(true)
  vi.mocked(fetchOtaRelease).mockRejectedValueOnce(new HttpError('内部错误不显示', Number(code)))
  await wrapper!.get('[data-testid="ota-release-proof-refresh"]').trigger('click')
  await flushPromises()
  expect(content().exists()).toBe(false)
  expect(wrapper!.text()).toContain(message)
  expect(wrapper!.text()).not.toContain('内部错误')
  await wrapper!.get('[data-testid="ota-release-proof-refresh"]').trigger('click')
  await flushPromises()
  expect(content().exists()).toBe(true)
})
it.each(['close', 'project', 'identity', 'role', 'firmware', 'unmount'])(
  '%s后丢弃证明迟响应',
  async (change) => {
    const pending = deferred()
    vi.mocked(fetchOtaRelease).mockReturnValue(pending.promise)
    render()
    await flushPromises()
    if (change === 'close') await wrapper!.setProps({ modelValue: false })
    if (change === 'project') state.user.info.currentProjectId = otherId
    if (change === 'identity') invalidateIdentity()
    if (change === 'role') state.user.info.roles = ['VIEWER']
    if (change === 'firmware') await wrapper!.setProps({ firmwareId: otherId })
    if (change === 'unmount') wrapper!.unmount()
    pending.resolve(response)
    await flushPromises()
    expect(content().exists()).toBe(false)
  }
)
it('资格迟响应在关闭后不继续读取证明，重新打开不复用上次响应', async () => {
  const pending = deferred()
  vi.mocked(fetchProjects).mockReturnValueOnce(pending.promise)
  render()
  await flushPromises()
  await wrapper!.setProps({ modelValue: false })
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  pending.resolve([{ id: projectId, status: 'ACTIVE', myRole: 'OWNER' }])
  await flushPromises()
  expect(fetchOtaRelease).toHaveBeenCalledTimes(1)
})
it.each([
  { firmwareId: otherId },
  { artifactSize: '0' },
  { artifactSize: 512 as unknown as string },
  { releaseCreatedAt: '2026-10-07' },
  { manifestBase64: '{}' },
  { downloadUrl: 'not-public-proof' }
])('不把异常合同当作成功或复制: %j', async (patch) => {
  vi.mocked(fetchOtaRelease).mockResolvedValue({ ...response, ...patch })
  render()
  await flushPromises()
  expect(content().exists()).toBe(false)
  expect(wrapper!.text()).toContain('响应无法确认')
  expect(
    wrapper!.get('[data-testid="ota-release-proof-copy"]').attributes('disabled')
  ).toBeDefined()
})
it('仅显式复制公开闭集JSON；剪贴板失败可恢复', async () => {
  const writeText = vi
    .fn()
    .mockRejectedValueOnce(new Error('clipboard denied'))
    .mockResolvedValueOnce(undefined)
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  render()
  await flushPromises()
  expect(writeText).not.toHaveBeenCalled()
  const button = wrapper!.get('[data-testid="ota-release-proof-copy"]')
  await button.trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('复制失败')
  await button.trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('公开证明已复制')
  expect(JSON.parse(writeText.mock.calls[1]![0])).toEqual(response)
})
