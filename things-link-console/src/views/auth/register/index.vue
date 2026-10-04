<!-- 注册页面 -->
<template>
  <div class="console-auth-page flex w-full h-screen">
    <LoginLeftView />

    <div class="relative flex-1">
      <AuthTopBar />

      <div class="auth-right-wrap">
        <div class="form">
          <h3 class="title">{{ $t('register.title') }}</h3>
          <p class="sub-title">
            {{
              submitted
                ? $t('register.verifySubTitle', { email: submittedEmail })
                : $t('register.subTitle')
            }}
          </p>

          <template v-if="submitted">
            <div style="margin-top: 15px">
              <ElButton class="w-full custom-height" type="primary" @click="openMailbox" v-ripple>
                {{ $t('register.openMailbox') }}
              </ElButton>
            </div>
            <div style="margin-top: 15px">
              <ElButton class="w-full custom-height" plain @click="toLogin">
                {{ $t('register.toLogin') }}
              </ElButton>
            </div>
          </template>

          <ElForm
            v-else
            class="mt-7.5"
            ref="formRef"
            :model="formData"
            :rules="rules"
            label-position="top"
            :key="formKey"
          >
            <ElFormItem prop="email">
              <ElInput
                class="custom-height"
                v-model.trim="formData.email"
                :placeholder="$t('register.placeholder.email')"
              />
            </ElFormItem>

            <ElFormItem prop="password">
              <ElInput
                class="custom-height"
                v-model.trim="formData.password"
                :placeholder="$t('register.placeholder.password')"
                type="password"
                autocomplete="off"
                show-password
              />
            </ElFormItem>

            <ElFormItem prop="confirmPassword">
              <ElInput
                class="custom-height"
                v-model.trim="formData.confirmPassword"
                :placeholder="$t('register.placeholder.confirmPassword')"
                type="password"
                autocomplete="off"
                @keyup.enter="register"
                show-password
              />
            </ElFormItem>

            <ElFormItem prop="agreement">
              <ElCheckbox v-model="formData.agreement">
                {{ $t('register.agreeText') }}
                <RouterLink
                  style="color: var(--theme-color); text-decoration: none"
                  to="/privacy-policy"
                  >{{ $t('register.privacyPolicy') }}</RouterLink
                >
              </ElCheckbox>
            </ElFormItem>

            <!-- 推拽验证：注册同样是公开入口，必须先确认是真人操作再创建账号和触发邮件。 -->
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
                @click="register"
                :loading="loading"
                v-ripple
              >
                {{ $t('register.submitBtnText') }}
              </ElButton>
            </div>

            <div class="mt-5 text-sm text-g-600">
              <span>{{ $t('register.hasAccount') }}</span>
              <RouterLink class="text-theme" :to="{ name: 'Login' }">{{
                $t('register.toLogin')
              }}</RouterLink>
            </div>
          </ElForm>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
  import { fetchRegister } from '@/api/auth'
  import { HttpError } from '@/utils/http/error'
  import { useSettingStore } from '@/store/modules/setting'

  defineOptions({ name: 'Register' })

  interface RegisterForm {
    email: string
    password: string
    confirmPassword: string
    agreement: boolean
  }

  /**
   * 与后端 RegisterRequest 的 @Size(min = 10) 保持一致。
   *
   * 两边都校验不是重复：前端这道是**即时反馈**，让用户在点提交前就知道；
   * 后端那道是**防线**，直接调接口就绕过前端了。两者中的任何一个单独存在都不够。
   *
   * 模板原值是 6，改成 10 —— 不改的话用户会在前端通过、到后端才被拒，
   * 拿到一个他无法理解的错误。
   */
  const PASSWORD_MIN_LENGTH = 10

  const { t } = useI18n()
  const router = useRouter()
  const settingStore = useSettingStore()
  const { isDark } = storeToRefs(settingStore)
  const formRef = ref<FormInstance>()
  const dragVerify = ref()

  const loading = ref(false)
  const formKey = ref(0)
  const submitted = ref(false)
  const submittedEmail = ref('')
  const isPassing = ref(false)
  const isClickPass = ref(false)

  // 模板原本监听 locale 变化重置表单。语言切换器已在 S1 切片 3 关闭
  // （只保留简体中文），这个监听不再会触发，一并删掉。
  // formKey 保留：它仍是「重置整个表单」的手段，将来可能用得上。

  const formData = reactive<RegisterForm>({
    email: '',
    password: '',
    confirmPassword: '',
    agreement: false
  })

  /**
   * 验证密码
   * 当密码输入后，如果确认密码已填写，则触发确认密码的验证
   */
  const validatePassword = (_rule: any, value: string, callback: (error?: Error) => void) => {
    if (!value) {
      callback(new Error(t('register.placeholder.password')))
      return
    }

    if (formData.confirmPassword) {
      formRef.value?.validateField('confirmPassword')
    }

    callback()
  }

  /**
   * 验证确认密码
   * 检查确认密码是否与密码一致
   */
  const validateConfirmPassword = (
    _rule: any,
    value: string,
    callback: (error?: Error) => void
  ) => {
    if (!value) {
      callback(new Error(t('register.rule.confirmPasswordRequired')))
      return
    }

    if (value !== formData.password) {
      callback(new Error(t('register.rule.passwordMismatch')))
      return
    }

    callback()
  }

  /**
   * 验证用户协议
   * 确保用户已勾选同意协议
   */
  const validateAgreement = (_rule: any, value: boolean, callback: (error?: Error) => void) => {
    if (!value) {
      callback(new Error(t('register.rule.agreementRequired')))
      return
    }
    callback()
  }

  const rules = computed<FormRules<RegisterForm>>(() => ({
    email: [
      { required: true, message: t('register.placeholder.email'), trigger: 'blur' },
      { type: 'email', message: t('register.rule.emailInvalid'), trigger: 'blur' }
    ],
    password: [
      { required: true, validator: validatePassword, trigger: 'blur' },
      { min: PASSWORD_MIN_LENGTH, message: t('register.rule.passwordLength'), trigger: 'blur' }
    ],
    confirmPassword: [{ required: true, validator: validateConfirmPassword, trigger: 'blur' }],
    agreement: [{ validator: validateAgreement, trigger: 'change' }]
  }))

  /**
   * 注册用户
   * 验证表单后提交注册请求
   */
  const register = async () => {
    if (!formRef.value) return

    try {
      await formRef.value.validate()

      if (!isPassing.value) {
        isClickPass.value = true
        ElMessage.error(t('login.placeholder.slider'))
        return
      }

      loading.value = true

      await fetchRegister({
        email: formData.email,
        password: formData.password
      })

      submittedEmail.value = formData.email
      submitted.value = true
      ElMessage.success(t('register.successMessage'))
    } catch (error) {
      // HttpError 已由 HTTP 层统一弹过提示（含后端的业务错误码与文案），
      // 这里不再重复提示，只放过表单校验失败那一类
      if (!(error instanceof HttpError)) {
        console.error('注册失败:', error)
      }
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

  const mailboxUrl = computed(() => {
    const domain = submittedEmail.value.split('@')[1]?.toLowerCase()
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

  const toLogin = () => {
    router.push({ name: 'Login' })
  }
</script>

<style scoped>
  @import '../login/style.css';
</style>
