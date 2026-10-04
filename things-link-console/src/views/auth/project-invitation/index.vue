<template>
  <div class="console-page invitation-entry">
    <ElCard v-loading="loading" shadow="never">
      <h2>项目协作邀请</h2>
      <ElAlert
        v-if="failed"
        type="error"
        :closable="false"
        title="邀请不可用，请联系邀请方确认或重发"
      />
      <template v-else-if="invitation">
        <p>项目：{{ invitation.projectName }} · 角色：{{ invitation.role }}</p>
        <p>到期时间：{{ formatTime(invitation.expiresAt) }}</p>
        <ElAlert
          type="info"
          :closable="false"
          title="仅绑定邮箱的已验证账号可以接受；打开链接或注册都不会自动加入项目。"
        />
        <ElForm label-position="top" class="invitation-entry__form" @submit.prevent="register">
          <ElFormItem label="邀请绑定邮箱">
            <ElInput :model-value="invitation.targetEmail" readonly aria-label="邀请绑定邮箱" />
          </ElFormItem>
          <template v-if="!user.isLogin && !registered">
            <ElFormItem label="注册口令（10至128个字符）">
              <ElInput
                v-model="password"
                type="password"
                show-password
                autocomplete="new-password"
                maxlength="128"
                aria-label="注册口令"
              />
            </ElFormItem>
            <ElFormItem label="确认口令">
              <ElInput
                v-model="confirmation"
                type="password"
                autocomplete="new-password"
                maxlength="128"
                aria-label="确认口令"
              />
            </ElFormItem>
            <ElCheckbox v-model="agree">我同意注册账号并接收用于验证该邮箱的邮件</ElCheckbox>
            <ElButton
              type="primary"
              :loading="submitting"
              :disabled="!canRegister"
              @click="register"
              >按此邮箱注册</ElButton
            >
          </template>
        </ElForm>
        <ElAlert
          v-if="registered"
          type="success"
          :closable="false"
          title="账号已注册。请查收邮箱验证邮件，完成验证并登录后，在个人中心确认接受邀请。"
        />
        <div class="console-page-actions">
          <ElButton v-if="user.isLogin" type="primary" @click="router.push('/system/user-center')"
            >到个人中心确认接受</ElButton
          >
          <ElButton
            v-else
            @click="router.push({ name: 'Login', query: { redirect: '/system/user-center' } })"
            >已有账号，前往登录</ElButton
          >
        </div>
      </template>
      <ElButton v-if="failed" @click="load">重新检查</ElButton>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import { formatTime } from '@/utils/time'
  import {
    previewProjectInvitation,
    registerWithProjectInvitation,
    type ProjectInvitation
  } from '@/api/project-invitations'

  const route = useRoute()
  const router = useRouter()
  const user = useUserStore()
  const invitation = ref<ProjectInvitation>()
  const loading = ref(false)
  const failed = ref(false)
  const registered = ref(false)
  const submitting = ref(false)
  const password = ref('')
  const confirmation = ref('')
  const agree = ref(false)
  const canRegister = computed(
    () =>
      agree.value &&
      password.value.length >= 10 &&
      password.value.length <= 128 &&
      password.value === confirmation.value
  )
  let controller: AbortController | undefined
  let epoch = 0
  function proof() {
    const invitationId =
      typeof route.params.invitationId === 'string' ? route.params.invitationId : ''
    const code = typeof route.query.code === 'string' ? route.query.code : ''
    if (
      !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(invitationId) ||
      !/^[A-Za-z0-9_-]{43}$/.test(code)
    )
      throw new Error('邀请凭据无效')
    return { invitationId, code }
  }
  async function load() {
    const run = ++epoch
    controller?.abort()
    controller = new AbortController()
    invitation.value = undefined
    password.value = ''
    confirmation.value = ''
    agree.value = false
    registered.value = false
    loading.value = true
    failed.value = false
    try {
      const value = await previewProjectInvitation(proof(), controller.signal)
      if (run === epoch) invitation.value = value
    } catch {
      if (run === epoch) failed.value = true
    } finally {
      if (run === epoch) loading.value = false
    }
  }
  async function register() {
    if (!canRegister.value || !invitation.value?.targetEmail || submitting.value) return
    const run = epoch
    submitting.value = true
    try {
      await registerWithProjectInvitation({
        ...proof(),
        email: invitation.value.targetEmail,
        password: password.value
      })
      if (run !== epoch) return
      registered.value = true
      password.value = ''
      confirmation.value = ''
      ElMessage.success('请查收邮箱验证邮件')
    } catch {
      // HTTP层展示错误，不记录含口令或邀请码的请求对象。
    } finally {
      submitting.value = false
    }
  }
  watch(
    () => [route.params.invitationId, route.query.code],
    () => void load(),
    { immediate: true }
  )
  onBeforeUnmount(() => {
    ++epoch
    controller?.abort()
    password.value = ''
    confirmation.value = ''
  })
</script>

<style scoped lang="scss">
  .invitation-entry {
    max-width: 680px;
    margin: 40px auto;

    &__form {
      margin: 20px 0;
    }
  }
</style>
