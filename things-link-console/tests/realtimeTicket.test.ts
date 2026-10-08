import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import {
  issueRealtimeTicket,
  validTicketRequest,
  type RealtimeTicketRequest,
  type RealtimeTicket
} from '@/api/realtime-ticket'
import { createTicketTool, type TicketToolState } from '@/features/realtime-ticket/model'
const auth = vi.hoisted(() => ({ epoch: 1, user: { accessToken: 'test-token' } }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => auth.user }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => auth.epoch }))
const id = '11111111-1111-4111-8111-111111111111'
const body: RealtimeTicketRequest = {
  protocol: 'WS',
  eventTypes: ['device.property.report'],
  devices: [{ deviceId: id, expectedModelVersionId: id, propertyKeys: ['temperature'] }]
}
const ticket = (): RealtimeTicket => ({
  ticketId: id,
  protocol: 'WS',
  credential: `tcrt1.${id}.${'a'.repeat(43)}`,
  expiresAt: new Date(Date.now() + 300000).toISOString(),
  endpoint: '/api/open/v1/realtime/ws',
  subprotocol: 'tc-realtime-v1',
  remainingMs: 300000
})
const fetcher = vi.fn()
beforeEach(() => {
  vi.stubGlobal('fetch', fetcher)
  vi.stubEnv('VITE_API_URL', '')
  fetcher.mockReset()
  auth.epoch = 1
  auth.user.accessToken = 'test-token'
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})
it('enforces exact event, unique devices/properties and per-device and aggregate bounds', () => {
  expect(validTicketRequest(body)).toBe(true)
  expect(validTicketRequest({ ...body, devices: [] })).toBe(false)
  expect(validTicketRequest({ ...body, devices: [body.devices[0], body.devices[0]] })).toBe(false)
  expect(
    validTicketRequest({ ...body, devices: [{ ...body.devices[0], propertyKeys: ['x', 'x'] }] })
  ).toBe(false)
  expect(
    validTicketRequest({
      ...body,
      devices: [{ ...body.devices[0], propertyKeys: Array.from({ length: 51 }, (_, i) => `p${i}`) }]
    })
  ).toBe(false)
  expect(
    validTicketRequest({
      ...body,
      eventTypes: ['other'] as unknown as RealtimeTicketRequest['eventTypes']
    })
  ).toBe(false)
})
it('sends the exact selected immutable model and one idempotency key, returns only matching protocol parameters', async () => {
  fetcher.mockResolvedValue(
    new Response(JSON.stringify(ticket()), {
      status: 201,
      headers: { Date: new Date().toUTCString() }
    })
  )
  const result = await issueRealtimeTicket(id, body, id, new AbortController().signal)
  expect(result.protocol).toBe('WS')
  expect(result.remainingMs).toBeGreaterThan(0)
  expect(fetcher.mock.calls[0][0]).toBe(`/api/v1/projects/${id}/realtime-tickets`)
  expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual(body)
  expect(fetcher.mock.calls[0][1].headers['Idempotency-Key']).toBe(id)
  expect(fetcher.mock.calls[0][1].credentials).toBe('omit')
})
it.each([
  [409, 10014],
  [503, 80002],
  [503, 80007],
  [429, 80005],
  [401, 80006]
])('does not replay rejected status %s code %s or expose server text', async (status, code) => {
  fetcher.mockResolvedValue(
    new Response(JSON.stringify({ code, message: 'untrusted-server-secret' }), { status })
  )
  await expect(issueRealtimeTicket(id, body, id, new AbortController().signal)).rejects.not.toThrow(
    'untrusted-server-secret'
  )
  expect(fetcher).toHaveBeenCalledTimes(1)
})
it('network failure stays unknown and is not retried', async () => {
  fetcher.mockRejectedValue(new TypeError('fetch'))
  await expect(issueRealtimeTicket(id, body, id, new AbortController().signal)).rejects.toThrow(
    '未知'
  )
  expect(fetcher).toHaveBeenCalledTimes(1)
})
it('drops secret when identity changes while receiving the first response', async () => {
  fetcher.mockImplementation(async () => {
    auth.epoch++
    return new Response(JSON.stringify(ticket()), { status: 201 })
  })
  await expect(issueRealtimeTicket(id, body, id, new AbortController().signal)).rejects.toThrow(
    '身份'
  )
})
it('rejects expired and mismatched protocol responses', async () => {
  fetcher.mockResolvedValue(
    new Response(JSON.stringify({ ...ticket(), protocol: 'MQTT' }), { status: 201 })
  )
  await expect(issueRealtimeTicket(id, body, id, new AbortController().signal)).rejects.toThrow(
    '协议'
  )
})
it('clears secret on expiration or close and requires an explicit new intent for each subsequent submission', async () => {
  vi.useFakeTimers()
  let visible!: TicketToolState
  const issue = vi.fn(async () => ({ ...ticket(), remainingMs: 1000 }))
  const model = createTicketTool({
    context: () => 'p:u',
    allowed: () => true,
    key: () => id,
    issue,
    changed: (state) => {
      visible = state
    }
  })
  await model.submit(body)
  expect(visible.ticket?.ticketId).toBe(id)
  await model.submit(body)
  expect(issue).toHaveBeenCalledTimes(1)
  vi.advanceTimersByTime(1000)
  expect(visible.ticket).toBeNull()
  expect(visible.error).toContain('到期')
  model.clear()
  await model.submit(body)
  expect(issue).toHaveBeenCalledTimes(2)
  model.clear()
  expect(visible.ticket).toBeNull()
})
it('ignores delayed result on scope disposal and does not submit without permission', async () => {
  let resolve!: (value: RealtimeTicket) => void
  let allowed = true
  let visible!: TicketToolState
  const issue = vi.fn(
    () =>
      new Promise<RealtimeTicket>((done) => {
        resolve = done
      })
  )
  const model = createTicketTool({
    context: () => 'p:u',
    allowed: () => allowed,
    key: () => id,
    issue,
    changed: (state) => {
      visible = state
    }
  })
  const pending = model.submit(body)
  model.clear()
  allowed = false
  resolve(ticket())
  await pending
  await model.submit(body)
  expect(visible.ticket).toBeNull()
  expect(visible.busy).toBe(false)
  expect(issue).toHaveBeenCalledTimes(1)
})

it.each(['', 'a b', '中文', 'a'.repeat(65), 'path/key'])(
  'rejects invalid property identifier %s before transport',
  async (property) => {
    const invalid = { ...body, devices: [{ ...body.devices[0], propertyKeys: [property] }] }
    expect(validTicketRequest(invalid)).toBe(false)
    await expect(
      issueRealtimeTicket(id, invalid, id, new AbortController().signal)
    ).rejects.toThrow('范围')
    expect(fetcher).not.toHaveBeenCalled()
  }
)
it.each([null, [], 'secret-shaped-but-invalid'])(
  'rejects malformed first response without exposing internal errors',
  async (value) => {
    fetcher.mockResolvedValue(new Response(JSON.stringify(value), { status: 201 }))
    await expect(issueRealtimeTicket(id, body, id, new AbortController().signal)).rejects.toThrow(
      '响应不完整'
    )
    expect(fetcher).toHaveBeenCalledTimes(1)
  }
)
it('rejects a genuinely expired first response without returning the credential', async () => {
  const now = Date.now()
  fetcher.mockResolvedValue(
    new Response(JSON.stringify({ ...ticket(), expiresAt: new Date(now - 1000).toISOString() }), {
      status: 201,
      headers: { Date: new Date(now).toUTCString() }
    })
  )
  await expect(issueRealtimeTicket(id, body, id, new AbortController().signal)).rejects.toThrow(
    '已过期'
  )
  expect(fetcher).toHaveBeenCalledTimes(1)
})
