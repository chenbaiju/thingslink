import type { PendingAdjustment } from './adjustment-model'

/** 每个申请独立保存；多标签并发不能覆盖另一申请的恢复身份。仅保存审批内容，不保存凭据。 */
export function commercialPendingStorage(storage: Storage, accountId: string) {
  const prefix = `tc-commercial-pending:${accountId}:`
  function entries() {
    return Array.from({ length: storage.length }, (_, i) => storage.key(i))
      .filter((key): key is string => !!key?.startsWith(prefix))
      .sort()
  }
  return {
    first(): unknown | null {
      const key = entries()[0]
      return key ? JSON.parse(storage.getItem(key) ?? 'null') : null
    },
    save(pending: PendingAdjustment) {
      const key = prefix + pending.request.idempotencyKey
      if (entries().some((existing) => existing !== key))
        throw new Error('还有尚未确认的申请，请先读取待确认记录。')
      storage.setItem(key, JSON.stringify(pending))
    },
    remove(key: string) {
      storage.removeItem(prefix + key)
    }
  }
}
