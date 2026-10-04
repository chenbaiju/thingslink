<template>
  <div class="device-tags console-fragment" v-loading="loading">
    <div class="device-tags__header console-page-header">
      <div>
        <strong>设备标签</strong>
        <p class="console-description"
          >标签键在单台设备内唯一，动态设备组会按这里的最新键值实时筛选。</p
        >
      </div>
    </div>

    <ElForm
      v-if="editable"
      ref="formRef"
      :model="form"
      :rules="rules"
      class="device-tags__form"
      label-position="top"
    >
      <ElFormItem label="标签键" prop="key">
        <ElInput v-model.trim="form.key" maxlength="64" placeholder="例如 region" />
      </ElFormItem>
      <ElFormItem label="标签值" prop="value">
        <ElInput v-model.trim="form.value" maxlength="128" placeholder="例如 east" />
      </ElFormItem>
      <ElFormItem class="device-tags__submit">
        <div class="console-actions">
          <ElButton type="primary" :loading="saving" @click="saveTag">保存标签</ElButton>
          <ElButton v-if="form.key || form.value" @click="resetForm">清空</ElButton>
        </div>
      </ElFormItem>
    </ElForm>

    <ElTable :data="tags" row-key="id" size="small">
      <ElTableColumn show-overflow-tooltip prop="key" label="标签键" min-width="160" />
      <ElTableColumn prop="value" label="标签值" min-width="200" show-overflow-tooltip />
      <ElTableColumn
        class-name="console-table-actions-cell"
        v-if="editable"
        label="操作"
        width="104"
        fixed="right"
      >
        <template #default="{ row }">
          <ConsoleTableAction
            type="primary"
            @click="editTag(row)"
            label="编辑"
            icon="ri:pencil-line"
          />
          <ConsoleTableAction
            type="danger"
            @click="removeTag(row)"
            label="删除"
            icon="ri:delete-bin-5-line"
          />
        </template>
      </ElTableColumn>
      <template #empty><ElEmpty description="还没有设备标签" :image-size="56" /></template>
    </ElTable>
  </div>
</template>

<script setup lang="ts">
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import type { FormInstance, FormRules } from 'element-plus'
  import {
    fetchDeleteDeviceTag,
    fetchDeviceTags,
    fetchPutDeviceTag,
    type DeviceTagResponse
  } from '@/api/device-group'
  import { HttpError } from '@/utils/http/error'

  const props = defineProps<{
    projectId: string
    deviceId: string
    editable: boolean
  }>()

  const loading = ref(false)
  const saving = ref(false)
  const tags = ref<DeviceTagResponse[]>([])
  const formRef = ref<FormInstance>()
  const form = reactive({ key: '', value: '' })
  const rules: FormRules = {
    key: [
      { required: true, message: '请输入标签键', trigger: 'blur' },
      {
        pattern: /^[A-Za-z][A-Za-z0-9_-]{0,63}$/,
        message: '须以字母开头，仅支持字母、数字、下划线和横线',
        trigger: 'blur'
      }
    ],
    value: [{ required: true, message: '请输入标签值', trigger: 'blur' }]
  }

  /** 清空表单校验，避免切换设备时把上一台设备的输入带进来。 */
  const resetForm = () => {
    form.key = ''
    form.value = ''
    formRef.value?.clearValidate()
  }

  /** 标签读取允许快速切换设备；过期响应不能覆盖当前设备。 */
  const loadTags = async () => {
    if (!props.projectId || !props.deviceId) {
      tags.value = []
      return
    }
    const requestScope = `${props.projectId}:${props.deviceId}`
    loading.value = true
    try {
      const result = await fetchDeviceTags(props.projectId, props.deviceId)
      if (requestScope === `${props.projectId}:${props.deviceId}`) {
        tags.value = result
      }
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('读取设备标签失败:', error)
    } finally {
      if (requestScope === `${props.projectId}:${props.deviceId}`) loading.value = false
    }
  }

  /** 新增或覆盖同键标签，成功后重新读取服务端事实。 */
  const saveTag = async () => {
    if (!formRef.value || !props.projectId || !props.deviceId) return
    try {
      await formRef.value.validate()
      saving.value = true
      await fetchPutDeviceTag(props.projectId, props.deviceId, {
        key: form.key,
        value: form.value
      })
      ElMessage.success(
        tags.value.some((tag) => tag.key === form.key) ? '标签已更新' : '标签已添加'
      )
      resetForm()
      await loadTags()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('保存设备标签失败:', error)
    } finally {
      saving.value = false
    }
  }

  /** 把已有键值带回表单；保存时由后端 upsert 原子覆盖。 */
  const editTag = (tag: DeviceTagResponse) => {
    form.key = tag.key ?? ''
    form.value = tag.value ?? ''
    formRef.value?.clearValidate()
  }

  /** 删除前明确展示标签键，避免用户误删相邻行。 */
  const removeTag = async (tag: DeviceTagResponse) => {
    if (!props.projectId || !props.deviceId || !tag.key) return
    try {
      await ElMessageBox.confirm(`确定删除标签“${tag.key}”吗？`, '删除设备标签', {
        type: 'warning'
      })
      await fetchDeleteDeviceTag(props.projectId, props.deviceId, tag.key)
      ElMessage.success('标签已删除')
      if (form.key === tag.key) resetForm()
      await loadTags()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError)) {
        console.error('删除设备标签失败:', error)
      }
    }
  }

  watch(
    () => [props.projectId, props.deviceId],
    () => {
      resetForm()
      void loadTags()
    },
    { immediate: true }
  )
</script>

<style lang="scss" scoped>
  .device-tags {
    &__header {
      margin-bottom: 12px;
      p {
        margin: 4px 0 0;
        font-size: 12px;
        color: var(--art-text-gray-600);
      }
    }
    &__form {
      display: grid;
      grid-template-columns: minmax(140px, 1fr) minmax(180px, 1.5fr) auto;
      gap: 12px;
      align-items: end;
      margin-bottom: 12px;
    }
    &__submit {
      :deep(.el-form-item__content) {
        flex-wrap: nowrap;
      }
    }
  }

  @media (width <= 760px) {
    .device-tags__form {
      grid-template-columns: 1fr;
    }
  }
</style>
