import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Dialog from '@/views/ota/firmwares/OtaTrustImportDialog.vue'
import { fetchOtaTrustDomain, importOtaTrustBundle, sha256Hex } from '@/api/ota'
import { fetchProjects } from '@/api/project'
import { HttpError } from '@/utils/http/error'
import { invalidateIdentity } from '@/utils/http/identity-scope'
import { bytes, material, publicSha, snapshot } from './helpers/otaTrustPublicFixture'

const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/ota', () => ({
  fetchOtaTrustDomain: vi.fn(),
  importOtaTrustBundle: vi.fn(),
  sha256Hex: vi.fn()
}))
vi.mock('@/api/project', () => ({ fetchProjects: vi.fn() }))
const projectId = '11111111-1111-4111-8111-111111111111'
let wrapper: VueWrapper | undefined
const get = (name: string) => wrapper!.get(`[data-testid="ota-trust-import-${name}"]`)
const enabled = () => get('submit').attributes('disabled') === undefined
function deferred() {
  let resolve!: (value: any) => void, reject!: (error: unknown) => void
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
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot/></button>'
        },
        ElInput: {
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue'],
          template:
            '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)"/>'
        },
        ElCheckbox: {
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue'],
          template:
            '<label><input type="checkbox" :checked="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.checked)"/><slot/></label>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' },
        ElForm: { template: '<div><slot/></div>' },
        ElFormItem: { template: '<div><slot/></div>' }
      }
    }
  })
}
async function load(source: unknown = material(), filePromise?: Promise<ArrayBuffer>) {
  const input = get('file'),
    content = bytes(source)
  Object.defineProperty(input.element, 'files', {
    configurable: true,
    value: [
      { size: content.length, arrayBuffer: () => filePromise ?? Promise.resolve(content.buffer) }
    ]
  })
  await input.trigger('change')
  await flushPromises()
}
async function check() {
  await get('check').trigger('click')
  await flushPromises()
}
async function submit() {
  await get('submit').trigger('click')
  await flushPromises()
}
async function prepare(source = material()) {
  render()
  await get('domain').setValue('controlled.example')
  await load(source)
  await check()
  if (wrapper!.find('[data-testid="ota-trust-import-root-confirm"] input').exists())
    await get('root-confirm').get('input').setValue(true)
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
  vi.mocked(fetchProjects).mockResolvedValue([
    { id: projectId, status: 'ACTIVE', myRole: 'OWNER' }
  ] as any)
  vi.mocked(fetchOtaTrustDomain).mockRejectedValue(new HttpError('UNTRUSTED_PRIVATE_ERROR', 70013))
  vi.mocked(sha256Hex).mockImplementation(async (value) => publicSha(value))
  vi.mocked(importOtaTrustBundle).mockImplementation(async (_project, _domain, body) =>
    snapshot(body, String(BigInt(body.expectedRevision) + 1n))
  )
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it('进入不读不写；先载公开材料，再显式GET与根预配确认，才可发精确三字段意图', async () => {
  const local = vi.fn(),
    session = vi.fn()
  vi.stubGlobal('localStorage', { setItem: local })
  vi.stubGlobal('sessionStorage', { setItem: session })
  render()
  expect(fetchProjects).not.toHaveBeenCalled()
  expect(importOtaTrustBundle).not.toHaveBeenCalled()
  await get('domain').setValue('controlled.example')
  await load()
  expect(get('bundle-version').text()).toContain('1')
  expect(get('bundle-sha').text()).toContain(snapshot().bundleSha256)
  expect(enabled()).toBe(false)
  await check()
  expect(get('revision').text()).toContain('0')
  expect(enabled()).toBe(false)
  await get('root-confirm').get('input').setValue(true)
  expect(enabled()).toBe(true)
  await submit()
  expect(fetchProjects).toHaveBeenCalledTimes(2)
  expect(importOtaTrustBundle).toHaveBeenCalledExactlyOnceWith(
    projectId,
    'controlled.example',
    material(),
    expect.any(String)
  )
  expect(Object.isFrozen(vi.mocked(importOtaTrustBundle).mock.calls[0]![2])).toBe(true)
  expect(wrapper!.text()).toContain('不代表当前发布资格或设备已收到信任包')
  expect(wrapper!.html()).not.toContain(material().signature)
  expect(wrapper!.html()).not.toContain(material().bundle.keys[0]!.spki)
  expect(wrapper!.find('textarea').exists()).toBe(false)
  expect(local).not.toHaveBeenCalled()
  expect(session).not.toHaveBeenCalled()
})
it('完整包公开状态预览包括REVOKED影响，但不展示SPKI或签名原文', async () => {
  const source = material(),
    revokedBytes = Buffer.from('302a300506032b6570032100' + '02'.repeat(32), 'hex')
  source.bundle.keys.push({
    ...source.bundle.keys[0]!,
    keyVersion: 'revoked-key',
    spki: revokedBytes.toString('base64'),
    fingerprint: publicSha(revokedBytes),
    state: 'REVOKED'
  })
  render()
  await load(source)
  expect(get('keys').text()).toContain('revoked-key')
  expect(get('keys').text()).toContain('REVOKED')
  expect(get('keys').text()).toContain('1970-01-01T00:00:01.000Z')
  expect(wrapper!.text()).toContain('历史键必须保留')
  expect(wrapper!.text()).toContain('相关活动安全暂停')
  expect(wrapper!.html()).not.toContain(revokedBytes.toString('base64'))
  expect(wrapper!.html()).not.toContain(source.signature)
})
it.each([
  new HttpError('PRIVATE', 404),
  new HttpError('PRIVATE', 70013, { outcomeUnknown: true }),
  new Error('PRIVATE')
])('未确认70013的读失败不能当首次修订0', async (error) => {
  vi.mocked(fetchOtaTrustDomain).mockRejectedValue(error)
  await prepare()
  expect(wrapper!.find('[data-testid="ota-trust-import-revision"]').exists()).toBe(false)
  expect(enabled()).toBe(false)
  expect(wrapper!.text()).not.toContain('PRIVATE')
  expect(importOtaTrustBundle).not.toHaveBeenCalled()
})
it('完整材料修订或域不匹配时阻止导入，不自动改写材料', async () => {
  vi.mocked(fetchOtaTrustDomain).mockResolvedValue(snapshot(material('0'), '1') as any)
  await prepare(material('2', 2))
  expect(wrapper!.text()).toContain('材料期望修订与当前登记不同')
  expect(enabled()).toBe(false)
  await get('domain').setValue('another.example')
  await check()
  expect(wrapper!.text()).toContain('材料绑定域与所填域不同')
  expect(importOtaTrustBundle).not.toHaveBeenCalled()
})
it.each(['unknown', 'processing', 'root503'])(
  '%s保留精确原正文和键；公开GET推进不能更换恢复意图',
  async (kind) => {
    vi.mocked(importOtaTrustBundle).mockRejectedValueOnce(
      new HttpError(
        'UNTRUSTED_PRIVATE_ERROR',
        kind === 'processing' ? 10010 : kind === 'root503' ? 70010 : 500,
        { outcomeUnknown: kind !== 'processing' }
      )
    )
    await prepare()
    await submit()
    const original = vi.mocked(importOtaTrustBundle).mock.calls[0]!
    expect(get('submit').text()).toBe('使用原键恢复')
    expect(get('domain').attributes('disabled')).toBeDefined()
    expect(get('file').attributes('disabled')).toBeDefined()
    if (kind === 'root503') expect(wrapper!.text()).toContain('服务端受控根未配置或不可用')
    vi.mocked(fetchOtaTrustDomain).mockResolvedValue(snapshot() as any)
    await check()
    expect(enabled()).toBe(true)
    await submit()
    const repeated = vi.mocked(importOtaTrustBundle).mock.calls[1]!
    expect(repeated[2]).toBe(original[2])
    expect(repeated[3]).toBe(original[3])
    expect(repeated[2].expectedRevision).toBe('0')
    expect(wrapper!.text()).not.toContain('UNTRUSTED_PRIVATE_ERROR')
  }
)
it.each(['same', 'higher', 'different', 'missing', 'failed'])(
  '墓碑证明原请求完成；%s GET只观察当前快照且不能新键重提',
  async (kind) => {
    vi.mocked(importOtaTrustBundle).mockRejectedValueOnce(new HttpError('PRIVATE', 10014))
    await prepare()
    await submit()
    expect(wrapper!.text()).toContain('原请求已完成，原响应不能重放')
    expect(enabled()).toBe(false)
    if (kind === 'same') vi.mocked(fetchOtaTrustDomain).mockResolvedValue(snapshot() as any)
    else if (kind === 'higher')
      vi.mocked(fetchOtaTrustDomain).mockResolvedValue(snapshot(material('1', 2), '2') as any)
    else if (kind === 'different')
      vi.mocked(fetchOtaTrustDomain).mockResolvedValue({
        ...snapshot(),
        bundleSha256: 'b'.repeat(64)
      } as any)
    else if (kind === 'failed')
      vi.mocked(fetchOtaTrustDomain).mockRejectedValue(new HttpError('PRIVATE', 403))
    await check()
    expect(wrapper!.text()).toContain(kind === 'same' ? '当前公开包摘要与原材料一致' : '不能')
    if (kind !== 'same') expect(wrapper!.text()).toContain('原写失败')
    expect(enabled()).toBe(false)
    await submit()
    expect(importOtaTrustBundle).toHaveBeenCalledOnce()
  }
)
it.each(['success', 'completed'])(
  '%s重新加载同签包清除旧登记，并阻止当前版本旧包再次发新键',
  async (kind) => {
    if (kind === 'completed')
      vi.mocked(importOtaTrustBundle).mockRejectedValueOnce(new HttpError('PRIVATE', 10014))
    await prepare()
    await submit()
    await load()
    expect(wrapper!.find('[data-testid="ota-trust-import-revision"]').exists()).toBe(false)
    expect(enabled()).toBe(false)
    vi.mocked(fetchOtaTrustDomain).mockResolvedValue(snapshot() as any)
    await check()
    expect(wrapper!.text()).toContain('当前包版本已等于或高于材料版本')
    expect(enabled()).toBe(false)
    await submit()
    expect(importOtaTrustBundle).toHaveBeenCalledOnce()
  }
)
it('未知关闭重开只能新身份内重新读取，同包已提交时拒绝生成新键', async () => {
  vi.mocked(importOtaTrustBundle).mockRejectedValueOnce(
    new HttpError('PRIVATE', 500, { outcomeUnknown: true })
  )
  await prepare()
  await submit()
  await wrapper!
    .findAll('button')
    .find((button) => button.text() === '关闭')!
    .trigger('click')
  await flushPromises()
  expect(wrapper!.find('[role="dialog"]').exists()).toBe(false)
  await wrapper!.setProps({ modelValue: true })
  await get('domain').setValue('controlled.example')
  await load()
  vi.mocked(fetchOtaTrustDomain).mockResolvedValue(snapshot() as any)
  await check()
  expect(enabled()).toBe(false)
  expect(importOtaTrustBundle).toHaveBeenCalledOnce()
})
it.each(['success', 'completed'])(
  '%s同身份已知完成后，重新加载或关开再读70013都不能换新键',
  async (kind) => {
    if (kind === 'completed')
      vi.mocked(importOtaTrustBundle).mockRejectedValueOnce(new HttpError('PRIVATE', 10014))
    await prepare()
    await submit()
    await load()
    await check()
    await get('root-confirm').get('input').setValue(true)
    expect(get('completed-fence').text()).toContain('同版或更低材料不能新键重提')
    expect(enabled()).toBe(false)
    await wrapper!.setProps({ modelValue: false })
    await wrapper!.setProps({ modelValue: true })
    await get('domain').setValue('controlled.example')
    await load()
    await check()
    await get('root-confirm').get('input').setValue(true)
    expect(get('completed-fence').text()).toContain('不表示原写失败')
    expect(enabled()).toBe(false)
    await submit()
    expect(importOtaTrustBundle).toHaveBeenCalledOnce()
  }
)
it('新完整更高材料必须新GET匹配修订；公开完成记录按域只保留最高版本', async () => {
  await prepare()
  await submit()
  await load(material('1', 3))
  expect(enabled()).toBe(false)
  vi.mocked(fetchOtaTrustDomain).mockResolvedValue(snapshot() as any)
  await check()
  expect(enabled()).toBe(true)
  await submit()
  expect(importOtaTrustBundle).toHaveBeenCalledTimes(2)
  await load(material('0', 2))
  vi.mocked(fetchOtaTrustDomain).mockRejectedValue(new HttpError('PRIVATE', 70013))
  await check()
  await get('root-confirm').get('input').setValue(true)
  expect(get('completed-fence').text()).toContain('版本 3')
  expect(enabled()).toBe(false)
})
it('身份变化清公开完成记录，旧正文与键不继承到新身份', async () => {
  await prepare()
  await submit()
  const original = vi.mocked(importOtaTrustBundle).mock.calls[0]![3]
  state.user.info.userId = 'new-owner'
  await flushPromises()
  await wrapper!.setProps({ modelValue: true })
  await get('domain').setValue('controlled.example')
  await load()
  await check()
  await get('root-confirm').get('input').setValue(true)
  expect(wrapper!.find('[data-testid="ota-trust-import-completed-fence"]').exists()).toBe(false)
  expect(enabled()).toBe(true)
  await submit()
  expect(vi.mocked(importOtaTrustBundle).mock.calls[1]![3]).not.toBe(original)
})
it('公开完成记录最多64域，达到预算拒绝新域且已有域可导入更高完整包', async () => {
  render()
  for (let index = 0; index < 64; index++) {
    const source = material()
    source.bundle.trustDomain = `domain-${index}`
    await get('domain').setValue(source.bundle.trustDomain)
    await load(source)
    await check()
    await get('root-confirm').get('input').setValue(true)
    expect(enabled()).toBe(true)
    await submit()
  }
  const newDomain = material()
  newDomain.bundle.trustDomain = 'domain-64'
  await get('domain').setValue(newDomain.bundle.trustDomain)
  await load(newDomain)
  await check()
  await get('root-confirm').get('input').setValue(true)
  expect(wrapper!.text()).toContain('64域的完成记录内存预算')
  expect(enabled()).toBe(false)
  await submit()
  expect(importOtaTrustBundle).toHaveBeenCalledTimes(64)
  const existingDomain = material('1', 2)
  existingDomain.bundle.trustDomain = 'domain-0'
  const oldState = material()
  oldState.bundle.trustDomain = 'domain-0'
  await get('domain').setValue(existingDomain.bundle.trustDomain)
  await load(existingDomain)
  vi.mocked(fetchOtaTrustDomain).mockResolvedValue(snapshot(oldState) as any)
  await check()
  expect(enabled()).toBe(true)
  await submit()
  expect(importOtaTrustBundle).toHaveBeenCalledTimes(65)
})
it.each(['ARCHIVED', 'DELETING', 'DELETED', 'VIEWER'])(
  '%s真实项目读不满足OWNER/ADMIN ACTIVE，不发POST',
  async (value) => {
    vi.mocked(fetchProjects).mockResolvedValue([
      {
        id: projectId,
        status: value === 'VIEWER' ? 'ACTIVE' : value,
        myRole: value === 'VIEWER' ? value : 'OWNER'
      }
    ] as any)
    await prepare()
    expect(enabled()).toBe(false)
    expect(fetchOtaTrustDomain).not.toHaveBeenCalled()
  }
)
it('导入前重新读取项目；归档公共错误清当前登记且不泄露原错误', async () => {
  await prepare()
  vi.mocked(fetchProjects).mockResolvedValue([
    { id: projectId, status: 'ARCHIVED', myRole: 'OWNER' }
  ] as any)
  await submit()
  expect(importOtaTrustBundle).not.toHaveBeenCalled()
  vi.mocked(fetchProjects).mockResolvedValue([
    { id: projectId, status: 'ACTIVE', myRole: 'OWNER' }
  ] as any)
  await check()
  await get('root-confirm').get('input').setValue(true)
  vi.mocked(importOtaTrustBundle).mockRejectedValueOnce(new HttpError('PRIVATE', 50017))
  await submit()
  expect(wrapper!.text()).toContain('项目已非活动状态')
  expect(enabled()).toBe(false)
})
it.each([70011, 70012, 403])('确定拒绝%s清预读并要求再读；没有未知结果或自动重提', async (code) => {
  vi.mocked(importOtaTrustBundle).mockRejectedValueOnce(new HttpError('PRIVATE_SECRET', code))
  await prepare()
  await submit()
  expect(get('submit').text()).toBe('导入签包')
  expect(enabled()).toBe(false)
  expect(wrapper!.text()).not.toContain('PRIVATE_SECRET')
  expect(importOtaTrustBundle).toHaveBeenCalledOnce()
})
it('首次写前资格读取失败不误锁无原键未知；恢复前读取失败仍保留原键', async () => {
  await prepare()
  vi.mocked(fetchProjects).mockRejectedValueOnce(new Error('PRIVATE'))
  await submit()
  expect(get('submit').text()).toBe('导入签包')
  expect(importOtaTrustBundle).not.toHaveBeenCalled()
  vi.mocked(importOtaTrustBundle).mockRejectedValueOnce(
    new HttpError('PRIVATE', 500, { outcomeUnknown: true })
  )
  await submit()
  const original = vi.mocked(importOtaTrustBundle).mock.calls[0]!
  vi.mocked(fetchProjects).mockRejectedValueOnce(new Error('PRIVATE'))
  await submit()
  expect(get('submit').text()).toBe('使用原键恢复')
  expect(importOtaTrustBundle).toHaveBeenCalledOnce()
  await submit()
  expect(vi.mocked(importOtaTrustBundle).mock.calls[1]![3]).toBe(original[3])
})
it('读与导入在途都禁止双发', async () => {
  render()
  await get('domain').setValue('controlled.example')
  await load()
  const read = deferred()
  vi.mocked(fetchOtaTrustDomain).mockReturnValueOnce(read.promise)
  await get('check').trigger('click')
  await flushPromises()
  await get('check').trigger('click')
  expect(fetchOtaTrustDomain).toHaveBeenCalledOnce()
  read.reject(new HttpError('PRIVATE', 70013))
  await flushPromises()
  await get('root-confirm').get('input').setValue(true)
  const write = deferred()
  vi.mocked(importOtaTrustBundle).mockReturnValueOnce(write.promise)
  await get('submit').trigger('click')
  await flushPromises()
  await get('submit').trigger('click')
  expect(importOtaTrustBundle).toHaveBeenCalledOnce()
  write.resolve(snapshot())
  await flushPromises()
})
it.each(['project', 'tenant', 'user', 'epoch', 'roles', 'buttons', 'authorized', 'close'])(
  '%s变化立即清内存并拒绝晚到导入响应',
  async (change) => {
    await prepare()
    const write = deferred()
    vi.mocked(importOtaTrustBundle).mockReturnValueOnce(write.promise)
    await get('submit').trigger('click')
    await flushPromises()
    if (change === 'project')
      state.user.info.currentProjectId = '22222222-2222-4222-8222-222222222222'
    else if (change === 'tenant') state.user.info.tenantId = 'other'
    else if (change === 'user') state.user.info.userId = 'other'
    else if (change === 'epoch') invalidateIdentity()
    else if (change === 'roles') state.user.info.roles = ['VIEWER']
    else if (change === 'buttons') state.user.info.buttons = []
    else if (change === 'authorized') await wrapper!.setProps({ authorized: false })
    else await wrapper!.setProps({ modelValue: false })
    await flushPromises()
    write.resolve(snapshot())
    await flushPromises()
    expect(wrapper!.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper!.text()).not.toContain(snapshot().bundleSha256)
    expect(importOtaTrustBundle).toHaveBeenCalledOnce()
  }
)
it.each(['file', 'read'])('关闭拒绝晚到%s读取，不恢复材料或登记', async (kind) => {
  render()
  await get('domain').setValue('controlled.example')
  const late = deferred()
  if (kind === 'file') await load(material(), late.promise)
  else {
    await load()
    vi.mocked(fetchOtaTrustDomain).mockReturnValueOnce(late.promise)
    await get('check').trigger('click')
    await flushPromises()
  }
  await wrapper!.setProps({ modelValue: false })
  await wrapper!.setProps({ modelValue: true })
  late.resolve(kind === 'file' ? bytes(material()).buffer : snapshot())
  await flushPromises()
  expect(get('domain').element).toHaveProperty('value', '')
  expect(wrapper!.find('[data-testid="ota-trust-import-bundle-sha"]').exists()).toBe(false)
  expect(wrapper!.find('[data-testid="ota-trust-import-current"]').exists()).toBe(false)
})
it.each(['private', 'wrongFingerprint', 'duplicate', 'decimal', 'oversize'])(
  '非法公开材料%s只给固定失败文案，不展示原文',
  async (kind) => {
    render()
    await get('domain').setValue('controlled.example')
    const source = material() as any
    if (kind === 'private') source.privateKey = 'PRIVATE_SECRET'
    else if (kind === 'wrongFingerprint') source.bundle.keys[0].fingerprint = 'b'.repeat(64)
    const input =
      kind === 'duplicate'
        ? JSON.stringify(source).replace(
            '"expectedRevision":"0"',
            '"expectedRevision":"0","expectedRevision":"0"'
          )
        : kind === 'decimal'
          ? JSON.stringify(source).replace('"bundleVersion":1', '"bundleVersion":1e0')
          : kind === 'oversize'
            ? '{}'.padEnd(65537, ' ')
            : source
    await load(input)
    expect(wrapper!.text()).toContain('公开材料检查失败')
    expect(wrapper!.html()).not.toContain('PRIVATE_SECRET')
    expect(enabled()).toBe(false)
  }
)
it.each([{ revision: '2' }, { bundleSha256: 'b'.repeat(64) }, { privateKey: 'PRIVATE' }])(
  '成功响应仍须匹配原意图与闭集DTO，否则保持未知 %j',
  async (change) => {
    vi.mocked(importOtaTrustBundle).mockResolvedValue({ ...snapshot(), ...change } as any)
    await prepare()
    await submit()
    expect(get('submit').text()).toBe('使用原键恢复')
    expect(wrapper!.text()).toContain('响应无法确认')
    expect(wrapper!.text()).not.toContain('PRIVATE')
  }
)
