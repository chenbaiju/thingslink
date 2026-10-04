import type { CommercialAdjustment, CommercialPreview, CommercialRequest } from '@/api/commercial'

export interface PendingAdjustment {
  tenantId: string
  request: CommercialRequest
}
export interface CommercialState {
  preview: CommercialPreview | null
  pending: PendingAdjustment | null
  result: CommercialAdjustment | null
  busy: boolean
  error: string
}
export const commercialState = (): CommercialState => ({
  preview: null,
  pending: null,
  result: null,
  busy: false,
  error: ''
})
interface Ports {
  preview(tenant: string): Promise<CommercialPreview>
  create(tenant: string, request: CommercialRequest): Promise<CommercialAdjustment>
  recover(tenant: string, key: string): Promise<CommercialAdjustment>
  revoke(tenant: string, id: string, reason: string): Promise<unknown>
  save(pending: PendingAdjustment | null): void
  key(): string
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const positiveLong = (s: string) =>
  /^[1-9][0-9]{0,18}$/.test(s) && BigInt(s) <= 9223372036854775807n
const failureCode = (error: unknown) => (error as { code?: number } | null)?.code
// 只在首次提交得到明确零写入拒绝时可丢弃原申请；恢复后的申请一律按可能已受理处理。
const definiteRejections = new Set([10001, 10004, 50034, 50039, 50040, 50048, 50051, 50052])

/** 原申请在发出前持久化；未知结果必须先查询，404也不能证明原事务已结束。 */
export function createCommercialModel(ports: Ports, state = commercialState()) {
  let generation = 0
  let ambiguous = false
  let recoveryAttempted = false
  function restore(value: unknown) {
    const pending = value as PendingAdjustment | null
    if (
      !pending ||
      typeof pending.tenantId !== 'string' ||
      !UUID.test(pending.tenantId) ||
      typeof pending.request?.idempotencyKey !== 'string' ||
      !pending.request.idempotencyKey
    )
      throw new Error('保存的申请无法解析，请按原申请编号查询，不要重复授予。')
    state.pending = pending
    ambiguous = true
    state.error = '存在尚未确认结果的申请，请先查询原申请。'
    return pending
  }
  async function preview(tenantId: string) {
    if (state.busy || state.pending) return
    state.preview = null
    state.result = null
    state.error = ''
    if (!UUID.test(tenantId)) {
      state.error = '请填写完整租户 ID。'
      return
    }
    const epoch = generation
    state.busy = true
    try {
      const value = await ports.preview(tenantId)
      if (epoch !== generation) return
      if (value.tenantId !== tenantId || !value.assignmentVersion || !value.dimensions?.length)
        throw new Error('审批摘要不完整，请重新读取。')
      state.preview = value
    } catch (error) {
      if (epoch === generation) state.error = error instanceof Error ? error.message : '读取失败'
    } finally {
      if (epoch === generation) state.busy = false
    }
  }
  function validate(
    input: Omit<CommercialRequest, 'idempotencyKey' | 'expectedAssignmentVersion'>
  ) {
    if (!positiveLong(input.amount)) throw new Error('数量须为1至9223372036854775807的整数。')
    if (!state.preview?.dimensions?.some((d) => d.code === input.dimensionCode))
      throw new Error('请选择摘要中的可调整维度。')
    if (!input.reason.trim() || input.reason.length > 512)
      throw new Error('请填写不超过512字的审批原因。')
    if (
      ![input.startsAt, input.endsAt].every(
        (t) => /Z$|[+-]\d\d:\d\d$/.test(t) && Number.isFinite(Date.parse(t))
      ) ||
      Date.parse(input.endsAt) <= Date.parse(input.startsAt)
    )
      throw new Error('起止时间须包含时区，且终点晚于起点。')
  }
  async function submit(
    input: Omit<CommercialRequest, 'idempotencyKey' | 'expectedAssignmentVersion'>
  ) {
    if (state.busy || state.pending || !state.preview?.tenantId || !state.preview.assignmentVersion)
      return
    try {
      validate(input)
      const pending: PendingAdjustment = {
        tenantId: state.preview.tenantId,
        request: {
          ...input,
          reason: input.reason.trim(),
          expectedAssignmentVersion: state.preview.assignmentVersion,
          idempotencyKey: ports.key()
        }
      }
      ports.save(pending) // 存储失败则不发送，不能留下无法恢复的未知写。
      state.pending = pending
      ambiguous = false
      recoveryAttempted = false
      await send()
    } catch (error) {
      state.error = error instanceof Error ? error.message : '申请未发送'
    }
  }
  function received(result: CommercialAdjustment) {
    const pending = state.pending
    if (
      !pending ||
      result.tenantId !== pending.tenantId ||
      result.idempotencyKey !== pending.request.idempotencyKey ||
      !result.id
    )
      throw new Error('返回身份不匹配，保留原申请等待核对。')
    state.result = result
    ports.save(null)
    state.pending = null
    state.preview = null
    state.error = ''
    ambiguous = false
    recoveryAttempted = false
  }
  async function send() {
    if (!state.pending || state.busy) return
    const epoch = generation,
      pending = state.pending
    state.busy = true
    state.error = ''
    try {
      const result = await ports.create(pending.tenantId, pending.request)
      if (epoch === generation) received(result)
    } catch (error) {
      if (epoch !== generation) return
      if (!ambiguous && definiteRejections.has(failureCode(error) ?? -1)) {
        ports.save(null)
        state.pending = null
        state.preview = null
        state.error = '申请被明确拒绝，请重新读取摘要后修改并审批。'
      } else {
        ambiguous = true
        state.error = '提交结果尚未确认，请查询原申请；不要另建相同调整。'
      }
    } finally {
      if (epoch === generation) state.busy = false
    }
  }
  async function recover() {
    if (!state.pending || state.busy) return
    const epoch = generation,
      pending = state.pending
    state.busy = true
    state.error = ''
    try {
      const result = await ports.recover(pending.tenantId, pending.request.idempotencyKey)
      if (epoch === generation) received(result)
    } catch (error) {
      if (epoch !== generation) return
      recoveryAttempted = true
      state.error =
        failureCode(error) === 50037
          ? '暂未查到原申请，可能仍在处理。可再次查询，或用原编号和原内容重试。'
          : '查询失败，原申请保留，请稍后重试。'
    } finally {
      if (epoch === generation) state.busy = false
    }
  }
  async function retryOriginal() {
    if (ambiguous && recoveryAttempted) {
      recoveryAttempted = false
      await send()
    }
  }
  async function lookup(tenant: string, key: string) {
    if (state.busy || state.pending) return
    if (!UUID.test(tenant) || !/^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/.test(key)) {
      state.error = '请填写租户ID及原申请编号。'
      return
    }
    const epoch = generation
    state.busy = true
    state.error = ''
    state.result = null
    try {
      const result = await ports.recover(tenant, key)
      if (epoch !== generation) return
      if (result.tenantId !== tenant || result.idempotencyKey !== key || !result.id)
        throw new Error('返回身份不匹配')
      state.result = result
    } catch (error) {
      if (epoch === generation) state.error = error instanceof Error ? error.message : '查询失败'
    } finally {
      if (epoch === generation) state.busy = false
    }
  }
  async function revoke(reason: string) {
    const result = state.result
    if (state.busy || state.pending || !result?.tenantId || !result.id || !result.idempotencyKey)
      return
    if (!reason.trim() || reason.length > 512) {
      state.error = '请填写不超过512字的撤销原因。'
      return
    }
    const epoch = generation
    state.busy = true
    state.error = ''
    try {
      await ports.revoke(result.tenantId, result.id, reason.trim())
      const fact = await ports.recover(result.tenantId, result.idempotencyKey)
      if (epoch === generation) state.result = fact
    } catch {
      if (epoch === generation)
        state.error = '撤销尚未确认，请按原申请编号查询状态；重试撤销不会重复授予。'
    } finally {
      if (epoch === generation) state.busy = false
    }
  }
  function invalidate() {
    generation++
    state.busy = false
    state.preview = null
    state.pending = null
    state.result = null
  }
  return { state, preview, submit, restore, recover, retryOriginal, lookup, revoke, invalidate }
}
