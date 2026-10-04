<template>
  <div class="console-page device-groups console-page--single-panel">
    <div class="device-groups__header console-toolbar console-page-actions">
      <ElButton v-if="hasAuth('device:create')" type="primary" :icon="Plus" @click="openCreate">
        创建设备组
      </ElButton>
    </div>

    <ElCard shadow="never" class="console-table-panel console-page__main-panel">
      <ElTable v-loading="loading" :data="groups" row-key="id">
        <ElTableColumn show-overflow-tooltip prop="name" label="名称" min-width="160" />
        <ElTableColumn label="类型" width="110">
          <template #default="{ row }">
            <ElTag :type="row.type === 'DYNAMIC' ? 'warning' : 'info'">
              {{ row.type === 'DYNAMIC' ? '动态组' : '静态组' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="成员规则" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">{{ ruleSummary(row) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="description" label="说明" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn
          class-name="console-table-actions-cell"
          label="操作"
          width="144"
          fixed="right"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="openMembers(row)"
              :label="row.type === 'STATIC' && hasAuth('device:update') ? '维护成员' : '查看设备'"
              icon="ri:group-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('device:update')"
              type="primary"
              @click="openEdit(row)"
              label="编辑"
              icon="ri:pencil-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('device:delete')"
              type="danger"
              @click="removeGroup(row)"
              label="删除"
              icon="ri:delete-bin-5-line"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有设备组" /></template>
      </ElTable>
    </ElCard>

    <ElDialog
      class="console-dialog"
      v-model="formVisible"
      :title="editingId ? '编辑设备组' : '创建设备组'"
      width="680px"
      destroy-on-close
    >
      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top">
        <ElFormItem label="组名称" prop="name">
          <ElInput v-model.trim="form.name" maxlength="128" />
        </ElFormItem>
        <ElFormItem label="组类型" prop="type">
          <ElRadioGroup v-model="form.type" :disabled="!!editingId">
            <ElRadioButton value="STATIC">静态组</ElRadioButton>
            <ElRadioButton value="DYNAMIC">动态组</ElRadioButton>
          </ElRadioGroup>
          <div class="form-help">创建后类型不可修改，避免静态成员被静默解释为动态结果。</div>
        </ElFormItem>
        <template v-if="form.type === 'DYNAMIC'">
          <ElDivider content-position="left">动态规则（条件之间为 AND）</ElDivider>
          <ElFormItem label="设备类型">
            <ElSelect
              v-model="form.deviceTypeIds"
              class="form-control"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="不限制设备类型"
              :loading="deviceTypesLoading"
              @popup-scroll="onDeviceTypePopupScroll"
            >
              <ElOption
                v-for="type in deviceTypes"
                :key="type.id ?? ''"
                :label="type.name"
                :value="type.id ?? ''"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="设备状态">
            <ElSelect
              v-model="form.statuses"
              class="form-control"
              multiple
              placeholder="不限制设备状态"
            >
              <ElOption label="未激活" value="INACTIVE" />
              <ElOption label="在线" value="ONLINE" />
              <ElOption label="离线" value="OFFLINE" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="标签条件">
            <div class="tag-rule-list">
              <div v-for="(tag, index) in form.tags" :key="index" class="tag-rule-row">
                <ElInput v-model.trim="tag.key" maxlength="64" placeholder="标签键" />
                <ElInput v-model.trim="tag.value" maxlength="128" placeholder="标签值" />
                <ElButton link type="danger" @click="form.tags.splice(index, 1)">删除</ElButton>
              </div>
              <ElButton :icon="Plus" @click="form.tags.push({ key: '', value: '' })">
                添加标签条件
              </ElButton>
            </div>
          </ElFormItem>
          <ElFormItem v-if="form.tags.length" label="多标签匹配">
            <ElRadioGroup v-model="form.tagMatch">
              <ElRadio value="ALL">全部标签命中</ElRadio>
              <ElRadio value="ANY">任一标签命中</ElRadio>
            </ElRadioGroup>
          </ElFormItem>
        </template>
        <ElFormItem label="说明">
          <ElInput v-model.trim="form.description" type="textarea" :rows="3" maxlength="500" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="formVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitGroup">确定</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      class="console-dialog"
      v-model="membersVisible"
      :title="`${selectedGroup?.name ?? ''} · ${selectedGroup?.type === 'STATIC' ? '静态成员' : '动态匹配设备'}`"
      width="720px"
    >
      <div v-loading="membersLoading">
        <template v-if="selectedGroup?.type === 'STATIC' && hasAuth('device:update')">
          <ElAlert
            class="member-alert"
            type="info"
            :closable="false"
            title="保存会原子替换该组的完整成员集合，未选中的设备会被移出。"
          />
          <ElSelect
            v-model="memberIds"
            class="form-control"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            remote
            :remote-method="searchDevices"
            :loading="devicesLoading"
            placeholder="输入名称或标识搜索项目设备"
            @popup-scroll="onDevicePopupScroll"
          >
            <ElOption
              v-for="device in allDevices"
              :key="device.id ?? ''"
              :label="`${device.name ?? '未命名设备'}（${device.deviceKey ?? '—'}）`"
              :value="device.id ?? ''"
            />
          </ElSelect>
        </template>
        <ElTable v-else :data="memberDevices" row-key="id" size="small">
          <ElTableColumn show-overflow-tooltip prop="name" label="设备名称" min-width="180" />
          <ElTableColumn show-overflow-tooltip prop="deviceKey" label="标识符" min-width="160" />
          <ElTableColumn label="状态" width="100">
            <template #default="{ row }">{{ statusLabel(row.status) }}</template>
          </ElTableColumn>
          <template #empty><ElEmpty description="当前没有匹配设备" :image-size="64" /></template>
        </ElTable>
        <div v-if="memberHasMore" class="member-more">
          <ElButton :loading="membersLoading" text type="primary" @click="loadMemberPage(true)">
            加载更多成员
          </ElButton>
        </div>
      </div>
      <template #footer>
        <ElButton @click="membersVisible = false">关闭</ElButton>
        <ElButton
          v-if="selectedGroup?.type === 'STATIC' && hasAuth('device:update')"
          type="primary"
          :loading="membersSaving"
          :disabled="memberHasMore"
          @click="saveMembers"
        >
          保存成员
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { formatTime } from '@/utils/time'
  import { Plus } from '@element-plus/icons-vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import {
    fetchCreateDeviceGroup,
    fetchDeleteDeviceGroup,
    fetchDeviceGroups,
    fetchReplaceDeviceGroupMembers,
    fetchUpdateDeviceGroup,
    type DeviceGroupResponse,
    type GroupDeviceResponse,
    type SaveDeviceGroupRequest
  } from '@/api/device-group'
  import { fetchSearchDevices } from '@/api/device'
  import { usePagedDeviceCatalog } from '@/composables/usePagedDeviceCatalog'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'DeviceGroups' })

  type GroupType = 'STATIC' | 'DYNAMIC'
  type DeviceStatus = 'INACTIVE' | 'ONLINE' | 'OFFLINE'
  type TagMatch = 'ANY' | 'ALL'

  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')
  const groups = ref<DeviceGroupResponse[]>([])
  const {
    devices: allDevices,
    deviceTypes,
    devicesLoading,
    deviceTypesLoading,
    loadDevices,
    searchDevices,
    ensureDevices,
    onDevicePopupScroll,
    loadDeviceTypes,
    onDeviceTypePopupScroll
  } = usePagedDeviceCatalog(projectId)
  const loading = ref(false)
  const submitting = ref(false)
  const formVisible = ref(false)
  const editingId = ref('')
  const formRef = ref<FormInstance>()
  const form = reactive({
    name: '',
    description: '',
    type: 'STATIC' as GroupType,
    deviceTypeIds: [] as string[],
    statuses: [] as DeviceStatus[],
    tags: [] as Array<{ key: string; value: string }>,
    tagMatch: 'ALL' as TagMatch
  })
  const rules: FormRules = {
    name: [{ required: true, message: '请输入设备组名称', trigger: 'blur' }],
    type: [{ required: true, message: '请选择设备组类型', trigger: 'change' }]
  }

  const membersVisible = ref(false)
  const membersLoading = ref(false)
  const membersSaving = ref(false)
  const selectedGroup = ref<DeviceGroupResponse>()
  const memberDevices = ref<GroupDeviceResponse[]>([])
  const memberIds = ref<string[]>([])
  const memberCursor = ref<string>()
  const memberHasMore = ref(false)

  const statusLabels: Record<string, string> = {
    INACTIVE: '未激活',
    ONLINE: '在线',
    OFFLINE: '离线'
  }
  const statusLabel = (status?: string) => statusLabels[status ?? ''] ?? status ?? '—'
  const typeName = (id: string) => deviceTypes.value.find((item) => item.id === id)?.name ?? id

  /** 把结构化动态规则压缩为可扫描摘要，不向用户暴露 JSON。 */
  const ruleSummary = (group: DeviceGroupResponse) => {
    if (group.type === 'STATIC') return '显式维护成员'
    const parts: string[] = []
    const rule = group.rule
    if (rule?.deviceTypeIds?.length) {
      parts.push(`类型：${rule.deviceTypeIds.map(typeName).join('、')}`)
    }
    if (rule?.statuses?.length) {
      parts.push(`状态：${rule.statuses.map(statusLabel).join('、')}`)
    }
    const tags = Object.entries(rule?.tags ?? {})
    if (tags.length) {
      parts.push(
        `标签（${rule?.tagMatch === 'ANY' ? '任一' : '全部'}）：${tags
          .map(([key, value]) => `${key}=${value}`)
          .join('、')}`
      )
    }
    return parts.join('；') || '规则为空'
  }

  /** 同时读取组和类型目录，类型目录用于动态规则选择与摘要渲染。 */
  const load = async () => {
    if (!projectId.value) return
    loading.value = true
    try {
      const [groupResult] = await Promise.all([
        fetchDeviceGroups(projectId.value),
        loadDeviceTypes()
      ])
      groups.value = groupResult
    } finally {
      loading.value = false
    }
  }

  /** 重置创建表单；动态条件不得沿用上一次编辑内容。 */
  const resetForm = () => {
    Object.assign(form, {
      name: '',
      description: '',
      type: 'STATIC' as GroupType,
      deviceTypeIds: [],
      statuses: [],
      tags: [],
      tagMatch: 'ALL' as TagMatch
    })
    formRef.value?.clearValidate()
  }

  const openCreate = () => {
    editingId.value = ''
    resetForm()
    formVisible.value = true
  }

  /** 编辑时回填结构化规则；组类型由后端冻结，界面同步禁用切换。 */
  const openEdit = (group: DeviceGroupResponse) => {
    if (!group.id || !group.type) return
    editingId.value = group.id
    Object.assign(form, {
      name: group.name ?? '',
      description: group.description ?? '',
      type: group.type,
      deviceTypeIds: [...(group.rule?.deviceTypeIds ?? [])],
      statuses: [...(group.rule?.statuses ?? [])],
      tags: Object.entries(group.rule?.tags ?? {}).map(([key, value]) => ({ key, value })),
      tagMatch: group.rule?.tagMatch ?? 'ALL'
    })
    formVisible.value = true
  }

  /** 校验并转换动态规则；空规则和重复标签键在发请求前明确反馈。 */
  const buildRule = (): SaveDeviceGroupRequest['rule'] => {
    if (form.type === 'STATIC') return undefined
    const invalidTag = form.tags.some(
      (tag) => !/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(tag.key) || !tag.value
    )
    if (invalidTag) throw new Error('标签条件的键值不完整或标签键格式不正确')
    if (new Set(form.tags.map((tag) => tag.key)).size !== form.tags.length) {
      throw new Error('动态规则不能包含重复标签键')
    }
    if (!form.deviceTypeIds.length && !form.statuses.length && !form.tags.length) {
      throw new Error('动态组至少需要一个设备类型、状态或标签条件')
    }
    return {
      deviceTypeIds: form.deviceTypeIds,
      statuses: form.statuses,
      tags: Object.fromEntries(form.tags.map((tag) => [tag.key, tag.value])),
      tagMatch: form.tags.length ? form.tagMatch : undefined
    }
  }

  const submitGroup = async () => {
    if (!formRef.value || !projectId.value) return
    try {
      await formRef.value.validate()
      const body: SaveDeviceGroupRequest = {
        name: form.name,
        description: form.description || undefined,
        type: form.type,
        rule: buildRule()
      }
      submitting.value = true
      if (editingId.value) {
        await fetchUpdateDeviceGroup(projectId.value, editingId.value, body)
      } else {
        await fetchCreateDeviceGroup(projectId.value, body)
      }
      ElMessage.success(editingId.value ? '设备组已更新' : '设备组已创建')
      formVisible.value = false
      await load()
    } catch (error) {
      if (error instanceof Error && !(error instanceof HttpError) && error.message) {
        ElMessage.warning(error.message)
      }
    } finally {
      submitting.value = false
    }
  }

  const removeGroup = async (group: DeviceGroupResponse) => {
    if (!projectId.value || !group.id) return
    try {
      await ElMessageBox.confirm(`确定删除设备组“${group.name ?? ''}”吗？`, '删除设备组', {
        type: 'warning'
      })
      await fetchDeleteDeviceGroup(projectId.value, group.id)
      ElMessage.success('设备组已删除')
      await load()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError)) {
        console.error('删除设备组失败:', error)
      }
    }
  }

  /** 按组条件读取一页权威成员；静态组未读完前禁止整集替换，避免把未加载成员误删。 */
  const loadMemberPage = async (append = false) => {
    if (!projectId.value || !selectedGroup.value?.id) return
    membersLoading.value = true
    try {
      const page = await fetchSearchDevices(projectId.value, {
        groupId: selectedGroup.value.id,
        cursor: append ? memberCursor.value : undefined,
        limit: 50
      })
      const incoming = page.items ?? []
      memberDevices.value = append ? [...memberDevices.value, ...incoming] : incoming
      memberCursor.value = page.nextCursor ?? undefined
      memberHasMore.value = page.hasMore ?? false
      memberIds.value = memberDevices.value
        .map((device) => device.id)
        .filter((id): id is string => Boolean(id))
      await ensureDevices(memberIds.value)
    } finally {
      membersLoading.value = false
    }
  }

  /** 静态组候选设备按需远程搜索，动态组成员同样使用键集页。 */
  const openMembers = async (group: DeviceGroupResponse) => {
    if (!projectId.value || !group.id) return
    selectedGroup.value = group
    memberDevices.value = []
    memberIds.value = []
    memberCursor.value = undefined
    memberHasMore.value = false
    membersVisible.value = true
    await Promise.all([
      loadMemberPage(),
      group.type === 'STATIC' && hasAuth('device:update') ? loadDevices() : Promise.resolve()
    ])
  }

  /** 后端在同一事务中整集替换，前端成功后再刷新展示事实。 */
  const saveMembers = async () => {
    if (!projectId.value || !selectedGroup.value?.id) return
    membersSaving.value = true
    try {
      await fetchReplaceDeviceGroupMembers(projectId.value, selectedGroup.value.id, {
        deviceIds: memberIds.value
      })
      ElMessage.success('静态组成员已更新')
      membersVisible.value = false
    } finally {
      membersSaving.value = false
    }
  }

  onMounted(load)
</script>

<style lang="scss" scoped>
  .device-groups {
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
  }
  .form-control {
    width: 100%;
  }
  .form-help {
    margin-top: 4px;
    font-size: 12px;
    color: var(--art-text-gray-600);
  }
  .tag-rule-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
    width: 100%;
  }
  .member-more {
    display: flex;
    justify-content: center;
    padding-top: 12px;
  }
  .tag-rule-row {
    display: grid;
    grid-template-columns: 1fr 1.5fr auto;
    gap: 8px;
  }
  .member-alert {
    margin-bottom: 10px;
  }
</style>
