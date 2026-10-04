import { describe, expect, it } from 'vitest'
import {
  batchStatusLabel,
  batchStatusTag,
  batchTotals,
  campaignActions,
  campaignErrorHint,
  campaignStatusLabel,
  campaignStatusTag,
  checkCampaignDraft,
  defaultCampaignPolicy,
  toNotBeforeUtc
} from '@/features/ota/campaign-model'

describe('OTA活动展示与判定', () => {
  it('活动与批次的未知状态原样透传，不折叠成已知状态', () => {
    expect(campaignStatusLabel('RUNNING')).toBe('运行中')
    expect(campaignStatusTag('PAUSED')).toBe('warning')
    expect(campaignStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(campaignStatusTag('SOMETHING_NEW')).toBe('info')
    expect(campaignStatusLabel(null)).toBe('—')

    expect(batchStatusLabel('DRAINING')).toBe('收敛中')
    expect(batchStatusTag('FAILED')).toBe('danger')
    expect(batchStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(batchStatusLabel(undefined)).toBe('—')
  })

  it('动作资格与服务端判定一致：草稿才可排程，终态不可再操作', () => {
    expect(campaignActions('DRAFT')).toMatchObject({
      canSchedule: true,
      canStart: false,
      canCancel: true,
      canAdvance: false
    })
    // 未到notBefore时不呈现启动按钮，并给出70041提示。
    const early = campaignActions('SCHEDULED', false, false)
    expect(early.canStart).toBe(false)
    expect(early.reason).toContain('notBefore')
    expect(campaignActions('SCHEDULED', false, true).canStart).toBe(true)

    expect(campaignActions('RUNNING')).toMatchObject({ canPause: true, canAdvance: false })
    expect(campaignActions('RUNNING', true).canAdvance).toBe(true)
    expect(campaignActions('PAUSED')).toMatchObject({ canResume: true, canPause: false })

    const cancelling = campaignActions('CANCELLING')
    expect(cancelling.canCancel).toBe(false)
    expect(cancelling.reason).toContain('安全终态')

    for (const terminal of ['CANCELLED', 'COMPLETED']) {
      const actions = campaignActions(terminal)
      expect(actions.canCancel).toBe(false)
      expect(actions.canSchedule).toBe(false)
      expect(actions.reason).toContain('终态')
    }
  })

  it('把活动错误码翻译成稳定提示，未知码不臆造提示', () => {
    expect(campaignErrorHint(70035)).toContain('OWNER/ADMIN')
    expect(campaignErrorHint(70036)).toContain('刷新')
    expect(campaignErrorHint(70038)).toContain('未完成的OTA作业')
    expect(campaignErrorHint(70041)).toContain('notBefore')
    // 无发布产物时服务端以发布物错误码拒绝，提示必须指向ADR0119 signer边界。
    expect(campaignErrorHint(70021)).toContain('ADR0119')
    expect(campaignErrorHint(70022)).toContain('ADR0119')
    expect(campaignErrorHint(70099)).toBe('')
    expect(campaignErrorHint(undefined)).toBe('')
  })

  it('计划草稿只做可诚实判定的本地校验', () => {
    const base = {
      firmwareId: 'f1',
      deviceIds: ['d1', 'd2'],
      batchSize: 10,
      notBefore: '2026-09-15T12:00:00Z'
    }
    expect(checkCampaignDraft(base).ok).toBe(true)

    expect(checkCampaignDraft({ ...base, firmwareId: '' }).reason).toContain('固件')
    expect(checkCampaignDraft({ ...base, deviceIds: [] }).reason).toContain('目标设备')
    expect(checkCampaignDraft({ ...base, deviceIds: ['d1', 'd1'] }).reason).toContain('重复')
    expect(checkCampaignDraft({ ...base, batchSize: 0 }).reason).toContain('批次大小')
    expect(checkCampaignDraft({ ...base, batchSize: 1001 }).reason).toContain('批次大小')
    expect(checkCampaignDraft({ ...base, notBefore: '2026-09-15 12:00:00' }).reason).toContain(
      'UTC'
    )
    const many = Array.from({ length: 1001 }, (_, index) => `d${index}`)
    expect(checkCampaignDraft({ ...base, deviceIds: many }).reason).toContain('1000')
  })

  it('默认执行策略取保守值并落在服务端上下界内', () => {
    const policy = defaultCampaignPolicy()
    expect(policy.maxConcurrentDownloads).toBeGreaterThanOrEqual(1)
    expect(policy.maxConcurrentDownloads).toBeLessThanOrEqual(1000)
    expect(policy.downloadRetryLimit).toBeGreaterThanOrEqual(0)
    expect(policy.downloadRetryLimit).toBeLessThanOrEqual(10)
    expect(policy.retryBackoffSeconds).toBeGreaterThanOrEqual(1)
    expect(policy.retryBackoffSeconds).toBeLessThanOrEqual(3600)
    expect(policy.pauseFailureRateBps).toBeLessThanOrEqual(10_000)
    expect(policy.batchMinSuccessRateBps).toBeLessThanOrEqual(10_000)
    expect(policy.requireManualBatchApproval).toBe(true)
    // 九个阶段都必须给出期限，缺一个服务端就会以计划不合法拒绝。
    expect(Object.keys(policy.stageTimeoutSeconds)).toHaveLength(9)
    for (const seconds of Object.values(policy.stageTimeoutSeconds)) {
      expect(seconds).toBeGreaterThanOrEqual(1)
      expect(seconds).toBeLessThanOrEqual(86_400)
    }
  })

  it('批次计数只汇总真实终态，并给出可诚实检查的一致性', () => {
    const batch = {
      targetCount: 5,
      succeededCount: 3,
      rolledBackCount: 1,
      skippedCount: 1,
      cancelledCount: 0
    }
    expect(batchTotals(batch)).toMatchObject({ settled: 5, inconsistent: false })
    expect(batchTotals({ ...batch, succeededCount: 9 }).inconsistent).toBe(true)
    expect(batchTotals({ ...batch, targetCount: 6, timedOutCount: 1 })).toMatchObject({
      settled: 6,
      timedOut: 1,
      succeeded: 3,
      rolledBack: 1,
      inconsistent: false
    })
    expect(batchTotals({ ...batch, timedOutCount: 1 }).inconsistent).toBe(true)
    expect(batchTotals({})).toMatchObject({ settled: 0, inconsistent: false })
  })

  it('本地时间只转成契约要求的UTC秒级字符串', () => {
    const utc = toNotBeforeUtc(new Date(Date.UTC(2026, 8, 15, 12, 0, 0)))
    expect(utc).toBe('2026-09-15T12:00:00Z')
    expect(utc).toMatch(/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$/)
  })
})
