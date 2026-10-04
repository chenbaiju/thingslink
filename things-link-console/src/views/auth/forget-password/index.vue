<!-- 找回密码：填邮箱，后端向已验证的地址发一封一次性重置链接 -->
<template>
  <div class="console-auth-page flex w-full h-screen">
    <LoginLeftView />

    <div class="relative flex-1">
      <AuthTopBar />

      <div class="auth-right-wrap">
        <div class="form">
          <h3 class="title">{{ $t('forgetPassword.title') }}</h3>
          <p class="sub-title">{{ $t('forgetPassword.subTitle') }}</p>

          <!--
            提交后不回到表单，而是换成一段说明。
            这是刻意的：后端无论邮箱是否注册都返回 204，让用户停在表单上反复重试
            只会让他觉得「是不是没发出去」，而我们既不能确认也不能否认。
          -->
          <template v-if="submitted">
            <p class="sub-title mt-5">{{ $t('forgetPassword.sentMessage') }}</p>
            <!--
              这句对所有人**一样地显示**，与该邮箱的真实状态无关，因此不泄露任何东西。
              它解释的是「为什么你收到的可能是一封验证信而不是重置信」——
              未验证的邮箱拿不到重置令牌（那是「用别人邮箱注册再找回」的入口），
              后端改发验证信，这里得让用户知道那不是发错了。
            -->
            <p class="sub-title">{{ $t('forgetPassword.sentHint') }}</p>
            <div style="margin-top: 15px">
              <ElButton class="w-full custom-height" type="primary" @click="toLogin" v-ripple>
                {{ $t('forgetPassword.backBtnText') }}
              </ElButton>
            </div>
          </template>

          <template v-else>
            <ElForm
              class="mt-7.5"
              ref="formRef"
              :model="formData"
              :rules="rules"
              label-position="top"
            >
              <ElFormItem prop="email">
                <ElInput
                  class="custom-height"
                  v-model.trim="formData.email"
                  :placeholder="$t('forgetPassword.placeholder')"
                  @keyup.enter="submit"
                />
              </ElFormItem>

              <!-- 推拽验证：找回密码会触发真实邮件发送，公开入口必须先挡住自动化请求。 -->
              <div class="relative pb-5 mt-6">
                <div
                  class="relative z-[2] overflow-hidden select-none rounded-lg border border-transparent tad-300"
                  :class="{ '!border-[#FF4E4F]': !isPassing && isClickPass }"
                >
                  <ArtDragVerify
                    ref="dragVerify"
                    v-model:value="isPassing"
                    :text="$t('login.sliderText')"
                    textColor="var(--art-gray-700)"
                    :successText="$t('login.sliderSuccessText')"
                    progressBarBg="var(--main-color)"
                    :background="isDark ? '#26272F' : '#F1F1F4'"
                    handlerBg="var(--default-box-color)"
                  />
                </div>
                <p
                  class="absolute top-0 z-[1] px-px mt-2 text-xs text-[#f56c6c] tad-300"
                  :class="{ 'translate-y-10': !isPassing && isClickPass }"
                >
                  {{ $t('login.placeholder.slider') }}
                </p>
              </div>

              <div style="margin-top: 15px">
                <ElButton
                  class="w-full custom-height"
                  type="primary"
                  :loading="loading"
                  :disabled="sendCooldown > 0"
                  @click="submit"
                  v-ripple
                >
                  {{
                    sendCooldown > 0
                      ? $t('forgetPassword.sendCooldown', { seconds: sendCooldown })
                      : $t('forgetPassword.submitBtnText')
                  }}
                </ElButton>
              </div>

              <div style="margin-top: 15px">
                <ElButton class="w-full custom-height" plain @click="toLogin">
                  {{ $t('forgetPassword.backBtnText') }}
                </ElButton>
              </div>
            </ElForm>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
  import { fetchForgotPassword } from '@/api/auth'
  import { useSettingStore } from '@/store/modules/setting'

  defineOptions({ name: 'ForgetPassword' })

  const { t } = useI18n()
  const router = useRouter()
  const settingStore = useSettingStore()
  const { isDark } = storeToRefs(settingStore)
  const formRef = ref<FormInstance>()
  const dragVerify = ref()

  const loading = ref(false)
  const submitted = ref(false)
  const isPassing = ref(false)
  const isClickPass = ref(false)
  const sendCooldown = ref(0)
  const SEND_COOLDOWN_SECONDS = 60
  let sendCooldownTimer: number | null = null
  const formData = reactive({ email: '' })

  const rules = computed<FormRules<typeof formData>>(() => ({
    email: [
      { required: true, message: t('forgetPassword.rule.emailRequired'), trigger: 'blur' },
      { type: 'email', message: t('forgetPassword.rule.emailInvalid'), trigger: 'blur' }
    ]
  }))

  const submit = async () => {
    if (!formRef.value) return
    if (sendCooldown.value > 0) return

    try {
      await formRef.value.validate()
      if (!isPassing.value) {
        isClickPass.value = true
        ElMessage.error(t('login.placeholder.slider'))
        return
      }

      loading.value = true
      await fetchForgotPassword(formData.email)
      startSendCooldown()
      submitted.value = true
    } catch {
      // 表单校验失败与 10029 限流都走到这里。限流的提示由 HTTP 层统一弹出，
      // 表单校验失败由 Element Plus 在字段下方标红，两者都不需要在这里再说一遍
    } finally {
      loading.value = false
      // 未通过时保留页内提示，避免 finally 把刚显示的验证错误立即清掉。
      if (isPassing.value) {
        resetDragVerify()
      }
    }
  }

  const resetDragVerify = () => {
    dragVerify.value?.reset()
    isPassing.value = false
    isClickPass.value = false
  }

  const startSendCooldown = () => {
    stopSendCooldown()
    sendCooldown.value = SEND_COOLDOWN_SECONDS
    sendCooldownTimer = window.setInterval(() => {
      sendCooldown.value -= 1
      if (sendCooldown.value <= 0) {
        stopSendCooldown()
      }
    }, 1000)
  }

  const stopSendCooldown = () => {
    if (sendCooldownTimer) {
      window.clearInterval(sendCooldownTimer)
      sendCooldownTimer = null
    }
    if (sendCooldown.value < 0) {
      sendCooldown.value = 0
    }
  }

  onBeforeUnmount(stopSendCooldown)

  const toLogin = () => {
    router.push({ name: 'Login' })
  }
</script>

<style scoped>
  @import '../login/style.css';
</style>
