import { afterEach, describe, expect, it, vi } from 'vitest';
import { createDeviceControl } from './device-control';
const device = '11111111-1111-4111-8111-111111111111';
const commandId = '22222222-2222-4222-8222-222222222222';
const user = '33333333-3333-4333-8333-333333333333';
const key = '44444444-4444-4444-8444-444444444444';
const definition = { commandKey: '1-start', name: '<img src=x onerror=alert(1)>', description: '<script>private</script>', inputSchema: '{"type":"object"}', outputSchema: null, timeoutSeconds: 30 };
const result = (status = 'ACCEPTED') => ({ commandId, status, commandKey: definition.commandKey, response: null, acceptedAt: '2026-09-07T00:00:00Z', failureCode: null, failureMessage: null });
function fixture() {
  let now = 0; let available = true;
  const checkCurrent = vi.fn(() => ({ status: 'authenticated' as const, appUserId: user, projectId: device }));
  const fetch = vi.fn(async (_path: string, init?: RequestInit): Promise<Response> => Response.json(result(), { status: init?.method === 'POST' ? 202 : 200 }));
  const session = { checkCurrent, fetchDeviceControl: vi.fn((path: string, init: RequestInit, transport: typeof fetch) => transport(path, init)) };
  const changed = vi.fn();
  const control = createDeviceControl({ session, fetch, now: () => now, randomId: () => key, available: () => available,
    setTimer: (callback, delay) => setTimeout(callback, delay), clearTimer: timer => clearTimeout(timer as ReturnType<typeof setTimeout>), changed });
  async function ready() {
    fetch.mockResolvedValueOnce(Response.json({ items: [{ id: device, name: '设备', deviceKey: 'key', description: null, status: 'ONLINE', location: null, lastOnlineAt: null, createdAt: null }], nextCursor: null, hasMore: false }));
    await control.devices();
    fetch.mockResolvedValueOnce(Response.json({ deviceId: device, commands: [definition] }));
    await control.selectDevice(device); control.selectCommand(definition.commandKey); fetch.mockClear();
  }
  return { control, fetch, checkCurrent, changed, ready, advance: (ms: number) => { now += ms; }, offline: () => { available = false; } };
}
afterEach(() => vi.useRealTimers());
describe('bounded explicit device control intents', () => {
  it('requires explicit directory and command selection and preserves input lexical bytes in the fixed POST', async () => {
    const f = fixture(); expect(f.fetch).not.toHaveBeenCalled(); await f.ready();
    const input = '{ "n":9007199254740993.123456789, "html":"<script>evil</script>" }';
    f.control.edit(input); await f.control.submit();
    expect(f.fetch).toHaveBeenCalledTimes(1);
    expect(f.fetch.mock.calls[0]![1]).toMatchObject({ method: 'POST', headers: { 'Idempotency-Key': key }, body: `{"commandKey":"1-start","input":${input}}` });
    expect(f.control.snapshot().result?.status).toBe('ACCEPTED'); expect(f.control.snapshot().result?.status).not.toBe('SUCCEEDED');
  });
  it('times out to UNKNOWN without automatic writes, locks edits and explicitly retries the exact original intent', async () => {
    vi.useFakeTimers(); const f = fixture(); await f.ready();
    f.fetch.mockImplementationOnce((_path, init) => new Promise((_, reject) => init?.signal?.addEventListener('abort', () => reject(new Error('network')), { once: true })));
    f.control.edit('{"a":1}'); const sent = f.control.submit(); await vi.advanceTimersByTimeAsync(10000); await sent;
    expect(f.control.snapshot().state).toBe('UNKNOWN'); expect(f.fetch).toHaveBeenCalledTimes(1);
    f.control.edit('{"a":2}'); await f.control.selectDevice(device); await f.control.submit();
    expect(f.control.snapshot().input).toBe('{"a":1}'); expect(f.fetch).toHaveBeenCalledTimes(1);
    await f.control.retry(); expect(f.fetch).toHaveBeenCalledTimes(2);
    expect(f.fetch.mock.calls[1]![1]?.body).toBe(f.fetch.mock.calls[0]![1]?.body);
    expect(f.fetch.mock.calls[1]![1]?.headers).toEqual(f.fetch.mock.calls[0]![1]?.headers);
    expect(f.control.snapshot().state).toBe('RESULT');
  });
  it('bounds delayed response bodies and does not create a second transport while one ignores cancellation', async () => {
    vi.useFakeTimers(); const f = fixture(); await f.ready(); let finish!: (response: Response) => void;
    f.fetch.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const sent = f.control.submit(); await vi.advanceTimersByTimeAsync(10000); await sent;
    await f.control.retry(); expect(f.fetch).toHaveBeenCalledTimes(1); expect(f.control.snapshot().state).toBe('UNKNOWN');
    finish(Response.json(result(), { status: 202 })); await vi.runAllTimersAsync();
    expect(f.control.snapshot().state).toBe('UNKNOWN'); await f.control.retry(); expect(f.fetch).toHaveBeenCalledTimes(2);
    f.fetch.mockResolvedValueOnce(new Response(new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode('{')); } })));
    const refresh = f.control.refresh(); await vi.advanceTimersByTimeAsync(10000); await refresh;
    expect(f.control.snapshot().message).toContain('未完成');
  });
  it('clears successful A before a new B intent and cannot refresh A after B becomes UNKNOWN', async () => {
    const f = fixture(); await f.ready(); f.fetch.mockResolvedValueOnce(Response.json(result('SUCCEEDED'), { status: 202 }));
    await f.control.submit(); expect(f.control.snapshot().result?.status).toBe('SUCCEEDED');
    f.fetch.mockRejectedValueOnce(new Error('network')); await f.control.submit();
    expect(f.control.snapshot()).toMatchObject({ state: 'UNKNOWN', result: null });
    const count = f.fetch.mock.calls.length; await f.control.refresh(); expect(f.fetch).toHaveBeenCalledTimes(count);
  });
  it('enforces the absolute ten-second deadline even when timer callbacks are starved', async () => {
    const f = fixture(); await f.ready(); f.fetch.mockImplementationOnce(async () => {
      f.advance(10000); return Response.json(result('SUCCEEDED'), { status: 202 });
    });
    await f.control.submit(); expect(f.control.snapshot()).toMatchObject({ state: 'UNKNOWN', result: null });
    expect(f.fetch).toHaveBeenCalledTimes(1);
  });
  it('abandon is explicit and explains it cannot undo a potentially accepted command', async () => {
    const f = fixture(); await f.ready(); f.fetch.mockRejectedValueOnce(new Error('network'));
    await f.control.submit(); expect(f.control.snapshot().state).toBe('UNKNOWN');
    f.control.abandon(); expect(f.control.snapshot().message).toContain('不是撤销');
    f.control.edit('{"new":true}'); expect(f.control.snapshot().input).toBe('{"new":true}');
  });
  it.each(['ACCEPTED', 'DISPATCHED', 'ACKNOWLEDGED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT'])('keeps authoritative %s distinct on explicit status refresh', async status => {
    const f = fixture(); await f.ready(); await f.control.submit(); f.fetch.mockClear();
    f.fetch.mockResolvedValueOnce(Response.json(result(status))); await f.control.refresh();
    expect(f.fetch).toHaveBeenCalledTimes(1); expect(f.fetch.mock.calls[0]![0].endsWith(`/commands/${commandId}`)).toBe(true);
    expect(f.control.snapshot().result?.status).toBe(status);
  });
  it('rejects duplicate/array/oversize input locally and bounds the whole command body, not just input', async () => {
    const f = fixture(); await f.ready();
    for (const input of ['[]', '{"a":1,"a":2}', '{', 'null']) { f.control.edit(input); await f.control.submit(); }
    f.control.edit('{"a":"' + 'x'.repeat(65520) + '"}'); await f.control.submit();
    expect(f.fetch).not.toHaveBeenCalled();
    f.control.edit('x'.repeat(65537)); expect(f.control.snapshot().message).toContain('未采用');
  });
  it('rejects an incomplete or oversized response and retains UNKNOWN for writes rather than claiming rejection', async () => {
    const f = fixture(); await f.ready();
    f.fetch.mockResolvedValueOnce(new Response(' '.repeat(262145), { status: 202 })); await f.control.submit();
    expect(f.control.snapshot().state).toBe('UNKNOWN'); expect(f.control.snapshot().result).toBeNull();
    f.fetch.mockResolvedValueOnce(Response.json({ ...result(), unexpected: true }, { status: 202 })); await f.control.retry();
    expect(f.control.snapshot().state).toBe('UNKNOWN');
  });
  it('enforces one request and twenty starts per minute without automatic pagination', async () => {
    const f = fixture(); f.fetch.mockResolvedValue(Response.json({ items: [], nextCursor: 'next', hasMore: true }));
    for (let i = 0; i < 21; i++) await f.control.devices();
    expect(f.fetch).toHaveBeenCalledTimes(20); expect(f.control.snapshot().message).toContain('频繁');
    f.advance(60000); await f.control.devices('next'); expect(f.fetch).toHaveBeenCalledTimes(21);
    expect(f.fetch.mock.calls[20]![0]).toContain('cursor=next');
  });
  it('clears all private state on dispose and fences a late successful response', async () => {
    const f = fixture(); await f.ready(); let finish!: (response: Response) => void;
    f.fetch.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })); const pending = f.control.submit();
    f.control.dispose(); finish(Response.json(result('SUCCEEDED'), { status: 202 })); await pending;
    expect(f.control.snapshot()).toMatchObject({ state: 'CLOSED', input: '', devices: [], commands: [], result: null });
    await f.control.retry(); expect(f.fetch).toHaveBeenCalledTimes(1);
  });
  it('clears stale data on control revocation and never starts a write offline', async () => {
    const f = fixture(); await f.ready(); await f.control.submit();
    f.fetch.mockResolvedValueOnce(Response.json({ code: 60010 }, { status: 404 })); await f.control.refresh();
    expect(f.control.snapshot()).toMatchObject({ result: null, commands: [], devices: [], state: 'REJECTED' });
    const offline = fixture(); await offline.ready(); offline.offline(); await offline.control.submit(); expect(offline.fetch).not.toHaveBeenCalled();
  });
});
