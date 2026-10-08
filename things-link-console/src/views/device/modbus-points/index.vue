<template>
  <div class="console-page modbus-points console-page--single-panel">
    <ConsoleWorkspaceHeader
      title="Modbus 点位"
      description="选择网关，维护寄存器点位并发布配置。"
      :links="[{ label: '设备与接入', path: '/device/list', permission: 'device:read' }]"
    />
    <div class="modbus-points__header console-toolbar console-page-actions">
      <div class="modbus-points__actions console-actions">
        <ElButton v-if="hasAuth('device:update')" type="primary" @click="openCreate" :icon="Plus"
          >添加点位</ElButton
        >
        <ElButton
          v-if="hasAuth('device:update') && draftCount > 0"
          type="primary"
          :loading="publishing"
          @click="publish"
          >发布（{{ draftCount }} 个草稿）</ElButton
        >
      </div>
    </div>

    <ElCard class="console-list-filter" shadow="never">
      <ConsoleFilterBar
        :items="[{ key: 'field0', label: '网关' }]"
        :show-expand="false"
        :show-reset="false"
        :show-search="false"
      >
        <template #field0
          ><ElSelect
            v-model="gatewayId"
            class="gateway-select"
            placeholder="选择 Modbus 网关"
            filterable
            remote
            :remote-method="searchDevices"
            :loading="devicesLoading"
            @popup-scroll="onCatalogPopupScroll"
            @change="loadPoints"
          >
            <ElOption
              v-for="g in modbusGateways"
              :key="g.id"
              :label="g.name ?? g.deviceKey"
              :value="g.id"
            /> </ElSelect
        ></template>
      </ConsoleFilterBar>
    </ElCard>

    <ElCard shadow="never" class="modbus-points__card console-list-data console-page__main-panel">
      <ElTable v-loading="loading" :data="points" empty-text="还没有点位，先选择网关并添加">
        <ElTableColumn show-overflow-tooltip prop="slaveAddress" label="从站" width="70" />
        <ElTableColumn label="功能码" width="150">
          <template #default="{ row }">{{ functionCodeLabel(row.functionCode) }}</template>
        </ElTableColumn>
        <ElTableColumn
          show-overflow-tooltip
          prop="registerAddress"
          label="寄存器地址"
          width="110"
        />
        <ElTableColumn show-overflow-tooltip prop="dataType" label="数据类型" width="90" />
        <ElTableColumn label="子设备" min-width="120">
          <template #default="{ row }">{{ subDeviceName(row.subDeviceId) }}</template>
        </ElTableColumn>
        <ElTableColumn show-overflow-tooltip prop="propertyKey" label="属性" min-width="100" />
        <ElTableColumn show-overflow-tooltip prop="pollingIntervalMs" label="轮询(ms)" width="90" />
        <ElTableColumn label="状态" width="90">
          <template #default="{ row }">
            <ElTag :type="row.status === 'PUBLISHED' ? 'success' : 'info'" size="small">
              {{ row.status === 'PUBLISHED' ? '已发布' : '草稿' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          class-name="console-table-actions-cell"
          label="操作"
          width="104"
          fixed="right"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              v-if="row.status === 'DRAFT' && hasAuth('device:update')"
              type="primary"
              @click="openEdit(row)"
              label="编辑"
              icon="ri:pencil-line"
            />
            <ConsoleTableAction
              v-if="row.status === 'DRAFT' && hasAuth('device:update')"
              type="danger"
              @click="remove(row)"
              label="删除"
              icon="ri:delete-bin-5-line"
            />
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog
      class="console-dialog"
      v-model="dialogVisible"
      :title="editingId ? '编辑点位' : '添加点位'"
      width="560px"
    >
      <ElForm label-width="110px">
        <ElFormItem label="子设备">
          <ElSelect
            v-model="form.subDeviceId"
            class="form-control"
            placeholder="选择已绑定的子设备"
            filterable
            remote
            :remote-method="searchDevices"
            :loading="devicesLoading"
            @popup-scroll="onCatalogPopupScroll"
            @change="onSubDeviceChange"
          >
            <ElOption
              v-for="s in boundSubDevices"
              :key="s.id"
              :label="s.name ?? s.deviceKey"
              :value="s.id"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="属性">
          <ElSelect
            v-model="form.propertyKey"
            class="form-control"
            placeholder="选择属性"
            :disabled="!form.subDeviceId"
          >
            <ElOption
              v-for="p in properties"
              :key="p.propertyKey"
              :label="`${p.name ?? p.propertyKey}（${p.dataType}）`"
              :value="p.propertyKey ?? ''"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="从站地址">
          <ElInputNumber v-model="form.slaveAddress" :min="1" :max="247" class="form-control" />
        </ElFormItem>
        <ElFormItem label="功能码">
          <ElSelect v-model="form.functionCode" class="form-control">
            <ElOption
              v-for="(label, value) in functionCodeLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="寄存器地址">
          <ElInputNumber v-model="form.registerAddress" :min="0" class="form-control" />
        </ElFormItem>
        <ElFormItem label="数据类型">
          <ElSelect v-model="form.dataType" class="form-control">
            <ElOption v-for="value in dataTypes" :key="value" :label="value" :value="value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="字节序">
          <ElSelect v-model="form.byteOrder" class="form-control">
            <ElOption label="大端" value="BIG_ENDIAN" />
            <ElOption label="小端" value="LITTLE_ENDIAN" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="缩放系数">
          <ElInputNumber v-model="form.scale" :step="0.1" class="form-control" />
        </ElFormItem>
        <ElFormItem label="偏移量">
          <ElInputNumber v-model="form.offset" :step="0.1" class="form-control" />
        </ElFormItem>
        <ElFormItem label="轮询周期(ms)">
          <ElInputNumber v-model="form.pollingIntervalMs" :min="1" class="form-control" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submit">确定</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import ConsoleFilterBar from '@/components/ConsoleFilterBar.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { Plus } from '@element-plus/icons-vue'
  import {
    fetchDevicePropertyDefinitions,
    type DeviceResponse,
    type DeviceTypeResponse,
    type DevicePropertyDefinitionResponse
  } from '@/api/device'
  import { usePagedDeviceCatalog } from '@/composables/usePagedDeviceCatalog'
  import {
    fetchModbusPoints,
    createModbusPoint,
    updateModbusPoint,
    deleteModbusPoint,
    publishModbusPoints,
    type ModbusPointMappingResponse,
    type SaveModbusPointMappingRequest
  } from '@/api/modbus-point'
  import { useUserStore } from '@/store/modules/user'
  import { useAuth } from '@/hooks/core/useAuth'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'DeviceModbusPoints' })

  type ModbusPoint = ModbusPointMappingResponse & { id: string }

  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')

  const loading = ref(false),
    submitting = ref(false),
    publishing = ref(false),
    dialogVisible = ref(false)
  const gatewayId = ref('')
  const {
    devices,
    deviceTypes,
    devicesLoading,
    loadDevices: loadDeviceOptions,
    searchDevices,
    onDevicePopupScroll,
    loadDeviceTypes,
    typeHasMore
  } = usePagedDeviceCatalog(projectId)
  const points = ref<ModbusPoint[]>([])
  const properties = ref<DevicePropertyDefinitionResponse[]>([])
  const editingId = ref('')

  const form = reactive({
    subDeviceId: '',
    propertyKey: '',
    slaveAddress: 1,
    functionCode: 'READ_HOLDING_REGISTERS' as SaveModbusPointMappingRequest['functionCode'],
    registerAddress: 0,
    dataType: 'FLOAT32' as SaveModbusPointMappingRequest['dataType'],
    byteOrder: 'BIG_ENDIAN' as SaveModbusPointMappingRequest['byteOrder'],
    scale: undefined as number | undefined,
    offset: undefined as number | undefined,
    pollingIntervalMs: 1000
  })

  const functionCodeLabels: Record<string, string> = {
    READ_COILS: '读线圈 (0x01)',
    READ_DISCRETE_INPUTS: '读离散输入 (0x02)',
    READ_HOLDING_REGISTERS: '读保持寄存器 (0x03)',
    READ_INPUT_REGISTERS: '读输入寄存器 (0x04)'
  }
  const dataTypes: SaveModbusPointMappingRequest['dataType'][] = [
    'BIT',
    'INT16',
    'UINT16',
    'INT32',
    'UINT32',
    'FLOAT32'
  ]
  const functionCodeLabel = (code?: string) => functionCodeLabels[code ?? ''] ?? code ?? ''

  const typeById = computed(() => {
    const map: Record<string, DeviceTypeResponse> = {}
    for (const type of deviceTypes.value) {
      if (type.id) map[type.id] = type
    }
    return map
  })

  /** 仅 Modbus 网关类型（STANDARD_GATEWAY / MODBUS_RTU_CLOUD_GATEWAY）。 */
  const modbusGateways = computed(() =>
    devices.value.filter((device): device is DeviceResponse & { id: string } => {
      if (!device.id) return false
      const protocol = typeById.value[device.deviceTypeId ?? '']?.payloadProtocol
      return protocol === 'STANDARD_GATEWAY' || protocol === 'MODBUS_RTU_CLOUD_GATEWAY'
    })
  )

  /** 已绑定到所选网关的子设备。 */
  const boundSubDevices = computed(() =>
    devices.value.filter((device): device is DeviceResponse & { id: string } => {
      if (!device.id) return false
      return (
        typeById.value[device.deviceTypeId ?? '']?.deviceKind === 'SUB_DEVICE' &&
        device.gatewayId === gatewayId.value
      )
    })
  )

  const draftCount = computed(() => points.value.filter((point) => point.status === 'DRAFT').length)

  const subDeviceName = (subDeviceId?: string) => {
    if (!subDeviceId) return ''
    const device = devices.value.find((d) => d.id === subDeviceId)
    return device?.name ?? device?.deviceKey ?? subDeviceId
  }

  const loadDevices = async () => {
    await Promise.all([loadDeviceOptions(), loadDeviceTypes()])
  }

  /** 设备和类型都只随用户滚动按页推进，不能在页面启动时循环拉全项目目录。 */
  const onCatalogPopupScroll = (event: Event) => {
    onDevicePopupScroll(event)
    if (typeHasMore.value) void loadDeviceTypes(true)
  }

  const loadPoints = async () => {
    if (!projectId.value || !gatewayId.value) return
    loading.value = true
    try {
      points.value = (await fetchModbusPoints(projectId.value, gatewayId.value)).filter(
        (point): point is ModbusPoint => Boolean(point.id)
      )
    } finally {
      loading.value = false
    }
  }

  const onSubDeviceChange = async (subDeviceId: string) => {
    form.propertyKey = ''
    properties.value = []
    if (!subDeviceId) return
    const subDevice = devices.value.find((d) => d.id === subDeviceId)
    if (!subDevice?.deviceTypeId) return
    properties.value = await fetchDevicePropertyDefinitions(projectId.value, subDevice.deviceTypeId)
  }

  const openCreate = () => {
    editingId.value = ''
    Object.assign(form, {
      subDeviceId: '',
      propertyKey: '',
      slaveAddress: 1,
      functionCode: 'READ_HOLDING_REGISTERS',
      registerAddress: 0,
      dataType: 'FLOAT32',
      byteOrder: 'BIG_ENDIAN',
      scale: undefined,
      offset: undefined,
      pollingIntervalMs: 1000
    })
    properties.value = []
    dialogVisible.value = true
  }

  const openEdit = async (point: ModbusPoint) => {
    editingId.value = point.id
    Object.assign(form, {
      subDeviceId: point.subDeviceId ?? '',
      propertyKey: point.propertyKey ?? '',
      slaveAddress: point.slaveAddress ?? 1,
      functionCode: point.functionCode ?? 'READ_HOLDING_REGISTERS',
      registerAddress: point.registerAddress ?? 0,
      dataType: point.dataType ?? 'FLOAT32',
      byteOrder: point.byteOrder ?? 'BIG_ENDIAN',
      scale: point.scale,
      offset: point.offset,
      pollingIntervalMs: point.pollingIntervalMs ?? 1000
    })
    await onSubDeviceChange(point.subDeviceId ?? '')
    dialogVisible.value = true
  }

  const submit = async () => {
    if (!projectId.value || !gatewayId.value) return
    if (!form.subDeviceId || !form.propertyKey) {
      ElMessage.warning('请选择子设备和属性')
      return
    }
    submitting.value = true
    try {
      if (editingId.value) {
        await updateModbusPoint(projectId.value, gatewayId.value, editingId.value, { ...form })
        ElMessage.success('已更新点位')
      } else {
        await createModbusPoint(projectId.value, gatewayId.value, { ...form })
        ElMessage.success('已添加点位')
      }
      dialogVisible.value = false
      await loadPoints()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('保存点位失败:', error)
    } finally {
      submitting.value = false
    }
  }

  const remove = async (point: ModbusPoint) => {
    if (!projectId.value || !gatewayId.value) return
    try {
      await ElMessageBox.confirm('确定删除该草稿点位吗？', '删除点位', { type: 'warning' })
      await deleteModbusPoint(projectId.value, gatewayId.value, point.id)
      ElMessage.success('已删除')
      await loadPoints()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError))
        console.error('删除点位失败:', error)
    }
  }

  const publish = async () => {
    if (!projectId.value || !gatewayId.value) return
    try {
      await ElMessageBox.confirm('发布后点位将冻结为不可变版本，确定发布吗？', '发布点位', {
        type: 'warning'
      })
      publishing.value = true
      await publishModbusPoints(projectId.value, gatewayId.value)
      ElMessage.success('已发布')
      await loadPoints()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError))
        console.error('发布点位失败:', error)
    } finally {
      publishing.value = false
    }
  }

  onMounted(async () => {
    if (!projectId.value) return
    await loadDevices()
    gatewayId.value = modbusGateways.value[0]?.id ?? ''
    await loadPoints()
  })
</script>

<style lang="scss" scoped>
  .modbus-points {
    padding: 10px;
    &__header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      margin-bottom: 10px;
      h3 {
        margin: 0;
        font-size: 18px;
      }
      p {
        margin: 6px 0 0;
        font-size: 13px;
        color: var(--art-text-gray-600);
      }
    }
    &__actions {
      display: flex;
      gap: 8px;
    }
    &__filter {
      margin-bottom: 10px;
    }
  }
  .gateway-select {
    width: 260px;
  }
  .form-control {
    width: 100%;
  }
</style>
