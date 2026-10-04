import { describe, expect, it, vi } from 'vitest'
import {
  assertHistoryContainsReport,
  instantNanos,
  reportFromMessageLogs,
  synchronizeHistory,
  type HistoryObservation,
  type ReportFact,
  type MessageObservation
} from '../e2e/history-contract'

const report: ReportFact = {
  projectId: 'project',
  deviceId: 'child',
  propertyKey: 'temperature',
  occurredAt: '2026-09-02T18:12:49.718386Z',
  value: 22,
  createdAt: '2026-09-02T18:12:48Z',
  messageId: '01a06352-dbb6-7c46-acd7-e674dd32c735',
  sourceLog: {
    id: '01a06352-dbb6-7c46-acd7-e674dd32c736',
    deviceId: 'child',
    messageId: '01a06352-dbb6-7c46-acd7-e674dd32c735',
    protocol: 'MQTT',
    direction: 'UP',
    errorCode: null,
    ts: '2026-09-02T18:12:49.718386Z',
    payloadSummary: '{"temperature":22}'
  }
}
function messages(): MessageObservation {
  return {
    url: 'http://localhost:3007/api/v1/projects/project/devices/child/messages?direction=UP&protocol=MQTT&from=2026-09-02T18:12:48Z&to=2026-09-02T18:12:50Z&limit=200',
    method: 'GET',
    status: 200,
    body: { items: [{ ...report.sourceLog }], nextCursor: null, hasMore: false }
  }
}
function observation(): HistoryObservation {
  return {
    url:
      'http://localhost:3007/api/v1/projects/project/devices/child/telemetry/property/history?' +
      'propertyKey=temperature&from=2026-09-02T17:12:50Z&to=2026-09-02T18:12:50Z&granularity=RAW&aggregation=AVG',
    method: 'GET',
    status: 200,
    requestSequence: 2,
    afterSequence: 1,
    body: {
      requestedGranularity: 'RAW',
      actualGranularity: 'RAW',
      aggregation: 'AVG',
      points: [{ ts: report.occurredAt, value: report.value, sampleCount: 1 }]
    }
  }
}
describe('Q6 上报与新历史查询的因果同步', () => {
  it('R1 旧路径反例：实际热缓存的毫秒投影会误拒同消息微秒历史', () => {
    const cached = { ...report, occurredAt: '2026-09-02T18:12:49.718Z' }
    expect(instantNanos(cached.occurredAt)).not.toBe(instantNanos(report.occurredAt))
    expect(() => assertHistoryContainsReport(cached, observation())).toThrow('绑定被更改')
    expect(() =>
      assertHistoryContainsReport(reportFromMessageLogs(report, messages()), observation())
    ).not.toThrow()
  })
  it('重现 Q5 旧窗口反例：上报已出现也不能让首次空响应通过', () => {
    const old = observation()
    old.url = old.url.replace('18:12:50Z', '18:12:48.948Z')
    old.body = {
      requestedGranularity: 'RAW',
      actualGranularity: 'RAW',
      aggregation: 'AVG',
      points: []
    }
    expect(() => assertHistoryContainsReport(report, old)).toThrow('窗口不包含')
  })
  it('受控异步：上报未完成时不得触发历史，随后只触发一次新查询', async () => {
    let resolve!: (value: ReportFact) => void
    const pending = new Promise<ReportFact>((done) => {
      resolve = done
    })
    const fresh = vi.fn(async () => observation())
    const result = synchronizeHistory(() => pending, fresh)
    await Promise.resolve()
    expect(fresh).not.toHaveBeenCalled()
    resolve(report)
    await expect(result).resolves.toEqual(report)
    expect(fresh).toHaveBeenCalledExactlyOnceWith(report)
  })
  it('新响应可以精确匹配微秒目标点', () => {
    expect(() => assertHistoryContainsReport(report, observation())).not.toThrow()
    expect(instantNanos('2026-09-02T18:12:49.718386001Z') - instantNanos(report.occurredAt)).toBe(
      1n
    )
  })
  it.each([
    'project',
    'device',
    'property',
    'value',
    'old',
    'unknown-sequence',
    'negative-sequence',
    'empty',
    'wrong-point',
    'duplicate-point',
    'equal-to',
    'micro-to',
    'from',
    'status',
    'aggregate'
  ])('拒绝 %s 不匹配', (kind) => {
    const value = observation()
    if (kind === 'project') value.url = value.url.replace('/projects/project/', '/projects/other/')
    if (kind === 'device') value.url = value.url.replace('/devices/child/', '/devices/other/')
    if (kind === 'property')
      value.url = value.url.replace('propertyKey=temperature', 'propertyKey=pressure')
    if (kind === 'old') value.requestSequence = value.afterSequence
    if (kind === 'unknown-sequence') value.requestSequence = Number.NaN
    if (kind === 'negative-sequence') value.afterSequence = -1
    if (kind === 'status') value.status = 500
    if (kind === 'equal-to') value.url = value.url.replace('18:12:50Z', report.occurredAt.slice(11))
    if (kind === 'micro-to') value.url = value.url.replace('18:12:50Z', '18:12:49.718385999Z')
    if (kind === 'from') value.url = value.url.replace('17:12:50Z', '18:12:50Z')
    if (['value', 'empty', 'wrong-point', 'duplicate-point', 'aggregate'].includes(kind)) {
      value.body = {
        requestedGranularity: 'RAW',
        actualGranularity: kind === 'aggregate' ? 'ONE_MINUTE' : 'RAW',
        aggregation: 'AVG',
        points:
          kind === 'empty'
            ? []
            : Array.from({ length: kind === 'duplicate-point' ? 2 : 1 }, () => ({
                ts: kind === 'wrong-point' ? '2026-09-02T18:12:49.718385Z' : report.occurredAt,
                value: kind === 'value' ? 23 : 22,
                sampleCount: 1
              }))
      }
    }
    expect(() => assertHistoryContainsReport(report, value)).toThrow()
  })
  it.each([
    'missing-value',
    'missing-time',
    'wrong-device',
    'wrong-property',
    'missing-message',
    'invalid-message',
    'duplicate-message',
    'wrong-direction',
    'error-log',
    'missing-error-state',
    'invalid-payload',
    'truncated-payload',
    'extra-property',
    'non-numeric',
    'old-device-log',
    'unpersisted-nanos',
    'outside-to',
    'empty',
    'incomplete',
    'wrong-project-path',
    'wrong-device-path',
    'wrong-from',
    'wrong-protocol',
    'wrong-status'
  ])('日志 %s 不能伪装成本轮目标上报', (kind) => {
    const input = messages()
    const row = { ...report.sourceLog }
    if (kind === 'missing-value') row.payloadSummary = '{}'
    if (kind === 'missing-time') delete row.ts
    if (kind === 'missing-message') delete row.messageId
    if (kind === 'invalid-message') row.messageId = 'not-uuid'
    if (kind === 'wrong-device') row.deviceId = 'other'
    if (kind === 'wrong-direction') row.direction = 'DOWN'
    if (kind === 'error-log') row.errorCode = 'PROCESSING_FAILED'
    if (kind === 'missing-error-state') delete row.errorCode
    if (kind === 'invalid-payload') row.payloadSummary = 'not-json'
    if (kind === 'truncated-payload') row.payloadSummary = '{"temperature":22'
    if (kind === 'extra-property') row.payloadSummary = '{"temperature":22,"other":1}'
    if (kind === 'non-numeric') row.payloadSummary = '{"temperature":"22"}'
    if (kind === 'old-device-log') row.ts = '2026-09-02T18:12:47Z'
    if (kind === 'unpersisted-nanos') row.ts = '2026-09-02T18:12:49.718386500Z'
    if (kind === 'outside-to') row.ts = '2026-09-02T18:12:50Z'
    if (kind === 'wrong-protocol') row.protocol = 'HTTP'
    if (kind === 'wrong-project-path')
      input.url = input.url.replace('/projects/project/', '/projects/other/')
    if (kind === 'wrong-device-path')
      input.url = input.url.replace('/devices/child/', '/devices/other/')
    if (kind === 'wrong-from') input.url = input.url.replace('18:12:48Z', '18:12:47Z')
    if (kind === 'wrong-status') input.status = 500
    input.body = {
      items:
        kind === 'empty'
          ? []
          : kind === 'duplicate-message'
            ? [row, { ...row, id: '01a06352-dbb6-7c46-acd7-e674dd32c737' }]
            : [row],
      hasMore: kind === 'incomplete',
      nextCursor: null
    }
    expect(() =>
      reportFromMessageLogs(
        { ...report, propertyKey: kind === 'wrong-property' ? 'pressure' : 'temperature' },
        input
      )
    ).toThrow()
  })
  it('持久化日志冻结后不能更换 messageId 或值', () => {
    const bound = reportFromMessageLogs(report, messages())
    expect(() =>
      assertHistoryContainsReport(
        { ...bound, messageId: '01a06352-dbb6-7c46-acd7-e674dd32c734' },
        observation()
      )
    ).toThrow('绑定被更改')
    expect(() => assertHistoryContainsReport({ ...bound, value: 99 }, observation())).toThrow(
      '绑定被更改'
    )
  })
  it('只从本轮新设备完整日志选取最早确定性消息，不以列表首个任意事件替代', () => {
    const input = messages()
    input.body = {
      items: [
        {
          ...report.sourceLog,
          id: '01a06352-dbb6-7c46-acd7-e674dd32c738',
          messageId: '01a06352-dbb6-7c46-acd7-e674dd32c739',
          ts: '2026-09-02T18:12:49.900Z'
        },
        report.sourceLog
      ],
      hasMore: false,
      nextCursor: null
    }
    expect(reportFromMessageLogs(report, input)).toEqual(report)
  })
  it.each(['2026-02-30T00:00:00Z', '2026-09-02T18:12:49.1234567891Z', 'not-time'])(
    '未知或非法时间 %s 失败关闭',
    (value) => {
      expect(() => instantNanos(value)).toThrow()
    }
  )
})
