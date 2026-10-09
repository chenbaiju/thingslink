<template>
  <section
    class="device-location console-fragment"
    v-loading="loading"
    data-testid="device-location-point"
  >
    <ElAlert type="info" :closable="false" show-icon
      >WGS84 当前坐标，与安装位置说明分别保存。经度在前，纬度在后。</ElAlert
    >
    <ElAlert v-if="notice" :title="notice" type="warning" :closable="false" show-icon />
    <template v-if="current">
      <p
        v-if="current.longitude != null"
        class="console-description"
        data-testid="location-current"
      >
        经度 {{ current.longitude }}°，纬度 {{ current.latitude }}°
      </p>
      <ElForm
        class="device-location__form"
        v-if="editable"
        label-position="top"
        :disabled="loading || saving || needsRefresh"
      >
        <ElFormItem label="经度（-180° 至 180°）"
          ><ElInput
            v-model="longitude"
            aria-label="设备经度"
            placeholder="留空并清空纬度可移除坐标"
        /></ElFormItem>
        <ElFormItem label="纬度（-90° 至 90°）"
          ><ElInput v-model="latitude" aria-label="设备纬度" placeholder="留空并清空经度可移除坐标"
        /></ElFormItem>
      </ElForm>
      <ElAlert v-else type="info" :closable="false" show-icon>当前角色仅可查看坐标。</ElAlert>
    </template>
    <div class="console-actions">
      <ElButton
        v-if="current && editable"
        type="primary"
        :disabled="loading || saving || needsRefresh"
        :loading="saving"
        @click="save"
        >保存坐标</ElButton
      >
      <ElButton :disabled="loading || saving" @click="load">重新读取坐标</ElButton>
    </div>
  </section>
</template>
<script setup lang="ts">
  import {
    fetchDeviceLocationPoint,
    fetchUpdateDeviceLocationPoint,
    type DeviceLocationPointResponse
  } from '@/api/device'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { HttpError } from '@/utils/http/error'
  const props = defineProps<{
    projectId: string
    deviceId: string
    identityKey: string
    editable: boolean
  }>()
  const current = ref<DeviceLocationPointResponse>()
  const longitude = ref(''),
    latitude = ref(''),
    notice = ref('')
  const loading = ref(false),
    saving = ref(false),
    needsRefresh = ref(true)
  let generation = 0
  const active = (epoch: number, identity: number) =>
    epoch === generation && identity === currentIdentityEpoch()
  function accept(value: DeviceLocationPointResponse) {
    current.value = value
    longitude.value = value.longitude == null ? '' : String(value.longitude)
    latitude.value = value.latitude == null ? '' : String(value.latitude)
    needsRefresh.value = false
  }
  async function load() {
    const epoch = ++generation,
      identity = currentIdentityEpoch()
    loading.value = true
    needsRefresh.value = true
    try {
      const value = await fetchDeviceLocationPoint(props.projectId, props.deviceId)
      if (active(epoch, identity)) {
        accept(value)
        notice.value = ''
      }
    } catch {
      if (active(epoch, identity)) {
        current.value = undefined
        notice.value = '坐标读取失败，请重新读取。'
      }
    } finally {
      if (active(epoch, identity)) loading.value = false
    }
  }
  async function save() {
    if (!props.editable || !current.value || loading.value || saving.value || needsRefresh.value)
      return
    const lon = longitude.value.trim() === '' ? null : Number(longitude.value)
    const lat = latitude.value.trim() === '' ? null : Number(latitude.value)
    if (
      (lon === null) !== (lat === null) ||
      (lon !== null &&
        lat !== null &&
        (!Number.isFinite(lon) ||
          !Number.isFinite(lat) ||
          lon < -180 ||
          lon > 180 ||
          lat < -90 ||
          lat > 90))
    ) {
      notice.value = '请输入合法的经纬度，或将两项同时清空。'
      return
    }
    const epoch = generation,
      identity = currentIdentityEpoch()
    saving.value = true
    needsRefresh.value = true
    try {
      const value = await fetchUpdateDeviceLocationPoint(props.projectId, props.deviceId, {
        longitude: lon,
        latitude: lat,
        version: current.value.version
      })
      if (active(epoch, identity)) {
        accept(value)
        notice.value = '坐标已保存。'
      }
    } catch (error) {
      if (!active(epoch, identity)) return
      notice.value =
        error instanceof HttpError && error.code === 30069
          ? '坐标已被修改，草稿已保留；请重新读取后核对并提交。'
          : '保存未获成功确认，草稿已保留；请重新读取后核对，避免重复覆盖。'
    } finally {
      if (active(epoch, identity)) saving.value = false
    }
  }
  watch(
    () => [props.projectId, props.deviceId, props.identityKey, props.editable],
    () => {
      generation++
      current.value = undefined
      longitude.value = latitude.value = notice.value = ''
      loading.value = saving.value = false
      needsRefresh.value = true
      if (props.projectId && props.deviceId && props.identityKey) void load()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    generation++
  })
</script>

<style scoped>
  .device-location__form {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
    margin-top: 10px;
  }
  @media (width <= 640px) {
    .device-location__form {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
