<template>
  <ElDialog
    class="console-dialog"
    v-model="visible"
    title="一型一密产品凭据"
    width="620px"
    destroy-on-close
  >
    <ElAlert v-if="busy" class="console-hint" type="warning" show-icon :closable="false"
      >正在处理，请勿重复申请；关闭后结果可能未知。</ElAlert
    >
    <ElAlert class="console-hint" type="info" show-icon :closable="false"
      >产品识别码是公开标识；产品注册秘密仅用于HTTPS动态注册，不是设备连接密码。</ElAlert
    >
    <ElAlert class="console-hint" type="warning" show-icon :closable="false"
      >轮换立即使旧产品注册秘密失效，影响后续注册；已经签发的一机一密设备凭据保持独立。</ElAlert
    >
    <ElAlert
      v-if="unknown"
      title="上次结果未确认，可能已生成或轮换。秘密无法回查；核对类型后再次申请将重新轮换。"
      type="warning"
      :closable="false"
      show-icon
    />
    <ElAlert
      v-if="failed"
      title="类型核对失败，请恢复登录或权限后重试；不能据此判断尚未生成。"
      type="error"
      :closable="false"
      show-icon
    />
    <p v-if="loaded">{{ loaded.name }}（{{ loaded.id }}）· {{ loaded.status }}</p>
    <p v-if="loaded">产品识别码：{{ loaded.productKey || '尚未生成' }}</p>
    <ElAlert v-if="loaded && !eligible" class="console-hint" type="info" show-icon :closable="false"
      >只有活动项目的OWNER/ADMIN可为已发布的独立设备或网关类型生成产品凭据。</ElAlert
    >
    <ElAlert
      v-if="secret"
      title="注册秘密仅本次可见。关闭、刷新核对或切换身份后清除，无法重新查回。请妥善保存。"
      type="warning"
      :closable="false"
      show-icon
    />
    <ElInput
      v-if="secret"
      :model-value="secret"
      aria-label="产品注册秘密（仅本次可见）"
      readonly
      type="password"
      show-password
      autocomplete="off"
      data-testid="product-secret"
    />
    <template #footer>
      <ElButton :disabled="reading || busy" @click="refresh">核对类型</ElButton>
      <ElButton
        v-if="manager"
        type="primary"
        :disabled="!eligible || reading || busy || !loaded || failed || !!secret"
        @click="generate"
        data-testid="product-generate"
        >{{
          unknown ? '重新确认轮换' : loaded?.productKey ? '轮换产品注册秘密' : '生成产品凭据'
        }}</ElButton
      >
      <ElButton @click="visible = false">关闭</ElButton>
    </template>
  </ElDialog>
</template>
<script setup lang="ts">
  import { ElMessageBox } from 'element-plus'
  import {
    fetchDeviceTypeDetail,
    generateProductCredential,
    type DeviceTypeResponse
  } from '@/api/device'
  import { fetchProjects } from '@/api/project'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  const props = defineProps<{ modelValue: boolean; projectId: string; typeId: string }>()
  const emit = defineEmits<{ 'update:modelValue': [boolean]; changed: [] }>()
  const user = useUserStore()
  const loaded = ref<DeviceTypeResponse>(),
    status = ref<string>(),
    secret = ref(''),
    failed = ref(false),
    unknown = ref(false),
    reading = ref(false),
    busy = ref(false)
  let generation = 0,
    sequence = 0,
    sent = false
  const uuid = (v: unknown) =>
    typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const key = (v: unknown) => typeof v === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$/.test(v)
  const manager = computed(
    () =>
      user.info.roles?.some((r) => r === 'OWNER' || r === 'ADMIN') &&
      user.info.buttons?.includes('device:update')
  )
  const eligible = computed(
    () =>
      manager.value &&
      status.value === 'ACTIVE' &&
      loaded.value?.status === 'PUBLISHED' &&
      ['DIRECT', 'GATEWAY'].includes(loaded.value.deviceKind || '')
  )
  function invalidate() {
    generation++
    sequence++
    secret.value = ''
    loaded.value = undefined
    status.value = undefined
    reading.value = false
    failed.value = false
    if (sent && busy.value) unknown.value = true
  }
  const visible = computed({
    get: () => props.modelValue,
    set: (v: boolean) => {
      if (!v) invalidate()
      emit('update:modelValue', v)
    }
  })
  function valid(v: DeviceTypeResponse) {
    return (
      v &&
      Object.keys(v).sort().join(',') ===
        'createdAt,deviceKind,id,name,networkType,payloadProtocol,productKey,projectId,status,typeKey,version' &&
      v.id === props.typeId &&
      v.projectId === props.projectId &&
      ['DRAFT', 'PUBLISHED'].includes(v.status || '') &&
      ['DIRECT', 'GATEWAY', 'SUB_DEVICE'].includes(v.deviceKind || '') &&
      (v.productKey === null || key(v.productKey)) &&
      Number.isInteger(v.version) &&
      v.version! >= 1 &&
      typeof v.name === 'string' &&
      typeof v.typeKey === 'string' &&
      typeof v.createdAt === 'string' &&
      Number.isFinite(Date.parse(v.createdAt))
    )
  }
  async function refresh() {
    if (!props.modelValue || reading.value || busy.value) return
    secret.value = ''
    loaded.value = undefined
    status.value = undefined
    failed.value = false
    if (!uuid(props.projectId) || !uuid(props.typeId)) {
      failed.value = true
      return
    }
    const epoch = generation,
      read = ++sequence
    reading.value = true
    try {
      const [type, projects] = await Promise.all([
        fetchDeviceTypeDetail(props.projectId, props.typeId),
        fetchProjects()
      ])
      if (epoch !== generation || read !== sequence || !props.modelValue) return
      if (!valid(type) || !Array.isArray(projects)) throw new Error('公开类型响应无效')
      loaded.value = type
      status.value = projects.find((p) => p.id === props.projectId)?.status
    } catch {
      if (epoch === generation && read === sequence) failed.value = true
    } finally {
      if (epoch === generation && read === sequence) reading.value = false
    }
  }
  async function generate() {
    if (
      !eligible.value ||
      !loaded.value ||
      busy.value ||
      reading.value ||
      failed.value ||
      secret.value
    )
      return
    const epoch = generation,
      projectId = props.projectId,
      typeId = props.typeId
    busy.value = true
    try {
      await ElMessageBox.confirm(
        loaded.value.productKey || unknown.value
          ? '再次生成会立即轮换产品注册秘密，旧秘密不能继续注册新设备；已注册设备的一机一密保持独立。是否继续？'
          : '本次生成产品注册秘密，仅返回一次，用于后续HTTPS动态注册。是否继续？',
        '确认产品凭据操作',
        {
          confirmButtonText: loaded.value.productKey || unknown.value ? '确认轮换' : '确认生成',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
      if (epoch !== generation || !props.modelValue || !eligible.value) return
      sent = true
      const result = await generateProductCredential(projectId, typeId)
      if (epoch !== generation || !props.modelValue || !eligible.value) return
      if (
        !result ||
        Object.keys(result).sort().join(',') !== 'deviceTypeId,productKey,productSecret' ||
        result.deviceTypeId !== typeId ||
        !key(result.productKey) ||
        typeof result.productSecret !== 'string' ||
        !/^[0-9a-f]{64}$/.test(result.productSecret)
      )
        throw new Error('一次性响应无效')
      secret.value = result.productSecret
      loaded.value = { ...loaded.value, productKey: result.productKey }
      unknown.value = false
      emit('changed')
    } catch (error) {
      if (epoch === generation && sent) {
        unknown.value = true
        loaded.value = undefined
        status.value = undefined
      } else if (error !== 'cancel' && error !== 'close' && epoch === generation)
        failed.value = true
    } finally {
      busy.value = false
      sent = false
    }
  }
  watch(
    [
      () => props.projectId,
      () => props.typeId,
      () => user.info.userId,
      () => user.info.tenantId,
      currentIdentityEpoch,
      () => manager.value
    ],
    () => {
      invalidate()
      unknown.value = false
      if (props.modelValue) visible.value = false
    },
    { flush: 'sync' }
  )
  watch(
    () => props.modelValue,
    (open) => {
      invalidate()
      if (open) void refresh()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(invalidate)
</script>
