<!-- 登录页面 -->
<template>
  <div class="console-auth-page flex w-full h-screen">
    <LoginLeftView />

    <div class="relative flex-1">
      <AuthTopBar />

      <div class="auth-right-wrap">
        <div class="form">
          <h3 class="title">{{ $t('login.title') }}</h3>
          <p class="sub-title">{{ $t('login.subTitle') }}</p>
          <template v-if="emailVerificationRequired">
            <p class="mt-7.5 text-sm leading-6 text-g-600">
              {{ $t('login.emailVerificationRequired.hint', { email: pendingVerificationEmail }) }}
            </p>

            <div style="margin-top: 20px">
              <ElButton class="w-full custom-height" type="primary" @click="openMailbox" v-ripple>
                {{ $t('register.openMailbox') }}
              </ElButton>
            </div>

            <div style="margin-top: 15px">
              <ElButton
                class="w-full custom-height"
                :loading="resendingVerification"
                :disabled="resendCooldown > 0"
                @click="resendVerification"
              >
                {{
                  resendCooldown > 0
                    ? $t('verifyEmail.resendCooldown', { seconds: resendCooldown })
                    : $t('verifyEmail.resendBtnText')
                }}
              </ElButton>
            </div>

            <div style="margin-top: 15px">
              <ElButton class="w-full custom-height" plain @click="backToLoginForm">
                {{ $t('login.emailVerificationRequired.backBtnText') }}
              </ElButton>
            </div>
          </template>

          <ElForm
            v-else
            ref="formRef"
            :model="formData"
            :rules="rules"
            :key="formKey"
            @keyup.enter="handleSubmit"
            style="margin-top: 25px"
          >
            <!--
              模板原本在这里放了一个「选择演示账号」的下拉框，选中后自动填入
              Super/Admin/User 与固定口令 123456。那是演示用的，留着等于在登录页
              上公开三组可用凭据。已移除。
            -->
            <ElFormItem prop="email">
              <ElInput
                class="custom-height"
                :placeholder="$t('login.placeholder.username')"
                v-model.trim="formData.email"
              />
            </ElFormItem>
            <ElFormItem prop="password">
              <ElInput
                class="custom-height"
                :placeholder="$t('login.placeholder.password')"
                v-model.trim="formData.password"
                type="password"
                autocomplete="off"
                show-password
              />
            </ElFormItem>

            <!-- 推拽验证 -->
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

            <div class="flex-cb mt-2 text-sm">
              <ElCheckbox v-model="formData.rememberPassword">{{
                $t('login.rememberPwd')
              }}</ElCheckbox>
              <RouterLink class="text-theme" :to="{ name: 'ForgetPassword' }">{{
                $t('login.forgetPwd')
              }}</RouterLink>
            </div>

            <div style="margin-top: 30px">
              <ElButton
                class="w-full custom-height"
                type="primary"
                @click="handleSubmit"
                :loading="loading"
                v-ripple
              >
                {{ $t('login.btnText') }}
              </ElButton>
            </div>

            <div class="mt-5 text-sm text-gray-600">
              <span>{{ $t('login.noAccount') }}</span>
              <RouterLink class="text-theme" :to="{ name: 'Register' }">{{
                $t('login.register')
              }}</RouterLink>
            </div>
          </ElForm>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { useUserStore } from '@/store/modules/user'
  import { useI18n } from 'vue-i18n'
  import { HttpError } from '@/utils/http/error'
  import { fetchLogin, fetchResendVerification } from '@/api/auth'
  import { RoutesAlias } from '@/router/routesAlias'
  import { ElMessage, ElNotification, type FormInstance, type FormRules } from 'element-plus'
  import { useSettingStore } from '@/store/modules/setting'

  defineOptions({ name: 'Login' })

  const settingStore = useSettingStore()
  const { isDark } = storeToRefs(settingStore)
  const { t, locale } = useI18n()
  const formKey = ref(0)

  // 监听语言切换，重置表单
  watch(locale, () => {
    formKey.value++
  })

  const dragVerify = ref()

  const userStore = useUserStore()
  const router = useRouter()
  const route = useRoute()
  const isPassing = ref(false)
  const isClickPass = ref(false)

  const formRef = ref<FormInstance>()

  const formData = reactive({
    // 用邮箱而非用户名：后端的登录标识就是邮箱，且它全局唯一
    // （登录时还不知道租户是谁，租户内唯一无法定位账号）
    email: '',
    password: '',
    rememberPassword: true
  })

  const rules = computed<FormRules>(() => ({
    email: [
      { required: true, message: t('login.placeholder.username'), trigger: 'blur' },
      { type: 'email', message: t('login.placeholder.emailInvalid'), trigger: 'blur' }
    ],
    password: [{ required: true, message: t('login.placeholder.password'), trigger: 'blur' }]
  }))

  const loading = ref(false)
  const resendingVerification = ref(false)
  const resendCooldown = ref(0)
  const emailVerificationRequired = ref(false)
  const pendingVerificationEmail = ref('')
  let resendCooldownTimer: number | null = null

  /** 后端 EMAIL_NOT_VERIFIED。这里不从接口类型生成，因为错误码不是 OpenAPI schema。 */
  const EMAIL_NOT_VERIFIED_CODE = 20022
  const RESEND_COOLDOWN_SECONDS = 60

  // 登录
  const handleSubmit = async () => {
    if (!formRef.value) return

    try {
      // 表单验证
      const valid = await formRef.value.validate()
      if (!valid) return

      // 拖拽验证
      if (!isPassing.value) {
        isClickPass.value = true
        ElMessage.error(t('login.placeholder.slider'))
        return
      }

      loading.value = true

      // 登录请求。类型来自 OpenAPI 生成物，字段名对不上会在编译期就报错
      const { email, password } = formData

      const { accessToken } = await fetchLogin({ email, password }, { showErrorMessage: false })

      if (!accessToken) {
        throw new Error('登录响应中没有 accessToken')
      }

      // 只存访问令牌，且只存在内存里（ADR 0010）。
      // 刷新令牌由后端写在 HttpOnly Cookie 里，前端读不到也不需要管；
      // 访问令牌过期时 HTTP 层会自动刷新并重放请求，用户无感
      userStore.setToken(accessToken)
      userStore.setLoginStatus(true)

      // 路由守卫取得当前账号资料后，再用真实名称显示欢迎提示。
      await router.push(resolveRedirect(route.query.redirect))
      showLoginSuccessNotice(userStore.info.userName?.trim())
    } catch (error) {
      // 处理 HttpError
      if (error instanceof HttpError) {
        if (error.code === EMAIL_NOT_VERIFIED_CODE) {
          pendingVerificationEmail.value = formData.email
          emailVerificationRequired.value = true
          return
        }
        ElMessage.error(error.message)
      } else {
        // 处理非 HttpError
        // ElMessage.error('登录失败，请稍后重试')
        console.error('[Login] Unexpected error:', error)
      }
    } finally {
      loading.value = false
      // 未通过时保留页内提示，避免 finally 把刚显示的验证错误立即清掉。
      if (isPassing.value) {
        resetDragVerify()
      }
    }
  }

  // 重置拖拽验证
  const resetDragVerify = () => {
    dragVerify.value?.reset()
    isPassing.value = false
    isClickPass.value = false
  }

  const mailboxUrl = computed(() => {
    const domain = pendingVerificationEmail.value.split('@')[1]?.toLowerCase()
    if (!domain) return ''

    const known: Record<string, string> = {
      'qq.com': 'https://mail.qq.com',
      '163.com': 'https://mail.163.com',
      '126.com': 'https://mail.126.com',
      'yeah.net': 'https://www.yeah.net',
      'sina.com': 'https://mail.sina.com.cn',
      'sohu.com': 'https://mail.sohu.com',
      'foxmail.com': 'https://mail.qq.com',
      'gmail.com': 'https://mail.google.com',
      'outlook.com': 'https://outlook.live.com/mail',
      'hotmail.com': 'https://outlook.live.com/mail'
    }
    return known[domain] ?? `https://mail.${domain}`
  })

  const openMailbox = () => {
    if (mailboxUrl.value) {
      window.open(mailboxUrl.value, '_blank', 'noopener,noreferrer')
    }
  }

  const resendVerification = async () => {
    if (!pendingVerificationEmail.value || resendCooldown.value > 0) return

    resendingVerification.value = true
    try {
      await fetchResendVerification(pendingVerificationEmail.value)
      ElMessage.success(t('verifyEmail.resendMessage'))
      startResendCooldown()
    } finally {
      resendingVerification.value = false
    }
  }

  const startResendCooldown = () => {
    stopResendCooldown()
    resendCooldown.value = RESEND_COOLDOWN_SECONDS
    resendCooldownTimer = window.setInterval(() => {
      resendCooldown.value -= 1
      if (resendCooldown.value <= 0) {
        stopResendCooldown()
      }
    }, 1000)
  }

  const stopResendCooldown = () => {
    if (resendCooldownTimer) {
      window.clearInterval(resendCooldownTimer)
      resendCooldownTimer = null
    }
    if (resendCooldown.value < 0) {
      resendCooldown.value = 0
    }
  }

  const backToLoginForm = () => {
    emailVerificationRequired.value = false
    pendingVerificationEmail.value = ''
  }

  onBeforeUnmount(stopResendCooldown)

  const resolveRedirect = (value: unknown) => {
    const redirect = Array.isArray(value) ? value[0] : value
    if (typeof redirect !== 'string' || !redirect.startsWith('/')) {
      return '/'
    }

    // 登录页不能成为登录后的落点；历史坏 URL 里可能已经嵌套了多层 redirect。
    if (redirect === RoutesAlias.Login || redirect.startsWith(`${RoutesAlias.Login}?`)) {
      return '/'
    }

    return redirect
  }

  // 登录成功提示
  const showLoginSuccessNotice = (userName?: string) => {
    setTimeout(() => {
      ElNotification({
        title: t('login.success.title'),
        type: 'success',
        duration: 2500,
        zIndex: 10000,
        message: userName
          ? `${t('login.success.message')}, ${userName}!`
          : `${t('login.success.message')}!`
      })
    }, 1000)
  }
</script>

<style scoped>
  @import './style.css';
</style>

<style lang="scss" scoped>
  :deep(.el-select__wrapper) {
    height: 40px !important;
  }
</style>
