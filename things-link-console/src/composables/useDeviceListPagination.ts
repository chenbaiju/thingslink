import { onScopeDispose, ref, watch, type Ref } from 'vue'
import { fetchSearchDevices, type DeviceResponse, type DeviceSearchQuery } from '@/api/device'

/** 用后端游标按页替换设备列表；页码只表示当前查询已经经过的页，不推算总数。 */
export function useDeviceListPagination(
  projectId: Ref<string>,
  query: () => DeviceSearchQuery,
  scopeKey: () => string
) {
  const items = ref<DeviceResponse[]>([])
  const pageSize = ref(20)
  const currentPage = ref(1)
  const hasNext = ref(false)
  const loading = ref(false)
  const error = ref('')
  const revision = ref(0)
  let generation = 0
  let cursors: Array<string | undefined> = [undefined]
  let activeQuery: DeviceSearchQuery = {}
  let retryPage = 1

  const clear = () => {
    generation++
    cursors = [undefined]
    items.value = []
    currentPage.value = 1
    hasNext.value = false
    error.value = ''
    loading.value = false
    retryPage = 1
  }

  const read = async (target: number) => {
    if (!projectId.value) return
    retryPage = target
    const request = ++generation
    const project = projectId.value
    const scope = scopeKey()
    const current = () =>
      request === generation && project === projectId.value && scope === scopeKey()
    loading.value = true
    error.value = ''
    try {
      const page = await fetchSearchDevices(project, {
        ...activeQuery,
        cursor: cursors[target - 1],
        limit: pageSize.value
      })
      if (!current()) return
      if (page.hasMore && (!page.nextCursor || cursors.slice(0, target).includes(page.nextCursor)))
        throw new Error('分页游标无效')
      items.value = page.items ?? []
      currentPage.value = target
      hasNext.value = Boolean(page.hasMore && page.nextCursor)
      cursors = cursors.slice(0, target)
      if (hasNext.value) cursors.push(page.nextCursor!)
      revision.value++
    } catch {
      if (current()) error.value = '设备列表查询失败，请重试。'
    } finally {
      if (current()) loading.value = false
    }
  }

  const search = async (size = pageSize.value) => {
    clear()
    pageSize.value = size
    const filters = query()
    activeQuery = {
      ...filters,
      deviceTypeIds: filters.deviceTypeIds ? [...filters.deviceTypeIds] : undefined,
      statuses: filters.statuses ? [...filters.statuses] : undefined
    }
    await read(1)
  }

  const goToPage = async (target: number) => {
    if (loading.value || target < 1 || target > cursors.length) return
    await read(target)
  }

  watch(
    scopeKey,
    () => {
      clear()
      if (projectId.value) void search()
    },
    { flush: 'sync' }
  )
  onScopeDispose(clear)

  const retry = () => goToPage(retryPage)
  return {
    items,
    pageSize,
    currentPage,
    hasNext,
    loading,
    error,
    revision,
    search,
    goToPage,
    retry
  }
}
