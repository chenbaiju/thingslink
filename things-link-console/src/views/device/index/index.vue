<template>
  <div class="console-page device-list" :class="{ 'device-list--detail': detailVisible }">
    <div v-show="!detailVisible" class="device-list__content">
      <div class="device-list__header console-toolbar console-page-actions">
        <ElButton v-if="hasAuth('device:create')" type="primary" :icon="Plus" @click="openCreate">
          创建设备
        </ElButton>
      </div>
      <ElCard class="device-list__filter console-list-filter" shadow="never">
        <DeviceAdvancedFilter
          v-model="deviceFilter"
          :device-types="filterTypeOptions"
          :groups="filterGroupOptions"
          @search="searchDevices"
          @reset="resetDeviceFilter"
        />
      </ElCard>
      <ElCard class="device-list__data" shadow="never">
        <ElTable
          v-loading="loading"
          :data="items"
          row-key="id"
          row-class-name="device-list__clickable-row"
          @row-click="handleDeviceRowClick"
        >
          <ElTableColumn show-overflow-tooltip prop="name" label="名称" min-width="160" />
          <ElTableColumn show-overflow-tooltip prop="deviceKey" label="标识符" min-width="150" />
          <ElTableColumn label="设备类型" min-width="140">
            <template #default="{ row }">{{ typeName(row.deviceTypeId) }}</template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="110">
            <template #default="{ row }">
              <ElTag :type="deviceStatusTag(row.status)">{{ deviceStatusLabel(row.status) }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="location" label="位置" min-width="130" show-overflow-tooltip />
          <ElTableColumn label="告警状态" min-width="170">
            <template #default="{ row }">
              <DeviceAlarmStatus
                :key="`${row.id}:${alarmListRevision}`"
                :project-id="projectId"
                :device-id="row.id"
                :active="!detailVisible"
              />
            </template>
          </ElTableColumn>
          <ElTableColumn label="最后上线" width="180">
            <template #default="{ row }">{{
              row.lastOnlineAt ? formatTime(row.lastOnlineAt) : '从未上线'
            }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作" column-key="actions" width="76" fixed="right" align="center">
            <template #default="{ row }">
              <ElDropdown
                trigger="hover"
                placement="bottom-end"
                popper-class="device-list-actions-menu"
                :show-arrow="false"
                @command="handleRowAction(row, $event)"
              >
                <ElButton text class="device-list__actions-trigger" aria-label="设备操作">
                  <ArtSvgIcon icon="ri:more-2-fill" />
                </ElButton>
                <template #dropdown>
                  <ElDropdownMenu>
                    <ElDropdownItem command="detail">
                      <ArtSvgIcon icon="ri:eye-line" />详情
                    </ElDropdownItem>
                    <ElDropdownItem v-if="hasAuth('device:update')" command="credentials">
                      <ArtSvgIcon icon="ri:key-2-line" />凭据
                    </ElDropdownItem>
                    <ElDropdownItem v-if="hasAuth('device:update')" command="edit">
                      <ArtSvgIcon icon="ri:edit-box-line" />编辑
                    </ElDropdownItem>
                    <ElDropdownItem
                      v-if="hasAuth('device:delete')"
                      command="delete"
                      class="device-list__danger-action"
                    >
                      <ArtSvgIcon icon="ri:delete-bin-line" />删除
                    </ElDropdownItem>
                  </ElDropdownMenu>
                </template>
              </ElDropdown>
            </template>
          </ElTableColumn>
          <template #empty><ElEmpty description="还没有设备" /></template>
        </ElTable>
        <div v-if="deviceHasMore" class="device-list__more">
          <ElButton :loading="loading" text type="primary" @click="loadDevices(true)"
            >加载更多</ElButton
          >
        </div>
      </ElCard>
    </div>

    <ElDialog
      class="console-dialog"
      v-model="formVisible"
      :title="editingId ? '编辑设备' : '创建设备'"
      width="520px"
    >
      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top">
        <ElFormItem label="设备名称" prop="name"
          ><ElInput v-model.trim="form.name" maxlength="128"
        /></ElFormItem>
        <ElFormItem label="设备标识符" prop="deviceKey">
          <ElInput
            v-model.trim="form.deviceKey"
            maxlength="64"
            placeholder="例如 sensor_01"
            :disabled="!!editingId"
          />
          <div v-if="!editingId" class="form-help"
            >创建后不可修改，仅支持字母、数字、下划线和横线，用于 MQTT Topic。</div
          >
        </ElFormItem>
        <ElFormItem label="设备类型">
          <ElSelect
            v-model="form.deviceTypeId"
            class="form-control"
            clearable
            placeholder="选择设备类型（可选）"
            :loading="deviceTypesLoading"
            @popup-scroll="onDeviceTypePopupScroll"
          >
            <ElOption
              v-for="t in deviceTypes"
              :key="t.id ?? ''"
              :label="t.name"
              :value="t.id ?? ''"
            />
          </ElSelect>
          <div class="form-help"
            >设备类型定义了一类设备的属性、事件、命令等物模型，绑定后自动获得该类型的校验规则。</div
          >
        </ElFormItem>
        <ElFormItem label="位置"
          ><ElInput v-model.trim="form.location" maxlength="256" placeholder="例如 机房A"
        /></ElFormItem>
        <ElFormItem label="说明"
          ><ElInput v-model.trim="form.description" type="textarea" :rows="2"
        /></ElFormItem>
      </ElForm>
      <template #footer
        ><ElButton @click="formVisible = false">取消</ElButton
        ><ElButton type="primary" :loading="submitting" @click="submit">确定</ElButton></template
      >
    </ElDialog>
    <ElDialog
      class="console-dialog"
      v-model="credVisible"
      :title="`${credDeviceName} · 设备凭据`"
      width="680px"
      :close-on-click-modal="false"
      :before-close="closeCredentials"
    >
      <div class="cred-warn" v-if="pendingSecret">
        <ElAlert type="warning" :closable="false" show-icon>
          <template #title
            >请立即复制并安全保存以下密钥，<b>关闭此弹窗后将无法再次查看</b></template
          >
          <div class="cred-secret">
            <ElInput v-model="pendingSecret" readonly
              ><template #append><ElButton @click="copySecret">复制</ElButton></template></ElInput
            >
          </div>
          <ElCheckbox v-model="secretConfirmed" class="cred-confirm">
            我已复制并安全保存该密钥
          </ElCheckbox>
        </ElAlert>
      </div>
      <div class="stream-toolbar">
        <span>设备密钥用于 MQTT 连接认证，每台设备同时只有一个有效密钥。</span>
        <ElButton type="primary" :icon="Plus" :loading="credLoading" @click="generateCred"
          >生成新密钥</ElButton
        >
      </div>
      <ElTable v-loading="credLoading" :data="credentials" row-key="id">
        <ElTableColumn show-overflow-tooltip prop="displayName" label="名称" min-width="120" />
        <ElTableColumn label="类型" width="120"
          ><template #default="{ row }">{{
            row.authType === 'ACCESS_TOKEN' ? '设备密钥' : row.authType
          }}</template></ElTableColumn
        >
        <ElTableColumn label="创建时间" width="180"
          ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></ElTableColumn
        >
        <ElTableColumn class-name="console-table-actions-cell" label="操作" width="64">
          <template #default="{ row }">
            <ConsoleTableAction
              type="danger"
              @click="revokeCred(row)"
              label="作废"
              icon="ri:close-circle-line"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有凭据，请生成新密钥" /></template>
      </ElTable>
    </ElDialog>
    <ElCard v-if="detailVisible" v-loading="detailLoading" class="device-detail" shadow="never">
      <div class="device-detail__heading">
        <ElButton class="device-detail__back" text :icon="ArrowLeft" @click="returnToList"
          >返回</ElButton
        >
        <span class="device-detail__separator" aria-hidden="true"></span>
        <ArtSvgIcon class="device-detail__icon" icon="ri:box-3-line" />
        <h2 class="console-heading">{{ detailDevice ? detailName : '正在加载设备…' }}</h2>
        <ElButton
          v-if="detailDevice && hasAuth('device:update')"
          class="device-detail__edit"
          size="small"
          @click="openEdit(detailDevice)"
          >编辑</ElButton
        >
      </div>
      <div v-if="detailDevice" class="device-detail__metadata">
        <span>ID：{{ detailTagDeviceId }}</span>
        <span>设备标识：{{ detailDeviceKey }}</span>
        <span>设备类型：{{ detailTypeId ? typeName(detailTypeId) : '无' }}</span>
        <span class="device-detail__status" :class="{ 'is-online': detailStatus === 'ONLINE' }">
          <ArtSvgIcon icon="ri:plug-line" />{{ detailConnectionLabel }}
        </span>
        <span class="device-detail__status" :class="{ 'has-alarm': detailAlarmState === 'active' }">
          <ArtSvgIcon
            :icon="
              detailAlarmState === 'active' ? 'ri:alarm-warning-line' : 'ri:notification-off-line'
            "
          />
          {{ detailAlarmLabel }}
        </span>
      </div>
      <ElTabs v-if="detailDevice" v-model="detailTab" class="device-detail__tabs">
        <ElTabPane name="overview" label="概览" lazy>
          <ElDescriptions :column="2" border>
            <ElDescriptionsItem label="设备标识符">{{ detailDeviceKey }}</ElDescriptionsItem>
            <ElDescriptionsItem label="设备类型">{{ typeName(detailTypeId) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="状态">
              <span>{{ detailConnectionLabel }}</span>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="位置">{{ detailLocation || '—' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="说明" :span="2">{{
              detailDescription || '—'
            }}</ElDescriptionsItem>
          </ElDescriptions>
          <ElDivider content-position="left">当前坐标</ElDivider>
          <DeviceLocationPoint
            v-if="detailVisible"
            :project-id="projectId"
            :device-id="detailTagDeviceId"
            :identity-key="userStore.info.userId ?? ''"
            :editable="hasAuth('device:update')"
          />
        </ElTabPane>
        <ElTabPane name="connection" label="连接" lazy>
          <ElDivider content-position="left">连接记录</ElDivider>
          <ElTable v-loading="connLoading" :data="connections" row-key="id" size="small">
            <ElTableColumn label="协议" width="70"
              ><template #default="{ row }">{{ row.protocol }}</template></ElTableColumn
            >
            <ElTableColumn label="IP" width="140" prop="clientIp" show-overflow-tooltip />
            <ElTableColumn label="连接时间" width="170"
              ><template #default="{ row }">{{
                formatTime(row.connectedAt)
              }}</template></ElTableColumn
            >
            <ElTableColumn label="断开时间" width="170">
              <template #default="{ row }"
                ><span v-if="row.disconnectedAt">{{ formatTime(row.disconnectedAt) }}</span
                ><ElTag v-else type="success" size="small">在线</ElTag></template
              >
            </ElTableColumn>
            <ElTableColumn
              label="断开原因"
              min-width="120"
              prop="disconnectReason"
              show-overflow-tooltip
            />
            <template #empty
              ><span style="color: var(--art-text-gray-500)"
                >暂无连接记录，设备上线后将自动记录</span
              ></template
            >
          </ElTable>
        </ElTabPane>
        <ElTabPane name="info" label="信息" lazy>
          <ElDescriptions :column="2" border>
            <ElDescriptionsItem label="设备 ID">{{ detailTagDeviceId }}</ElDescriptionsItem>
            <ElDescriptionsItem label="设备名称">{{ detailName }}</ElDescriptionsItem>
            <ElDescriptionsItem label="创建时间">{{
              detailDevice.createdAt ? formatTime(detailDevice.createdAt) : '—'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="最后上线">{{
              detailDevice.lastOnlineAt ? formatTime(detailDevice.lastOnlineAt) : '从未上线'
            }}</ElDescriptionsItem>
          </ElDescriptions>
          <ElDivider content-position="left">设备标签</ElDivider>
          <DeviceTagEditor
            :project-id="projectId"
            :device-id="detailTagDeviceId"
            :editable="hasAuth('device:update')"
          />
          <ElDivider content-position="left">设备类型事件定义</ElDivider>
          <p class="form-help console-description">当前设备类型定义的事件。</p>
          <ElAlert
            v-if="!eventDefinitionsAvailable"
            title="事件定义暂不可用，请重新进入详情重试"
            type="warning"
            :closable="false"
          />
          <ElTable v-else :data="detailEvents" row-key="id">
            <ElTableColumn show-overflow-tooltip prop="name" label="事件名称" min-width="160" />
            <ElTableColumn show-overflow-tooltip prop="eventKey" label="标识符" min-width="160" />
            <ElTableColumn label="级别" width="100">
              <template #default="{ row }">{{ eventLevelLabel(row.level) }}</template>
            </ElTableColumn>
            <ElTableColumn prop="description" label="说明" min-width="200" show-overflow-tooltip />
            <template #empty
              ><ElEmpty
                :description="detailTypeId ? '设备类型尚未定义事件' : '设备尚未绑定设备类型'"
            /></template>
          </ElTable>
        </ElTabPane>
        <ElTabPane name="properties" label="属性" lazy>
          <div v-loading="shadowLoading">
            <div class="shadow-section">
              <div class="shadow-section__header console-page-header">
                <span>期望状态（Desired）</span>
                <span class="shadow-version">版本 {{ shadowVersion }}</span>
              </div>
              <ElInput
                v-model="shadowDesired"
                type="textarea"
                :rows="6"
                placeholder="暂无期望状态"
              />
            </div>
            <div class="shadow-section">
              <div class="shadow-section__header console-page-header"
                ><span>上报状态（Reported）</span></div
              >
              <ElTag
                class="realtime-status"
                :type="
                  realtimeStatus === 'connected'
                    ? 'success'
                    : realtimeStatus === 'quota_exceeded'
                      ? 'warning'
                      : 'info'
                "
              >
                {{
                  realtimeStatus === 'connected'
                    ? '实时连接已建立'
                    : realtimeStatus === 'connecting'
                      ? '实时连接中'
                      : realtimeStatus === 'quota_exceeded'
                        ? '租户共享连接额度已满，暂不自动重连'
                        : '实时连接已断开'
                }}
              </ElTag>
              <ElInput
                v-model="shadowReported"
                type="textarea"
                :rows="6"
                placeholder="暂无设备上报"
                readonly
              />
              <ul aria-label="上报属性来源" data-testid="reported-property-sources">
                <li v-for="fact in reportedSources" :key="fact.key"
                  >{{ fact.key }}：{{ fact.source }} · 接受序号 {{ fact.revision }}</li
                >
              </ul>
            </div>
          </div>
          <ElButton
            v-if="hasAuth('device:update')"
            type="primary"
            :loading="shadowLoading"
            @click="saveDesired"
            >保存期望</ElButton
          >
          <ElDivider content-position="left">历史曲线</ElDivider>
          <div class="history-toolbar">
            <ElSelect v-model="historyPropertyKey" placeholder="选择数值属性" @change="loadHistory">
              <ElOption
                v-for="property in historyProperties"
                :key="property.propertyKey ?? property.id"
                :label="property.name"
                :value="property.propertyKey ?? ''"
              />
            </ElSelect>
            <ElSelect v-model="historyRangeHours" @change="loadHistory">
              <ElOption label="最近 1 小时" :value="1" />
              <ElOption label="最近 24 小时" :value="24" />
              <ElOption label="最近 7 天" :value="168" />
              <ElOption label="最近 30 天" :value="720" />
            </ElSelect>
            <ElTag v-if="historyActualGranularity" type="info">
              实际粒度：{{ granularityLabel(historyActualGranularity) }}
            </ElTag>
          </div>
          <ArtLineChart
            height="260px"
            :loading="historyLoading"
            :is-empty="historyValues.length === 0"
            :data="historyValues"
            :x-axis-data="historyTimes"
            show-area-color
          />
        </ElTabPane>

        <ElTabPane name="commands" label="命令" lazy>
          <div v-if="hasAuth('device:control')" class="command-panel">
            <div class="command-toolbar console-actions">
              <ElSelect v-model="commandKey" placeholder="选择命令" clearable>
                <ElOption
                  v-for="command in commandDefinitions"
                  :key="command.commandKey ?? command.id"
                  :label="command.name"
                  :value="command.commandKey ?? ''"
                />
              </ElSelect>
              <ElButton type="primary" :loading="commandSubmitting" @click="submitCommand"
                >下发命令</ElButton
              >
              <ElButton v-if="lastCommand?.id" :loading="commandLoading" @click="refreshCommand"
                >刷新状态</ElButton
              >
            </div>
            <ElInput
              v-model="commandInput"
              type="textarea"
              :rows="4"
              placeholder='请输入命令参数 JSON，例如 {"enabled":true}'
            />
            <ElDescriptions v-if="lastCommand" :column="3" border class="command-result">
              <ElDescriptionsItem label="状态">{{
                commandStatusLabel(lastCommand.status)
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="尝试次数"
                >{{ lastCommand.attemptCount ?? 0 }} /
                {{ lastCommand.maxAttempts ?? 0 }}</ElDescriptionsItem
              >
              <ElDescriptionsItem label="命令 ID">{{ lastCommand.id }}</ElDescriptionsItem>
              <ElDescriptionsItem v-if="lastCommand.failureMessage" label="失败原因" :span="3">{{
                lastCommand.failureMessage
              }}</ElDescriptionsItem>
            </ElDescriptions>
            <DeviceCommandHistory
              v-if="detailTab === 'commands' && projectId && detailTagDeviceId"
              :project-id="projectId"
              :device-id="detailTagDeviceId"
              :refresh-key="lastCommand?.id"
            />
          </div>
          <ElEmpty v-else description="当前角色没有设备控制权限" :image-size="56" />
        </ElTabPane>
        <ElTabPane name="messages" label="消息日志" lazy>
          <ElTable v-loading="msgLoading" :data="messages" row-key="id" size="small">
            <ElTableColumn label="方向" width="70">
              <template #default="{ row }">
                <ElTag :type="row.direction === 'UP' ? 'success' : 'warning'" size="small">{{
                  row.direction === 'UP' ? '上行' : '下行'
                }}</ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn show-overflow-tooltip label="协议" width="70" prop="protocol" />
            <ElTableColumn label="Topic" min-width="180" prop="topic" show-overflow-tooltip />
            <ElTableColumn
              label="载荷"
              min-width="160"
              prop="payloadSummary"
              show-overflow-tooltip
            />
            <ElTableColumn label="时间" width="170">
              <template #default="{ row }">{{ formatTime(row.ts) }}</template>
            </ElTableColumn>
            <template #empty
              ><span style="color: var(--art-text-gray-500)"
                >暂无消息日志，设备上报数据后将自动记录</span
              ></template
            >
          </ElTable>
          <div v-if="msgHasMore" class="message-load-more">
            <ElButton :loading="msgLoading" text type="primary" @click="loadMessages(true)"
              >加载更多</ElButton
            >
          </div>
        </ElTabPane>
        <ElTabPane
          v-for="tab in capabilityTabs"
          :key="tab.key"
          :name="tab.key"
          :label="tab.label"
          lazy
        >
          <ElButton
            v-if="tab.key === 'credentials'"
            class="device-detail__manage-credentials"
            @click="openCredentials(detailDevice)"
            >管理凭据</ElButton
          >
          <DeviceCapabilityContent
            v-if="detailTab === tab.key"
            :project-id="projectId"
            :device="detailDevice"
            :kind="tab.key"
          />
        </ElTabPane>
        <ElTabPane v-if="hasAuth('enduser:read')" name="end-users" label="终端用户" lazy>
          <DeviceEndUsers
            v-if="detailTab === 'end-users' && projectId && detailTagDeviceId"
            :project-id="projectId"
            :device-id="detailTagDeviceId"
          />
        </ElTabPane>
        <ElTabPane v-if="hasAuth('task:read')" name="tasks" label="任务调度" lazy>
          <DeviceTasks
            v-if="detailTab === 'tasks' && projectId && detailTagDeviceId"
            :project-id="projectId"
            :device-id="detailTagDeviceId"
          />
        </ElTabPane>
        <ElTabPane v-if="hasAuth('rule:read')" name="automations" label="自动化" lazy>
          <DeviceAutomations
            v-if="detailTab === 'automations' && projectId && detailTagDeviceId"
            :project-id="projectId"
            :device-id="detailTagDeviceId"
            :can-manage="hasAuth('rule:manage')"
          />
        </ElTabPane>
        <ElTabPane v-if="hasAuth('rule:read')" name="scenes" label="场景" lazy>
          <DeviceScenes
            v-if="detailTab === 'scenes' && projectId && detailTagDeviceId"
            :project-id="projectId"
            :device-id="detailTagDeviceId"
            :can-manage="hasAuth('rule:manage')"
          />
        </ElTabPane>
        <ElTabPane v-if="hasAuth('rule:read')" name="message-rules" label="消息规则" lazy>
          <DeviceMessageRules
            v-if="detailTab === 'message-rules' && projectId && detailTagDeviceId"
            :project-id="projectId"
            :device-id="detailTagDeviceId"
            :can-manage="hasAuth('rule:manage')"
          />
        </ElTabPane>
        <ElTabPane name="settings" label="设置" lazy>
          <DeviceAccessConfiguration
            v-if="detailVisible"
            :project-id="projectId"
            :device-id="detailTagDeviceId"
          />
        </ElTabPane>
      </ElTabs>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessageBox } from 'element-plus'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { formatTime } from '@/utils/time'
  import { deviceStatusLabel, deviceStatusTag } from '@/utils/deviceStatus'
  import { toSeriesValue } from '@/utils/series'
  import { ArrowLeft, Plus } from '@element-plus/icons-vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import {
    fetchSearchDevices,
    fetchDeviceDetail,
    fetchDeviceTypePage,
    fetchDeviceEventDefinitions,
    fetchCreateDevice,
    fetchUpdateDevice,
    fetchDeleteDevice,
    fetchDeviceCredentials,
    fetchDeviceMessages,
    fetchDeviceConnections,
    fetchDeviceShadow,
    fetchBatchCurrentValues,
    fetchGenerateCredential,
    fetchRevokeCredential,
    fetchUpdateDesired,
    fetchDevicePropertyDefinitions,
    fetchDeviceCommandDefinitions,
    fetchSubmitDeviceCommand,
    fetchDeviceCommand,
    fetchPropertyHistory,
    type MessageLogResponse,
    type DeviceConnectionResponse,
    type DeviceCredentialResponse,
    type DeviceResponse,
    type DeviceEventDefinitionResponse,
    type DeviceTypeResponse,
    type DevicePropertyDefinitionResponse,
    type DeviceCommandDefinitionResponse,
    type DeviceCommandResponse,
    type SubmitDeviceCommandRequest
  } from '@/api/device'
  import { fetchDeviceGroups, type DeviceGroupResponse } from '@/api/device-group'
  import { useUserStore } from '@/store/modules/user'
  import { useAuth } from '@/hooks/core/useAuth'
  import { HttpError } from '@/utils/http/error'
  import { RealtimeClient, type PropertyBatchMessage } from '@/utils/realtime'
  import { ReportedValues, type ReportedProjection } from '@/features/device/reported-values'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { IdempotentSubmission } from '@/utils/idempotent-submission'
  import { usePagedDeviceCatalog } from '@/composables/usePagedDeviceCatalog'
  import { fetchBindingMetadata, createDesignerReadScope } from '@/api/dashboard-binding'
  import { fetchDesignerAlarms } from '@/api/dashboard-alarms'
  import type { DesignerReadScope } from '@/api/designer-read-scope'
  import DeviceCapabilityContent, {
    type DeviceCapability
  } from '../components/DeviceCapabilityContent.vue'
  import DeviceTagEditor from '../components/DeviceTagEditor.vue'
  import DeviceAlarmStatus from '../components/DeviceAlarmStatus.vue'
  import DeviceAccessConfiguration from '../components/DeviceAccessConfiguration.vue'
  import DeviceLocationPoint from '../components/DeviceLocationPoint.vue'
  import DeviceCommandHistory from '../components/DeviceCommandHistory.vue'
  import DeviceEndUsers from '../components/DeviceEndUsers.vue'
  import DeviceTasks from '../components/DeviceTasks.vue'
  import DeviceAutomations from '../components/DeviceAutomations.vue'
  import DeviceScenes from '../components/DeviceScenes.vue'
  import DeviceMessageRules from '../components/DeviceMessageRules.vue'
  import DeviceAdvancedFilter, {
    type DeviceAdvancedFilterModel
  } from '../components/DeviceAdvancedFilter.vue'

  defineOptions({ name: 'Devices' })
  type DeviceRowAction = 'detail' | 'credentials' | 'edit' | 'delete'

  const route = useRoute()
  const router = useRouter()
  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')
  const loading = ref(false),
    submitting = ref(false),
    formVisible = ref(false),
    editingId = ref('')
  const credVisible = ref(false),
    credLoading = ref(false),
    credDeviceName = ref('')
  const credentials = ref<DeviceCredentialResponse[]>([])
  const pendingSecret = ref('')
  const secretConfirmed = ref(false)
  let credDeviceId = ''
  const detailVisible = ref(false),
    shadowLoading = ref(false)
  const detailLoading = ref(false)
  const detailTab = ref('overview')
  const detailDevice = ref<DeviceResponse>()
  const detailDeviceType = ref<DeviceTypeResponse>()
  const capabilityTabs = computed(() => {
    const tabs: Array<{ key: DeviceCapability; label: string }> = []
    if (detailTypeId.value) tabs.push({ key: 'alarms', label: '告警' })
    if (hasAuth('device:update')) tabs.push({ key: 'credentials', label: '凭据' })
    if (userStore.info.roles?.some((role) => role === 'OWNER' || role === 'ADMIN'))
      tabs.push({ key: 'ota', label: '固件升级' })
    if (detailDeviceType.value?.deviceKind === 'GATEWAY' || detailDevice.value?.gatewayId)
      tabs.push({ key: 'topology', label: '拓扑' })
    if (
      ['STANDARD_GATEWAY', 'MODBUS_RTU_CLOUD_GATEWAY'].includes(
        detailDeviceType.value?.payloadProtocol ?? ''
      )
    )
      tabs.push({ key: 'modbus', label: 'Modbus 点位' })
    tabs.push({ key: 'diagnostics', label: '接入诊断' })
    return tabs
  })
  const detailEvents = ref<DeviceEventDefinitionResponse[]>([])
  const eventDefinitionsAvailable = ref(false)
  let detailNavigation = 0
  const detailTagDeviceId = ref('')
  const detailName = ref(''),
    detailDeviceKey = ref(''),
    detailTypeId = ref(''),
    detailStatus = ref('')
  const detailConnectionLabel = computed(() =>
    detailStatus.value === 'ONLINE'
      ? '在线'
      : ['OFFLINE', 'INACTIVE'].includes(detailStatus.value)
        ? '离线'
        : '状态未知'
  )
  const detailAlarmState = ref<'loading' | 'active' | 'none' | 'unavailable'>('loading')
  const detailAlarmLabel = computed(
    () =>
      ({
        loading: '告警查询中…',
        active: '有告警',
        none: '无告警',
        unavailable: '告警状态不可用'
      })[detailAlarmState.value]
  )
  let detailAlarmReadScope: DesignerReadScope | undefined
  const detailLocation = ref(''),
    detailDescription = ref('')
  const shadowDesired = ref(''),
    shadowReported = ref(''),
    shadowVersion = ref(0)
  const reported = new ReportedValues()
  const reportedSources = ref<Array<{ key: string; source: string; revision: string }>>([])
  let detailGeneration = 0
  let detailIdentity = currentIdentityEpoch()
  let currentRequest: Promise<void> | undefined
  let currentDirty = false
  let dirtyTimer: ReturnType<typeof setTimeout> | undefined
  // 身份与详情代次共同保护同项目 A→B→A；HTTP 的身份围栏不能替代详情代次。
  const detailScope = () => ({
    generation: detailGeneration,
    identity: currentIdentityEpoch(),
    project: projectId.value,
    device: detailDeviceId,
    type: detailTypeId.value
  })
  const currentDetail = (scope: ReturnType<typeof detailScope>) =>
    detailVisible.value &&
    scope.generation === detailGeneration &&
    scope.identity === currentIdentityEpoch() &&
    scope.project === projectId.value &&
    scope.device === detailDeviceId
  const realtimeStatus = ref<'connecting' | 'connected' | 'disconnected' | 'quota_exceeded'>(
    'disconnected'
  )
  const connLoading = ref(false),
    msgLoading = ref(false),
    connections = ref<DeviceConnectionResponse[]>([]),
    messages = ref<MessageLogResponse[]>([])
  const historyLoading = ref(false),
    historyPropertyKey = ref(''),
    historyRangeHours = ref(24),
    historyActualGranularity = ref('')
  const historyProperties = ref<DevicePropertyDefinitionResponse[]>([]),
    detailProperties = ref<DevicePropertyDefinitionResponse[]>([]),
    historyValues = ref<(number | null)[]>([]),
    historyTimes = ref<string[]>([])
  const commandDefinitions = ref<DeviceCommandDefinitionResponse[]>([]),
    commandKey = ref(''),
    commandInput = ref('{}'),
    commandSubmitting = ref(false),
    commandLoading = ref(false),
    lastCommand = ref<DeviceCommandResponse>()
  const msgCursor = ref<string>(),
    msgHasMore = ref(false)
  let detailDeviceId = ''
  const realtimeClient = new RealtimeClient({
    accessToken: () => userStore.accessToken,
    onStatus: (status) => (realtimeStatus.value = status),
    onConnected: () => loadCurrentValues(),
    onBatch: (message) => mergeRealtimeBatch(message)
  })
  // 命令 POST 的响应可能在后端受理后丢失；同一用户意图重试必须复用原键，成功或明确拒绝后才释放。
  const commandSubmission = new IdempotentSubmission()
  const items = ref<DeviceResponse[]>([])
  const { deviceTypes, deviceTypesLoading, loadDeviceTypes, onDeviceTypePopupScroll } =
    usePagedDeviceCatalog(projectId)
  const deviceGroups = ref<DeviceGroupResponse[]>([])
  const deviceCursor = ref<string>()
  const deviceHasMore = ref(false)
  const deviceFilter = ref<DeviceAdvancedFilterModel>({
    keyword: '',
    deviceTypeIds: [],
    statuses: [],
    groupId: '',
    tagKey: '',
    tagValue: ''
  })
  const typeMap = ref<Record<string, string>>({})
  const formRef = ref<FormInstance>()
  const form = reactive({
    name: '',
    deviceKey: '',
    deviceTypeId: '',
    description: '',
    location: ''
  })
  const granularityLabel = (value: string) =>
    ({ RAW: '原始点', ONE_MINUTE: '1 分钟', ONE_HOUR: '1 小时', ONE_DAY: '1 天' })[value] ?? value
  const commandStatusLabel = (value?: string) =>
    ({
      ACCEPTED: '已受理',
      DISPATCHED: '已发送',
      ACKNOWLEDGED: '设备已确认',
      SUCCEEDED: '执行成功',
      FAILED: '执行失败',
      TIMED_OUT: '执行超时'
    })[value ?? ''] ??
    value ??
    '—'
  const typeName = (id?: string) => (id ? (typeMap.value[id] ?? '—') : '—')
  const rules: FormRules = {
    name: [{ required: true, message: '请输入设备名称', trigger: 'blur' }],
    deviceKey: [
      { required: true, message: '请输入设备标识符', trigger: 'blur' },
      {
        pattern: /^[a-zA-Z0-9][a-zA-Z0-9_-]*$/,
        message: '以字母或数字开头，仅支持字母、数字、下划线和横线',
        trigger: 'blur'
      }
    ]
  }

  const filterTypeOptions = computed(() =>
    deviceTypes.value
      .filter((type): type is DeviceTypeResponse & { id: string; name: string } =>
        Boolean(type.id && type.name)
      )
      .map((type) => ({ id: type.id, name: type.name }))
  )
  const filterGroupOptions = computed(() =>
    deviceGroups.value
      .filter((group): group is DeviceGroupResponse & { id: string; name: string } =>
        Boolean(group.id && group.name)
      )
      .map((group) => ({ id: group.id, name: group.name }))
  )
  const deviceSearchQuery = (cursor?: string) => ({
    keyword: deviceFilter.value.keyword || undefined,
    deviceTypeIds: deviceFilter.value.deviceTypeIds,
    statuses: deviceFilter.value.statuses,
    groupId: deviceFilter.value.groupId || undefined,
    tagKey: deviceFilter.value.tagKey || undefined,
    tagValue: deviceFilter.value.tagValue || undefined,
    cursor,
    limit: 50
  })
  const alarmListRevision = ref(0)
  const loadDevices = async (append = false) => {
    if (!projectId.value) return
    loading.value = true
    try {
      const page = await fetchSearchDevices(
        projectId.value,
        deviceSearchQuery(append ? deviceCursor.value : undefined)
      )
      items.value = append ? [...items.value, ...(page.items ?? [])] : (page.items ?? [])
      if (!append) alarmListRevision.value++
      deviceCursor.value = page.nextCursor ?? undefined
      deviceHasMore.value = page.hasMore ?? false
    } finally {
      loading.value = false
    }
  }
  const load = async () => {
    if (!projectId.value) return
    loading.value = true
    try {
      const [, groups] = await Promise.all([loadDeviceTypes(), fetchDeviceGroups(projectId.value)])
      deviceGroups.value = groups
      const map: Record<string, string> = {}
      for (const t of deviceTypes.value) {
        if (t.id && t.name) map[t.id] = t.name
      }
      typeMap.value = map
      await loadDevices()
    } finally {
      loading.value = false
    }
  }
  const searchDevices = () => {
    if (Boolean(deviceFilter.value.tagKey) !== Boolean(deviceFilter.value.tagValue)) {
      ElMessage.warning('标签键和值必须同时填写')
      return
    }
    void loadDevices()
  }
  const resetDeviceFilter = () => {
    deviceFilter.value = {
      keyword: '',
      deviceTypeIds: [],
      statuses: [],
      groupId: '',
      tagKey: '',
      tagValue: ''
    }
    void loadDevices()
  }
  const openCreate = () => {
    editingId.value = ''
    Object.assign(form, {
      name: '',
      deviceKey: '',
      deviceTypeId: '',
      description: '',
      location: ''
    })
    formVisible.value = true
  }
  const openEdit = (row: DeviceResponse) => {
    if (!row.id) return
    editingId.value = row.id
    Object.assign(form, {
      name: row.name,
      deviceKey: row.deviceKey,
      deviceTypeId: row.deviceTypeId ?? '',
      description: row.description ?? '',
      location: row.location ?? ''
    })
    formVisible.value = true
  }
  const handleRowAction = (row: DeviceResponse, action: DeviceRowAction) => {
    if (action === 'detail') void openDetail(row)
    else if (action === 'credentials') void openCredentials(row)
    else if (action === 'edit') openEdit(row)
    else if (action === 'delete') void remove(row)
  }
  const submit = async () => {
    if (!formRef.value || !projectId.value || submitting.value) return
    // 校验本身也是 await；必须在它之前置位并防重入，否则慢网前的快速双击会启动两条写链。
    submitting.value = true
    const scope = detailScope()
    try {
      await formRef.value.validate()
      const body = {
        name: form.name,
        deviceTypeId: form.deviceTypeId || undefined,
        description: form.description || undefined,
        location: form.location || undefined
      }
      if (editingId.value) {
        const saved = await fetchUpdateDevice(projectId.value, editingId.value, body)
        if (currentDetail(scope) && scope.device === editingId.value && saved.id === scope.device)
          await loadDetail(saved)
      } else {
        await fetchCreateDevice(projectId.value, { ...body, deviceKey: form.deviceKey })
      }
      ElMessage.success(editingId.value ? '设备修改成功' : '设备创建成功')
      formVisible.value = false
      await load()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('保存设备失败:', error)
    } finally {
      submitting.value = false
    }
  }
  const remove = async (row: DeviceResponse) => {
    if (!projectId.value || !row.id) return
    try {
      await ElMessageBox.confirm(
        `确定删除设备“${row.name}”吗？删除后设备将从有效列表移除，当前无自助恢复入口，历史遥测与告警事实不会立即物理清除。`,
        '删除设备',
        { type: 'warning' }
      )
      await fetchDeleteDevice(projectId.value, row.id)
      ElMessage.success('设备已删除')
      await load()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError))
        console.error('删除设备失败:', error)
    }
  }
  const loadCredentials = async () => {
    if (!projectId.value || !credDeviceId) return
    credLoading.value = true
    try {
      credentials.value = await fetchDeviceCredentials(projectId.value, credDeviceId)
    } finally {
      credLoading.value = false
    }
  }
  const openCredentials = async (row: DeviceResponse) => {
    if (!row.id) return
    credDeviceId = row.id
    credDeviceName.value = row.name ?? ''
    pendingSecret.value = ''
    secretConfirmed.value = false
    credVisible.value = true
    await loadCredentials()
  }
  const generateCred = async () => {
    if (!projectId.value || !credDeviceId) return
    if (pendingSecret.value && !secretConfirmed.value) {
      ElMessage.warning('请先复制并确认已保存当前密钥')
      return
    }
    credLoading.value = true
    try {
      const result = await fetchGenerateCredential(projectId.value, credDeviceId)
      pendingSecret.value = result.plainSecret ?? ''
      secretConfirmed.value = false
      await loadCredentials()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('生成凭据失败:', error)
    } finally {
      credLoading.value = false
    }
  }
  const revokeCred = async (row: DeviceCredentialResponse) => {
    if (!projectId.value || !credDeviceId || !row.id) return
    try {
      await ElMessageBox.confirm(
        '确定作废该凭据吗？作废后使用该密钥的设备将无法连接。',
        '作废凭据',
        { type: 'warning' }
      )
      await fetchRevokeCredential(projectId.value, credDeviceId, row.id)
      ElMessage.success('凭据已作废')
      await loadCredentials()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError))
        console.error('作废凭据失败:', error)
    }
  }
  const copySecret = () => {
    navigator.clipboard.writeText(pendingSecret.value)
    ElMessage.success('密钥已复制到剪贴板')
  }
  const closeCredentials = (done: () => void) => {
    if (pendingSecret.value && !secretConfirmed.value) {
      ElMessage.warning('请先复制密钥并勾选“我已复制并安全保存该密钥”')
      return
    }
    pendingSecret.value = ''
    secretConfirmed.value = false
    done()
  }
  const openDetail = (row: DeviceResponse) => {
    if (row.id)
      return router.push({ path: route.path, query: { ...route.query, deviceId: row.id } })
  }
  const handleDeviceRowClick = (row: DeviceResponse, column: { columnKey?: string }) => {
    if (column.columnKey === 'actions') return
    void openDetail(row)
  }
  const returnToList = () => {
    const query = { ...route.query }
    delete query.deviceId
    return router.replace({ path: route.path, query })
  }
  const eventLevelLabel = (value?: string) =>
    ({ INFO: '信息', WARNING: '警告', ERROR: '错误' })[value ?? ''] ?? value ?? '—'
  const loadDetail = async (row: DeviceResponse) => {
    if (!row.id) return
    closeRealtime()
    detailIdentity = currentIdentityEpoch()
    detailDeviceId = row.id
    detailTagDeviceId.value = row.id
    detailDevice.value = row
    detailDeviceType.value = undefined
    detailTab.value = 'overview'
    detailEvents.value = []
    eventDefinitionsAvailable.value = false
    detailName.value = row.name ?? ''
    detailDeviceKey.value = row.deviceKey ?? ''
    detailTypeId.value = row.deviceTypeId ?? ''
    detailStatus.value = row.status ?? ''
    detailAlarmState.value = 'loading'
    detailLocation.value = row.location ?? ''
    detailDescription.value = row.description ?? ''
    messages.value = []
    connections.value = []
    msgCursor.value = undefined
    msgHasMore.value = false
    historyProperties.value = []
    detailProperties.value = []
    historyPropertyKey.value = ''
    historyValues.value = []
    historyTimes.value = []
    historyActualGranularity.value = ''
    reported.clear()
    shadowReported.value = ''
    shadowDesired.value = ''
    shadowVersion.value = 0
    reportedSources.value = []
    commandSubmitting.value = commandLoading.value = false
    historyLoading.value = shadowLoading.value = connLoading.value = msgLoading.value = false
    commandDefinitions.value = []
    commandKey.value = ''
    commandInput.value = '{}'
    lastCommand.value = undefined
    detailVisible.value = true
    // 详情接口互不依赖；单个接口失败不清空其他已读取的详情事实。
    await Promise.allSettled([
      loadShadow(),
      loadHistoryProperties(),
      loadCommandDefinitions(),
      loadEventDefinitions(),
      loadDetailDeviceType(),
      loadDetailAlarmStatus(),
      loadConnections(),
      loadMessages()
    ])
  }
  const loadDetailAlarmStatus = async () => {
    const detail = detailScope()
    const scope = createDesignerReadScope()
    detailAlarmReadScope = scope
    try {
      const metadata = await fetchBindingMetadata(detail.project, detail.device, scope)
      if (!currentDetail(detail)) return
      const result = await fetchDesignerAlarms(
        detail.project,
        {
          queryId: 'device-header-alarms',
          devices: [{ deviceId: detail.device, expectedModelVersionId: metadata.model.versionId }],
          conditionStates: ['ACTIVE'],
          ackStates: ['UNACKNOWLEDGED', 'ACKNOWLEDGED'],
          severities: ['CRITICAL', 'MAJOR', 'MINOR', 'WARNING', 'INFO'],
          limit: 1
        },
        'device-header',
        undefined,
        scope
      )
      if (currentDetail(detail))
        detailAlarmState.value =
          result.status === 'READY' ? (result.items.length ? 'active' : 'none') : 'unavailable'
    } catch {
      if (currentDetail(detail)) detailAlarmState.value = 'unavailable'
    } finally {
      scope.close()
      if (detailAlarmReadScope === scope) detailAlarmReadScope = undefined
    }
  }
  const loadDetailDeviceType = async () => {
    const scope = detailScope()
    if (!projectId.value || !detailTypeId.value) return
    const typeId = detailTypeId.value
    let type = deviceTypes.value.find((item) => item.id === typeId)
    let cursor: string | undefined
    const cursors = new Set<string>()
    // 当前合同只有类型目录分页读取；不能假设存在单条 GET，也不能只检查目录首页。
    while (!type && currentDetail(scope)) {
      const page = await fetchDeviceTypePage(scope.project, cursor)
      if (!currentDetail(scope)) return
      type = page.items?.find((item) => item.id === typeId)
      if (type || !page.hasMore || !page.nextCursor) break
      if (cursors.has(page.nextCursor)) throw new Error('设备类型目录游标重复')
      cursor = page.nextCursor
      cursors.add(cursor)
    }
    if (!currentDetail(scope) || !type) return
    detailDeviceType.value = type
    if (type.id && type.name) typeMap.value = { ...typeMap.value, [type.id]: type.name }
  }
  const loadEventDefinitions = async () => {
    const scope = detailScope()
    if (!detailTypeId.value) {
      eventDefinitionsAvailable.value = true
      return
    }
    if (!projectId.value) return
    const observed = await fetchDeviceEventDefinitions(projectId.value, detailTypeId.value)
    if (currentDetail(scope)) {
      detailEvents.value = observed
      eventDefinitionsAvailable.value = true
    }
  }
  const loadCommandDefinitions = async () => {
    const scope = detailScope()
    if (!projectId.value || !detailTypeId.value) return
    const observed = await fetchDeviceCommandDefinitions(projectId.value, detailTypeId.value)
    if (!currentDetail(scope)) return
    commandDefinitions.value = observed
    commandKey.value = commandDefinitions.value[0]?.commandKey ?? ''
  }
  const submitCommand = async () => {
    const scope = detailScope()
    if (commandSubmitting.value) return
    if (!projectId.value || !detailDeviceId || !commandKey.value) {
      ElMessage.warning('请先选择命令')
      return
    }
    let input: SubmitDeviceCommandRequest['input']
    try {
      input = JSON.parse(commandInput.value) as SubmitDeviceCommandRequest['input']
    } catch {
      ElMessage.warning('命令参数必须是合法 JSON')
      return
    }
    const fingerprint = JSON.stringify([projectId.value, detailDeviceId, commandKey.value, input])
    const idempotencyKey = commandSubmission.keyFor(fingerprint)
    commandSubmitting.value = true
    try {
      const observed = await fetchSubmitDeviceCommand(
        projectId.value,
        detailDeviceId,
        { commandKey: commandKey.value, input },
        idempotencyKey
      )
      if (!currentDetail(scope)) return
      lastCommand.value = observed
      commandSubmission.succeeded(idempotencyKey)
      ElMessage.success('命令已受理')
    } catch (error) {
      if (!currentDetail(scope)) return
      // 只有网络/超时/取消属于“服务端可能已经提交”；明确 ApiError 可安全释放本次键。
      commandSubmission.failed(
        idempotencyKey,
        error instanceof HttpError ? error.outcomeUnknown : true
      )
      if (!(error instanceof HttpError)) console.error('下发命令失败:', error)
    } finally {
      if (currentDetail(scope)) commandSubmitting.value = false
    }
  }
  const refreshCommand = async () => {
    const scope = detailScope()
    if (!projectId.value || !detailDeviceId || !lastCommand.value?.id) return
    commandLoading.value = true
    try {
      const observed = await fetchDeviceCommand(
        projectId.value,
        detailDeviceId,
        lastCommand.value.id
      )
      if (!currentDetail(scope)) return
      lastCommand.value = observed
    } finally {
      if (currentDetail(scope)) commandLoading.value = false
    }
  }
  const loadHistoryProperties = async () => {
    const scope = detailScope()
    if (!projectId.value || !detailTypeId.value) return
    const properties = await fetchDevicePropertyDefinitions(projectId.value, detailTypeId.value)
    if (!currentDetail(scope)) return
    detailProperties.value = properties
    historyProperties.value = properties.filter((property) => property.dataType === 'NUMBER')
    historyPropertyKey.value = historyProperties.value[0]?.propertyKey ?? ''
    const propertyKeys = properties
      .map((property) => property.propertyKey)
      .filter((key): key is string => Boolean(key))
    realtimeClient.connect([{ deviceId: detailDeviceId, propertyKeys }])
    await Promise.allSettled([loadCurrentValues(), loadHistory()])
  }
  const loadCurrentValues = (): Promise<void> => {
    if (!projectId.value || !detailDeviceId || !detailVisible.value) return Promise.resolve()
    if (currentRequest) {
      currentDirty = true
      return currentRequest
    }
    const scope = detailScope()
    const propertyKeys = detailProperties.value
      .map((property) => property.propertyKey)
      .filter((key): key is string => Boolean(key))
    if (!propertyKeys.length) return Promise.resolve()
    const execute = async () => {
      do {
        currentDirty = false
        const result = await fetchBatchCurrentValues(scope.project, {
          deviceIds: [scope.device],
          propertyKeys
        })
        if (!currentDetail(scope)) return
        const items = result.items ?? []
        if (items.length !== 1 || items[0]?.deviceId !== scope.device)
          throw new Error('当前值响应设备身份不匹配')
        const item = items[0]
        reported.merge(item as ReportedProjection, propertyKeys)
        shadowVersion.value = Math.max(shadowVersion.value, item?.version ?? 0)
        renderReportedValues()
      } while (currentDirty && currentDetail(scope))
    }
    const pending = execute().finally(() => {
      if (currentRequest === pending) currentRequest = undefined
    })
    currentRequest = pending
    return pending
  }
  const mergeRealtimeBatch = (message: PropertyBatchMessage) => {
    if (
      !detailVisible.value ||
      message.projectId !== projectId.value ||
      message.deviceId !== detailDeviceId
    )
      return
    const keys = detailProperties.value
      .map((property) => property.propertyKey)
      .filter((key): key is string => Boolean(key))
    const dirty = reported.merge(
      {
        values: message.properties,
        occurredAt: Object.fromEntries(
          Object.keys(message.properties).map((key) => [key, message.occurredAt])
        ),
        reportedRevisions: message.reportedRevisions,
        thingModelVersionIds: message.thingModelVersionIds
      },
      keys,
      true
    )
    if (Number.isInteger(message.shadowVersion) && message.shadowVersion >= 0)
      shadowVersion.value = Math.max(shadowVersion.value, message.shadowVersion)
    renderReportedValues()
    // 旧无序号消息不直写值；每秒合并一次补拉，在途消息保留下一次读取意图。
    if (dirty && !dirtyTimer) {
      const scope = detailScope()
      dirtyTimer = setTimeout(() => {
        dirtyTimer = undefined
        if (currentDetail(scope))
          void loadCurrentValues().catch(() => {
            /* HTTP 已显示真实读取错误。 */
          })
      }, 1000)
    }
  }
  const renderReportedValues = () => {
    shadowReported.value = JSON.stringify(reported.values(), null, 2)
    reportedSources.value = reported.entries().map(([key, fact]) => ({
      key,
      source: fact.thingModelVersionId ?? '历史来源未知',
      revision: fact.reportedRevision ?? '历史顺序未知'
    }))
  }
  const closeRealtime = () => {
    detailGeneration++
    detailAlarmReadScope?.close()
    detailAlarmReadScope = undefined
    realtimeClient.close()
    currentRequest = undefined
    currentDirty = false
    if (dirtyTimer) clearTimeout(dirtyTimer)
    dirtyTimer = undefined
    reported.clear()
    reportedSources.value = []
    shadowReported.value = ''
  }
  const loadHistory = async () => {
    const scope = detailScope()
    if (!projectId.value || !detailDeviceId || !historyPropertyKey.value) return
    historyLoading.value = true
    try {
      const to = new Date()
      const from = new Date(to.getTime() - historyRangeHours.value * 60 * 60 * 1000)
      const result = await fetchPropertyHistory(projectId.value, detailDeviceId, {
        propertyKey: historyPropertyKey.value,
        from: from.toISOString(),
        to: to.toISOString(),
        granularity: 'RAW',
        aggregation: 'AVG'
      })
      if (!currentDetail(scope)) return
      historyValues.value = (result.points ?? []).map((point) => toSeriesValue(point.value))
      historyTimes.value = (result.points ?? []).map((point) =>
        point.ts ? formatTime(point.ts) : '—'
      )
      historyActualGranularity.value = result.actualGranularity ?? ''
    } catch (error) {
      if (!currentDetail(scope)) return
      if (!(error instanceof HttpError)) console.error('加载历史曲线失败:', error)
    } finally {
      if (currentDetail(scope)) historyLoading.value = false
    }
  }
  const loadMessages = async (append = false) => {
    const scope = detailScope()
    if (!projectId.value || !detailDeviceId) return
    msgLoading.value = true
    try {
      const page = await fetchDeviceMessages(
        projectId.value,
        detailDeviceId,
        append ? msgCursor.value : undefined
      )
      if (!currentDetail(scope)) return
      messages.value = append ? [...messages.value, ...(page.items ?? [])] : (page.items ?? [])
      msgCursor.value = page.nextCursor ?? undefined
      msgHasMore.value = page.hasMore ?? false
    } catch (error) {
      if (!currentDetail(scope)) return
      if (!(error instanceof HttpError)) console.error('加载消息日志失败:', error)
    } finally {
      if (currentDetail(scope)) msgLoading.value = false
    }
  }
  const loadConnections = async () => {
    const scope = detailScope()
    if (!projectId.value || !detailDeviceId) return
    connLoading.value = true
    try {
      const observed = await fetchDeviceConnections(projectId.value, detailDeviceId)
      if (!currentDetail(scope)) return
      connections.value = observed
    } finally {
      if (currentDetail(scope)) connLoading.value = false
    }
  }
  const loadShadow = async () => {
    const scope = detailScope()
    if (!projectId.value || !detailDeviceId) return
    shadowLoading.value = true
    try {
      const s = await fetchDeviceShadow(projectId.value, detailDeviceId)
      if (!currentDetail(scope)) return
      shadowDesired.value = s.desired ?? ''
      shadowVersion.value = s.version ?? 0
    } catch (error) {
      if (!currentDetail(scope)) return
      if (!(error instanceof HttpError)) console.error('加载影子失败:', error)
    } finally {
      if (currentDetail(scope)) shadowLoading.value = false
    }
  }
  const saveDesired = async () => {
    const scope = detailScope()
    if (!projectId.value || !detailDeviceId) return
    shadowLoading.value = true
    try {
      const result = await fetchUpdateDesired(projectId.value, detailDeviceId, {
        desired: shadowDesired.value,
        version: shadowVersion.value
      })
      if (!currentDetail(scope)) return
      shadowVersion.value = result.version ?? 0
      ElMessage.success('期望状态已更新')
    } catch (error) {
      if (!currentDetail(scope)) return
      if (error instanceof HttpError && error.code === 30023) {
        ElMessage.warning('影子版本冲突，请刷新后重试')
        await loadShadow()
      } else console.error('更新期望状态失败:', error)
    } finally {
      if (currentDetail(scope)) shadowLoading.value = false
    }
  }
  watch(
    [projectId, () => userStore.info.userId],
    () => {
      detailNavigation += 1
      detailVisible.value = false
      closeRealtime()
      if (route.query.deviceId) void returnToList()
    },
    { flush: 'sync' }
  )
  watch(
    () => userStore.accessToken,
    () => {
      if (detailIdentity !== currentIdentityEpoch()) {
        detailNavigation += 1
        detailVisible.value = false
        closeRealtime()
        if (route.query.deviceId) void returnToList()
      }
    },
    { flush: 'sync' }
  )
  watch(
    () => route.query.deviceId,
    async (value) => {
      const navigation = ++detailNavigation
      closeRealtime()
      detailDevice.value = undefined
      detailVisible.value = typeof value === 'string' && Boolean(value)
      detailLoading.value = false
      if (!detailVisible.value || typeof value !== 'string' || !projectId.value) return
      const identity = currentIdentityEpoch()
      const project = projectId.value
      detailLoading.value = true
      try {
        const device = await fetchDeviceDetail(project, value)
        if (
          navigation !== detailNavigation ||
          identity !== currentIdentityEpoch() ||
          project !== projectId.value
        )
          return
        await loadDetail(device)
      } catch (error) {
        if (navigation === detailNavigation && !(error instanceof HttpError))
          console.error('加载设备详情失败:', error)
        if (navigation === detailNavigation && identity === currentIdentityEpoch())
          void returnToList()
      } finally {
        if (navigation === detailNavigation) detailLoading.value = false
      }
    },
    { immediate: true }
  )
  onMounted(load)
  onUnmounted(() => {
    detailNavigation += 1
    closeRealtime()
  })
</script>

<style lang="scss" scoped>
  .device-list {
    display: flex;
    flex-direction: column;
    padding: 10px;
    &__content {
      display: flex;
      flex: 1;
      flex-direction: column;
    }
    &__data {
      flex: 1;
    }
    &--detail {
      box-sizing: border-box;
      display: flex;
      flex-direction: column;
      min-height: var(--art-full-height);
      padding: 10px;
    }
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
    &__filter {
      margin-bottom: 10px;
    }
    &__more {
      display: flex;
      justify-content: center;
      padding-top: 12px;
    }
  }
  .device-list__actions-trigger.el-button {
    width: 36px;
    height: 36px;
    padding: 0;
    font-size: 18px;
    color: var(--el-text-color-regular);
    background-color: var(--el-fill-color) !important;
    border-color: transparent !important;
    border-radius: 7px;
    outline: none !important;
    box-shadow: none !important;

    &:hover,
    &:focus-visible {
      background-color: color-mix(
        in srgb,
        var(--el-fill-color),
        var(--el-text-color-regular) 4%
      ) !important;
    }
  }
  :global(.device-list-actions-menu.el-popper) {
    border-radius: 8px;
    box-shadow: 0 4px 16px rgb(0 0 0 / 12%);
  }
  :global(.device-list-actions-menu .el-dropdown-menu) {
    min-width: 120px;
    padding: 6px;
  }
  :global(.device-list-actions-menu .el-dropdown-menu__item) {
    gap: 8px;
    min-height: 34px;
    padding: 0 12px;
    border-radius: 4px;
  }
  :global(.device-list-actions-menu .device-list__danger-action) {
    color: var(--el-color-danger);
  }
  :deep(.device-list__clickable-row) {
    cursor: pointer;
  }
  .device-detail {
    flex: 1 0 auto;
    width: 100%;
    &__heading {
      display: flex;
      gap: 10px;
      align-items: center;
      font-size: 22px;
      h2 {
        min-width: 0;
        margin: 0;
        font-size: 20px;
        font-weight: 400;
        overflow-wrap: anywhere;
      }
    }
    &__back {
      flex-shrink: 0;
    }
    &__separator {
      flex-shrink: 0;
      width: 1px;
      height: 16px;
      background-color: var(--el-border-color);
    }
    &__icon {
      flex-shrink: 0;
      color: var(--el-text-color-secondary);
    }
    &__edit {
      flex-shrink: 0;
    }
    &__status {
      display: inline-flex;
      gap: 6px;
      align-items: center;
      &.is-online {
        color: var(--el-color-success);
      }
      &.has-alarm {
        color: var(--el-color-danger);
      }
    }
    &__metadata {
      display: flex;
      flex-wrap: wrap;
      gap: 12px 24px;
      align-items: center;
      margin: 14px 0 24px;
      font-size: 13px;
      color: var(--el-text-color-secondary);
      span {
        overflow-wrap: anywhere;
      }
    }
    &__manage-credentials {
      margin-bottom: 10px;
    }
    &__tabs {
      min-height: 360px;
    }
  }
  .form-control {
    width: 100%;
  }
  .command-panel {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .command-toolbar {
    display: flex;
    gap: 8px;
  }
  .command-result {
    margin-top: 4px;
  }
  .form-help {
    margin-top: 4px;
    font-size: 12px;
    color: var(--art-text-gray-600);
  }
  .cred-warn {
    margin-bottom: 10px;
  }
  .cred-secret {
    margin-top: 8px;
  }
  .cred-confirm {
    margin-top: 10px;
  }
  .shadow-section {
    margin-bottom: 10px;
    &__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 6px;
      font-weight: 500;
    }
  }
  .shadow-version {
    font-size: 12px;
    color: var(--art-text-gray-600);
  }
  .realtime-status {
    margin-bottom: 8px;
  }
  .history-toolbar {
    display: flex;
    gap: 10px;
    align-items: center;
    margin-bottom: 10px;
    .el-select {
      width: 160px;
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
