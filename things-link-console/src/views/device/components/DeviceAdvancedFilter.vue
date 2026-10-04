<template>
  <ConsoleFilterBar
    class="device-advanced-filter"
    :items="filterItems"
    :span="8"
    @search="emit('search')"
    @reset="emit('reset')"
  >
    <template #keyword>
      <ElInput
        v-model.trim="model.keyword"
        maxlength="128"
        clearable
        placeholder="请输入名称或标识符"
      />
    </template>
    <template #deviceTypeIds>
      <ElSelect
        v-model="model.deviceTypeIds"
        multiple
        collapse-tags
        collapse-tags-tooltip
        clearable
        placeholder="全部类型"
      >
        <ElOption v-for="type in deviceTypes" :key="type.id" :label="type.name" :value="type.id" />
      </ElSelect>
    </template>
    <template #statuses>
      <ElSelect
        v-model="model.statuses"
        multiple
        collapse-tags
        collapse-tags-tooltip
        clearable
        placeholder="全部状态"
      >
        <ElOption label="未激活" value="INACTIVE" />
        <ElOption label="在线" value="ONLINE" />
        <ElOption label="离线" value="OFFLINE" />
      </ElSelect>
    </template>
    <template #groupId>
      <ElSelect v-model="model.groupId" clearable placeholder="全部设备组">
        <ElOption v-for="group in groups" :key="group.id" :label="group.name" :value="group.id" />
      </ElSelect>
    </template>
    <template #tagKey>
      <ElInput v-model.trim="model.tagKey" maxlength="64" clearable placeholder="例如 region" />
    </template>
    <template #tagValue>
      <ElInput
        v-model.trim="model.tagValue"
        maxlength="128"
        clearable
        placeholder="例如 cn-east-1"
      />
    </template>
  </ConsoleFilterBar>
</template>

<script setup lang="ts">
  import ConsoleFilterBar from '@/components/ConsoleFilterBar.vue'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'

  const filterItems: SearchFormItem[] = [
    { key: 'keyword', label: '名称 / 标识' },
    { key: 'statuses', label: '设备状态' },
    { key: 'deviceTypeIds', label: '设备类型' },
    { key: 'groupId', label: '设备组' },
    { key: 'tagKey', label: '标签键' },
    { key: 'tagValue', label: '标签值' }
  ]
  /** 设备高级筛选的固定白名单状态；不向后端传递自由表达式或排序字段。 */
  export interface DeviceAdvancedFilterModel {
    keyword: string
    deviceTypeIds: string[]
    statuses: Array<'INACTIVE' | 'ONLINE' | 'OFFLINE'>
    groupId: string
    tagKey: string
    tagValue: string
  }

  interface SelectOption {
    id: string
    name: string
  }

  defineProps<{
    deviceTypes: SelectOption[]
    groups: SelectOption[]
  }>()

  const model = defineModel<DeviceAdvancedFilterModel>({ required: true })
  const emit = defineEmits<{
    search: []
    reset: []
  }>()
</script>
