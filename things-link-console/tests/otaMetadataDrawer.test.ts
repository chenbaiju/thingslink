import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, h, inject, provide, reactive } from 'vue'
import Drawer from '@/views/ota/firmwares/OtaMetadataDrawer.vue'
import { fetchDeviceTypePage } from '@/api/device'
import { fetchOtaTrustDomains, fetchOtaTrustKeys, fetchOtaBaselineVersions } from '@/api/ota'
import { invalidateIdentity } from '@/utils/http/identity-scope'
import { HttpError } from '@/utils/http/error'

const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/device', () => ({ fetchDeviceTypePage: vi.fn() }))
vi.mock('@/api/ota', () => ({
  fetchOtaTrustDomains: vi.fn(),
  fetchOtaTrustKeys: vi.fn(),
  fetchOtaBaselineVersions: vi.fn()
}))
const projectId = '11111111-1111-4111-8111-111111111111'
const typeId = '22222222-2222-4222-8222-222222222222'
const otherTypeId = '33333333-3333-4333-8333-333333333333'
const hash = 'a'.repeat(64),
  otherHash = 'b'.repeat(64)
const domain = {
  trustDomain: 'example',
  revision: '1',
  bundleVersion: '1',
  policyRevision: '1',
  rootProfile: 'TC_OTA_ED25519_V1',
  rootFingerprint: hash,
  bundleSha256: hash,
  activeKeyVersion: 'key-1',
  activeKeyFingerprint: hash,
  createdAt: '2026-10-06T00:00:00Z',
  updatedAt: '2026-10-06T00:00:00Z'
}
const key = {
  keyVersion: 'key-1',
  state: 'ACTIVE',
  signatureProfile: 'TC_OTA_ED25519_V1',
  fingerprint: hash,
  notBefore: 0,
  notAfter: 253402300799
}
const baseline = { baselineVersion: 2, baselineHash: hash, registeredAt: '2026-10-06T00:00:00Z' }
const type = {
  id: typeId,
  projectId,
  name: '已发布类型',
  typeKey: 'published',
  deviceKind: 'DIRECT',
  payloadProtocol: 'STANDARD',
  networkType: 'WIFI',
  version: 1,
  status: 'PUBLISHED',
  productKey: null,
  createdAt: '2026-10-06T00:00:00Z'
}
function page(items: unknown[], cursor: string | null = null) {
  return { items, nextCursor: cursor, hasMore: cursor !== null } as any
}
let wrapper: VueWrapper | undefined
const table = (id: string) => wrapper!.get(`[data-testid="${id}"]`)
const click = (id: string) => table(id).trigger('click')
function deferred() {
  let resolve!: (value: any) => void, reject!: (value: unknown) => void
  const promise = new Promise<any>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function render(
  props: Partial<{ modelValue: boolean; projectId: string; authorized: boolean }> = {}
) {
  wrapper = mount(Drawer, {
    props: {
      modelValue: true,
      projectId,
      authorized: true,
      ...props,
      'onUpdate:modelValue': (value: boolean) => {
        if (wrapper) void wrapper.setProps({ modelValue: value })
      }
    },
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElDrawer: {
          props: ['modelValue', 'title'],
          template:
            '<div v-if="modelValue" role="dialog" :aria-label="title"><slot/><slot name="footer"/></div>'
        },
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot/></button>'
        },
        ElDivider: { template: '<h3><slot/></h3>' },
        ElTag: { template: '<span><slot/></span>' },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' },
        ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' },
        ElSelect: {
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue', 'change'],
          template:
            '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\', $event.target.value)"><option value=""/><slot/></select>'
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
            return () => h('section', props.data.length ? slots.default?.() : slots.empty?.())
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
async function selectType(id = typeId) {
  await table('ota-baseline-type').setValue(id)
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  wrapper = undefined
  state.user = reactive({
    info: {
      userId: 'owner',
      tenantId: 'tenant',
      currentProjectId: projectId,
      roles: ['OWNER'],
      buttons: ['ota:read', 'ota:deploy']
    }
  })
  vi.mocked(fetchOtaTrustDomains).mockResolvedValue(page([domain]))
  vi.mocked(fetchOtaTrustKeys).mockResolvedValue(page([key]))
  vi.mocked(fetchDeviceTypePage).mockResolvedValue(page([type]))
  vi.mocked(fetchOtaBaselineVersions).mockResolvedValue(page([baseline]))
})
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
})

it('只读展示公开域、发布键及基线，epoch秒窗口不推断当前资格', async () => {
  vi.stubGlobal('localStorage', { setItem: vi.fn() })
  vi.stubGlobal('sessionStorage', { setItem: vi.fn() })
  render()
  await flushPromises()
  expect(table('ota-trust-domains').text()).toContain('example')
  expect(table('ota-trust-domains').text()).toContain(hash)
  expect(fetchOtaTrustKeys).not.toHaveBeenCalled()
  expect(fetchOtaBaselineVersions).not.toHaveBeenCalled()
  await click('ota-trust-keys-open')
  await flushPromises()
  await selectType()
  expect(table('ota-trust-keys').text()).toContain('使用中')
  expect(table('ota-trust-keys').text()).toContain('1970-01-01T00:00:00.000Z')
  expect(table('ota-trust-keys').text()).toContain('不含终点')
  expect(table('ota-baseline-versions').text()).toContain(hash)
  expect(wrapper!.text()).toContain('不代表当前设备升级资格')
  expect(localStorage.setItem).not.toHaveBeenCalled()
  expect(sessionStorage.setItem).not.toHaveBeenCalled()
})

it('域、键和基线各自续页，末页移除加载更多且不自造游标', async () => {
  vi.mocked(fetchOtaTrustDomains)
    .mockResolvedValueOnce(page([domain], 'domain-cursor'))
    .mockResolvedValueOnce(page([{ ...domain, trustDomain: 'other' }]))
  vi.mocked(fetchOtaTrustKeys)
    .mockResolvedValueOnce(page([key], 'key-cursor'))
    .mockResolvedValueOnce(page([{ ...key, keyVersion: 'key-2', state: 'VERIFY_ONLY' }]))
  vi.mocked(fetchOtaBaselineVersions)
    .mockResolvedValueOnce(page([baseline], 'baseline-cursor'))
    .mockResolvedValueOnce(page([{ ...baseline, baselineVersion: 1 }]))
  render()
  await flushPromises()
  await click('ota-trust-domains-more')
  await flushPromises()
  expect(fetchOtaTrustDomains).toHaveBeenLastCalledWith(projectId, {
    cursor: 'domain-cursor',
    limit: 20
  })
  expect(table('ota-trust-domains').text()).toContain('other')
  expect(wrapper!.find('[data-testid="ota-trust-domains-more"]').exists()).toBe(false)
  await wrapper!.findAll('[data-testid="ota-trust-keys-open"]')[0].trigger('click')
  await flushPromises()
  await click('ota-trust-keys-more')
  await flushPromises()
  expect(fetchOtaTrustKeys).toHaveBeenLastCalledWith(projectId, 'example', {
    cursor: 'key-cursor',
    limit: 20
  })
  expect(table('ota-trust-keys').text()).toContain('key-2')
  expect(wrapper!.find('[data-testid="ota-trust-keys-more"]').exists()).toBe(false)
  await selectType()
  await click('ota-baseline-versions-more')
  await flushPromises()
  expect(fetchOtaBaselineVersions).toHaveBeenLastCalledWith(projectId, typeId, {
    cursor: 'baseline-cursor',
    limit: 20
  })
  expect(wrapper!.find('[data-testid="ota-baseline-versions-more"]').exists()).toBe(false)
})

it('设备类型读取全部分页，仅显示PUBLISHED，不能从草稿制造基线历史', async () => {
  vi.mocked(fetchDeviceTypePage)
    .mockResolvedValueOnce(page([{ ...type, status: 'DRAFT', name: '草稿类型' }], 'types-next'))
    .mockResolvedValueOnce(page([{ ...type, id: otherTypeId, name: '第二页类型' }]))
  render()
  await flushPromises()
  expect(fetchDeviceTypePage).toHaveBeenNthCalledWith(2, projectId, 'types-next', 100)
  expect(table('ota-baseline-type').text()).toContain('第二页类型')
  expect(table('ota-baseline-type').text()).not.toContain('草稿类型')
  await selectType(otherTypeId)
  expect(fetchOtaBaselineVersions).toHaveBeenCalledWith(projectId, otherTypeId, {
    cursor: undefined,
    limit: 20
  })
})

it('空集与未选择状态明确，不用错误冒充成功空集', async () => {
  vi.mocked(fetchOtaTrustDomains).mockResolvedValue(page([]))
  vi.mocked(fetchDeviceTypePage).mockResolvedValue(page([]))
  render()
  await flushPromises()
  expect(wrapper!.text()).toContain('本项目尚未登记信任域')
  expect(wrapper!.text()).toContain('请先选择信任域')
  expect(wrapper!.text()).toContain('没有已发布设备类型')
  expect(wrapper!.text()).toContain('请先选择已发布设备类型')
})

it('选定类型的真实空历史正常展示', async () => {
  vi.mocked(fetchOtaBaselineVersions).mockResolvedValue(page([]))
  render()
  await flushPromises()
  await selectType()
  expect(table('ota-baseline-versions').text()).toContain('该类型尚未登记基线版本')
})

it('旧包游标10001立即清除旧键并自动重读首页，不混合两代包', async () => {
  const replacement = deferred()
  vi.mocked(fetchOtaTrustKeys)
    .mockResolvedValueOnce(page([key], 'old-cursor'))
    .mockRejectedValueOnce(new HttpError('UNTRUSTED_BODY', 10001))
    .mockReturnValueOnce(replacement.promise)
  render()
  await flushPromises()
  await click('ota-trust-keys-open')
  await flushPromises()
  await click('ota-trust-keys-more')
  await flushPromises()
  expect(table('ota-trust-keys').text()).not.toContain('key-1')
  expect(table('ota-trust-keys-reset').text()).toContain('已清除旧包')
  expect(fetchOtaTrustKeys).toHaveBeenNthCalledWith(3, projectId, 'example', { limit: 20 })
  replacement.resolve(page([{ ...key, keyVersion: 'replacement', fingerprint: otherHash }]))
  await flushPromises()
  expect(table('ota-trust-keys').text()).toContain('replacement')
  expect(table('ota-trust-keys').text()).not.toContain('key-1')
  expect(wrapper!.text()).not.toContain('UNTRUSTED_BODY')
})

it('换包恢复首页也失败时不无限重试、不恢复旧包事实', async () => {
  vi.mocked(fetchOtaTrustKeys)
    .mockResolvedValueOnce(page([key], 'old'))
    .mockRejectedValue(new HttpError('hidden', 10001))
  render()
  await flushPromises()
  await click('ota-trust-keys-open')
  await flushPromises()
  await click('ota-trust-keys-more')
  await flushPromises()
  expect(fetchOtaTrustKeys).toHaveBeenCalledTimes(3)
  expect(table('ota-trust-keys').text()).not.toContain('key-1')
  expect(table('ota-trust-keys-error').text()).toContain('分页参数已失效')
})

it('非游标错误保留已加载键，显示失败并允许手动重试', async () => {
  vi.mocked(fetchOtaTrustKeys)
    .mockResolvedValueOnce(page([key], 'same'))
    .mockRejectedValueOnce(new HttpError('hidden', 503))
  render()
  await flushPromises()
  await click('ota-trust-keys-open')
  await flushPromises()
  await click('ota-trust-keys-more')
  await flushPromises()
  expect(fetchOtaTrustKeys).toHaveBeenCalledTimes(2)
  expect(table('ota-trust-keys').text()).toContain('key-1')
  expect(table('ota-trust-keys-error').text()).toContain('读取发布键失败')
})

it.each([70013, 50001, 401])('域错误%s只显示固定公开分类，不能泄露异常正文', async (code) => {
  vi.mocked(fetchOtaTrustDomains).mockRejectedValue(
    new HttpError('SECRET_OR_INTERNAL_DETAIL', code)
  )
  render()
  await flushPromises()
  expect(wrapper!.find('[data-testid="ota-trust-domains-error"]').exists()).toBe(true)
  expect(wrapper!.text()).not.toContain('SECRET_OR_INTERNAL_DETAIL')
  expect(wrapper!.text()).not.toContain('本项目尚未登记信任域')
})

it('未知或跨项目类型404显示不可见，不宣称无登记', async () => {
  vi.mocked(fetchOtaBaselineVersions).mockRejectedValue(new HttpError('hidden', 70031))
  render()
  await flushPromises()
  await selectType()
  expect(table('ota-baseline-error').text()).toContain('尚未生成产品标识或当前项目不可见')
  expect(table('ota-baseline-versions').text()).not.toContain('尚未登记')
})

it.each([
  { ...domain, privateKey: 'DO_NOT_DISPLAY' },
  { ...domain, revision: null },
  { ...domain, bundleVersion: 1 },
  { ...domain, activeKeyVersion: undefined }
])('拒绝额外秘密字段或缺失必填域字段', async (value) => {
  vi.mocked(fetchOtaTrustDomains).mockResolvedValue(page([value]))
  render()
  await flushPromises()
  expect(wrapper!.find('[data-testid="ota-trust-domains-error"]').exists()).toBe(true)
  expect(wrapper!.text()).not.toContain('DO_NOT_DISPLAY')
  expect(wrapper!.text()).not.toContain('example')
})

it.each([
  { ...key, spki: 'DO_NOT_DISPLAY' },
  { ...key, notBefore: null },
  { ...key, notAfter: 0 },
  { ...key, state: ['ACTIVE'] }
])('拒绝公钥正文、空值及错误键字段', async (value) => {
  vi.mocked(fetchOtaTrustKeys).mockResolvedValue(page([value]))
  render()
  await flushPromises()
  await click('ota-trust-keys-open')
  await flushPromises()
  expect(wrapper!.find('[data-testid="ota-trust-keys-error"]').exists()).toBe(true)
  expect(table('ota-trust-keys').text()).not.toContain('key-1')
  expect(wrapper!.text()).not.toContain('DO_NOT_DISPLAY')
})

it.each([
  { ...baseline, supportsAbSlots: true },
  { ...baseline, baselineVersion: 0 },
  { ...baseline, baselineVersion: Number.MAX_SAFE_INTEGER + 1 },
  { ...baseline, registeredAt: null }
])('基线历史拒绝能力字段及非法版本/空时间', async (value) => {
  vi.mocked(fetchOtaBaselineVersions).mockResolvedValue(page([value]))
  render()
  await flushPromises()
  await selectType()
  expect(wrapper!.find('[data-testid="ota-baseline-error"]').exists()).toBe(true)
  expect(table('ota-baseline-versions').text()).not.toContain(hash)
})

it.each([{}, { items: [], hasMore: false }, { items: [], hasMore: true, nextCursor: 'bad' }])(
  '坏分页不被默认值吞成成功空集',
  async (value) => {
    vi.mocked(fetchOtaTrustDomains).mockResolvedValue(value as any)
    render()
    await flushPromises()
    expect(wrapper!.find('[data-testid="ota-trust-domains-error"]').exists()).toBe(true)
  }
)

it('重复类型游标或跨项目类型不能进入类型选择', async () => {
  vi.mocked(fetchDeviceTypePage).mockResolvedValue(page([{ ...type, projectId: otherTypeId }]))
  render()
  await flushPromises()
  expect(wrapper!.find('[data-testid="ota-baseline-types-error"]').exists()).toBe(true)
  expect(table('ota-baseline-type').text()).not.toContain('已发布类型')
})

it.each(['VIEWER', 'OPERATOR'])('%s没有抽屉读取入口且不发API', async (role) => {
  state.user.info.roles = [role]
  render()
  await flushPromises()
  expect(fetchOtaTrustDomains).not.toHaveBeenCalled()
  expect(fetchDeviceTypePage).not.toHaveBeenCalled()
  expect(wrapper!.find('[role="dialog"]').exists()).toBe(false)
})

it('路由许可或当前权限列表缺失不发API', async () => {
  render({ authorized: false })
  await flushPromises()
  expect(fetchOtaTrustDomains).not.toHaveBeenCalled()
  await wrapper!.setProps({ authorized: true, modelValue: true })
  state.user.info.buttons = []
  await flushPromises()
  expect(fetchOtaTrustDomains).not.toHaveBeenCalled()
})

it.each(['account', 'tenant', 'project', 'epoch', 'permission', 'route'])(
  '切换%s立即清理并拒迟响应',
  async (boundary) => {
    const pending = deferred()
    vi.mocked(fetchOtaTrustDomains).mockReturnValue(pending.promise)
    render()
    await flushPromises()
    if (boundary === 'account') state.user.info.userId = 'other'
    if (boundary === 'tenant') state.user.info.tenantId = 'other'
    if (boundary === 'project') state.user.info.currentProjectId = otherTypeId
    if (boundary === 'epoch') invalidateIdentity()
    if (boundary === 'permission') state.user.info.buttons = []
    if (boundary === 'route') await wrapper!.setProps({ authorized: false })
    pending.resolve(page([domain]))
    await flushPromises()
    expect(wrapper!.text()).not.toContain('example')
    expect(wrapper!.find('[role="dialog"]').exists()).toBe(false)
  }
)

it('关闭、重开后旧请求不能替换新会话事实或清除新加载状态', async () => {
  const pending = deferred(),
    fresh = deferred()
  vi.mocked(fetchOtaTrustDomains)
    .mockReturnValueOnce(pending.promise)
    .mockReturnValueOnce(fresh.promise)
  render()
  await flushPromises()
  await wrapper!
    .findAll('button')
    .find((button) => button.text() === '关闭')!
    .trigger('click')
  await flushPromises()
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  pending.resolve(page([domain]))
  await flushPromises()
  expect(wrapper!.text()).not.toContain('example')
  fresh.resolve(page([{ ...domain, trustDomain: 'fresh' }]))
  await flushPromises()
  expect(table('ota-trust-domains').text()).toContain('fresh')
  expect(table('ota-trust-domains').text()).not.toContain('example')
})

it('连续选不同域时丢弃上一个域的迟到发布键', async () => {
  const pending = deferred()
  vi.mocked(fetchOtaTrustDomains).mockResolvedValue(
    page([domain, { ...domain, trustDomain: 'other' }])
  )
  vi.mocked(fetchOtaTrustKeys)
    .mockReturnValueOnce(pending.promise)
    .mockResolvedValueOnce(page([{ ...key, keyVersion: 'other-key' }]))
  render()
  await flushPromises()
  await wrapper!.findAll('[data-testid="ota-trust-keys-open"]')[0].trigger('click')
  await wrapper!.findAll('[data-testid="ota-trust-keys-open"]')[1].trigger('click')
  await flushPromises()
  pending.resolve(page([key]))
  await flushPromises()
  expect(table('ota-trust-keys').text()).toContain('other-key')
  expect(table('ota-trust-keys').text()).not.toContain('key-1')
})

it('连续选不同类型时丢弃旧类型迟到基线', async () => {
  const pending = deferred()
  vi.mocked(fetchDeviceTypePage).mockResolvedValue(
    page([type, { ...type, id: otherTypeId, name: '另一个' }])
  )
  vi.mocked(fetchOtaBaselineVersions)
    .mockReturnValueOnce(pending.promise)
    .mockResolvedValueOnce(page([{ ...baseline, baselineHash: otherHash }]))
  render()
  await flushPromises()
  await selectType()
  await selectType(otherTypeId)
  pending.resolve(page([baseline]))
  await flushPromises()
  expect(table('ota-baseline-versions').text()).toContain(otherHash)
  expect(table('ota-baseline-versions').text()).not.toContain(hash)
})

it('类型续页遇到重复游标时停止读取且不暴露半截目录', async () => {
  vi.mocked(fetchDeviceTypePage)
    .mockResolvedValueOnce(page([type], 'repeated'))
    .mockResolvedValueOnce(page([{ ...type, id: otherTypeId }], 'repeated'))
  render()
  await flushPromises()
  expect(fetchDeviceTypePage).toHaveBeenCalledTimes(2)
  expect(wrapper!.find('[data-testid="ota-baseline-types-error"]').exists()).toBe(true)
  expect(table('ota-baseline-type').text()).not.toContain('已发布类型')
})

it('类型续页在身份变化后不再读下一页或回填旧类型', async () => {
  const pending = deferred()
  vi.mocked(fetchDeviceTypePage).mockReturnValueOnce(pending.promise)
  render()
  await flushPromises()
  invalidateIdentity()
  pending.resolve(page([type], 'next'))
  await flushPromises()
  expect(fetchDeviceTypePage).toHaveBeenCalledOnce()
  expect(wrapper!.text()).not.toContain('已发布类型')
})

it('换包首页恢复在关闭后到达也不回填', async () => {
  const pending = deferred()
  vi.mocked(fetchOtaTrustKeys)
    .mockResolvedValueOnce(page([key], 'old'))
    .mockRejectedValueOnce(new HttpError('hidden', 10001))
    .mockReturnValueOnce(pending.promise)
  render()
  await flushPromises()
  await click('ota-trust-keys-open')
  await flushPromises()
  await click('ota-trust-keys-more')
  await flushPromises()
  await wrapper!.setProps({ modelValue: false })
  pending.resolve(page([{ ...key, keyVersion: 'late-key' }]))
  await flushPromises()
  expect(wrapper!.text()).not.toContain('late-key')
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  expect(table('ota-trust-keys').text()).toContain('请先选择信任域')
})
