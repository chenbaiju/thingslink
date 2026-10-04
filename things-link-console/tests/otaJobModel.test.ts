import { describe, expect, it } from 'vitest'
import {
  attemptLabel,
  emptyOtaDeviceJobHistory,
  isJobTerminal,
  JOB_STATUSES,
  jobFailureHint,
  jobStatusLabel,
  jobStatusTag,
  mergeOtaDeviceJobPage,
  needsAttention,
  transitionSummary
} from '@/features/ota/job-model'

describe('OTA设备作业展示', () => {
  it('状态闭集包含重试等待、失败与超时，未知状态原样透传', () => {
    // D-151：契约里窄于数据库的旧清单会把这些真实状态显示成未知字符串。
    expect(JOB_STATUSES).toContain('RETRY_WAIT')
    expect(JOB_STATUSES).toContain('FAILED')
    expect(JOB_STATUSES).toContain('TIMED_OUT')
    expect(jobStatusLabel('RETRY_WAIT')).toBe('重试等待')
    expect(jobStatusTag('RECOVERY_REQUIRED')).toBe('danger')
    expect(jobStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(jobStatusTag('SOMETHING_NEW')).toBe('info')
    expect(jobStatusLabel(null)).toBe('—')
  })

  it('失败归因翻译成中文，未知归因原样透传', () => {
    expect(jobFailureHint('RETRY_BUDGET_EXHAUSTED')).toContain('重试预算')
    expect(jobFailureHint('DISPATCH_TRANSIENT_FAILURE')).toContain('暂时失败')
    expect(jobFailureHint('BRAND_NEW_CODE')).toBe('BRAND_NEW_CODE')
    expect(jobFailureHint(null)).toBe('—')
  })

  it('代次只显示第几次尝试，不臆造"第几次重试"', () => {
    expect(attemptLabel(1)).toBe('第 1 次尝试')
    expect(attemptLabel(3)).toBe('第 3 次尝试')
    expect(attemptLabel(undefined)).toBe('—')
  })

  it('终态与需关注状态的判定与服务端语义一致', () => {
    for (const status of [
      'SUCCEEDED',
      'FAILED',
      'TIMED_OUT',
      'CANCELLED',
      'SKIPPED_INELIGIBLE',
      'ROLLED_BACK'
    ]) {
      expect(isJobTerminal(status), status).toBe(true)
    }
    for (const status of ['DISPATCHED', 'DOWNLOADING', 'RETRY_WAIT', 'RECOVERY_REQUIRED']) {
      expect(isJobTerminal(status), status).toBe(false)
    }
    expect(needsAttention('RECOVERY_REQUIRED')).toBe(true)
    expect(needsAttention('TIMED_OUT')).toBe(true)
    expect(needsAttention('SUCCEEDED')).toBe(false)
  })

  it('转移摘要用中文标签并保留原因', () => {
    expect(
      transitionSummary({
        fromStatus: 'DISPATCHED',
        toStatus: 'RETRY_WAIT',
        reason: 'DISPATCH_TRANSIENT_FAILURE'
      })
    ).toBe('已派发 → 重试等待（DISPATCH_TRANSIENT_FAILURE）')
    // 首次转移没有来源状态时显示"准入"，不显示空串或 null。
    expect(transitionSummary({ fromStatus: null, toStatus: 'PENDING' })).toBe('准入 → 待准入')
  })

  it('设备作业分页续页追加、末页清游标，切换设备时首页替换', () => {
    type Job = { id: string }
    expect(emptyOtaDeviceJobHistory<Job>()).toEqual({ items: [], cursor: '', hasMore: false })

    const jobA = { id: 'a' }
    const jobB = { id: 'b' }
    const first = mergeOtaDeviceJobPage(
      emptyOtaDeviceJobHistory<Job>(),
      { items: [jobA], nextCursor: 'c1', hasMore: true },
      false
    )
    expect(first).toEqual({ items: [jobA], cursor: 'c1', hasMore: true })

    const second = mergeOtaDeviceJobPage(
      first,
      { items: [jobB], nextCursor: 'c2', hasMore: true },
      true
    )
    expect(second.items.map((job) => job.id)).toEqual(['a', 'b'])
    expect(second.cursor).toBe('c2')

    // 末页必须清空游标，避免用一个已到末页的游标继续请求。
    const last = mergeOtaDeviceJobPage(
      second,
      { items: [], nextCursor: 'stale', hasMore: false },
      true
    )
    expect(last).toEqual({ items: [jobA, jobB], cursor: '', hasMore: false })

    // 首页走替换而不是追加：切换设备后不能混入上一台设备的作业。
    expect(mergeOtaDeviceJobPage(second, { items: [jobB], hasMore: false }, false).items).toEqual([
      jobB
    ])
  })
})
