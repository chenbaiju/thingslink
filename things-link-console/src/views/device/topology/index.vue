<template>
  <div class="console-page topology console-page--single-panel">
    <ConsoleWorkspaceHeader
      title="网关与拓扑"
      description="查看网关、子设备及当前绑定关系。"
      :links="[{ label: '设备与接入', path: '/device/list', permission: 'device:read' }]"
    />
    <div class="topology__header console-toolbar console-page-actions">
      <ElButton v-if="hasAuth('device:update')" type="primary" :icon="Plus" @click="openBind">
        绑定子设备
      </ElButton>
    </div>

    <ElCard class="console-table-panel console-page__main-panel" shadow="never">
      <ElTable
        v-loading="loading"
        :data="treeData"
        row-key="id"
        default-expand-all
        :tree-props="{ children: 'children' }"
        class="topology__table"
      >
        <ElTableColumn label="设备名称" prop="label" min-width="175" show-overflow-tooltip />
        <ElTableColumn
          :label="compactTable ? '设备标识 / 绑定时间' : '设备标识'"
          min-width="155"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <div class="topology__device-key">{{ row.deviceKey || '—' }}</div>
            <div v-if="compactTable && row.isSub" class="topology__binding-time">
              {{ formatTime(row.boundAt) }}
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="设备角色" width="85">
          <template #default="{ row }">
            <ElTag :type="row.isSub ? 'info' : 'primary'" effect="plain" size="small">
              {{ row.isSub ? '子设备' : '网关' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="在线状态" width="85">
          <template #default="{ row }">
            <ElTag :type="statusTagType(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="子设备数" width="85" align="center">
          <template #default="{ row }">{{
            row.isSub ? '—' : (row.children?.length ?? 0)
          }}</template>
        </ElTableColumn>
        <ElTableColumn v-if="!compactTable" label="绑定时间" width="180">
          <template #default="{ row }">{{ formatTime(row.boundAt) }}</template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          width="88"
          fixed="right"
          class-name="console-table-actions-cell"
        >
          <template #default="{ row }">
            <ConsoleTableAction label="设备详情" icon="ri:eye-line" @click="openDetail(row)" />
            <ConsoleTableAction
              v-if="row.isSub && hasAuth('device:update')"
              label="解绑子设备"
              icon="ri:link-unlink-m"
              type="danger"
              @click="unbind(row)"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有网关设备" /></template>
      </ElTable>
    </ElCard>

    <ElDialog class="console-dialog" v-model="bindVisible" title="绑定子设备" width="520px">
      <ElForm label-position="top">
        <ElFormItem label="网关">
          <ElSelect
            v-model="bindForm.gatewayId"
            class="form-control"
            filterable
            remote
            :remote-method="searchDevices"
            :loading="devicesLoading"
            placeholder="输入名称或标识搜索网关"
            @popup-scroll="onCatalogPopupScroll"
          >
            <ElOption v-for="g in gateways" :key="g.id" :label="g.name" :value="g.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="子设备">
          <ElSelect
            v-model="bindForm.subDeviceId"
            class="form-control"
            placeholder="选择未绑定的子设备"
            filterable
            remote
            :remote-method="searchDevices"
            :loading="devicesLoading"
            @popup-scroll="onCatalogPopupScroll"
          >
            <ElOption v-for="s in unboundSubDevices" :key="s.id" :label="s.name" :value="s.id" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="bindVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitBind">确定</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import { Plus } from '@element-plus/icons-vue'
  import { useWindowSize } from '@vueuse/core'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'
  import { formatTime } from '@/utils/time'
  import type { DeviceResponse } from '@/api/device'
  import { usePagedDeviceCatalog } from '@/composables/usePagedDeviceCatalog'
  import {
    fetchDeviceTopologies,
    fetchBindTopology,
    fetchUnbindTopology,
    type DeviceTopologyResponse
  } from '@/api/device-topology'
  import { useUserStore } from '@/store/modules/user'
  import { useAuth } from '@/hooks/core/useAuth'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'DeviceTopology' })

  /** 拓扑树节点：网关为根节点，子设备为叶子节点。 */
  interface TopologyNode {
    id: string
    label: string
    deviceKey?: string
    status: string
    boundAt?: string
    isSub: boolean
    children?: TopologyNode[]
  }

  const userStore = useUserStore()
  const router = useRouter()
  const { width } = useWindowSize()
  const compactTable = computed(() => width.value < 1100)
  const { hasAuth } = useAuth()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')

  const loading = ref(false),
    submitting = ref(false),
    bindVisible = ref(false)
  const {
    devices,
    deviceTypes,
    devicesLoading,
    loadDevices,
    searchDevices,
    ensureDevices,
    onDevicePopupScroll,
    loadDeviceTypes,
    typeHasMore
  } = usePagedDeviceCatalog(projectId)
  const treeData = ref<TopologyNode[]>([])
  const bindForm = reactive({ gatewayId: '', subDeviceId: '' })

  const statusLabels: Record<string, string> = {
    INACTIVE: '未激活',
    ONLINE: '在线',
    OFFLINE: '离线',
    UNKNOWN: '未知'
  }
  const statusTags: Record<string, 'info' | 'success' | 'danger'> = {
    INACTIVE: 'info',
    ONLINE: 'success',
    OFFLINE: 'danger'
  }
  const statusLabel = (s: string) => statusLabels[s] ?? s
  const statusTagType = (s: string) => statusTags[s] ?? 'info'
  const openDetail = (node: TopologyNode) =>
    router.push({ path: '/device/list', query: { deviceId: node.id } })

  /** 设备类型 kind → 设备集合。kind 只挂在设备类型上，设备列表不含它，需 join。 */
  const kindByTypeId = computed(() => {
    const map: Record<string, string> = {}
    for (const type of deviceTypes.value) {
      if (type.id && type.deviceKind) map[type.id] = type.deviceKind
    }
    return map
  })

  /** 已确认 id 存在的设备；ElSelect/ElOption 的 value 不接受 undefined。 */
  type IdentifiedDevice = DeviceResponse & { id: string }

  const gateways = computed(() =>
    devices.value.filter(
      (device): device is IdentifiedDevice =>
        Boolean(device.id) && kindByTypeId.value[device.deviceTypeId ?? ''] === 'GATEWAY'
    )
  )
  const subDevices = computed(() =>
    devices.value.filter(
      (device): device is IdentifiedDevice =>
        Boolean(device.id) && kindByTypeId.value[device.deviceTypeId ?? ''] === 'SUB_DEVICE'
    )
  )
  /** 尚未绑定到任何网关的子设备（gatewayId 为空），作为绑定对话框的可选集合。 */
  const unboundSubDevices = computed(() => subDevices.value.filter((device) => !device.gatewayId))

  const load = async () => {
    if (!projectId.value) return
    loading.value = true
    try {
      const [, , topologies] = await Promise.all([
        loadDevices(),
        loadDeviceTypes(),
        fetchDeviceTopologies(projectId.value)
      ])
      await ensureDevices(topologies.flatMap((item) => [item.gatewayDeviceId, item.subDeviceId]))
      buildTree(topologies)
    } finally {
      loading.value = false
    }
  }

  /** 把权威拓扑事实投影成树形表格：网关为根，绑定子设备为叶。 */
  const buildTree = (topologies: DeviceTopologyResponse[]) => {
    const deviceById = new Map(devices.value.map((device) => [device.id, device]))
    const childrenByGateway = new Map<string, TopologyNode[]>()
    for (const topology of topologies) {
      const subDeviceId = topology.subDeviceId
      const gatewayId = topology.gatewayDeviceId
      if (!subDeviceId || !gatewayId) continue
      const subDevice = deviceById.get(subDeviceId)
      if (!subDevice) continue
      const node: TopologyNode = {
        id: subDeviceId,
        label: subDevice.name ?? subDevice.deviceKey ?? subDeviceId,
        deviceKey: subDevice.deviceKey,
        status: topology.onlineStatus ?? 'UNKNOWN',
        boundAt: topology.boundAt,
        isSub: true
      }
      const siblings = childrenByGateway.get(gatewayId) ?? []
      siblings.push(node)
      childrenByGateway.set(gatewayId, siblings)
    }
    treeData.value = gateways.value.map((gateway) => ({
      id: gateway.id ?? '',
      label: gateway.name ?? gateway.deviceKey ?? gateway.id ?? '',
      deviceKey: gateway.deviceKey,
      status: gateway.status ?? 'INACTIVE',
      isSub: false,
      children: childrenByGateway.get(gateway.id ?? '') ?? []
    }))
  }

  const openBind = () => {
    bindForm.gatewayId = gateways.value[0]?.id ?? ''
    bindForm.subDeviceId = ''
    bindVisible.value = true
  }

  /** 设备与类型目录都按需推进，避免为了判断 kind 在页面启动时抽干 201+ 类型。 */
  const onCatalogPopupScroll = (event: Event) => {
    onDevicePopupScroll(event)
    if (typeHasMore.value) void loadDeviceTypes(true)
  }

  const submitBind = async () => {
    if (!projectId.value) return
    if (!bindForm.gatewayId || !bindForm.subDeviceId) {
      ElMessage.warning('请选择网关和子设备')
      return
    }
    submitting.value = true
    try {
      await fetchBindTopology(projectId.value, {
        gatewayId: bindForm.gatewayId,
        subDeviceId: bindForm.subDeviceId
      })
      ElMessage.success('绑定成功')
      bindVisible.value = false
      await load()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('绑定子设备失败:', error)
    } finally {
      submitting.value = false
    }
  }

  const unbind = async (node: TopologyNode) => {
    if (!projectId.value || !node.id) return
    try {
      await ElMessageBox.confirm(`确定解除子设备"${node.label}"的绑定吗？`, '解绑子设备', {
        type: 'warning'
      })
      await fetchUnbindTopology(projectId.value, node.id)
      ElMessage.success('已解绑')
      await load()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError))
        console.error('解绑子设备失败:', error)
    }
  }

  onMounted(load)
</script>

<style lang="scss" scoped>
  .topology {
    padding: 10px;
    &__header {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      align-items: center;
      margin-bottom: 10px;
    }
    &__table {
      width: 100%;
    }
    &__device-key {
      font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
    &__binding-time {
      margin-top: 4px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }
  .form-control {
    width: 100%;
  }
</style>
