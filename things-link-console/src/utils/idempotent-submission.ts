/** 一次结果未知的写入意图及其稳定幂等键。 */
interface PendingSubmission {
  fingerprint: string
  key: string
}

/**
 * 管理“用户写入意图”的幂等键生命周期。
 *
 * 首次提交生成键；网络断开、超时或响应丢失时结果未知，重试同一意图必须复用原键。
 * 成功或服务端明确拒绝后才能释放。用户修改意图会生成新键，不能把不同请求体塞进旧键触发 409。
 */
export class IdempotentSubmission {
  private pending?: PendingSubmission

  /** @return 当前意图应使用的稳定键；新意图生成新键。 */
  keyFor(fingerprint: string): string {
    if (this.pending?.fingerprint !== fingerprint) {
      this.pending = { fingerprint, key: crypto.randomUUID() }
    }
    return this.pending.key
  }

  /** 服务端已确认成功，释放已完成意图。 */
  succeeded(key: string): void {
    if (this.pending?.key === key) this.pending = undefined
  }

  /**
   * 处理失败：响应结果未知时保留键；收到明确拒绝时释放，允许用户修正后重新提交。
   */
  failed(key: string, outcomeUnknown: boolean): void {
    if (!outcomeUnknown && this.pending?.key === key) this.pending = undefined
  }
}
