<!-- 邮箱验证页。邮件里的链接指向这里，路径与后端 EmailVerificationMailer.VERIFY_PATH 一一对应 -->
<template>
  <div class="console-auth-page flex w-full h-screen">
    <LoginLeftView />

    <div class="relative flex-1">
      <AuthTopBar />

      <div class="auth-right-wrap">
        <div class="form">
          <h3 class="title">{{ $t('verifyEmail.title') }}</h3>

          <!-- 待用户点击。刻意不自动提交，理由见下面 script 里的说明 -->
          <template v-if="status === 'idle'">
            <p class="sub-title">{{ $t('verifyEmail.prompt') }}</p>
            <div style="margin-top: 15px">
              <ElButton
                class="w-full custom-height"
                type="primary"
                :loading="verifying"
                @click="verify"
                v-ripple
              >
                {{ $t('verifyEmail.submitBtnText') }}
              </ElButton>
            </div>
          </template>

          <!-- 成功。倒计时结束自动去登录，同时保留手动入口 -->
          <template v-else-if="status === 'success'">
            <p class="sub-title">{{ $t('verifyEmail.success', { email: verifiedEmail }) }}</p>
            <p class="sub-title">{{ $t('verifyEmail.redirecting', { seconds: remaining }) }}</p>
            <div style="margin-top: 15px">
              <ElButton class="w-full custom-height" type="primary" @click="toLogin" v-ripple>
                {{ $t('verifyEmail.toLogin') }}
              </ElButton>
            </div>
          </template>

          <!-- 失败。后端刻意不区分「无效 / 过期 / 已用过」，所以这里也只有一种文案 -->
          <template v-else>
            <p class="sub-title">{{ $t('verifyEmail.failed') }}</p>

            <div class="mt-5">
              <ElInput
                class="custom-height"
                v-model.trim="resendEmail"
                :placeholder="$t('verifyEmail.placeholder.email')"
                @keyup.enter="resend"
              />
            </div>

            <div style="margin-top: 15px">
              <ElButton
                class="w-full custom-height"
                type="primary"
                :loading="resending"
                :disabled="resendCooldown > 0"
                @click="resend"
                v-ripple
              >
                {{
                  resendCooldown > 0
                    ? $t('verifyEmail.resendCooldown', { seconds: resendCooldown })
                    : $t('verifyEmail.resendBtnText')
                }}
              </ElButton>
            </div>

            <div style="margin-top: 15px">
              <ElButton class="w-full custom-height" plain @click="toLogin">
                {{ $t('verifyEmail.backBtnText') }}
              </ElButton>
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { fetchResendVerification, fetchVerifyEmail } from '@/api/auth'
  import { useRedirectCountdown } from '@/hooks'

  defineOptions({ name: 'VerifyEmail' })

  const { t } = useI18n()
  const route = useRoute()
  const router = useRouter()
  const { remaining, start: startCountdown } = useRedirectCountdown(3)

  type Status = 'idle' | 'success' | 'failed'

  const status = ref<Status>('idle')
  const verifiedEmail = ref('')
  const verifying = ref(false)
  const resendEmail = ref('')
  const resending = ref(false)
  const resendCooldown = ref(0)
  const RESEND_COOLDOWN_SECONDS = 60
  let resendCooldownTimer: number | null = null

  /** 链接被邮件客户端截断、或只复制了一半时为空 */
  const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))

  /**
   * 进页面**不自动提交**，等用户点按钮。
   *
   * 这一步看着多余——他已经在邮件里点过一次了——但它挡掉了一个真实问题：
   * 企业邮箱与反钓鱼网关会**预取邮件里的链接**做安全扫描，而验证令牌是一次性的，
   * 扫描器那一下就把它消费掉了。用户真去点时只会看到「链接已失效」，
   * 而服务端日志显示令牌确实被正常消费过——这种故障几乎无法排查。
   *
   * 按钮点不了，扫描器就消费不掉。
   */
  onMounted(() => {
    // 地址栏里根本没有令牌，不必等用户点了才告诉他
    if (!token.value) {
      status.value = 'failed'
    }
  })

  const verify = async () => {
    if (!token.value) {
      status.value = 'failed'
      return
    }

    verifying.value = true
    try {
      const result = await fetchVerifyEmail(token.value)
      verifiedEmail.value = result.email ?? ''
      status.value = 'success'
      // 验证完这个页面就没用了。3 秒够看清结果，又不至于让人干等
      startCountdown('Login')
    } catch {
      // 失败原因一律不细分（后端只给 20021）。异常里没有比「重新获取链接」
      // 更有用的信息，所以不做分支，也不打印——它不是异常，是预期内的一种结果
      status.value = 'failed'
    } finally {
      verifying.value = false
    }
  }

  /**
   * 重发验证邮件。
   *
   * 无论邮箱是否注册、是否已验证，后端都返回 204，因此这里的成功提示只能说
   * 「如果该邮箱已注册，我们已把新链接发过去了」——写成「已发送至 xxx」
   * 就等于把后端刻意隐藏的「这个邮箱存在」又还了回去。
   */
  const resend = async () => {
    if (resendCooldown.value > 0) return

    if (!resendEmail.value) {
      ElMessage.warning(t('verifyEmail.rule.emailRequired'))
      return
    }

    resending.value = true
    try {
      await fetchResendVerification(resendEmail.value)
      ElMessage.success(t('verifyEmail.resendMessage'))
      startResendCooldown()
    } finally {
      // 限流（10029）由 HTTP 层统一弹提示，这里只负责把按钮恢复可点
      resending.value = false
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

  onBeforeUnmount(stopResendCooldown)

  const toLogin = () => {
    router.push({ name: 'Login' })
  }
</script>

<style scoped>
  @import '../login/style.css';
</style>
