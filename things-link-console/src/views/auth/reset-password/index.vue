<!-- 重置密码。邮件里的重置链接指向这里，路径与后端 EmailVerificationMailer.RESET_PATH 一一对应 -->
<template>
  <div class="console-auth-page flex w-full h-screen">
    <LoginLeftView />

    <div class="relative flex-1">
      <AuthTopBar />

      <div class="auth-right-wrap">
        <div class="form">
          <h3 class="title">{{ $t('resetPassword.title') }}</h3>

          <!-- 成功。这里不自动登录：后端刚刚撤销了这个账号的全部会话 -->
          <template v-if="done">
            <p class="sub-title">{{ $t('resetPassword.success') }}</p>
            <p class="sub-title">{{ $t('resetPassword.redirecting', { seconds: remaining }) }}</p>
            <div style="margin-top: 15px">
              <ElButton class="w-full custom-height" type="primary" @click="toLogin" v-ripple>
                {{ $t('resetPassword.toLogin') }}
              </ElButton>
            </div>
          </template>

          <!-- 链接本身就不对（地址栏里没有 token） -->
          <template v-else-if="!token">
            <p class="sub-title">{{ $t('resetPassword.invalidLink') }}</p>
            <div style="margin-top: 15px">
              <ElButton class="w-full custom-height" type="primary" @click="toForgot" v-ripple>
                {{ $t('resetPassword.retryBtnText') }}
              </ElButton>
            </div>
          </template>

          <template v-else>
            <p class="sub-title">{{ $t('resetPassword.subTitle') }}</p>

            <ElForm
              class="mt-7.5"
              ref="formRef"
              :model="formData"
              :rules="rules"
              label-position="top"
            >
              <ElFormItem prop="password">
                <ElInput
                  class="custom-height"
                  v-model.trim="formData.password"
                  :placeholder="$t('resetPassword.placeholder.password')"
                  type="password"
                  autocomplete="new-password"
                  show-password
                />
              </ElFormItem>

              <ElFormItem prop="confirmPassword">
                <ElInput
                  class="custom-height"
                  v-model.trim="formData.confirmPassword"
                  :placeholder="$t('resetPassword.placeholder.confirmPassword')"
                  type="password"
                  autocomplete="new-password"
                  show-password
                  @keyup.enter="submit"
                />
              </ElFormItem>

              <div style="margin-top: 15px">
                <ElButton
                  class="w-full custom-height"
                  type="primary"
                  :loading="loading"
                  @click="submit"
                  v-ripple
                >
                  {{ $t('resetPassword.submitBtnText') }}
                </ElButton>
              </div>

              <div class="mt-5 text-sm text-g-600">
                {{ $t('resetPassword.sessionNotice') }}
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
  import type { FormInstance, FormRules } from 'element-plus'
  import { fetchResetPassword } from '@/api/auth'
  import { useRedirectCountdown } from '@/hooks'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'ResetPassword' })

  /**
   * 与后端 PasswordPolicy.MIN_LENGTH 保持一致。
   *
   * 两边都校验不是重复：前端这道是即时反馈，后端那道是防线（直接调接口就绕过前端了）。
   * 不一致的话用户会在前端通过、到后端才被拒，拿到一个他无法理解的错误。
   */
  const PASSWORD_MIN_LENGTH = 10

  const { t } = useI18n()
  const route = useRoute()
  const router = useRouter()
  const userStore = useUserStore()
  const formRef = ref<FormInstance>()
  const { remaining, start: startCountdown } = useRedirectCountdown(3)

  const loading = ref(false)
  const done = ref(false)
  const formData = reactive({ password: '', confirmPassword: '' })

  /** 链接被邮件客户端截断、或只复制了一半时为空 */
  const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))

  const validateConfirmPassword = (
    _rule: unknown,
    value: string,
    callback: (error?: Error) => void
  ) => {
    if (!value) {
      callback(new Error(t('resetPassword.rule.confirmRequired')))
      return
    }
    if (value !== formData.password) {
      callback(new Error(t('resetPassword.rule.mismatch')))
      return
    }
    callback()
  }

  const rules = computed<FormRules<typeof formData>>(() => ({
    password: [
      { required: true, message: t('resetPassword.placeholder.password'), trigger: 'blur' },
      { min: PASSWORD_MIN_LENGTH, message: t('resetPassword.rule.length'), trigger: 'blur' }
    ],
    confirmPassword: [{ required: true, validator: validateConfirmPassword, trigger: 'blur' }]
  }))

  const submit = async () => {
    if (!formRef.value) return

    try {
      await formRef.value.validate()
      loading.value = true
      await fetchResetPassword(token.value, formData.password)

      // 后端已经撤销了这个账号的全部会话，包括本地这一份（如果有的话）。
      // 这里只清前端状态，不调用 logOut()：那个方法会立刻 router.push(Login)，
      // 用户就看不到下面的成功提示与 3 秒倒计时
      userStore.setToken('')
      userStore.setLoginStatus(false)
      done.value = true
      // 口令已改、全部会话已撤销，这个页面就没用了。3 秒够看清结果，
      // 又不至于让人干等；旁边的按钮仍可随时手动跳
      startCountdown('Login')
    } catch {
      // 20021（链接无效/过期/已用过）与 20011（口令强度不足）都由 HTTP 层统一弹提示。
      // 这里刻意不把页面切成失败态：口令太短时后端不会消耗令牌，
      // 用户改一下再提交就能成功，把表单撤掉反而断了他的路
    } finally {
      loading.value = false
    }
  }

  const toLogin = () => {
    router.push({ name: 'Login' })
  }

  const toForgot = () => {
    router.push({ name: 'ForgetPassword' })
  }
</script>

<style scoped>
  @import '../login/style.css';
</style>
