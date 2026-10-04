import { onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'

/**
 * 「N 秒后自动跳转」的倒计时。
 *
 * 用在邮箱验证成功与重置口令成功两处：操作已经完成，用户此刻唯一要做的事就是去登录，
 * 停在结果页上没有任何意义。但**不能立刻跳** —— 那样他根本来不及看清刚才发生了什么，
 * 只会以为自己被莫名其妙地弹回了登录页。
 *
 * 因此保留可见的倒计时，并且**同时保留手动跳转的按钮**：倒计时是兜底，不是唯一出路。
 *
 * @param seconds 倒计时秒数
 * @returns 剩余秒数与启动函数
 */
export function useRedirectCountdown(seconds = 3) {
  const router = useRouter()
  const remaining = ref(seconds)
  let timer: ReturnType<typeof setInterval> | null = null

  const stop = () => {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  /**
   * 开始倒计时，归零后跳转。
   *
   * @param to 目标路由名
   */
  const start = (to: string) => {
    stop()
    remaining.value = seconds
    timer = setInterval(() => {
      remaining.value -= 1
      if (remaining.value <= 0) {
        stop()
        router.push({ name: to })
      }
    }, 1000)
  }

  // 组件卸载时必须清掉。不清的话，用户在倒计时结束前手动点了按钮离开，
  // 一秒后这个定时器还会再 push 一次 —— 表现是「刚点进某个页面又被弹回登录页」，
  // 而那时页面上早已没有任何倒计时的痕迹，极难联想到是它干的
  onUnmounted(stop)

  return { remaining, start, stop }
}
