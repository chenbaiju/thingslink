<template>
  <section v-if="canManage" class="notification-contact">
    <ElDivider content-position="left">本项目接收号码</ElDivider>
    <ElAlert type="info" show-icon :closable="false" class="console-hint"
      >仅用于本项目通知，不修改其他项目号码或账号登录信息。电话、短信渠道暂不可用。</ElAlert
    >
    <ElAlert
      v-if="notice"
      :title="notice"
      :type="failed ? 'warning' : 'success'"
      :closable="false"
      show-icon
    />
    <ElForm label-position="top" @submit.prevent="save">
      <div class="notification-contact__fields">
        <ElFormItem label="电话告警接收号码">
          <ElInput
            v-model="voice"
            aria-label="电话告警接收号码"
            :disabled="!editable"
            maxlength="16"
            placeholder="例如 +8613800000000；留空清除"
            autocomplete="off"
          />
        </ElFormItem>
        <ElFormItem label="短信告警接收号码">
          <ElInput
            v-model="sms"
            aria-label="短信告警接收号码"
            :disabled="!editable"
            maxlength="16"
            placeholder="例如 +8613800000000；留空清除"
            autocomplete="off"
          />
        </ElFormItem>
      </div>
      <ElAlert type="info" show-icon :closable="false" class="console-hint"
        >使用 +国家码和号码，共 7～15 位数字。保存只校验格式，不代表号码已验证或渠道可用。</ElAlert
      >
      <div class="console-actions">
        <ElButton
          type="primary"
          :disabled="!editable || !dirty || !valid"
          :loading="saving"
          @click="save"
          >保存接收号码</ElButton
        >
      </div>
      <p v-if="!allowWrite">当前项目只读，不能修改接收号码。</p>
      <p v-if="!valid">号码格式不正确，请使用 +国家码和数字，或留空清除。</p>
    </ElForm>
  </section>
</template>
<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import {
    fetchEndUserNotificationContact,
    updateEndUserNotificationContact,
    type EndUserNotificationContact
  } from '@/api/end-users'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { HttpError } from '@/utils/http/error'
  const props = defineProps<{ projectId: string; appUserId: string; allowWrite: boolean }>()
  const user = useUserStore()
  const canManage = computed(() => (user.info.buttons || []).includes('enduser:manage'))
  const voice = ref(''),
    sms = ref(''),
    notice = ref('')
  const loading = ref(false),
    saving = ref(false),
    failed = ref(false)
  const saved = ref<EndUserNotificationContact>()
  let generation = 0
  const editable = computed(
    () => canManage.value && props.allowWrite && !!saved.value && !loading.value && !saving.value
  )
  const validNumber = (value: string) => !value || /^\+[1-9][0-9]{6,14}$/.test(value)
  const valid = computed(() => validNumber(voice.value) && validNumber(sms.value))
  const dirty = computed(
    () =>
      voice.value !== (saved.value?.voiceNumber ?? '') ||
      sms.value !== (saved.value?.smsNumber ?? '')
  )
  function apply(value: EndUserNotificationContact) {
    if (
      typeof value.revision !== 'string' ||
      !/^(0|[1-9][0-9]{0,18})$/.test(value.revision) ||
      BigInt(value.revision) > 9223372036854775807n ||
      !('voiceNumber' in value) ||
      !('smsNumber' in value) ||
      [value.voiceNumber, value.smsNumber].some(
        (n) => n !== null && (typeof n !== 'string' || !n || !validNumber(n))
      )
    )
      throw new Error('号码响应无效')
    saved.value = value
    voice.value = value.voiceNumber ?? ''
    sms.value = value.smsNumber ?? ''
  }
  async function load() {
    if (!canManage.value || loading.value || saving.value) return
    const scope = generation
    loading.value = true
    saved.value = undefined
    voice.value = sms.value = ''
    try {
      const value = await fetchEndUserNotificationContact(props.projectId, props.appUserId)
      if (scope === generation) apply(value)
    } catch {
      if (scope === generation) {
        failed.value = true
        notice.value = '接收号码读取失败，请重新进入页面；不能据此判断尚未配置。'
      }
    } finally {
      if (scope === generation) loading.value = false
    }
  }
  async function save() {
    if (!editable.value || !dirty.value || !valid.value) return
    const scope = generation
    const body = {
      voiceNumber: voice.value || null,
      smsNumber: sms.value || null,
      expectedRevision: saved.value!.revision!
    }
    saving.value = true
    notice.value = ''
    try {
      const value = await updateEndUserNotificationContact(props.projectId, props.appUserId, body)
      if (scope !== generation) return
      if (
        value.voiceNumber !== body.voiceNumber ||
        value.smsNumber !== body.smsNumber ||
        value.revision === body.expectedRevision
      )
        throw new Error('写入回执无效')
      apply(value)
      failed.value = false
      notice.value = '接收号码已保存。'
    } catch (error) {
      if (scope !== generation) return
      failed.value = true
      notice.value =
        error instanceof HttpError && error.code === 60062
          ? '号码已被其他管理员修改，请按重新读取的号码核对。'
          : '保存未确认，请按重新读取的号码核对；未自动重复提交。'
      saving.value = false
      await load()
    } finally {
      if (scope === generation) saving.value = false
    }
  }
  watch(
    () => [props.projectId, props.appUserId, canManage.value, currentIdentityEpoch()],
    () => {
      generation++
      saved.value = undefined
      voice.value = sms.value = notice.value = ''
      loading.value = saving.value = failed.value = false
      void load()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    generation++
    saved.value = undefined
    voice.value = sms.value = ''
  })
</script>
<style scoped>
  .notification-contact {
    width: 100%;
  }
  .notification-contact__fields {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }
  @media (width <= 680px) {
    .notification-contact__fields {
      grid-template-columns: 1fr;
      gap: 0;
    }
  }
  .console-hint {
    margin-block: 12px;
    color: var(--el-text-color-secondary);
  }
  .console-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
</style>
