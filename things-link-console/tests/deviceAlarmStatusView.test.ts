import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import DeviceAlarmStatus from '@/views/device/components/DeviceAlarmStatus.vue'

const f = vi.hoisted(() => ({ request: vi.fn(), epoch: 1 }))
vi.mock('@/api/device-alarm-status', () => ({ requestDeviceAlarmStatus: f.request }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => f.epoch }))
let wrapper: ReturnType<typeof shallowMount> | undefined
beforeEach(() => {
  f.epoch = 1
  f.request.mockReset()
  vi.stubGlobal('IntersectionObserver', undefined)
})
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
})
const mountRow = () =>
  shallowMount(DeviceAlarmStatus, {
    props: { projectId: 'project', deviceId: 'device', active: true },
    global: { stubs: { ArtSvgIcon: true, ElButton: { template: '<button><slot/></button>' } } }
  })
describe('device alarm status presentation', () => {
  it('shows explicit failure, retries and displays observation time with the new state', async () => {
    f.request
      .mockReturnValueOnce({ result: Promise.reject(new Error('unavailable')), cancel: vi.fn() })
      .mockReturnValueOnce({
        result: Promise.resolve({ state: 'ACTIVE', observedAt: '2026-09-30T12:00:00Z' }),
        cancel: vi.fn()
      })
    wrapper = mountRow()
    await flushPromises()
    expect(wrapper.text()).toContain('状态不可用')
    expect(wrapper.text()).not.toContain('无告警')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('有告警')
    expect(wrapper.attributes('title')).toContain('2026-09-30T12:00:00Z')
    expect(f.request).toHaveBeenCalledTimes(2)
  })
  it('cancels hidden rows and refuses late successful responses', async () => {
    let resolve!: (value: unknown) => void
    const cancel = vi.fn()
    f.request.mockReturnValue({
      result: new Promise((r) => {
        resolve = r
      }),
      cancel
    })
    wrapper = mountRow()
    await flushPromises()
    expect(wrapper.text()).toContain('查询中')
    await wrapper.setProps({ active: false })
    resolve({ state: 'NORMAL', observedAt: '2026-09-30T12:00:00Z' })
    await flushPromises()
    expect(cancel).toHaveBeenCalled()
    expect(wrapper.text()).toContain('待查询')
    expect(wrapper.text()).not.toContain('无告警')
    expect(wrapper.attributes('title')).toBeUndefined()
  })
})
