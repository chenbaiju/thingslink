import { beforeEach, describe, expect, it, vi } from 'vitest'
import { IdempotentSubmission } from '@/utils/idempotent-submission'

describe('写入意图幂等键生命周期', () => {
  beforeEach(() => vi.stubGlobal('crypto', { randomUUID: vi.fn() }))

  it('响应结果未知时，同一意图重试复用原键', () => {
    vi.mocked(crypto.randomUUID).mockReturnValue('00000000-0000-4000-8000-000000000001')
    const submission = new IdempotentSubmission()
    const first = submission.keyFor('device-1/reboot/{}')

    submission.failed(first, true)

    expect(submission.keyFor('device-1/reboot/{}')).toBe(first)
    expect(crypto.randomUUID).toHaveBeenCalledTimes(1)
  })

  it('成功或明确拒绝后释放；修改意图也必须使用新键', () => {
    vi.mocked(crypto.randomUUID)
      .mockReturnValueOnce('00000000-0000-4000-8000-000000000001')
      .mockReturnValueOnce('00000000-0000-4000-8000-000000000002')
      .mockReturnValueOnce('00000000-0000-4000-8000-000000000003')
    const submission = new IdempotentSubmission()

    const first = submission.keyFor('device-1/reboot/{}')
    expect(submission.keyFor('device-1/reboot/{"force":true}')).not.toBe(first)

    const rejected = submission.keyFor('device-1/reboot/{"force":true}')
    submission.failed(rejected, false)
    const retryAfterRejection = submission.keyFor('device-1/reboot/{"force":true}')
    expect(retryAfterRejection).not.toBe(rejected)

    submission.succeeded(retryAfterRejection)
    expect(crypto.randomUUID).toHaveBeenCalledTimes(3)
  })
})
