import { fetchProjectRegions, type ProjectRegionResponse } from '@/api/region'

/** 前端使用的区域目录项。OpenAPI 生成类型偏保守，这里在 API 边界做一次归一化。 */
type ProjectRegionCatalogItem = {
  areaCode: string
  areaName: string
  code: string
  displayOrder: number
  enabled: boolean
  name: string
  projectCreationEnabled: boolean
}

/** 区域目录缓存。它是平台目录，单次会话内没有必要每个组件重复请求。 */
const regions = ref<ProjectRegionCatalogItem[]>([])

/** 正在进行中的请求。多个组件同时挂载时复用它，避免重复打接口。 */
let loadingTask: Promise<void> | undefined

/**
 * 项目区域目录。
 *
 * 后端返回的是官方控制台同款两级模型：地域 + 可用区。项目创建提交的是可用区编码。
 */
export function useProjectRegionCatalog() {
  const loading = ref(false)

  /** 只允许创建项目的区域出现在默认值候选里。禁用区域仍可展示，但不能被提交。 */
  const creatableRegions = computed(() =>
    regions.value.filter((region) => region.enabled && region.projectCreationEnabled)
  )

  /** 保留 sh-1 作为本项目默认区域；若配置里移除它，再退回后端排序的第一个可创建区域。 */
  const defaultProjectRegion = computed(
    () =>
      creatableRegions.value.find((region) => region.code === 'sh-1')?.code ??
      creatableRegions.value[0]?.code ??
      'sh-1'
  )

  /** 按地域分组，供 Element Plus 的 OptionGroup 直接消费。 */
  const regionGroups = computed(() => {
    const groups = new Map<
      string,
      {
        areaCode: string
        areaName: string
        regions: ProjectRegionCatalogItem[]
      }
    >()
    for (const region of regions.value) {
      const group = groups.get(region.areaCode) ?? {
        areaCode: region.areaCode,
        areaName: region.areaName,
        regions: []
      }
      group.regions.push(region)
      groups.set(region.areaCode, group)
    }
    return [...groups.values()]
  })

  /** 把项目保存的区域编码翻译成目录展示名。目录请求失败时退回编码，列表仍可用。 */
  const regionName = (code?: string) =>
    regions.value.find((region) => region.code === code)?.name ?? code ?? '-'

  /**
   * 归一化后端区域。
   *
   * OpenAPI 生成物把字段标成可选，是生成器的保守表达；运行时如果缺少 code/name，
   * 这条目录无法用于展示和提交，前端直接丢弃，避免把 undefined 透传到组件。
   */
  const normalizeRegions = (data: ProjectRegionResponse[]) =>
    data
      .filter((region) => region.code && region.name && region.areaCode && region.areaName)
      .map((region) => ({
        areaCode: region.areaCode!,
        areaName: region.areaName!,
        code: region.code!,
        displayOrder: region.displayOrder ?? 0,
        enabled: region.enabled ?? false,
        name: region.name!,
        projectCreationEnabled: region.projectCreationEnabled ?? false
      }))

  /** 加载区域目录。 */
  const loadProjectRegions = async () => {
    if (regions.value.length) return
    loading.value = true
    try {
      loadingTask ??= fetchProjectRegions().then((data) => {
        regions.value = normalizeRegions(data)
      })
      await loadingTask
    } finally {
      loadingTask = undefined
      loading.value = false
    }
  }

  return {
    defaultProjectRegion,
    loading,
    loadProjectRegions,
    regionGroups,
    regionName,
    regions
  }
}
