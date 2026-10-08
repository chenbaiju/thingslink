<template>
  <div class="console-page device-types console-page--single-panel">
    <ConsoleWorkspaceHeader
      class="device-types__header"
      title="设备类型与物模型"
      description="定义属性、事件和命令，发布后继续接入设备。"
    >
      <template #actions>
        <ElButton
          v-if="hasAuth('device:create')"
          type="primary"
          :icon="Plus"
          @click="openCreateType"
        >
          创建设备类型
        </ElButton>
      </template>
    </ConsoleWorkspaceHeader>
    <ElCard
      v-if="workspaceId"
      class="device-types__workspace"
      shadow="never"
      v-loading="workspaceLoading"
    >
      <div class="stream-toolbar"
        ><h3>物模型工作区</h3><ElButton text @click="closeWorkspace">关闭</ElButton></div
      >
      <ElAlert v-if="workspaceError" :title="workspaceError" type="error" :closable="false" />
      <ElButton v-if="workspaceError" @click="openWorkspace({ id: workspaceId })"
        >重试读取</ElButton
      >
      <template v-if="workspaceType">
        <ElDescriptions :column="2" border>
          <ElDescriptionsItem label="名称">{{ workspaceType.name }}</ElDescriptionsItem>
          <ElDescriptionsItem label="标识符">{{ workspaceType.typeKey }}</ElDescriptionsItem>
          <ElDescriptionsItem label="状态">{{
            workspaceType.status === 'DRAFT' ? '草稿 · 可编辑物模型' : '已发布 · 物模型已冻结'
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="设备分类">{{
            kindLabels[workspaceType.deviceKind!]
          }}</ElDescriptionsItem>
        </ElDescriptions>
        <div class="stream-toolbar device-types__steps">
          <ElButton @click="openProperties(workspaceType)">属性定义</ElButton>
          <ElButton @click="openEvents(workspaceType)">事件定义</ElButton>
          <ElButton @click="openCommands(workspaceType)">命令定义</ElButton>
          <ElButton
            v-if="hasAuth('device:update') && workspaceType.status === 'DRAFT'"
            @click="publishType(workspaceType)"
            >发布物模型</ElButton
          >
          <ElButton
            v-if="
              productManager &&
              workspaceType.status === 'PUBLISHED' &&
              ['DIRECT', 'GATEWAY'].includes(workspaceType.deviceKind || '')
            "
            @click="openProductCredential(workspaceType)"
            >产品凭据</ElButton
          >
          <ElButton
            v-if="canCreateDevices && workspaceType.status === 'PUBLISHED'"
            type="primary"
            @click="continueCreateDevice(workspaceType)"
            >继续创建设备</ElButton
          >
        </div>
        <p v-if="workspaceType.status === 'DRAFT'"
          >完成物模型定义并发布后，即可使用该类型创建设备。发布后的修改遵循既有版本规则。</p
        >
      </template>
    </ElCard>

    <ElCard shadow="never" class="console-table-panel console-page__main-panel">
      <ElTable v-loading="loading" :data="items" row-key="id">
        <ElTableColumn label="名称" min-width="160">
          <template #default="{ row }"
            ><ElButton link type="primary" @click="openWorkspace(row)">{{
              row.name
            }}</ElButton></template
          >
        </ElTableColumn>
        <ElTableColumn show-overflow-tooltip prop="typeKey" label="标识符" min-width="150" />
        <ElTableColumn label="设备分类" width="120">
          <template #default="{ row }">{{ kindLabels[row.deviceKind] }}</template>
        </ElTableColumn>
        <ElTableColumn label="报文协议" min-width="180">
          <template #default="{ row }">{{ protocolLabels[row.payloadProtocol] }}</template>
        </ElTableColumn>
        <ElTableColumn label="通信方式" width="130">
          <template #default="{ row }">{{ networkLabels[row.networkType] }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="90">
          <template #default="{ row }"
            ><ElTag>{{ row.status === 'DRAFT' ? '草稿' : '已发布' }}</ElTag></template
          >
        </ElTableColumn>
        <ElTableColumn
          class-name="console-table-actions-cell"
          label="操作"
          width="264"
          fixed="right"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="openProperties(row)"
              label="属性"
              icon="ri:list-settings-line"
            />
            <ConsoleTableAction
              type="primary"
              @click="openEvents(row)"
              label="事件"
              icon="ri:time-line"
            />
            <ConsoleTableAction
              type="primary"
              @click="openCommands(row)"
              label="命令"
              icon="ri:terminal-box-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('device:update') && row.status === 'DRAFT'"
              type="success"
              @click="publishType(row)"
              label="发布"
              icon="ri:send-plane-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('device:update') && row.status === 'DRAFT'"
              type="primary"
              @click="openEditType(row)"
              label="编辑"
              icon="ri:pencil-line"
            />
            <ConsoleTableAction
              v-if="
                productManager &&
                row.status === 'PUBLISHED' &&
                ['DIRECT', 'GATEWAY'].includes(row.deviceKind)
              "
              type="primary"
              @click="openProductCredential(row)"
              label="产品凭据"
              icon="ri:key-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('device:delete') && row.status === 'DRAFT'"
              type="danger"
              @click="removeType(row)"
              label="删除"
              icon="ri:delete-bin-5-line"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有设备类型" /></template>
      </ElTable>
    </ElCard>
    <div v-if="typeHasMore" class="device-types__more">
      <ElButton :loading="loading" text type="primary" @click="load(true)">加载更多</ElButton>
    </div>

    <ProductCredentialDialog
      v-model="productCredentialVisible"
      :project-id="projectId"
      :type-id="productCredentialTypeId"
      @changed="load()"
    />

    <ElDialog
      class="console-dialog"
      v-model="typeVisible"
      :title="editingTypeId ? '编辑设备类型' : '创建设备类型'"
      width="560px"
    >
      <ElForm ref="typeFormRef" :model="typeForm" :rules="typeRules" label-position="top">
        <ElFormItem label="名称" prop="name"
          ><ElInput v-model.trim="typeForm.name" maxlength="128"
        /></ElFormItem>
        <ElFormItem label="标识符" prop="typeKey">
          <ElInput
            v-model.trim="typeForm.typeKey"
            maxlength="64"
            placeholder="例如 temperature_sensor"
          />
        </ElFormItem>
        <ElFormItem label="设备分类" prop="deviceKind">
          <ElSelect v-model="typeForm.deviceKind" class="form-control" @change="resetProtocol">
            <ElOption
              v-for="option in kindOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="报文协议" prop="payloadProtocol">
          <ElSelect v-model="typeForm.payloadProtocol" class="form-control">
            <ElOption
              v-for="option in availableProtocols"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="设备通信方式" prop="networkType">
          <ElSelect v-model="typeForm.networkType" class="form-control" filterable>
            <ElOption
              v-for="option in networkOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer
        ><ElButton @click="typeVisible = false">取消</ElButton
        ><ElButton type="primary" :loading="submitting" @click="submitType"
          >确定</ElButton
        ></template
      >
    </ElDialog>

    <ElDialog
      class="console-dialog"
      v-model="propertiesVisible"
      :title="`${selectedType?.name ?? ''} · 属性定义`"
      width="940px"
    >
      <div class="stream-toolbar">
        <span>属性定义用于校验设备上报、云端下发和云端私有数据。</span>
        <ElButton
          v-if="hasAuth('device:update') && selectedType?.status === 'DRAFT'"
          type="primary"
          :icon="Plus"
          @click="openCreateProperty"
          >添加属性</ElButton
        >
      </div>
      <ElTable v-loading="propertiesLoading" :data="properties" row-key="id">
        <ElTableColumn show-overflow-tooltip prop="name" label="名称" min-width="130" />
        <ElTableColumn show-overflow-tooltip prop="propertyKey" label="标识符" min-width="130" />
        <ElTableColumn label="属性类型" width="140"
          ><template #default="{ row }">{{
            accessTypeLabels[row.accessType]
          }}</template></ElTableColumn
        >
        <ElTableColumn label="数据类型" width="110"
          ><template #default="{ row }">{{
            propertyDataTypeLabels[row.dataType]
          }}</template></ElTableColumn
        >
        <ElTableColumn label="约束" min-width="180">
          <template #default="{ row }">{{ propertyConstraintText(row) }}</template>
        </ElTableColumn>
        <ElTableColumn
          class-name="console-table-actions-cell"
          v-if="hasAuth('device:update') && selectedType?.status === 'DRAFT'"
          label="操作"
          width="104"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="openEditProperty(row)"
              label="编辑"
              icon="ri:pencil-line"
            />
            <ConsoleTableAction
              type="danger"
              @click="removeProperty(row)"
              label="删除"
              icon="ri:delete-bin-5-line"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有属性定义" /></template>
      </ElTable>
    </ElDialog>

    <ElDialog
      class="console-dialog"
      v-model="propertyFormVisible"
      :title="editingPropertyId ? '编辑属性' : '添加属性'"
      width="620px"
      append-to-body
    >
      <ElForm
        ref="propertyFormRef"
        :model="propertyForm"
        :rules="propertyRules"
        label-position="top"
      >
        <ElFormItem label="属性名称" prop="name"
          ><ElInput v-model.trim="propertyForm.name"
        /></ElFormItem>
        <ElFormItem label="属性标识符" prop="propertyKey">
          <ElInput v-model.trim="propertyForm.propertyKey" placeholder="例如 temperature" />
        </ElFormItem>
        <ElFormItem label="属性类型" prop="accessType">
          <ElSelect v-model="propertyForm.accessType" class="form-control">
            <ElOption v-for="option in accessTypeOptions" :key="option.value" v-bind="option" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="数据类型" prop="dataType">
          <ElSelect
            v-model="propertyForm.dataType"
            class="form-control"
            @change="resetPropertyConfig"
          >
            <ElOption
              v-for="option in propertyDataTypeOptions"
              :key="option.value"
              v-bind="option"
            />
          </ElSelect>
        </ElFormItem>
        <template v-if="propertyForm.dataType === 'NUMBER'">
          <ElFormItem label="单位"
            ><ElInput v-model.trim="propertyForm.unit" placeholder="例如 ℃"
          /></ElFormItem>
          <ElFormItem label="精度（小数位）">
            <ElInputNumber v-model="propertyForm.decimalPlaces" :min="0" :max="10" />
          </ElFormItem>
          <div class="range-fields">
            <ElFormItem label="最小值"
              ><ElInputNumber v-model="propertyForm.minimumValue"
            /></ElFormItem>
            <ElFormItem label="最大值"
              ><ElInputNumber v-model="propertyForm.maximumValue"
            /></ElFormItem>
          </div>
        </template>
        <ElFormItem v-if="propertyForm.dataType === 'ENUM'" label="枚举值" prop="enumOptionsText">
          <ElInput
            v-model="propertyForm.enumOptionsText"
            placeholder="多个值用英文逗号分隔，例如 auto,manual"
          />
        </ElFormItem>
        <div v-if="propertyForm.dataType === 'SWITCH'" class="range-fields">
          <ElFormItem label="ON 文字"
            ><ElInput v-model.trim="propertyForm.onLabel" placeholder="开启"
          /></ElFormItem>
          <ElFormItem label="OFF 文字"
            ><ElInput v-model.trim="propertyForm.offLabel" placeholder="关闭"
          /></ElFormItem>
        </div>
        <ElFormItem label="排序"
          ><ElInputNumber v-model="propertyForm.sortOrder" :min="0"
        /></ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="propertyFormVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitProperty">确定</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      class="console-dialog"
      v-model="eventsVisible"
      :title="`${selectedType?.name ?? ''} · 事件定义`"
      width="940px"
    >
      <div class="stream-toolbar">
        <span>事件由设备主动上报，可携带经过 Schema 校验的参数。</span>
        <ElButton
          v-if="hasAuth('device:update') && selectedType?.status === 'DRAFT'"
          type="primary"
          :icon="Plus"
          @click="openCreateEvent"
          >添加事件</ElButton
        >
      </div>
      <ElTable v-loading="eventsLoading" :data="events" row-key="id">
        <ElTableColumn show-overflow-tooltip prop="name" label="名称" min-width="130" />
        <ElTableColumn show-overflow-tooltip prop="eventKey" label="标识符" min-width="130" />
        <ElTableColumn label="级别" width="110">
          <template #default="{ row }"
            ><ElTag :type="eventLevelType(row)">{{ eventLevelText(row) }}</ElTag></template
          >
        </ElTableColumn>
        <ElTableColumn label="参数" min-width="220">
          <template #default="{ row }">{{ eventParameterSummary(row) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="description" label="说明" min-width="180" show-overflow-tooltip />
        <ElTableColumn
          class-name="console-table-actions-cell"
          v-if="hasAuth('device:update') && selectedType?.status === 'DRAFT'"
          label="操作"
          width="104"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="openEditEvent(row)"
              label="编辑"
              icon="ri:pencil-line"
            />
            <ConsoleTableAction
              type="danger"
              @click="removeEvent(row)"
              label="删除"
              icon="ri:delete-bin-5-line"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有事件定义" /></template>
      </ElTable>
    </ElDialog>

    <ElDialog
      class="console-dialog"
      v-model="eventFormVisible"
      :title="editingEventId ? '编辑事件' : '添加事件'"
      width="760px"
      append-to-body
    >
      <ElForm ref="eventFormRef" :model="eventForm" :rules="eventRules" label-position="top">
        <div class="range-fields">
          <ElFormItem label="事件名称" prop="name"
            ><ElInput v-model.trim="eventForm.name"
          /></ElFormItem>
          <ElFormItem label="事件标识符" prop="eventKey"
            ><ElInput v-model.trim="eventForm.eventKey" placeholder="例如 fault"
          /></ElFormItem>
        </div>
        <div class="range-fields">
          <ElFormItem label="事件级别" prop="level">
            <ElSelect v-model="eventForm.level" class="form-control">
              <ElOption v-for="option in eventLevelOptions" :key="option.value" v-bind="option" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="排序"
            ><ElInputNumber v-model="eventForm.sortOrder" :min="0"
          /></ElFormItem>
        </div>
        <ElFormItem label="说明"
          ><ElInput v-model.trim="eventForm.description" type="textarea" :rows="2"
        /></ElFormItem>
        <ElDivider content-position="left">事件参数</ElDivider>
        <div
          v-for="(parameter, index) in eventForm.parameters"
          :key="parameter.localId"
          class="event-parameter"
        >
          <ElInput v-model.trim="parameter.name" placeholder="参数名称" />
          <ElInput v-model.trim="parameter.parameterKey" placeholder="参数标识符" />
          <ElSelect v-model="parameter.dataType" @change="parameter.enumOptionsText = ''">
            <ElOption
              v-for="option in propertyDataTypeOptions"
              :key="option.value"
              v-bind="option"
            />
          </ElSelect>
          <ElCheckbox v-model="parameter.required">必填</ElCheckbox>
          <ElButton link type="danger" @click="eventForm.parameters.splice(index, 1)"
            >删除</ElButton
          >
          <ElInput
            v-if="parameter.dataType === 'ENUM'"
            v-model="parameter.enumOptionsText"
            class="event-parameter__enum"
            placeholder="枚举值用英文逗号分隔"
          />
        </div>
        <ElButton :icon="Plus" @click="addEventParameter">添加参数</ElButton>
      </ElForm>
      <template #footer>
        <ElButton @click="eventFormVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitEvent">确定</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      class="console-dialog"
      v-model="commandsVisible"
      :title="`${selectedType?.name ?? ''} · 命令定义`"
      width="940px"
    >
      <div class="stream-toolbar">
        <span>命令是云端向设备发起的 RPC 动作，可定义输入/输出 JSON Schema 与超时。</span>
        <ElButton
          v-if="hasAuth('device:update') && selectedType?.status === 'DRAFT'"
          type="primary"
          :icon="Plus"
          @click="openCreateCommand"
          >添加命令</ElButton
        >
      </div>
      <ElTable v-loading="commandsLoading" :data="commands" row-key="id">
        <ElTableColumn show-overflow-tooltip prop="name" label="名称" min-width="130" />
        <ElTableColumn show-overflow-tooltip prop="commandKey" label="标识符" min-width="130" />
        <ElTableColumn label="超时" width="90">
          <template #default="{ row }">{{ row.timeoutSeconds }}s</template>
        </ElTableColumn>
        <ElTableColumn label="输入 Schema" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.inputSchema ?? '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="输出 Schema" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.outputSchema ?? '—' }}</template>
        </ElTableColumn>
        <ElTableColumn prop="description" label="说明" min-width="150" show-overflow-tooltip />
        <ElTableColumn
          class-name="console-table-actions-cell"
          v-if="hasAuth('device:update') && selectedType?.status === 'DRAFT'"
          label="操作"
          width="104"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="openEditCommand(row)"
              label="编辑"
              icon="ri:pencil-line"
            />
            <ConsoleTableAction
              type="danger"
              @click="removeCommand(row)"
              label="删除"
              icon="ri:delete-bin-5-line"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有命令定义" /></template>
      </ElTable>
    </ElDialog>

    <ElDialog
      class="console-dialog"
      v-model="commandFormVisible"
      :title="editingCommandId ? '编辑命令' : '添加命令'"
      width="620px"
      append-to-body
    >
      <ElForm ref="commandFormRef" :model="commandForm" :rules="commandRules" label-position="top">
        <div class="range-fields">
          <ElFormItem label="命令名称" prop="name"
            ><ElInput v-model.trim="commandForm.name"
          /></ElFormItem>
          <ElFormItem label="命令标识符" prop="commandKey"
            ><ElInput v-model.trim="commandForm.commandKey" placeholder="例如 reboot"
          /></ElFormItem>
        </div>
        <div class="range-fields">
          <ElFormItem label="超时（秒）" prop="timeoutSeconds">
            <ElInputNumber v-model="commandForm.timeoutSeconds" :min="1" :max="86400" />
          </ElFormItem>
          <ElFormItem label="排序"
            ><ElInputNumber v-model="commandForm.sortOrder" :min="0"
          /></ElFormItem>
        </div>
        <ElFormItem label="说明"
          ><ElInput v-model.trim="commandForm.description" type="textarea" :rows="2"
        /></ElFormItem>
        <ElFormItem label="输入 Schema（JSON）">
          <ElInput
            v-model="commandForm.inputSchema"
            type="textarea"
            :rows="4"
            placeholder='例如 {"type":"object","properties":{"delay":{"type":"integer"}}}'
          />
        </ElFormItem>
        <ElFormItem label="输出 Schema（JSON）">
          <ElInput
            v-model="commandForm.outputSchema"
            type="textarea"
            :rows="4"
            placeholder='例如 {"type":"object","properties":{"result":{"type":"string"}}}'
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="commandFormVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitCommand">确定</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'
  import { recordRecentResource } from '@/utils/workbench-recent'

  import { Plus } from '@element-plus/icons-vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import {
    fetchCreateDeviceCommandDefinition,
    fetchCreateDeviceEventDefinition,
    fetchCreateDevicePropertyDefinition,
    fetchCreateDeviceType,
    fetchDeleteDeviceCommandDefinition,
    fetchDeleteDeviceEventDefinition,
    fetchDeleteDevicePropertyDefinition,
    fetchDeleteDeviceType,
    fetchDeviceCommandDefinitions,
    fetchDeviceEventDefinitions,
    fetchDevicePropertyDefinitions,
    fetchDeviceTypePage,
    fetchDeviceTypeDetail,
    fetchPublishDeviceType,
    fetchUpdateDeviceCommandDefinition,
    fetchUpdateDeviceEventDefinition,
    fetchUpdateDevicePropertyDefinition,
    fetchUpdateDeviceType,
    type DeviceCommandDefinitionResponse,
    type DeviceEventDefinitionResponse,
    type DevicePropertyDefinitionResponse,
    type DeviceTypeResponse
  } from '@/api/device'
  import ProductCredentialDialog from './ProductCredentialDialog.vue'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { useUserStore } from '@/store/modules/user'
  import { useAuth } from '@/hooks/core/useAuth'
  import { HttpError } from '@/utils/http/error'
  import {
    accessTypeLabels,
    accessTypeOptions,
    eventLevelLabels,
    eventLevelOptions,
    eventLevelTagType,
    kindLabels,
    kindOptions,
    networkLabels,
    networkOptions,
    propertyDataTypeLabels,
    propertyDataTypeOptions,
    protocolLabels,
    protocolsByKind,
    type EventLevel,
    type Kind,
    type Network,
    type PropertyAccessType,
    type PropertyDataType,
    type Protocol
  } from './deviceTypeOptions'

  defineOptions({ name: 'DeviceTypes' })
  const userStore = useUserStore()
  const router = useRouter()
  const route = useRoute()
  const { hasAuth } = useAuth()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')
  const canCreateDevices = computed(
    () => hasAuth('device:create') && !!userStore.info.buttons?.includes('device:create')
  )
  const workspaceId = ref('')
  const workspaceType = ref<DeviceTypeResponse>()
  const workspaceLoading = ref(false)
  const workspaceError = ref('')
  let workspaceRead = 0
  function closeWorkspace() {
    workspaceRead++
    workspaceId.value = ''
    workspaceType.value = undefined
    workspaceLoading.value = false
    workspaceError.value = ''
  }
  async function openWorkspace(row: DeviceTypeResponse) {
    if (!row.id || !projectId.value) return
    const id = row.id,
      project = projectId.value,
      read = ++workspaceRead,
      identity = currentIdentityEpoch()
    workspaceId.value = id
    workspaceType.value = undefined
    workspaceError.value = ''
    workspaceLoading.value = true
    const current = () =>
      read === workspaceRead && project === projectId.value && identity === currentIdentityEpoch()
    try {
      const detail = await fetchDeviceTypeDetail(project, id)
      if (!current()) return
      if (detail.id !== id || detail.projectId !== project)
        throw new Error('设备类型不属于当前项目')
      workspaceType.value = detail
      recordRecentResource(
        {
          userId: userStore.info.userId ?? '',
          tenantId: userStore.info.tenantId ?? '',
          projectId: project
        },
        { kind: 'type', id, label: detail.name ?? '设备类型' }
      )
    } catch {
      if (current()) workspaceError.value = '设备类型读取失败，请重试或选择其他类型。'
    } finally {
      if (current()) workspaceLoading.value = false
    }
  }
  function continueCreateDevice(type: DeviceTypeResponse) {
    if (
      !canCreateDevices.value ||
      !type.id ||
      type.projectId !== projectId.value ||
      type.status !== 'PUBLISHED'
    )
      return
    void router.push({
      path: '/device/list',
      query: { createTypeId: type.id, contextProjectId: projectId.value }
    })
  }
  const productManager = computed(
    () =>
      userStore.info.roles?.some((r) => r === 'OWNER' || r === 'ADMIN') && hasAuth('device:update')
  )
  const productCredentialVisible = ref(false),
    productCredentialTypeId = ref('')
  const openProductCredential = (row: DeviceTypeResponse) => {
    if (
      productManager.value &&
      row.id &&
      row.status === 'PUBLISHED' &&
      ['DIRECT', 'GATEWAY'].includes(row.deviceKind || '')
    ) {
      productCredentialTypeId.value = row.id
      productCredentialVisible.value = true
    }
  }
  let catalogEpoch = 0,
    catalogRead = 0
  const loading = ref(false),
    submitting = ref(false),
    propertiesLoading = ref(false),
    eventsLoading = ref(false),
    commandsLoading = ref(false)
  const typeVisible = ref(false),
    propertiesVisible = ref(false),
    propertyFormVisible = ref(false),
    eventsVisible = ref(false),
    eventFormVisible = ref(false),
    commandsVisible = ref(false),
    commandFormVisible = ref(false)
  const editingTypeId = ref(''),
    editingPropertyId = ref(''),
    editingEventId = ref(''),
    editingCommandId = ref('')
  const items = ref<DeviceTypeResponse[]>([]),
    properties = ref<DevicePropertyDefinitionResponse[]>([]),
    events = ref<DeviceEventDefinitionResponse[]>([]),
    commands = ref<DeviceCommandDefinitionResponse[]>([])
  const selectedType = ref<DeviceTypeResponse>()
  const typeCursor = ref<string>()
  const typeHasMore = ref(false)
  const typeFormRef = ref<FormInstance>(),
    propertyFormRef = ref<FormInstance>(),
    eventFormRef = ref<FormInstance>(),
    commandFormRef = ref<FormInstance>()
  const typeForm = reactive({
    name: '',
    typeKey: '',
    deviceKind: 'DIRECT' as Kind,
    payloadProtocol: 'STANDARD' as Protocol,
    networkType: 'OTHER' as Network
  })
  const propertyForm = reactive({
    name: '',
    propertyKey: '',
    accessType: 'REPORT' as PropertyAccessType,
    dataType: 'NUMBER' as PropertyDataType,
    unit: '',
    decimalPlaces: 2 as number | undefined,
    minimumValue: undefined as number | undefined,
    maximumValue: undefined as number | undefined,
    enumOptionsText: '',
    onLabel: '',
    offLabel: '',
    sortOrder: 0
  })
  let nextEventParameterId = 0
  const createEventParameter = () => ({
    localId: String(++nextEventParameterId),
    name: '',
    parameterKey: '',
    dataType: 'TEXT' as PropertyDataType,
    required: false,
    enumOptionsText: ''
  })
  const eventForm = reactive({
    name: '',
    eventKey: '',
    level: 'ERROR' as EventLevel,
    description: '',
    sortOrder: 0,
    parameters: [] as ReturnType<typeof createEventParameter>[]
  })
  const commandForm = reactive({
    name: '',
    commandKey: '',
    description: '',
    timeoutSeconds: 30,
    sortOrder: 0,
    inputSchema: '',
    outputSchema: ''
  })
  const availableProtocols = computed(() =>
    protocolsByKind[typeForm.deviceKind].map((value) => ({ value, label: protocolLabels[value] }))
  )
  const typeRules: FormRules = {
    name: [{ required: true, message: '请输入设备类型名称', trigger: 'blur' }],
    typeKey: [
      { required: true, message: '请输入标识符', trigger: 'blur' },
      {
        pattern: /^[a-z][a-z0-9_]*$/,
        message: '仅支持小写字母、数字和下划线，且以字母开头',
        trigger: 'blur'
      }
    ]
  }
  const propertyRules: FormRules = {
    name: [{ required: true, message: '请输入属性名称', trigger: 'blur' }],
    propertyKey: [
      { required: true, message: '请输入属性标识符', trigger: 'blur' },
      { pattern: /^[A-Za-z0-9_-]+$/, message: '仅支持字母、数字、下划线和横线', trigger: 'blur' }
    ],
    enumOptionsText: [
      {
        validator: (_rule, value, callback) =>
          propertyForm.dataType === 'ENUM' && !value.trim()
            ? callback(new Error('请输入至少一个枚举值'))
            : callback(),
        trigger: 'blur'
      }
    ]
  }
  const eventRules: FormRules = {
    name: [{ required: true, message: '请输入事件名称', trigger: 'blur' }],
    eventKey: [
      { required: true, message: '请输入事件标识符', trigger: 'blur' },
      { pattern: /^[A-Za-z0-9_-]+$/, message: '仅支持字母、数字、下划线和横线', trigger: 'blur' }
    ]
  }
  const commandRules: FormRules = {
    name: [{ required: true, message: '请输入命令名称', trigger: 'blur' }],
    commandKey: [
      { required: true, message: '请输入命令标识符', trigger: 'blur' },
      { pattern: /^[A-Za-z0-9_-]+$/, message: '仅支持字母、数字、下划线和横线', trigger: 'blur' }
    ]
  }
  const load = async (append = false) => {
    if (!projectId.value || loading.value) return
    const epoch = catalogEpoch,
      read = ++catalogRead,
      id = projectId.value
    loading.value = true
    try {
      const page = await fetchDeviceTypePage(id, append ? typeCursor.value : undefined, 50)
      if (epoch !== catalogEpoch || read !== catalogRead) return
      items.value = append ? [...items.value, ...(page.items ?? [])] : (page.items ?? [])
      typeCursor.value = page.nextCursor ?? undefined
      typeHasMore.value = page.hasMore ?? false
    } catch (error) {
      if (epoch === catalogEpoch && read === catalogRead && !(error instanceof HttpError))
        console.error('设备类型读取失败', error)
    } finally {
      if (epoch === catalogEpoch && read === catalogRead) loading.value = false
    }
  }
  const resetProtocol = () => {
    typeForm.payloadProtocol = protocolsByKind[typeForm.deviceKind][0]
  }
  const openCreateType = () => {
    editingTypeId.value = ''
    Object.assign(typeForm, {
      name: '',
      typeKey: '',
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'OTHER'
    })
    typeVisible.value = true
  }
  const openEditType = (row: DeviceTypeResponse) => {
    if (!row.id) return
    editingTypeId.value = row.id
    Object.assign(typeForm, {
      name: row.name,
      typeKey: row.typeKey,
      deviceKind: row.deviceKind,
      payloadProtocol: row.payloadProtocol,
      networkType: row.networkType
    })
    typeVisible.value = true
  }
  const submitType = async () => {
    if (!typeFormRef.value || !projectId.value) return
    try {
      await typeFormRef.value.validate()
      submitting.value = true
      if (editingTypeId.value) {
        await fetchUpdateDeviceType(projectId.value, editingTypeId.value, {
          name: typeForm.name,
          typeKey: typeForm.typeKey,
          deviceKind: typeForm.deviceKind,
          payloadProtocol: typeForm.payloadProtocol,
          networkType: typeForm.networkType
        })
      } else await fetchCreateDeviceType(projectId.value, typeForm)
      ElMessage.success(editingTypeId.value ? '设备类型修改成功' : '设备类型创建成功')
      typeVisible.value = false
      await load()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('保存设备类型失败:', error)
    } finally {
      submitting.value = false
    }
  }
  const removeType = async (row: DeviceTypeResponse) => {
    if (!projectId.value || !row.id) return
    try {
      await ElMessageBox.confirm(`确定删除设备类型”${row.name}”吗？`, '删除设备类型', {
        type: 'warning'
      })
      await fetchDeleteDeviceType(projectId.value, row.id)
      ElMessage.success('设备类型已删除')
      await load()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError))
        console.error('删除设备类型失败:', error)
    }
  }
  const publishType = async (row: DeviceTypeResponse) => {
    if (!projectId.value || !row.id) return
    try {
      await ElMessageBox.confirm(
        `发布”${row.name}”后，物模型（属性/事件/命令）将全部冻结，只能通过创建新版本演进。确定发布吗？`,
        '发布设备类型',
        { type: 'warning', confirmButtonText: '确定发布' }
      )
      await fetchPublishDeviceType(projectId.value, row.id)
      ElMessage.success('设备类型已发布')
      await load()
      if (workspaceId.value === row.id) await openWorkspace(row)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError))
        console.error('发布设备类型失败:', error)
    }
  }
  const loadProperties = async () => {
    if (!projectId.value || !selectedType.value?.id) return
    propertiesLoading.value = true
    try {
      properties.value = await fetchDevicePropertyDefinitions(
        projectId.value,
        selectedType.value.id
      )
    } finally {
      propertiesLoading.value = false
    }
  }
  const openProperties = async (row: DeviceTypeResponse) => {
    selectedType.value = row
    propertiesVisible.value = true
    await loadProperties()
  }
  const resetPropertyConfig = () => {
    Object.assign(propertyForm, {
      unit: '',
      decimalPlaces: propertyForm.dataType === 'NUMBER' ? 2 : undefined,
      minimumValue: undefined,
      maximumValue: undefined,
      enumOptionsText: '',
      onLabel: '',
      offLabel: ''
    })
  }
  const openCreateProperty = () => {
    editingPropertyId.value = ''
    Object.assign(propertyForm, {
      name: '',
      propertyKey: '',
      accessType: 'REPORT',
      dataType: 'NUMBER',
      unit: '',
      decimalPlaces: 2,
      minimumValue: undefined,
      maximumValue: undefined,
      enumOptionsText: '',
      onLabel: '',
      offLabel: '',
      sortOrder: properties.value.length
    })
    propertyFormVisible.value = true
  }
  const openEditProperty = (row: DevicePropertyDefinitionResponse) => {
    if (!row.id) return
    editingPropertyId.value = row.id
    Object.assign(propertyForm, {
      name: row.name,
      propertyKey: row.propertyKey,
      accessType: row.accessType,
      dataType: row.dataType,
      unit: row.unit ?? '',
      decimalPlaces: row.decimalPlaces,
      minimumValue: row.minimumValue,
      maximumValue: row.maximumValue,
      enumOptionsText: row.enumOptions?.join(',') ?? '',
      onLabel: row.onLabel ?? '',
      offLabel: row.offLabel ?? '',
      sortOrder: row.sortOrder
    })
    propertyFormVisible.value = true
  }
  const submitProperty = async () => {
    if (!propertyFormRef.value || !projectId.value || !selectedType.value?.id) return
    try {
      await propertyFormRef.value.validate()
      submitting.value = true
      const body = {
        propertyKey: propertyForm.propertyKey,
        name: propertyForm.name,
        accessType: propertyForm.accessType,
        dataType: propertyForm.dataType,
        unit: propertyForm.dataType === 'NUMBER' ? propertyForm.unit || undefined : undefined,
        decimalPlaces: propertyForm.dataType === 'NUMBER' ? propertyForm.decimalPlaces : undefined,
        minimumValue: propertyForm.dataType === 'NUMBER' ? propertyForm.minimumValue : undefined,
        maximumValue: propertyForm.dataType === 'NUMBER' ? propertyForm.maximumValue : undefined,
        enumOptions:
          propertyForm.dataType === 'ENUM'
            ? propertyForm.enumOptionsText
                .split(',')
                .map((value) => value.trim())
                .filter(Boolean)
            : undefined,
        onLabel: propertyForm.dataType === 'SWITCH' ? propertyForm.onLabel || undefined : undefined,
        offLabel:
          propertyForm.dataType === 'SWITCH' ? propertyForm.offLabel || undefined : undefined,
        sortOrder: propertyForm.sortOrder
      }
      if (editingPropertyId.value)
        await fetchUpdateDevicePropertyDefinition(
          projectId.value,
          selectedType.value.id,
          editingPropertyId.value,
          body
        )
      else await fetchCreateDevicePropertyDefinition(projectId.value, selectedType.value.id, body)
      ElMessage.success(editingPropertyId.value ? '属性修改成功' : '属性添加成功')
      propertyFormVisible.value = false
      await loadProperties()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('保存属性定义失败:', error)
    } finally {
      submitting.value = false
    }
  }
  const removeProperty = async (row: DevicePropertyDefinitionResponse) => {
    if (!projectId.value || !selectedType.value?.id || !row.id) return
    try {
      await ElMessageBox.confirm(`确定删除属性“${row.name}”吗？`, '删除属性', { type: 'warning' })
      await fetchDeleteDevicePropertyDefinition(projectId.value, selectedType.value.id, row.id)
      ElMessage.success('属性已删除')
      await loadProperties()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError))
        console.error('删除属性定义失败:', error)
    }
  }
  const propertyConstraintText = (row: DevicePropertyDefinitionResponse) => {
    if (row.dataType === 'NUMBER') {
      const range =
        row.minimumValue != null || row.maximumValue != null
          ? `${row.minimumValue ?? '−∞'} ～ ${row.maximumValue ?? '+∞'}`
          : '不限范围'
      return [row.unit, `精度 ${row.decimalPlaces ?? 0}`, range].filter(Boolean).join('；')
    }
    if (row.dataType === 'ENUM') return row.enumOptions?.join('、') ?? '—'
    if (row.dataType === 'SWITCH') return `${row.offLabel ?? '关闭'} / ${row.onLabel ?? '开启'}`
    return '—'
  }
  const loadEvents = async () => {
    if (!projectId.value || !selectedType.value?.id) return
    eventsLoading.value = true
    try {
      events.value = await fetchDeviceEventDefinitions(projectId.value, selectedType.value.id)
    } finally {
      eventsLoading.value = false
    }
  }
  const openEvents = async (row: DeviceTypeResponse) => {
    selectedType.value = row
    eventsVisible.value = true
    await loadEvents()
  }
  const openCreateEvent = () => {
    editingEventId.value = ''
    Object.assign(eventForm, {
      name: '',
      eventKey: '',
      level: 'ERROR',
      description: '',
      sortOrder: events.value.length,
      parameters: []
    })
    eventFormVisible.value = true
  }
  const openEditEvent = (row: DeviceEventDefinitionResponse) => {
    if (!row.id) return
    editingEventId.value = row.id
    Object.assign(eventForm, {
      name: row.name,
      eventKey: row.eventKey,
      level: row.level,
      description: row.description ?? '',
      sortOrder: row.sortOrder,
      parameters: (row.parameters ?? []).map((parameter) => ({
        localId: String(++nextEventParameterId),
        name: parameter.name,
        parameterKey: parameter.parameterKey,
        dataType: parameter.dataType,
        required: parameter.required,
        enumOptionsText: parameter.enumOptions?.join(',') ?? ''
      }))
    })
    eventFormVisible.value = true
  }
  const addEventParameter = () => {
    eventForm.parameters.push(createEventParameter())
  }
  const submitEvent = async () => {
    if (!eventFormRef.value || !projectId.value || !selectedType.value?.id) return
    try {
      await eventFormRef.value.validate()
      const invalidParameter = eventForm.parameters.find(
        (parameter) =>
          !parameter.name.trim() ||
          !/^[A-Za-z0-9_-]+$/.test(parameter.parameterKey) ||
          (parameter.dataType === 'ENUM' && !parameter.enumOptionsText.trim())
      )
      if (invalidParameter) {
        ElMessage.warning('请完整填写参数名称、合法标识符及枚举选项')
        return
      }
      const parameterKeys = eventForm.parameters.map((parameter) => parameter.parameterKey)
      if (new Set(parameterKeys).size !== parameterKeys.length) {
        ElMessage.warning('同一事件内的参数标识符不能重复')
        return
      }
      submitting.value = true
      const body = {
        eventKey: eventForm.eventKey,
        name: eventForm.name,
        level: eventForm.level,
        description: eventForm.description || undefined,
        sortOrder: eventForm.sortOrder,
        parameters: eventForm.parameters.map((parameter, index) => ({
          parameterKey: parameter.parameterKey,
          name: parameter.name,
          dataType: parameter.dataType,
          required: parameter.required,
          enumOptions:
            parameter.dataType === 'ENUM'
              ? parameter.enumOptionsText
                  .split(',')
                  .map((value) => value.trim())
                  .filter(Boolean)
              : undefined,
          sortOrder: index
        }))
      }
      if (editingEventId.value)
        await fetchUpdateDeviceEventDefinition(
          projectId.value,
          selectedType.value.id,
          editingEventId.value,
          body
        )
      else await fetchCreateDeviceEventDefinition(projectId.value, selectedType.value.id, body)
      ElMessage.success(editingEventId.value ? '事件修改成功' : '事件添加成功')
      eventFormVisible.value = false
      await loadEvents()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('保存事件定义失败:', error)
    } finally {
      submitting.value = false
    }
  }
  const removeEvent = async (row: DeviceEventDefinitionResponse) => {
    if (!projectId.value || !selectedType.value?.id || !row.id) return
    try {
      await ElMessageBox.confirm(`确定删除事件“${row.name}”吗？`, '删除事件', { type: 'warning' })
      await fetchDeleteDeviceEventDefinition(projectId.value, selectedType.value.id, row.id)
      ElMessage.success('事件已删除')
      await loadEvents()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError))
        console.error('删除事件定义失败:', error)
    }
  }
  const eventParameterSummary = (row: DeviceEventDefinitionResponse) => {
    if (!row.parameters?.length) return '无参数'
    return row.parameters
      .map(
        (parameter) =>
          `${parameter.name ?? '未命名参数'}（${propertyDataTypeLabels[parameter.dataType ?? 'TEXT']}）`
      )
      .join('、')
  }
  const eventLevelText = (row: DeviceEventDefinitionResponse) =>
    eventLevelLabels[row.level ?? 'INFO']
  const eventLevelType = (row: DeviceEventDefinitionResponse) =>
    eventLevelTagType[row.level ?? 'INFO']
  const loadCommands = async () => {
    if (!projectId.value || !selectedType.value?.id) return
    commandsLoading.value = true
    try {
      commands.value = await fetchDeviceCommandDefinitions(projectId.value, selectedType.value.id)
    } finally {
      commandsLoading.value = false
    }
  }
  const openCommands = async (row: DeviceTypeResponse) => {
    selectedType.value = row
    commandsVisible.value = true
    await loadCommands()
  }
  const openCreateCommand = () => {
    editingCommandId.value = ''
    Object.assign(commandForm, {
      name: '',
      commandKey: '',
      description: '',
      timeoutSeconds: 30,
      sortOrder: commands.value.length,
      inputSchema: '',
      outputSchema: ''
    })
    commandFormVisible.value = true
  }
  const openEditCommand = (row: DeviceCommandDefinitionResponse) => {
    if (!row.id) return
    editingCommandId.value = row.id
    Object.assign(commandForm, {
      name: row.name,
      commandKey: row.commandKey,
      description: row.description ?? '',
      timeoutSeconds: row.timeoutSeconds,
      sortOrder: row.sortOrder,
      inputSchema: row.inputSchema ?? '',
      outputSchema: row.outputSchema ?? ''
    })
    commandFormVisible.value = true
  }
  const submitCommand = async () => {
    if (!commandFormRef.value || !projectId.value || !selectedType.value?.id) return
    try {
      await commandFormRef.value.validate()
      if (commandForm.inputSchema) {
        try {
          JSON.parse(commandForm.inputSchema)
        } catch {
          ElMessage.warning('输入 Schema 不是合法的 JSON')
          return
        }
      }
      if (commandForm.outputSchema) {
        try {
          JSON.parse(commandForm.outputSchema)
        } catch {
          ElMessage.warning('输出 Schema 不是合法的 JSON')
          return
        }
      }
      submitting.value = true
      const body = {
        commandKey: commandForm.commandKey,
        name: commandForm.name,
        description: commandForm.description || undefined,
        timeoutSeconds: commandForm.timeoutSeconds,
        sortOrder: commandForm.sortOrder,
        inputSchema: commandForm.inputSchema || undefined,
        outputSchema: commandForm.outputSchema || undefined
      }
      if (editingCommandId.value)
        await fetchUpdateDeviceCommandDefinition(
          projectId.value,
          selectedType.value.id,
          editingCommandId.value,
          body
        )
      else await fetchCreateDeviceCommandDefinition(projectId.value, selectedType.value.id, body)
      ElMessage.success(editingCommandId.value ? '命令修改成功' : '命令添加成功')
      commandFormVisible.value = false
      await loadCommands()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('保存命令定义失败:', error)
    } finally {
      submitting.value = false
    }
  }
  const removeCommand = async (row: DeviceCommandDefinitionResponse) => {
    if (!projectId.value || !selectedType.value?.id || !row.id) return
    try {
      await ElMessageBox.confirm(`确定删除命令"${row.name}"吗？`, '删除命令', { type: 'warning' })
      await fetchDeleteDeviceCommandDefinition(projectId.value, selectedType.value.id, row.id)
      ElMessage.success('命令已删除')
      await loadCommands()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError))
        console.error('删除命令定义失败:', error)
    }
  }
  watch(
    [projectId, () => userStore.info.userId, () => userStore.info.tenantId, currentIdentityEpoch],
    () => {
      closeWorkspace()
      catalogEpoch++
      catalogRead++
      loading.value = false
      items.value = []
      typeCursor.value = undefined
      typeHasMore.value = false
      productCredentialVisible.value = false
      productCredentialTypeId.value = ''
      selectedType.value = undefined
      typeVisible.value = false
      propertiesVisible.value = false
      propertyFormVisible.value = false
      eventsVisible.value = false
      eventFormVisible.value = false
      commandsVisible.value = false
      commandFormVisible.value = false
      void load()
    },
    { immediate: true, flush: 'sync' }
  )
  watch(
    () => [route.query.resourceId, route.query.contextProjectId] as const,
    ([resourceId, contextProject]) => {
      if (resourceId === undefined) return
      if (
        typeof resourceId !== 'string' ||
        !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(resourceId) ||
        contextProject !== projectId.value ||
        !userStore.info.buttons?.includes('device:read')
      ) {
        closeWorkspace()
        ElMessage.warning('设备类型入口已失效，请在当前项目重新选择。')
        return
      }
      void openWorkspace({ id: resourceId })
    },
    { immediate: true }
  )
  onBeforeUnmount(() => {
    closeWorkspace()
    catalogEpoch++
    catalogRead++
    productCredentialVisible.value = false
    productCredentialTypeId.value = ''
  })
</script>

<style lang="scss" scoped>
  .device-types {
    padding: 10px;
    &__workspace {
      margin-bottom: 16px;
    }
    &__steps {
      flex-wrap: wrap;
      margin-top: 16px;
    }
    &__header {
      margin-bottom: 16px;
    }
  }
  .form-control {
    width: 100%;
  }
  .form-help {
    width: 100%;
    font-size: 12px;
    line-height: 20px;
    color: var(--art-text-gray-600);
  }
  .range-fields {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }
  .event-parameter {
    display: grid;
    grid-template-columns: 1fr 1fr 150px 70px 45px;
    gap: 8px;
    align-items: center;
    margin-bottom: 10px;
    &__enum {
      grid-column: 1 / -1;
    }
  }
  .stream-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 10px;
    color: var(--art-text-gray-600);
  }
</style>
