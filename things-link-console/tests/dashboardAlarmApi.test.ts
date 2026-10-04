import { describe, expect, it, vi } from 'vitest'
import {
  parseDashboardRuntimeResponse,
  type AlarmQuery
} from '@things-link/client-contracts/dashboard/v1'
import { fetchDesignerAlarms } from '@/api/dashboard-alarms'
const id = '11111111-1111-4111-8111-111111111111'
const query: AlarmQuery = {
  queryId: 'one',
  devices: [{ deviceId: id, expectedModelVersionId: id }],
  conditionStates: ['PENDING'],
  ackStates: ['ACKNOWLEDGED'],
  severities: ['MAJOR'],
  limit: 1
}
const body = () =>
  parseDashboardRuntimeResponse(
    new TextEncoder().encode(
      JSON.stringify({
        items: [
          {
            id,
            deviceId: id,
            alarmType: '<script>不会执行</script>',
            severity: 'MAJOR',
            conditionState: 'PENDING',
            ackState: 'ACKNOWLEDGED',
            firstConditionAt: '2026-09-08T00:00:00Z',
            lastReceivedAt: '2026-09-08T00:00:00Z',
            activatedAt: null,
            clearedAt: null,
            acknowledgedAt: null,
            version: 2147483647
          }
        ],
        nextCursor: null,
        hasMore: false
      })
    )
  )
function scope() {
  return {
    deadline: 30_000,
    tryReserveWebSocket: vi.fn(() => true),
    pace: vi.fn(async () => {}),
    read: vi.fn(async () => body()),
    close: vi.fn()
  }
}
describe('Console告警严格适配', () => {
  it('精确Console路径与原游标，品牌int32验证后安全转number', async () => {
    const reads = scope()
    const result = await fetchDesignerAlarms(id, query, 'component', 'signed', reads)
    expect(reads.pace).toHaveBeenCalledOnce()
    expect(reads.read).toHaveBeenCalledWith(`/api/v1/projects/${id}/alarms/query`, {
      devices: query.devices,
      conditionStates: ['PENDING'],
      ackStates: ['ACKNOWLEDGED'],
      severities: ['MAJOR'],
      limit: 1,
      cursor: 'signed'
    })
    expect(result.items[0]?.version).toBe(2147483647)
    expect(result.items[0]?.conditionState).toBe('PENDING')
    expect(result.items[0]?.ackState).toBe('ACKNOWLEDGED')
    expect(reads.close).not.toHaveBeenCalled()
  })
  it('仅400/10001清列表，失权、非数值和系统错误继续抛出', async () => {
    const reads = scope()
    reads.read.mockRejectedValueOnce({ status: 400, code: 10001 })
    expect((await fetchDesignerAlarms(id, query, 'component', undefined, reads)).status).toBe(
      'CONFIGURATION_ERROR'
    )
    for (const error of [
      { status: 404 },
      { status: 400, code: 30058 },
      { status: 500, code: 10001 }
    ]) {
      reads.read.mockRejectedValueOnce(error)
      await expect(fetchDesignerAlarms(id, query, 'component', undefined, reads)).rejects.toEqual(
        error
      )
    }
  })
  it('非法cursor不发请求，也不回退首页', async () => {
    const reads = scope()
    await expect(fetchDesignerAlarms(id, query, 'component', '\n', reads)).rejects.toThrow()
    expect(reads.read).not.toHaveBeenCalled()
  })
})
