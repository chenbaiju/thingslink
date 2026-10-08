import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Dialog from '@/views/ota/firmwares/OtaTypeBaselineRegistrationDialog.vue'
import { fetchDeviceTypeDetail, fetchDeviceTypePage } from '@/api/device'
import { fetchOtaTypeBaseline, registerOtaTypeBaseline, sha256Hex } from '@/api/ota'
import { fetchProjects } from '@/api/project'
import { HttpError } from '@/utils/http/error'
import { invalidateIdentity } from '@/utils/http/identity-scope'
import {
  baseline,
  baselineScope,
  baselineSnapshot,
  publishedType
} from './helpers/otaBaselinePublicFixture'
import { publicSha } from './helpers/otaTrustPublicFixture'
const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/device', () => ({ fetchDeviceTypeDetail: vi.fn(), fetchDeviceTypePage: vi.fn() }))
vi.mock('@/api/ota', () => ({
  fetchOtaTypeBaseline: vi.fn(),
  registerOtaTypeBaseline: vi.fn(),
  sha256Hex: vi.fn()
}))
vi.mock('@/api/project', () => ({ fetchProjects: vi.fn() }))
const { projectId, deviceTypeId, tenantId } = baselineScope
let wrapper: VueWrapper | undefined
const get = (name: string) => wrapper!.get(`[data-testid="ota-baseline-registration-${name}"]`)
const enabled = () => get('submit').attributes('disabled') === undefined
const page = (items: unknown[], nextCursor: string | null = null) =>
  ({ items, nextCursor, hasMore: nextCursor !== null }) as any
function deferred() {
  let resolve!: (value: any) => void, reject!: (value: unknown) => void
  const promise = new Promise<any>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function render(props = {}) {
  wrapper = mount(Dialog, {
    props: {
      modelValue: true,
      projectId,
      authorized: true,
      ...props,
      'onUpdate:modelValue': (value: boolean) => {
        void wrapper?.setProps({ modelValue: value })
      }
    },
    global: {
      stubs: {
        ElDialog: {
          props: ['modelValue', 'title'],
          template:
            '<section v-if="modelValue" role="dialog" :aria-label="title"><slot/><slot name="footer"/></section>'
        },
        ElSelect: {
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue'],
          template:
            '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><option value="">请选择</option><slot/></select>'
        },
        ElOption: {
          props: ['value', 'label'],
          template: '<option :value="value">{{label}}</option>'
        },
        ElCheckbox: {
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue'],
          template:
            '<label><input type="checkbox" :checked="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.checked)"/><slot/></label>'
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
async function check() {
  await get('check').trigger('click')
  await flushPromises()
}
async function confirm() {
  await get('config-confirm').get('input').setValue(true)
}
async function submit() {
  await get('submit').trigger('click')
  await flushPromises()
}
async function prepare() {
  render()
  await flushPromises()
  await get('type').setValue(deviceTypeId)
  await check()
  await confirm()
}
beforeEach(() => {
  vi.resetAllMocks()
  state.user = reactive({
    info: {
      currentProjectId: projectId,
      tenantId,
      userId: 'owner',
      roles: ['OWNER'],
      buttons: ['ota:deploy']
    }
  })
  vi.mocked(fetchProjects).mockResolvedValue([
    { id: projectId, status: 'ACTIVE', myRole: 'OWNER' }
  ] as any)
  vi.mocked(fetchDeviceTypePage).mockResolvedValue(page([publishedType()]))
  vi.mocked(fetchDeviceTypeDetail).mockResolvedValue(publishedType() as any)
  vi.mocked(fetchOtaTypeBaseline).mockResolvedValue(baselineSnapshot())
  vi.mocked(sha256Hex).mockImplementation(async (value) => publicSha(value))
  vi.mocked(registerOtaTypeBaseline).mockImplementation(async (_project, _type, body) =>
    baselineSnapshot(
      baseline(Number(body.expectedRevision) + 1),
      String(BigInt(body.expectedRevision) + 1n)
    )
  )
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
it('登录tenantA的项目ADMIN接受精确项目tenantB的完整GET和真实格式成功POST', async () => {
  const projectTenant = '44444444-4444-4444-8444-444444444444'
  state.user.info.roles = ['ADMIN']
  vi.mocked(fetchProjects).mockResolvedValue([
    { id: projectId, status: 'ACTIVE', myRole: 'ADMIN' }
  ] as any)
  vi.mocked(fetchOtaTypeBaseline).mockResolvedValue(
    baselineSnapshot({ ...baseline(), tenantId: projectTenant })
  )
  vi.mocked(registerOtaTypeBaseline).mockResolvedValue(
    baselineSnapshot({ ...baseline(3), tenantId: projectTenant }, '3')
  )
  await prepare()
  expect(get('current').text()).toContain(projectTenant)
  expect(enabled()).toBe(true)
  await submit()
  expect(get('submit').text()).toBe('登记服务器配置')
  expect(wrapper!.text()).toContain('登记已确认')
  expect(get('version').text()).toContain('3')
  expect(state.user.info.tenantId).toBe(tenantId)
})
it('跨租户ADMIN首GET70031后，未知项目tenant的首POST只接受严格UUID且成功后绑定', async () => {
  const projectTenant = '44444444-4444-4444-8444-444444444444'
  state.user.info.roles = ['ADMIN']
  vi.mocked(fetchProjects).mockResolvedValue([
    { id: projectId, status: 'ACTIVE', myRole: 'ADMIN' }
  ] as any)
  vi.mocked(fetchOtaTypeBaseline).mockRejectedValue(new HttpError('PRIVATE', 70031))
  vi.mocked(registerOtaTypeBaseline).mockResolvedValue(
    baselineSnapshot({ ...baseline(1), tenantId: projectTenant }, '1')
  )
  await prepare()
  await submit()
  expect(vi.mocked(registerOtaTypeBaseline).mock.calls[0]![2]).toEqual({ expectedRevision: '0' })
  expect(wrapper!.text()).toContain('登记已确认')
  await wrapper!.setProps({ modelValue: false })
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  await get('type').setValue(deviceTypeId)
  vi.mocked(fetchOtaTypeBaseline).mockResolvedValue(
    baselineSnapshot({ ...baseline(1), tenantId: tenantId }, '1')
  )
  await check()
  expect(wrapper!.find('[data-testid="ota-baseline-registration-current"]').exists()).toBe(false)
})
it('已核SHA的server tenant绑定同type；unknown关开仍拒绝不同tenant成功响应但保留原key', async () => {
  const projectTenant = '44444444-4444-4444-8444-444444444444'
  vi.mocked(fetchOtaTypeBaseline).mockResolvedValue(
    baselineSnapshot({ ...baseline(), tenantId: projectTenant })
  )
  vi.mocked(registerOtaTypeBaseline).mockRejectedValueOnce(
    new HttpError('PRIVATE', 500, { outcomeUnknown: true })
  )
  await prepare()
  await submit()
  const original = vi.mocked(registerOtaTypeBaseline).mock.calls[0]!
  await wrapper!.setProps({ modelValue: false })
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  vi.mocked(registerOtaTypeBaseline).mockResolvedValueOnce(baselineSnapshot(baseline(3), '3'))
  await submit()
  expect(get('submit').text()).toBe('使用原键恢复')
  expect(vi.mocked(registerOtaTypeBaseline).mock.calls[1]![2]).toBe(original[2])
  expect(vi.mocked(registerOtaTypeBaseline).mock.calls[1]![3]).toBe(original[3])
  vi.mocked(registerOtaTypeBaseline).mockResolvedValueOnce(
    baselineSnapshot({ ...baseline(3), tenantId: projectTenant }, '3')
  )
  await submit()
  expect(wrapper!.text()).toContain('登记已确认')
})
it.each(['GET', 'POST'])('初始%s响应tenant非UUID不能被信任或绑定', async (kind) => {
  const malformed = baselineSnapshot({ ...baseline(3), tenantId: 'not-a-uuid' }, '3')
  if (kind === 'GET') {
    vi.mocked(fetchOtaTypeBaseline).mockResolvedValue(malformed)
    render()
    await flushPromises()
    await get('type').setValue(deviceTypeId)
    await check()
    expect(wrapper!.find('[data-testid="ota-baseline-registration-current"]').exists()).toBe(false)
    expect(enabled()).toBe(false)
  } else {
    vi.mocked(fetchOtaTypeBaseline).mockRejectedValue(new HttpError('PRIVATE', 70031))
    vi.mocked(registerOtaTypeBaseline).mockResolvedValue({ ...malformed, revision: '1' })
    await prepare()
    await submit()
    expect(get('submit').text()).toBe('使用原键恢复')
  }
})
it('登录tenant切换仍销毁业务pin，迟到的旧项目tenant响应不能建立新绑定', async () => {
  render()
  await flushPromises()
  await get('type').setValue(deviceTypeId)
  const late = deferred()
  vi.mocked(fetchOtaTypeBaseline).mockReturnValueOnce(late.promise)
  await get('check').trigger('click')
  await flushPromises()
  state.user.info.tenantId = '44444444-4444-4444-8444-444444444444'
  await flushPromises()
  late.resolve(
    baselineSnapshot({ ...baseline(), tenantId: '55555555-5555-4555-8555-555555555555' })
  )
  await flushPromises()
  expect(wrapper!.find('[role="dialog"]').exists()).toBe(false)
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  await get('type').setValue(deviceTypeId)
  await check()
  expect(get('current').text()).toContain(tenantId)
})

it('进入只读全部类型；显式GET、精确资格与配置更新确认后只POST冻结修订', async () => {
  const local = vi.fn(),
    session = vi.fn()
  vi.stubGlobal('localStorage', { setItem: local })
  vi.stubGlobal('sessionStorage', { setItem: session })
  render()
  await flushPromises()
  expect(fetchDeviceTypePage).toHaveBeenCalledExactlyOnceWith(projectId, undefined, 100)
  expect(fetchOtaTypeBaseline).not.toHaveBeenCalled()
  expect(registerOtaTypeBaseline).not.toHaveBeenCalled()
  await get('type').setValue(deviceTypeId)
  await check()
  expect(enabled()).toBe(false)
  expect(get('version').text()).toContain('2')
  expect(get('hash').text()).toContain(baselineSnapshot().baselineHash)
  expect(get('current').text()).toContain('false')
  expect(get('current').text()).toContain('受保护计数器位数')
  expect(get('current').text()).toContain('0')
  expect(get('current').text()).toContain('受控测试来源/公开索引')
  expect(wrapper!.find('textarea').exists()).toBe(false)
  expect(wrapper!.find('input[type="file"]').exists()).toBe(false)
  await confirm()
  await submit()
  expect(fetchProjects).toHaveBeenCalledTimes(2)
  expect(fetchDeviceTypeDetail).toHaveBeenCalledTimes(2)
  expect(registerOtaTypeBaseline).toHaveBeenCalledExactlyOnceWith(
    projectId,
    deviceTypeId,
    { expectedRevision: '2' },
    expect.any(String)
  )
  expect(Object.isFrozen(vi.mocked(registerOtaTypeBaseline).mock.calls[0]![2])).toBe(true)
  expect(wrapper!.text()).toContain('不代表制造证据、实物能力或设备升级资格已验收')
  expect(local).not.toHaveBeenCalled()
  expect(session).not.toHaveBeenCalled()
})
it('分页收集全部类型，仅PUBLISHED有productKey可选，坏页不能形成部分目录', async () => {
  vi.mocked(fetchDeviceTypePage)
    .mockResolvedValueOnce(
      page(
        [
          publishedType({ status: 'DRAFT' }),
          publishedType({ id: '44444444-4444-4444-8444-444444444444', productKey: null })
        ],
        'opaque'
      )
    )
    .mockResolvedValueOnce(
      page([publishedType({ id: '55555555-5555-4555-8555-555555555555', name: '续页发布类型' })])
    )
  render()
  await flushPromises()
  expect(fetchDeviceTypePage).toHaveBeenLastCalledWith(projectId, 'opaque', 100)
  expect(
    get('type')
      .findAll('option')
      .map((option) => option.text())
  ).toEqual(['请选择', '续页发布类型（55555555-5555-4555-8555-555555555555）'])
  expect(wrapper!.text()).toContain('缺少产品标识')
})
it.each(['cursor', 'duplicate', 'scope', 'failure'])(
  '%s类型页失败不洗成成功空集或局部目录',
  async (kind) => {
    if (kind === 'scope')
      vi.mocked(fetchDeviceTypePage).mockResolvedValue(
        page([publishedType({ projectId: 'wrong' })])
      )
    else if (kind === 'failure')
      vi.mocked(fetchDeviceTypePage).mockRejectedValue(new Error('PRIVATE'))
    else {
      vi.mocked(fetchDeviceTypePage)
        .mockResolvedValueOnce(page([publishedType()], 'again'))
        .mockResolvedValueOnce(
          page(kind === 'duplicate' ? [publishedType()] : [], kind === 'cursor' ? 'again' : null)
        )
    }
    render()
    await flushPromises()
    expect(wrapper!.text()).toContain('读取已发布设备类型失败')
    expect(get('type').findAll('option')).toHaveLength(1)
    expect(wrapper!.text()).not.toContain('PRIVATE')
  }
)
it('真实70031仅允许精确类型首0，仍须明确服务器预配置确认', async () => {
  vi.mocked(fetchOtaTypeBaseline).mockRejectedValue(new HttpError('PRIVATE', 70031))
  render()
  await flushPromises()
  await get('type').setValue(deviceTypeId)
  await check()
  expect(get('revision').text()).toContain('0')
  expect(enabled()).toBe(false)
  expect(wrapper!.text()).toContain('不证明服务器已配置基线')
  await confirm()
  expect(enabled()).toBe(true)
  await submit()
  expect(vi.mocked(registerOtaTypeBaseline).mock.calls[0]![2]).toEqual({ expectedRevision: '0' })
})
it.each([
  new HttpError('PRIVATE', 404),
  new HttpError('PRIVATE', 70031, { outcomeUnknown: true }),
  new Error('PRIVATE')
])('GET失败不能作首次0或继承旧快照', async (error) => {
  await prepare()
  vi.mocked(fetchOtaTypeBaseline).mockRejectedValue(error)
  await check()
  expect(wrapper!.find('[data-testid="ota-baseline-registration-revision"]').exists()).toBe(false)
  expect(enabled()).toBe(false)
  expect(wrapper!.text()).not.toContain('PRIVATE')
  expect(registerOtaTypeBaseline).not.toHaveBeenCalled()
})
it.each(['ARCHIVED', 'DELETING', 'DELETED', 'VIEWER'])(
  '%s项目或真实角色不能登记',
  async (value) => {
    vi.mocked(fetchProjects).mockResolvedValue([
      {
        id: projectId,
        status: value === 'VIEWER' ? 'ACTIVE' : value,
        myRole: value === 'VIEWER' ? value : 'OWNER'
      }
    ] as any)
    render()
    await flushPromises()
    await get('type').setValue(deviceTypeId)
    await check()
    expect(enabled()).toBe(false)
    expect(fetchOtaTypeBaseline).not.toHaveBeenCalled()
    expect(registerOtaTypeBaseline).not.toHaveBeenCalled()
  }
)
it.each([
  { status: 'DRAFT' },
  { productKey: null },
  { productKey: 'changed' },
  { id: '44444444-4444-4444-8444-444444444444' }
])('登记前重读权威类型，身份变化%s阻止POST', async (change) => {
  await prepare()
  vi.mocked(fetchDeviceTypeDetail).mockResolvedValue(publishedType(change) as any)
  await submit()
  expect(registerOtaTypeBaseline).not.toHaveBeenCalled()
  expect(wrapper!.text()).toContain('产品身份已变化')
})
it.each(['unknown', '10010', '70028'])(
  '%s原正文与键冻结，后续GET更高快照不改恢复请求',
  async (kind) => {
    vi.mocked(registerOtaTypeBaseline).mockRejectedValueOnce(
      new HttpError('PRIVATE', kind === '10010' ? 10010 : kind === '70028' ? 70028 : 500, {
        outcomeUnknown: kind !== '10010'
      })
    )
    await prepare()
    await submit()
    const original = vi.mocked(registerOtaTypeBaseline).mock.calls[0]!
    expect(get('submit').text()).toBe('使用原键恢复')
    expect(get('type').attributes('disabled')).toBeDefined()
    if (kind === '70028') expect(wrapper!.text()).toContain('精确类型受控基线尚未配置或不可用')
    vi.mocked(fetchOtaTypeBaseline).mockResolvedValue(baselineSnapshot(baseline(3), '3'))
    await check()
    await submit()
    expect(vi.mocked(registerOtaTypeBaseline).mock.calls[1]![2]).toBe(original[2])
    expect(vi.mocked(registerOtaTypeBaseline).mock.calls[1]![3]).toBe(original[3])
    expect(wrapper!.text()).not.toContain('PRIVATE')
  }
)
it.each(['unknown', '10010', 'inflight'])(
  '%s关闭重开仅原类型/原key恢复，晚到响应不恢复公开快照',
  async (kind) => {
    const late = deferred()
    if (kind === 'inflight') vi.mocked(registerOtaTypeBaseline).mockReturnValueOnce(late.promise)
    else
      vi.mocked(registerOtaTypeBaseline).mockRejectedValueOnce(
        new HttpError('PRIVATE', kind === '10010' ? 10010 : 500, {
          outcomeUnknown: kind !== '10010'
        })
      )
    await prepare()
    await submit()
    const original = vi.mocked(registerOtaTypeBaseline).mock.calls[0]!
    await wrapper!.setProps({ modelValue: false })
    late.resolve(baselineSnapshot(baseline(3), '3'))
    await flushPromises()
    await wrapper!.setProps({ modelValue: true })
    await flushPromises()
    expect(wrapper!.text()).toContain('上次请求结果未知或仍在处理')
    expect(get('type').element).toHaveProperty('value', deviceTypeId)
    expect(get('type').attributes('disabled')).toBeDefined()
    expect(wrapper!.find('[data-testid="ota-baseline-registration-current"]').exists()).toBe(false)
    await submit()
    const repeated = vi.mocked(registerOtaTypeBaseline).mock.calls[1]!
    expect(repeated[2]).toBe(original[2])
    expect(repeated[3]).toBe(original[3])
  }
)
it.each(['same', 'higher', 'missing', 'failure'])(
  '10014永久结束原意图；%s GET仅当前快照，新意图必须fresh GET及新确认',
  async (kind) => {
    vi.mocked(registerOtaTypeBaseline).mockRejectedValueOnce(new HttpError('PRIVATE', 10014))
    await prepare()
    await submit()
    expect(enabled()).toBe(false)
    expect(wrapper!.text()).toContain('原意图永久结束')
    if (kind === 'same') vi.mocked(fetchOtaTypeBaseline).mockResolvedValue(baselineSnapshot())
    else if (kind === 'higher')
      vi.mocked(fetchOtaTypeBaseline).mockResolvedValue(baselineSnapshot(baseline(4), '4'))
    else
      vi.mocked(fetchOtaTypeBaseline).mockRejectedValue(
        new HttpError('PRIVATE', kind === 'missing' ? 70031 : 403)
      )
    await check()
    expect(enabled()).toBe(false)
    await submit()
    expect(registerOtaTypeBaseline).toHaveBeenCalledOnce()
    expect(wrapper!.text()).toContain(
      kind === 'same' || kind === 'higher' ? '当前权威登记快照' : '原写失败'
    )
    await get('new-intent').trigger('click')
    expect(wrapper!.find('[data-testid="ota-baseline-registration-current"]').exists()).toBe(false)
    expect(enabled()).toBe(false)
    await flushPromises()
    await get('type').setValue(deviceTypeId)
    vi.mocked(fetchOtaTypeBaseline).mockResolvedValue(baselineSnapshot(baseline(3), '3'))
    await check()
    expect(enabled()).toBe(false)
    await confirm()
    await submit()
    const second = vi.mocked(registerOtaTypeBaseline).mock.calls[1]!
    expect(second[2]).toEqual({ expectedRevision: '3' })
    expect(second[3]).not.toBe(vi.mocked(registerOtaTypeBaseline).mock.calls[0]![3])
  }
)
it.each(['success', 'completed'])(
  '%s CAS槽围栏在关开后仍阻同slot或70031新键，不冒称目标版本',
  async (kind) => {
    if (kind === 'completed')
      vi.mocked(registerOtaTypeBaseline).mockRejectedValueOnce(new HttpError('PRIVATE', 10014))
    await prepare()
    await submit()
    await wrapper!.setProps({ modelValue: false })
    await wrapper!.setProps({ modelValue: true })
    await flushPromises()
    await get('type').setValue(deviceTypeId)
    await check()
    await confirm()
    expect(get('completed-fence').text()).toContain('同槽或更低修订')
    expect(enabled()).toBe(false)
    vi.mocked(fetchOtaTypeBaseline).mockRejectedValue(new HttpError('PRIVATE', 70031))
    await check()
    await confirm()
    expect(enabled()).toBe(false)
    expect(registerOtaTypeBaseline).toHaveBeenCalledOnce()
    expect(wrapper!.text()).not.toContain('将登记配置版本')
  }
)
it.each([70029, 70030, 50017, 403])(
  '明确拒绝%s清预读，固定分类不传播原文或自动重提',
  async (code) => {
    vi.mocked(registerOtaTypeBaseline).mockRejectedValueOnce(new HttpError('PRIVATE', code))
    await prepare()
    await submit()
    expect(get('submit').text()).toBe('登记服务器配置')
    expect(enabled()).toBe(false)
    expect(wrapper!.text()).not.toContain('PRIVATE')
    expect(registerOtaTypeBaseline).toHaveBeenCalledOnce()
  }
)
it('首次写前读异常不锁无key未知；恢复资格异常仍保留原key', async () => {
  await prepare()
  vi.mocked(fetchProjects).mockRejectedValueOnce(new Error('PRIVATE'))
  await submit()
  expect(registerOtaTypeBaseline).not.toHaveBeenCalled()
  expect(get('submit').text()).toBe('登记服务器配置')
  vi.mocked(registerOtaTypeBaseline).mockRejectedValueOnce(
    new HttpError('PRIVATE', 500, { outcomeUnknown: true })
  )
  await submit()
  const original = vi.mocked(registerOtaTypeBaseline).mock.calls[0]![3]
  vi.mocked(fetchProjects).mockRejectedValueOnce(new Error('PRIVATE'))
  await submit()
  expect(registerOtaTypeBaseline).toHaveBeenCalledOnce()
  await submit()
  expect(vi.mocked(registerOtaTypeBaseline).mock.calls[1]![3]).toBe(original)
})
it('未知关开原类型恢复10014后，新意图重新取全目录，可选第二类型且必须fresh GET/配置确认', async () => {
  const secondId = '66666666-6666-4666-8666-666666666666'
  const second = publishedType({
    id: secondId,
    name: '另一已发布类型',
    productKey: 'second_public_product'
  })
  vi.mocked(fetchDeviceTypePage).mockResolvedValue(page([publishedType(), second]))
  vi.mocked(fetchDeviceTypeDetail).mockImplementation(
    async (_project, typeId) => (typeId === secondId ? second : publishedType()) as any
  )
  vi.mocked(registerOtaTypeBaseline)
    .mockRejectedValueOnce(new HttpError('PRIVATE', 500, { outcomeUnknown: true }))
    .mockRejectedValueOnce(new HttpError('PRIVATE', 10014))
  await prepare()
  await submit()
  const original = vi.mocked(registerOtaTypeBaseline).mock.calls[0]!
  await wrapper!.setProps({ modelValue: false })
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  expect(get('type').findAll('option')).toHaveLength(2)
  expect(fetchDeviceTypePage).toHaveBeenCalledOnce()
  await submit()
  expect(vi.mocked(registerOtaTypeBaseline).mock.calls[1]![3]).toBe(original[3])
  const directory = deferred()
  vi.mocked(fetchDeviceTypePage)
    .mockReturnValueOnce(directory.promise)
    .mockResolvedValueOnce(page([second]))
  await get('new-intent').trigger('click')
  expect(get('type').element).toHaveProperty('value', '')
  expect(get('type').findAll('option')).toHaveLength(1)
  expect(enabled()).toBe(false)
  directory.resolve(page([publishedType()], 'next-directory-page'))
  await flushPromises()
  expect(fetchDeviceTypePage).toHaveBeenLastCalledWith(projectId, 'next-directory-page', 100)
  expect(
    get('type')
      .findAll('option')
      .map((option) => option.text())
  ).toEqual(['请选择', `受控已发布类型（${deviceTypeId}）`, `另一已发布类型（${secondId}）`])
  await get('type').setValue(deviceTypeId)
  await check()
  await confirm()
  expect(wrapper!.find('[data-testid="ota-baseline-registration-completed-fence"]').exists()).toBe(
    true
  )
  expect(enabled()).toBe(false)
  vi.mocked(fetchOtaTypeBaseline).mockResolvedValueOnce(
    baselineSnapshot({ ...baseline(), tenantId: '77777777-7777-4777-8777-777777777777' })
  )
  await check()
  expect(wrapper!.find('[data-testid="ota-baseline-registration-current"]').exists()).toBe(false)
  await get('type').setValue(secondId)
  expect(enabled()).toBe(false)
  vi.mocked(fetchOtaTypeBaseline).mockRejectedValueOnce(new HttpError('PRIVATE', 70031))
  await check()
  expect(enabled()).toBe(false)
  await confirm()
  expect(enabled()).toBe(true)
  vi.mocked(registerOtaTypeBaseline).mockRejectedValueOnce(
    new HttpError('PRIVATE', 70028, { outcomeUnknown: true })
  )
  await submit()
  const next = vi.mocked(registerOtaTypeBaseline).mock.calls[2]!
  expect(next[1]).toBe(secondId)
  expect(next[2]).toEqual({ expectedRevision: '0' })
  expect(next[3]).not.toBe(original[3])
})
it('读取与登记在途拒绝双发', async () => {
  render()
  await flushPromises()
  await get('type').setValue(deviceTypeId)
  const read = deferred()
  vi.mocked(fetchOtaTypeBaseline).mockReturnValueOnce(read.promise)
  await get('check').trigger('click')
  await flushPromises()
  await get('check').trigger('click')
  expect(fetchOtaTypeBaseline).toHaveBeenCalledOnce()
  read.resolve(baselineSnapshot())
  await flushPromises()
  await confirm()
  const write = deferred()
  vi.mocked(registerOtaTypeBaseline).mockReturnValueOnce(write.promise)
  await get('submit').trigger('click')
  await flushPromises()
  await get('submit').trigger('click')
  expect(registerOtaTypeBaseline).toHaveBeenCalledOnce()
  write.resolve(baselineSnapshot(baseline(3), '3'))
  await flushPromises()
})
it.each(['tenant', 'project', 'user', 'epoch', 'roles', 'buttons', 'authorized'])(
  '%s身份改变清原key、公开快照、完成槽并拒绝晚响应',
  async (change) => {
    await prepare()
    const late = deferred()
    vi.mocked(registerOtaTypeBaseline).mockReturnValueOnce(late.promise)
    await submit()
    if (change === 'tenant') state.user.info.tenantId = '44444444-4444-4444-8444-444444444444'
    else if (change === 'project')
      state.user.info.currentProjectId = '44444444-4444-4444-8444-444444444444'
    else if (change === 'user') state.user.info.userId = 'other'
    else if (change === 'epoch') invalidateIdentity()
    else if (change === 'roles') state.user.info.roles = ['VIEWER']
    else if (change === 'buttons') state.user.info.buttons = []
    else await wrapper!.setProps({ authorized: false })
    await flushPromises()
    late.resolve(baselineSnapshot(baseline(3), '3'))
    await flushPromises()
    expect(wrapper!.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper!.text()).not.toContain(baselineSnapshot().baselineHash)
  }
)
it('身份切换后重开是新意图，不继承未知key或完成槽', async () => {
  vi.mocked(registerOtaTypeBaseline).mockRejectedValueOnce(
    new HttpError('PRIVATE', 500, { outcomeUnknown: true })
  )
  await prepare()
  await submit()
  const original = vi.mocked(registerOtaTypeBaseline).mock.calls[0]![3]
  state.user.info.userId = 'other'
  await flushPromises()
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  await get('type').setValue(deviceTypeId)
  await check()
  await confirm()
  await submit()
  expect(vi.mocked(registerOtaTypeBaseline).mock.calls[1]![3]).not.toBe(original)
})
it.each(['types', 'read', 'hash'])('关闭拒绝晚到%s，不恢复目录或公开快照', async (kind) => {
  const late = deferred()
  if (kind === 'types') vi.mocked(fetchDeviceTypePage).mockReturnValueOnce(late.promise)
  render()
  await flushPromises()
  if (kind !== 'types') {
    await get('type').setValue(deviceTypeId)
    if (kind === 'read') vi.mocked(fetchOtaTypeBaseline).mockReturnValueOnce(late.promise)
    else vi.mocked(sha256Hex).mockReturnValueOnce(late.promise)
    await get('check').trigger('click')
    await flushPromises()
  }
  await wrapper!.setProps({ modelValue: false })
  late.resolve(
    kind === 'types'
      ? page([publishedType()])
      : kind === 'read'
        ? baselineSnapshot()
        : baselineSnapshot().baselineHash
  )
  await flushPromises()
  expect(wrapper!.find('[role="dialog"]').exists()).toBe(false)
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  expect(wrapper!.find('[data-testid="ota-baseline-registration-current"]').exists()).toBe(false)
})
it.each([
  { revision: '0' },
  { baselineHash: 'b'.repeat(64) },
  { privateKey: 'PRIVATE' },
  { updatedAt: '2026-10-06T20:00:00.123400000Z' }
])('坏响应%s不能成为权威快照，成功响应坏则按未知保原key', async (change) => {
  await prepare()
  vi.mocked(registerOtaTypeBaseline).mockResolvedValue({
    ...baselineSnapshot(baseline(3), '3'),
    ...change
  } as any)
  await submit()
  expect(get('submit').text()).toBe('使用原键恢复')
  expect(wrapper!.text()).not.toContain('PRIVATE')
})
it('GET摘要不匹配立即清快照；long修订上限只能只读', async () => {
  vi.mocked(fetchOtaTypeBaseline).mockResolvedValue({
    ...baselineSnapshot(),
    baselineHash: 'b'.repeat(64)
  })
  render()
  await flushPromises()
  await get('type').setValue(deviceTypeId)
  await check()
  expect(wrapper!.find('[data-testid="ota-baseline-registration-current"]').exists()).toBe(false)
  vi.mocked(fetchOtaTypeBaseline).mockResolvedValue(
    baselineSnapshot(baseline(), '9223372036854775807')
  )
  await check()
  await confirm()
  expect(enabled()).toBe(false)
})
