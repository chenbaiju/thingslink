import {
  fetchDeviceTypePage,
  fetchDeviceDetail,
  fetchSearchDevices,
  type DeviceResponse,
  type DeviceSearchQuery,
  type DeviceTypeResponse
} from '@/api/device'
import type { Ref } from 'vue'

/** 下拉滚动距离底部多少像素时按需读取下一页。 */
const LOAD_MORE_THRESHOLD = 48

/** 以 ID 合并按需取得的选项，避免远程搜索覆盖已经选中的显示名称。 */
function mergeById<T extends { id?: string }>(current: T[], incoming: T[]) {
  const merged = new Map(current.filter((item) => item.id).map((item) => [item.id as string, item]))
  incoming.forEach((item) => {
    if (item.id) merged.set(item.id, item)
  })
  return [...merged.values()]
}

/** 判断 Element Plus 下拉滚动是否已接近底部。 */
function nearBottom(event: Event) {
  const target = event.target as HTMLElement
  return target.scrollHeight - target.scrollTop - target.clientHeight <= LOAD_MORE_THRESHOLD
}

/**
 * 提供设备远程搜索与设备类型键集翻页，不在页面启动时循环抽干全部项目数据。
 *
 * 设备支持后端关键词白名单，因此每次远程输入只取首个 50 条匹配页；设备类型尚未冻结关键词
 * 查询，按弹层滚动逐页加载。两者都保留已经出现过的选项，保证编辑已有对象时标签不退化成 UUID。
 */
export function usePagedDeviceCatalog(projectId: Ref<string>) {
  const devices = ref<DeviceResponse[]>([])
  const deviceTypes = ref<DeviceTypeResponse[]>([])
  const devicesLoading = ref(false)
  const deviceTypesLoading = ref(false)
  const deviceCursor = ref<string>()
  const deviceHasMore = ref(false)
  const deviceKeyword = ref('')
  const typeCursor = ref<string>()
  const typeHasMore = ref(false)

  const loadDevices = async (query: DeviceSearchQuery = {}, append = false) => {
    if (!projectId.value || devicesLoading.value) return
    devicesLoading.value = true
    try {
      const keyword = query.keyword?.trim() ?? ''
      if (!append) {
        deviceKeyword.value = keyword
        deviceCursor.value = undefined
      }
      const page = await fetchSearchDevices(projectId.value, {
        ...query,
        keyword: keyword || undefined,
        cursor: append ? deviceCursor.value : undefined,
        limit: 50
      })
      devices.value = mergeById(devices.value, page.items ?? [])
      deviceCursor.value = page.nextCursor ?? undefined
      deviceHasMore.value = page.hasMore ?? false
    } finally {
      devicesLoading.value = false
    }
  }

  const searchDevices = (keyword: string) => loadDevices({ keyword })
  const ensureDevices = async (ids: Array<string | undefined>) => {
    if (!projectId.value) return
    const known = new Set(devices.value.map((item) => item.id))
    const missing = [...new Set(ids.filter((id): id is string => Boolean(id && !known.has(id))))]
    const loaded = await Promise.allSettled(
      missing.map((id) => fetchDeviceDetail(projectId.value, id))
    )
    devices.value = mergeById(
      devices.value,
      loaded
        .filter(
          (result): result is PromiseFulfilledResult<DeviceResponse> =>
            result.status === 'fulfilled'
        )
        .map((result) => result.value)
    )
  }
  const loadMoreDevices = () =>
    deviceHasMore.value ? loadDevices({ keyword: deviceKeyword.value }, true) : Promise.resolve()
  const onDevicePopupScroll = (event: Event) => {
    if (nearBottom(event)) void loadMoreDevices()
  }

  const loadDeviceTypes = async (append = false) => {
    if (!projectId.value || deviceTypesLoading.value || (append && !typeHasMore.value)) return
    deviceTypesLoading.value = true
    try {
      if (!append) typeCursor.value = undefined
      const page = await fetchDeviceTypePage(projectId.value, append ? typeCursor.value : undefined)
      deviceTypes.value = mergeById(deviceTypes.value, page.items ?? [])
      typeCursor.value = page.nextCursor ?? undefined
      typeHasMore.value = page.hasMore ?? false
    } finally {
      deviceTypesLoading.value = false
    }
  }

  const onDeviceTypePopupScroll = (event: Event) => {
    if (nearBottom(event) && typeHasMore.value) void loadDeviceTypes(true)
  }

  watch(projectId, () => {
    devices.value = []
    deviceTypes.value = []
    deviceCursor.value = undefined
    typeCursor.value = undefined
    deviceHasMore.value = false
    typeHasMore.value = false
  })

  return {
    devices,
    deviceTypes,
    devicesLoading,
    deviceTypesLoading,
    deviceHasMore,
    typeHasMore,
    loadDevices,
    searchDevices,
    ensureDevices,
    loadMoreDevices,
    onDevicePopupScroll,
    loadDeviceTypes,
    onDeviceTypePopupScroll
  }
}
