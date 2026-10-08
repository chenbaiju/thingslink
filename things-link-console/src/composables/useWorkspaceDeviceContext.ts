import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { fetchDeviceDetail, type DeviceResponse } from '@/api/device'
import { currentIdentityEpoch } from '@/utils/http/identity-scope'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** 只读取当前身份及项目内的来源设备；URL 只能提出预选，不能触发业务写入。 */
export function useWorkspaceDeviceContext(permission: string) {
  const route = useRoute()
  const user = useUserStore()
  const device = ref<DeviceResponse>()
  const loading = ref(false)
  const error = ref('')
  let generation = 0
  const allowed = computed(
    () =>
      user.isLogin &&
      !!user.info.currentProjectId &&
      !!user.info.buttons?.includes('device:read') &&
      !!user.info.buttons?.includes(permission)
  )
  const requested = computed(() => !!route?.query.deviceId)
  async function reload() {
    const epoch = ++generation
    device.value = undefined
    error.value = ''
    loading.value = false
    if (!requested.value) return
    const id = route?.query.deviceId
    const project = user.info.currentProjectId
    if (!allowed.value) {
      error.value = '当前身份无权读取来源设备或配置此功能。'
      return
    }
    if (route?.query.contextProjectId !== project) {
      error.value = '来源设备属于其他项目，请返回设备页重新进入。'
      return
    }
    if (typeof id !== 'string' || !uuid.test(id)) {
      error.value = '来源设备标识无效，请返回设备页重新进入。'
      return
    }
    const identity = currentIdentityEpoch()
    const current = () =>
      epoch === generation && identity === currentIdentityEpoch() && allowed.value
    loading.value = true
    try {
      const result = await fetchDeviceDetail(project!, id)
      if (!current()) return
      if (result.id !== id) throw new Error('来源设备与返回结果不匹配。')
      device.value = result
    } catch {
      if (current())
        error.value = '无法读取来源设备，设备可能已删除或访问权限已改变。可重试或返回设备页。'
    } finally {
      if (current()) loading.value = false
    }
  }
  watch(
    () => [
      route?.query.deviceId,
      route?.query.contextProjectId,
      user.info.currentProjectId,
      user.info.userId,
      user.info.tenantId,
      allowed.value,
      currentIdentityEpoch()
    ],
    () => void reload(),
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => generation++)
  return { device, loading, error, requested, reload }
}
