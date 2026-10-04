import { nextTick, ref } from 'vue'
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
