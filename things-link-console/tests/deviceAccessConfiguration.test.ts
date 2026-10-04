import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ read: vi.fn(), write: vi.fn() }))
vi.mock('@/api/device', () => ({
  fetchDeviceAccessConfiguration: api.read,
  fetchChangeDeviceAccessConfiguration: api.write
}))
vi.mock('@/utils/http/error', () => ({
  HttpError: class extends Error {
    constructor(
      message: string,
      public code: number
    ) {
      super(message)
    }
  }
}))
import { HttpError } from '@/utils/http/error'
import Configuration from '@/views/device/components/DeviceAccessConfiguration.vue'
const result = (version = '9007199254740993', canManage = true) => ({
  protocol: 'MQTT' as const,
  enabled: true,
  configVersion: version,
  credentialVersion: '9223372036854775807',
  configured: true,
  allowedProtocols: ['MQTT', 'HTTP', 'COAP', 'TCP'],
  canManage
})
interface State {
  current: ReturnType<typeof result> | undefined
  protocol: string
  enabled: boolean
  loading: boolean
  saving: boolean
  needsRefresh: boolean
  notice: string
  load(): Promise<void>
  save(): Promise<void>
}
const wrappers: ReturnType<typeof mount>[] = []
function setup() {
  const wrapper = mount(Configuration, {
    props: { projectId: 'p', deviceId: 'a' },
    global: {
      stubs: ['ElAlert', 'ElForm', 'ElFormItem', 'ElSelect', 'ElOption', 'ElSwitch', 'ElButton'],
      directives: { loading: {} }
    }
  })
  wrappers.push(wrapper)
  return { wrapper, state: (wrapper.vm.$ as unknown as { setupState: State }).setupState }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
beforeEach(() => {
  api.read.mockReset().mockResolvedValue(result())
  api.write.mockReset()
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
})
it('大整数版本原字符串提交，保存过程中重复点击不会重放', async () => {
  const { state } = setup()
  await flushPromises()
  const pending = deferred<ReturnType<typeof result>>()
  api.write.mockReturnValue(pending.promise)
  state.protocol = 'TCP'
  const saving = state.save()
  await state.save()
  expect(api.write).toHaveBeenCalledExactlyOnceWith('p', 'a', {
    protocol: 'TCP',
    enabled: true,
    expectedConfigVersion: '9007199254740993'
  })
  pending.resolve(result('9007199254740994'))
  await saving
  expect(state.current?.configVersion).toBe('9007199254740994')
  expect(state.saving).toBe(false)
})
it('冲突只重读当前配置，不将原草稿自动重放', async () => {
  const { state } = setup()
  await flushPromises()
  api.write.mockRejectedValue(new HttpError('conflict', 30065))
  api.read.mockResolvedValue(result('9007199254740995'))
  await state.save()
  expect(api.write).toHaveBeenCalledTimes(1)
  expect(state.current?.configVersion).toBe('9007199254740995')
  expect(state.notice).toContain('配置已被修改')
  expect(state.needsRefresh).toBe(false)
})
it('未知写结果且重读失败时禁止继续保存，显式成功重读才恢复', async () => {
  const { state } = setup()
  await flushPromises()
  api.write.mockRejectedValue(new Error('response lost'))
  api.read.mockRejectedValue(new Error('offline'))
  await state.save()
  await state.save()
  expect(api.write).toHaveBeenCalledTimes(1)
  expect(state.current).toBeUndefined()
  expect(state.needsRefresh).toBe(true)
  api.read.mockResolvedValue(result('9007199254740994'))
  await state.load()
  expect(state.needsRefresh).toBe(false)
})
it('只读资格在方法入口也阻止写，不只依赖按钮禁用', async () => {
  api.read.mockResolvedValue(result('0', false))
  const { state } = setup()
  await flushPromises()
  await state.save()
  expect(api.write).not.toHaveBeenCalled()
})
it('A到B到A拒绝旧GET，卸载后请求不能回填', async () => {
  const old = deferred<ReturnType<typeof result>>()
  api.read.mockReturnValueOnce(old.promise)
  const { state, wrapper } = setup()
  await wrapper.setProps({ deviceId: 'b' })
  await wrapper.setProps({ deviceId: 'a' })
  await flushPromises()
  old.resolve(result('3'))
  await flushPromises()
  expect(state.current?.configVersion).toBe('9007199254740993')
  const late = deferred<ReturnType<typeof result>>()
  api.read.mockReturnValueOnce(late.promise)
  const pending = state.load()
  wrapper.unmount()
  late.resolve(result('4'))
  await pending
  expect(state.current?.configVersion).toBe('9007199254740993')
})
it('保存返回前切换设备，不显示原设备成功提示或配置', async () => {
  const { state, wrapper } = setup()
  await flushPromises()
  const old = deferred<ReturnType<typeof result>>()
  api.write.mockReturnValueOnce(old.promise)
  const pending = state.save()
  await wrapper.setProps({ deviceId: 'b' })
  await flushPromises()
  old.resolve(result('4'))
  await pending
  expect(state.current?.configVersion).toBe('9007199254740993')
  expect(state.notice).toBe('')
  expect(state.saving).toBe(false)
})
