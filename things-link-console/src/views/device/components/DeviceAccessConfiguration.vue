<template>
  <section
    class="device-access console-fragment"
    data-testid="access-configuration"
    v-loading="loading"
  >
    <ElAlert v-if="notice" :title="notice" :type="noticeType" :closable="false" show-icon />
    <template v-if="current">
      <p class="console-description" data-testid="access-current">
        当前协议 {{ current.protocol }} · {{ current.enabled ? '已启用' : '已禁用' }} · 配置版本
        {{ current.configVersion }} · 凭据版本 {{ current.credentialVersion }}
        {{ current.configured ? '' : '（存量默认配置）' }}
      </p>
      <ElAlert
        v-if="!current.enabled"
        title="设备接入已禁用；恢复启用后设备需要重新连接或认证。"
        type="warning"
        :closable="false"
      />
      <p class="console-description" v-if="!current.canManage" data-testid="access-readonly">
        {{
          current.allowedProtocols.length
            ? '当前为只读状态，需要可写项目的管理员权限。'
            : '此设备类型不支持独立配置接入协议。'
        }}
      </p>
      <ElForm
        class="device-access__form"
        label-position="top"
        :disabled="!current.canManage || loading || saving || needsRefresh"
      >
        <ElFormItem label="接入协议">
          <ElSelect v-model="protocol" aria-label="接入协议">
            <ElOption
              v-for="value in current.allowedProtocols"
              :key="value"
              :label="value"
              :value="value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="允许设备接入">
          <ElSwitch v-model="enabled" aria-label="允许设备接入" />
        </ElFormItem>
        <p class="console-description"
          >修改协议或关闭接入会使旧连接失效；切换协议不会自动更换设备凭据。</p
        >
      </ElForm>
    </template>
    <div class="console-actions">
      <ElButton
        v-if="current?.canManage"
        type="primary"
        :loading="saving"
        :disabled="loading || saving || needsRefresh"
        @click="save"
        >保存接入配置</ElButton
      >
      <ElButton :disabled="saving || loading" @click="load">重新读取配置</ElButton>
    </div>
  </section>
</template>

<script setup lang="ts">
  import {
    fetchDeviceAccessConfiguration,
    fetchChangeDeviceAccessConfiguration,
    type DeviceAccessConfigurationResponse
  } from '@/api/device'
  import { HttpError } from '@/utils/http/error'

  const props = defineProps<{ projectId: string; deviceId: string }>()
  const current = ref<DeviceAccessConfigurationResponse>()
  const protocol = ref<DeviceAccessConfigurationResponse['protocol']>('MQTT')
  const enabled = ref(true)
  const loading = ref(false)
  const saving = ref(false)
  const needsRefresh = ref(true)
  const notice = ref('')
  const noticeType = ref<'success' | 'warning' | 'error'>('warning')
  let generation = 0

  /** 服务端返回同时替换事实和草稿；不将十进制版本转换为Number。 */
  function accept(value: DeviceAccessConfigurationResponse) {
    current.value = value
    protocol.value = value.protocol
    enabled.value = value.enabled
    needsRefresh.value = false
  }

  /** 每次重读递增代次，A→B→A和卸载后旧请求都不能重新获得可见性。 */
  async function load() {
    const epoch = ++generation
    loading.value = true
    needsRefresh.value = true
    try {
      const value = await fetchDeviceAccessConfiguration(props.projectId, props.deviceId)
      if (epoch === generation) accept(value)
    } catch {
      if (epoch !== generation) return
      current.value = undefined
      notice.value = '读取配置失败，请重新读取；当前不能保存。'
      noticeType.value = 'error'
    } finally {
      if (epoch === generation) loading.value = false
    }
  }

  /** 每次点击最多一次PUT；冲突或未知结果只重读，不自动以新版本重放旧意图。 */
  async function save() {
    if (!current.value?.canManage || saving.value || loading.value || needsRefresh.value) return
    const epoch = generation
    saving.value = true
    needsRefresh.value = true
    try {
      const value = await fetchChangeDeviceAccessConfiguration(props.projectId, props.deviceId, {
        protocol: protocol.value,
        enabled: enabled.value,
        expectedConfigVersion: current.value.configVersion
      })
      if (epoch !== generation) return
      accept(value)
      notice.value = '接入配置已保存。'
      noticeType.value = 'success'
    } catch (error) {
      if (epoch !== generation) return
      notice.value =
        error instanceof HttpError && error.code === 30065
          ? '配置已被修改，已重新读取；请核对后决定是否再次保存。'
          : '保存未获成功确认，已重新读取当前配置；请核对后决定是否再次保存。'
      noticeType.value = 'warning'
      // load会推进代次，因此在重读前释放保存状态；loading仍阻止所有写入。
      saving.value = false
      await load()
    } finally {
      if (epoch === generation) saving.value = false
    }
  }

  watch(
    () => [props.projectId, props.deviceId],
    () => {
      current.value = undefined
      saving.value = false
      notice.value = ''
      void load()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    generation++
  })
</script>

<style scoped>
  .device-access__form {
    max-width: 520px;
  }
</style>
