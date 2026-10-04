import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ read: vi.fn(), write: vi.fn() }))
vi.mock('@/api/device', () => ({
  fetchDeviceLocationPoint: api.read,
  fetchUpdateDeviceLocationPoint: api.write
}))
import Point from '@/views/device/components/DeviceLocationPoint.vue'
import { HttpError } from '@/utils/http/error'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const result = (version = '9007199254740993') => ({ longitude: 121.4, latitude: 31.2, version })
interface State {
  current?: ReturnType<typeof result>
  longitude: string
  latitude: string
  notice: string
  needsRefresh: boolean
  saving: boolean
  load(): Promise<void>
  save(): Promise<void>
}
const wrappers: ReturnType<typeof mount>[] = []
function setup(editable = true) {
  const wrapper = mount(Point, {
    props: { projectId: 'p', deviceId: 'a', identityKey: 'owner', editable },
    global: {
      stubs: ['ElAlert', 'ElForm', 'ElFormItem', 'ElInput', 'ElButton'],
      directives: { loading: {} }
    }
  })
  wrappers.push(wrapper)
  return { wrapper, state: (wrapper.vm.$ as unknown as { setupState: State }).setupState }
}
function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
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
it('精确版本字符串和经纬度顺序保持，重复点击只有一次PUT', async () => {
  const { state } = setup()
  await flushPromises()
  const pending = deferred<ReturnType<typeof result>>()
  api.write.mockReturnValue(pending.promise)
  state.longitude = '120'
  state.latitude = '30'
  const saving = state.save()
  await state.save()
  expect(api.write).toHaveBeenCalledExactlyOnceWith('p', 'a', {
    longitude: 120,
    latitude: 30,
    version: '9007199254740993'
  })
  pending.resolve(result('9007199254740994'))
  await saving
  expect(state.current?.version).toBe('9007199254740994')
})
it('空对清除，不把零坐标或单项空值误判为清除', async () => {
  const { state } = setup()
  await flushPromises()
  state.longitude = ''
  state.latitude = '0'
  await state.save()
  expect(api.write).not.toHaveBeenCalled()
  state.longitude = '0'
  api.write.mockResolvedValue(result())
  await state.save()
  expect(api.write).toHaveBeenLastCalledWith('p', 'a', {
    longitude: 0,
    latitude: 0,
    version: '9007199254740993'
  })
  state.longitude = state.latitude = ''
  await state.save()
  expect(api.write).toHaveBeenLastCalledWith('p', 'a', {
    longitude: null,
    latitude: null,
    version: '9007199254740993'
  })
})
it('范围、NaN与无穷输入不发送', async () => {
  const { state } = setup()
  await flushPromises()
  for (const value of ['181', '-181', 'Infinity', 'bad']) {
    state.longitude = value
    await state.save()
  }
  expect(api.write).not.toHaveBeenCalled()
})
it('冲突保留草稿并阻止重放，显式重读才恢复', async () => {
  const { state } = setup()
  await flushPromises()
  state.longitude = '100'
  api.write.mockRejectedValue(new HttpError('conflict', 30069))
  await state.save()
  await state.save()
  expect(state.longitude).toBe('100')
  expect(state.notice).toContain('草稿已保留')
  expect(state.needsRefresh).toBe(true)
  expect(api.write).toHaveBeenCalledTimes(1)
  expect(api.read).toHaveBeenCalledTimes(1)
  api.read.mockResolvedValue(result('9007199254740995'))
  await state.load()
  expect(state.current?.version).toBe('9007199254740995')
  expect(state.needsRefresh).toBe(false)
})
it('未知写结果和重读失败不能继续提交，只读角色也不能绕按钮', async () => {
  const { state } = setup()
  await flushPromises()
  api.write.mockRejectedValue(new Error('network'))
  await state.save()
  api.read.mockRejectedValue(new Error('offline'))
  await state.load()
  await state.save()
  expect(api.write).toHaveBeenCalledTimes(1)
  expect(state.current).toBeUndefined()
  api.read.mockResolvedValue(result())
  const readonly = setup(false)
  await flushPromises()
  await readonly.state.save()
  expect(api.write).toHaveBeenCalledTimes(1)
})
it('设备A-B-A和卸载拒绝迟到响应', async () => {
  const old = deferred<ReturnType<typeof result>>()
  api.read.mockReturnValueOnce(old.promise)
  const { state, wrapper } = setup()
  await wrapper.setProps({ deviceId: 'b' })
  await wrapper.setProps({ deviceId: 'a' })
  await flushPromises()
  old.resolve(result('3'))
  await flushPromises()
  expect(state.current?.version).toBe('9007199254740993')
  const late = deferred<ReturnType<typeof result>>()
  api.read.mockReturnValueOnce(late.promise)
  const loading = state.load()
  wrapper.unmount()
  late.resolve(result('4'))
  await loading
  expect(state.current?.version).toBe('9007199254740993')
})
it('账号或身份代次改变隔离响应，并清除旧草稿', async () => {
  const { state, wrapper } = setup()
  await flushPromises()
  state.longitude = '100'
  const old = deferred<ReturnType<typeof result>>()
  api.write.mockReturnValueOnce(old.promise)
  const saving = state.save()
  invalidateIdentity()
  await wrapper.setProps({ identityKey: '' })
  old.resolve(result('4'))
  await saving
  expect(state.current).toBeUndefined()
  expect(state.longitude).toBe('')
  expect(state.notice).toBe('')
})
