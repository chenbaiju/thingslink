import { effectScope, nextTick, ref } from 'vue'
import { invalidateIdentity } from '@/utils/http/identity-scope'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { fetchSearchDevices, fetchDeviceTypePage, fetchDeviceDetail } = vi.hoisted(() => ({
  fetchSearchDevices: vi.fn(),
  fetchDeviceTypePage: vi.fn(),
  fetchDeviceDetail: vi.fn()
}))

vi.mock('@/api/device', () => ({
  fetchSearchDevices,
  fetchDeviceTypePage,
  fetchDeviceDetail
}))

import { usePagedDeviceCatalog } from '@/composables/usePagedDeviceCatalog'

/** G1-C3c：选择器只按需翻页，不在启动或一次搜索后循环抽干高基数列表。 */
describe('设备与类型按需目录', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('设备类型只加载首屏，滚动到底才请求下一游标', async () => {
    fetchDeviceTypePage
      .mockResolvedValueOnce({
        items: [{ id: 'type-1', name: '类型一' }],
        nextCursor: 'next',
        hasMore: true
      })
      .mockResolvedValueOnce({
        items: [{ id: 'type-2', name: '类型二' }],
        nextCursor: null,
        hasMore: false
      })
    const catalog = usePagedDeviceCatalog(ref('project-1'))

    await catalog.loadDeviceTypes()
    expect(fetchDeviceTypePage).toHaveBeenCalledTimes(1)
    expect(fetchDeviceTypePage).toHaveBeenLastCalledWith('project-1', undefined)
    expect(catalog.deviceTypes.value.map((item) => item.id)).toEqual(['type-1'])

    catalog.onDeviceTypePopupScroll({
      target: { scrollHeight: 100, scrollTop: 60, clientHeight: 40 }
    } as unknown as Event)
    await vi.waitFor(() => expect(fetchDeviceTypePage).toHaveBeenCalledTimes(2))
    expect(fetchDeviceTypePage).toHaveBeenLastCalledWith('project-1', 'next')
    expect(catalog.deviceTypes.value.map((item) => item.id)).toEqual(['type-1', 'type-2'])
    await catalog.loadDeviceTypes(true)
    expect(fetchDeviceTypePage).toHaveBeenCalledTimes(2)
  })

  it('设备远程搜索只取 50 条首屏，缺失选中项才按 ID 补详情', async () => {
    fetchSearchDevices.mockResolvedValue({
      items: [{ id: 'device-1', name: '温度传感器' }],
      nextCursor: 'device-next',
      hasMore: true
    })
    fetchDeviceDetail.mockResolvedValue({ id: 'device-2', name: '既有设备' })
    const catalog = usePagedDeviceCatalog(ref('project-1'))

    await catalog.searchDevices(' 温度 ')
    expect(fetchSearchDevices).toHaveBeenCalledOnce()
    expect(fetchSearchDevices).toHaveBeenCalledWith('project-1', {
      keyword: '温度',
      cursor: undefined,
      limit: 50
    })
    expect(fetchDeviceDetail).not.toHaveBeenCalled()

    await catalog.ensureDevices(['device-1', 'device-2', 'device-2'])
    expect(fetchDeviceDetail).toHaveBeenCalledOnce()
    expect(fetchDeviceDetail).toHaveBeenCalledWith('project-1', 'device-2')
    expect(catalog.devices.value.map((item) => item.id)).toEqual(['device-1', 'device-2'])
  })

  it('项目切换清空游标和旧选项，不携带跨项目对象', async () => {
    fetchDeviceTypePage.mockResolvedValue({
      items: [{ id: 'type-1', name: '类型一' }],
      nextCursor: 'next',
      hasMore: true
    })
    const projectId = ref('project-1')
    const catalog = usePagedDeviceCatalog(projectId)
    await catalog.loadDeviceTypes()

    projectId.value = 'project-2'
    await nextTick()
    expect(catalog.deviceTypes.value).toEqual([])
    expect(catalog.typeHasMore.value).toBe(false)
  })
})

it.each(['search', 'detail', 'types'])(
  '%s 的旧项目迟响应不回填选项，当前项目可立即查询',
  async (kind) => {
    let resolve!: (value: any) => void
    const delayed = new Promise((done) => {
      resolve = done
    })
    const project = ref('old-project')
    const scope = effectScope()
    const catalog = scope.run(() => usePagedDeviceCatalog(project))!
    const api =
      kind === 'search'
        ? fetchSearchDevices
        : kind === 'detail'
          ? fetchDeviceDetail
          : fetchDeviceTypePage
    api.mockReturnValueOnce(delayed)
    api.mockResolvedValue(kind === 'detail' ? { id: 'new' } : { items: [{ id: 'new' }] })
    const invoke = () =>
      kind === 'search'
        ? catalog.loadDevices()
        : kind === 'detail'
          ? catalog.ensureDevices(['device'])
          : catalog.loadDeviceTypes()
    const oldRequest = invoke()
    project.value = 'new-project'
    await invoke()
    resolve({ items: [{ id: 'private-old' }], id: 'private-old', hasMore: true, nextCursor: 'old' })
    await oldRequest
    expect(kind === 'types' ? catalog.deviceTypes.value : catalog.devices.value).toEqual([
      { id: 'new' }
    ])
    expect(catalog.devicesLoading.value).toBe(false)
    expect(catalog.deviceTypesLoading.value).toBe(false)
    scope.stop()
  }
)
it('同项目身份失效及销毁都阻止在途设备回填', async () => {
  for (const reason of ['identity', 'dispose']) {
    let resolve!: (value: any) => void
    fetchSearchDevices.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      })
    )
    const scope = effectScope()
    const catalog = scope.run(() => usePagedDeviceCatalog(ref('same-project')))!
    const pending = catalog.loadDevices()
    if (reason === 'identity') invalidateIdentity()
    else scope.stop()
    resolve({ items: [{ id: 'old-session-device' }] })
    await pending
    expect(catalog.devices.value).toEqual([])
    scope.stop()
  }
})
