import { afterEach, describe, expect, it, vi } from 'vitest';
import { createRecoveryBudget, type RecoveryBudgetPorts } from './recovery-budget';
const app = `/api/v1/app/applications/app_${'a'.repeat(32)}`;
const data = '/api/v1/app/devices/snapshots/query';
const identity = '/api/v1/app/browser-auth/refresh';
function fixture() {
  vi.useFakeTimers(); vi.setSystemTime(0);
  const fetch = vi.fn<RecoveryBudgetPorts['fetch']>(async () => new Response('{}'));
  const onFailure = vi.fn();
  const onWait = vi.fn();
  const budget = createRecoveryBudget({ origin: 'https://app.test', fetch, now: () => Date.now(),
    setTimer: (callback, delay) => setTimeout(callback, delay), clearTimer: (timer) => clearTimeout(timer as ReturnType<typeof setTimeout>), onFailure, onWait });
  return { budget, fetch, onFailure, onWait };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
afterEach(() => vi.useRealTimers());
describe('tab-wide recovery transport budget', () => {
  it('counts resolve/auth/current/schema together, retaining attempts and received bytes across rounds', async () => {
    const f = fixture(); const first = f.budget.beginRound('full');
    await f.budget.fetch(first, `${app}/resolve`);
    await f.budget.fetch(first, identity, { method: 'POST' });
    await f.budget.fetch(first, `${app}/current`);
    await f.budget.fetch(first, `${app}/versions/id/dashboards/id/schema`);
    expect(f.budget.snapshot()).toMatchObject({ attempts60s: 4, receivedBytes60s: 8, reservedBytes: 0, inFlight: 0 });
    f.budget.cancelRound(first); const second = f.budget.beginRound('full');
    const fifth = f.budget.fetch(second, `${app}/resolve`);
    expect(f.fetch).toHaveBeenCalledTimes(4);
    await vi.advanceTimersByTimeAsync(1000); await fifth;
    expect(f.budget.snapshot().attempts60s).toBe(5);
  });
  it('rejects a 21st attempt and never refunds cancelled generations', async () => {
    const f = fixture(); const round = f.budget.beginRound('full');
    for (let n = 0; n < 20; n++) { if (n && n % 4 === 0) await vi.advanceTimersByTimeAsync(1000); await f.budget.fetch(round, data); }
    await expect(f.budget.fetch(round, data)).rejects.toMatchObject({ reason: 'attempts' });
    expect(f.fetch).toHaveBeenCalledTimes(20);
    expect(f.onFailure).toHaveBeenCalledWith(round, 'attempts');
    f.budget.beginRound('interaction'); expect(f.budget.snapshot().attempts60s).toBe(20);
  });
  it('holds at most four REST slots and exactly one current-values slot', async () => {
    const f = fixture(); const round = f.budget.beginRound('full');
    const response = deferred<Response>(); f.fetch.mockImplementationOnce(() => response.promise);
    const one = f.budget.fetch(round, '/api/v1/app/devices/current-values/query', { method: 'POST' });
    const two = f.budget.fetch(round, '/api/v1/app/devices/current-values/query', { method: 'POST' });
    await Promise.resolve(); expect(f.fetch).toHaveBeenCalledTimes(1);
    expect(f.budget.snapshot().currentInFlight).toBe(1);
    response.resolve(new Response('{}')); await one; await two;
    expect(f.budget.snapshot().currentInFlight).toBe(0);
  });
  it('drains all four 401 bodies before refresh needs a REST slot', async () => {
    const f = fixture(); const round = f.budget.beginRound('full');
    f.fetch.mockImplementation(async () => new Response('{}', { status: 401 }));
    const responses = await Promise.all(Array.from({ length: 4 }, () => f.budget.fetch(round, data)));
    expect(responses.map((entry) => entry.status)).toEqual([401, 401, 401, 401]);
    expect(f.budget.snapshot().inFlight).toBe(0);
    const refresh = f.budget.fetch(round, identity, { method: 'POST' });
    await vi.advanceTimersByTimeAsync(1000); await refresh;
    expect(f.fetch).toHaveBeenCalledTimes(5);
  });
  it('includes pre-fetch lock waiting in the fixed deadline and never sends an expired operation', async () => {
    const f = fixture(); const round = f.budget.beginRound('full');
    await vi.advanceTimersByTimeAsync(30000);
    await expect(f.budget.fetch(round, identity, { method: 'POST' })).rejects.toMatchObject({ reason: 'deadline' });
    expect(f.fetch).not.toHaveBeenCalled();
  });
  it('notifies identity deadline immediately but never settles or aborts until its stream terminates', async () => {
    const f = fixture(); const round = f.budget.beginRound('full');
    let stream!: ReadableStreamDefaultController<Uint8Array>;
    f.fetch.mockImplementation(async (_input, init) => {
      expect(init?.signal).toBeUndefined();
      return new Response(new ReadableStream({ start(controller) { stream = controller; } }));
    });
    let finished = false;
    const attempt = f.budget.fetch(round, identity, { method: 'POST' });
    const rejected = expect(attempt).rejects.toMatchObject({ reason: 'deadline' });
    void attempt.finally(() => { finished = true; }).catch(() => undefined);
    await Promise.resolve(); stream.enqueue(new Uint8Array(3));
    await vi.advanceTimersByTimeAsync(30000);
    expect(f.onFailure).toHaveBeenCalledWith(round, 'deadline');
    expect(finished).toBe(false); expect(f.budget.snapshot().inFlight).toBe(1);
    stream.enqueue(new Uint8Array(5)); stream.close(); await rejected;
    expect(f.budget.snapshot()).toMatchObject({ inFlight: 0, receivedBytes60s: 8 });
  });
  it('charges decoded chunks, rejects resolve overflow, and releases only unused reservation', async () => {
    const f = fixture(); const round = f.budget.beginRound('full');
    f.fetch.mockImplementation(async () => new Response(new Uint8Array(2049), { headers: { 'Content-Length': '1', 'Content-Encoding': 'gzip' } }));
    await expect(f.budget.fetch(round, `${app}/resolve`)).rejects.toMatchObject({ reason: 'bytes' });
    expect(f.budget.snapshot()).toMatchObject({ receivedBytes60s: 2049, reservedBytes: 0, attempts60s: 1 });
  });
  it('enforces the eight MiB round total instead of truncating a third valid response', async () => {
    const f = fixture(); const round = f.budget.beginRound('full');
    f.fetch.mockImplementation(async () => new Response(new Uint8Array(4 * 1024 * 1024)));
    await f.budget.fetch(round, data); await f.budget.fetch(round, data);
    await expect(f.budget.fetch(round, data)).rejects.toMatchObject({ reason: 'bytes' });
    expect(f.fetch).toHaveBeenCalledTimes(2);
  });
  it('reserves non-full bytes before sending, preserving the eight MiB full recovery margin', async () => {
    const f = fixture(); f.fetch.mockImplementation(async () => new Response(new Uint8Array(4 * 1024 * 1024)));
    for (let n = 0; n < 7; n++) {
      const round = f.budget.beginRound('interaction');
      await vi.advanceTimersByTimeAsync(1000);
      await f.budget.fetch(round, data); await f.budget.fetch(round, data); f.budget.endRound(round);
    }
    expect(f.budget.snapshot().interactionBytes60s).toBe(56 * 1024 * 1024);
    const blockedRound = f.budget.beginRound('interaction'); const blocked = f.budget.fetch(blockedRound, data);
    const cancelled = expect(blocked).rejects.toMatchObject({ reason: 'cancelled' });
    expect(f.fetch).toHaveBeenCalledTimes(14);
    expect(f.onWait).toHaveBeenLastCalledWith(blockedRound, { nextAvailableAt: 61000 });
    f.budget.cancelRound(blockedRound); await cancelled;
    const full = f.budget.beginRound('full'); await vi.advanceTimersByTimeAsync(1000);
    await f.budget.fetch(full, data); await f.budget.fetch(full, data);
    expect(f.budget.snapshot().receivedBytes60s).toBe(64 * 1024 * 1024);
  });
  it('retains non-full 100 attempts across rounds and leaves twenty for full recovery', async () => {
    const f = fixture();
    for (let batch = 0; batch < 5; batch++) {
      const round = f.budget.beginRound('interaction');
      for (let i = 0; i < 20; i++) { if (i % 4 === 0) await vi.advanceTimersByTimeAsync(1000); await f.budget.fetch(round, `${app}/resolve`); }
    }
    const blockedRound = f.budget.beginRound('interaction');
    const blocked = f.budget.fetch(blockedRound, data); const cancelled = expect(blocked).rejects.toMatchObject({ reason: 'cancelled' });
    expect(f.onWait).toHaveBeenLastCalledWith(blockedRound, { nextAvailableAt: 61000 });
    f.budget.cancelRound(blockedRound); await cancelled;
    const full = f.budget.beginRound('full'); await vi.advanceTimersByTimeAsync(1000);
    await f.budget.fetch(full, `${app}/resolve`);
    expect(f.budget.snapshot()).toMatchObject({ attempts60s: 101, interactionAttempts60s: 100 });
  });
  it('bounds the global sixty-second window at 120 attempts independently of new rounds', async () => {
    const f = fixture();
    for (let batch = 0; batch < 6; batch++) {
      const round = f.budget.beginRound('full');
      for (let i = 0; i < 20; i++) { if (i % 4 === 0) await vi.advanceTimersByTimeAsync(1000); await f.budget.fetch(round, `${app}/resolve`); }
      f.budget.endRound(round);
    }
    const round = f.budget.beginRound('full'); const blocked = f.budget.fetch(round, `${app}/resolve`);
    const rejection = expect(blocked).rejects.toMatchObject({ reason: 'deadline' });
    expect(f.onWait).toHaveBeenLastCalledWith(round, { nextAvailableAt: 61000 });
    await vi.advanceTimersByTimeAsync(30000); await rejection;
    expect(f.fetch).toHaveBeenCalledTimes(120);
    await vi.advanceTimersByTimeAsync(1000);
    const next = f.budget.beginRound('full'); await f.budget.fetch(next, `${app}/resolve`);
    expect(f.fetch).toHaveBeenCalledTimes(121);
  });
  it('keeps four pending HTTP slots across a generation change until identity requests finish', async () => {
    const f = fixture(); const first = f.budget.beginRound('full');
    const responses = Array.from({ length: 4 }, () => deferred<Response>());
    let index = 0; f.fetch.mockImplementation(() => responses[index++]!.promise);
    const pending = responses.map(() => f.budget.fetch(first, identity, { method: 'POST' }));
    const cancelled = pending.map((promise) => expect(promise).rejects.toMatchObject({ reason: 'cancelled' }));
    const next = f.budget.beginRound('full');
    const waiting = f.budget.fetch(next, data); const waitingCancelled = expect(waiting).rejects.toMatchObject({ reason: 'cancelled' });
    expect(f.onWait).toHaveBeenLastCalledWith(next, { nextAvailableAt: null });
    await vi.advanceTimersByTimeAsync(1000);
    expect(f.fetch).toHaveBeenCalledTimes(4); expect(f.budget.snapshot().inFlight).toBe(4);
    f.budget.cancelRound(next); await waitingCancelled;
    responses.forEach((entry) => entry.resolve(new Response('{}'))); await Promise.all(cancelled);
    expect(f.budget.snapshot()).toMatchObject({ inFlight: 0, receivedBytes60s: 8, attempts60s: 4 });
  });

  it('honors 429 retry-after without resetting windows on explicit retry', async () => {
    const f = fixture(); const first = f.budget.beginRound('full');
    f.fetch.mockImplementationOnce(async () => new Response('{}', { status: 429, headers: { 'Retry-After': '5' } }));
    await f.budget.fetch(first, data); f.budget.endRound(first);
    const next = f.budget.beginRound('full'); const waiting = f.budget.fetch(next, data);
    expect(f.onWait).toHaveBeenLastCalledWith(next, { nextAvailableAt: 5000 });
    await vi.advanceTimersByTimeAsync(4999); expect(f.fetch).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1); await waiting;
    expect(f.budget.snapshot().attempts60s).toBe(2);
  });
  it('accepts same-origin absolute session URLs but refuses external and parser-confusing routes before dispatch', async () => {
    const f = fixture(); const round = f.budget.beginRound('full');
    for (const input of [
      `https://evil.test${app}/resolve`, `//evil.test${app}/resolve`, `//app.test${app}/resolve`,
      `http://app.test${app}/resolve`, `https://app.test:444${app}/resolve`,
      `https://user:password@app.test${app}/resolve`, `https://app.test${app}/resolve#fragment`,
      `/\\evil.test${app}/resolve`, `/\n/evil.test${app}/resolve`, 'not a url',
    ]) await expect(f.budget.fetch(round, input)).rejects.toMatchObject({ reason: 'route' });
    expect(f.fetch).not.toHaveBeenCalled();
    expect(f.budget.snapshot()).toMatchObject({ attempts60s: 0, inFlight: 0, reservedBytes: 0 });
    await f.budget.fetch(round, `https://app.test${app}/resolve`, { mode: 'cors', redirect: 'follow' });
    expect(f.fetch.mock.calls[0][1]).toMatchObject({ mode: 'same-origin', redirect: 'error' });
  });

  it('reports the local one-second window and retains another queued wait when one request cancels', async () => {
    const f = fixture(); const round = f.budget.beginRound('full');
    for (let i = 0; i < 4; i++) await f.budget.fetch(round, `${app}/resolve`);
    const controller = new AbortController();
    const first = f.budget.fetch(round, data, { signal: controller.signal });
    const rejected = expect(first).rejects.toMatchObject({ name: 'AbortError' });
    const second = f.budget.fetch(round, data);
    expect(f.onWait).toHaveBeenLastCalledWith(round, { nextAvailableAt: 1000 });
    expect(f.budget.snapshot().retryAfter).toBe(0);
    controller.abort(); await rejected;
    expect(f.onWait).toHaveBeenLastCalledWith(round, { nextAvailableAt: 1000 });
    await vi.advanceTimersByTimeAsync(1000); await second;
    expect(f.onWait).toHaveBeenLastCalledWith(round, null);
  });

  it('retains Retry-After from a late identity 429 even after its original round expires', async () => {
    const f = fixture(); const round = f.budget.beginRound('full');
    const late = deferred<Response>(); f.fetch.mockReturnValueOnce(late.promise);
    const request = f.budget.fetch(round, identity, { method: 'POST' });
    const rejected = expect(request).rejects.toMatchObject({ reason: 'deadline' });
    await vi.advanceTimersByTimeAsync(30000);
    late.resolve(new Response('{}', { status: 429, headers: { 'Retry-After': '5' } }));
    await rejected;
    expect(f.budget.snapshot().retryAfter).toBe(35000);
    const next = f.budget.beginRound('full'); const pending = f.budget.fetch(next, data);
    expect(f.onWait).toHaveBeenLastCalledWith(next, { nextAvailableAt: 35000 });
    await vi.advanceTimersByTimeAsync(5000); await pending;
  });

  it('enforces monotonic deadline between ready chunks without relying on the timer task', async () => {
    const f = fixture(); const round = f.budget.beginRound('full');
    let pulls = 0;
    const cancelled = vi.fn();
    f.fetch.mockImplementationOnce(async () => new Response(new ReadableStream({
      pull(controller) {
        pulls++;
        // 只推进时钟，不运行setTimeout任务，模拟read微任务持续就绪。
        vi.setSystemTime(30001);
        controller.enqueue(new Uint8Array([1]));
      }, cancel: cancelled,
    }, { highWaterMark: 0 })));
    await expect(f.budget.fetch(round, data)).rejects.toMatchObject({ reason: 'deadline' });
    expect(pulls).toBe(1);
    expect(cancelled).toHaveBeenCalledTimes(1);
    expect(f.onFailure).toHaveBeenCalledWith(round, 'deadline');
    expect(f.budget.snapshot().receivedBytes60s).toBe(1);
  });

});

describe('realtime handshake budget', () => {
  it('permits one handshake per round and four per rolling minute without refunds or REST slots', async () => {
    const f = fixture();
    for (let i = 0; i < 4; i++) {
      const round = f.budget.beginRound('full');
      expect(f.budget.tryReserveWebSocket(round)).toBe(true);
      expect(f.budget.tryReserveWebSocket(round)).toBe(false);
      f.budget.cancelRound(round);
    }
    const blocked = f.budget.beginRound('full');
    expect(f.budget.tryReserveWebSocket(blocked)).toBe(false);
    expect(f.budget.snapshot()).toMatchObject({ handshakes60s: 4, attempts60s: 0, inFlight: 0 });
    expect(f.onFailure).not.toHaveBeenCalled();
    await f.budget.fetch(blocked, data);
    f.budget.endRound(blocked);
    await vi.advanceTimersByTimeAsync(60000);
    const next = f.budget.beginRound('full');
    expect(f.budget.tryReserveWebSocket(next)).toBe(true);
  });
  it('rejects a handshake after its original deadline', async () => {
    const f = fixture(); const round = f.budget.beginRound('full');
    await vi.advanceTimersByTimeAsync(30000);
    expect(() => f.budget.tryReserveWebSocket(round)).toThrow();
    expect(f.budget.snapshot().handshakes60s).toBe(0);
  });
});
it('defers automatic current rounds until rolling quota is eligible without failure', async () => {
  const f = fixture();
  const round = f.budget.beginRound('interaction');
  for (let i = 0; i < 4; i++) await f.budget.fetch(round, data);
  f.budget.endRound(round);
  expect(f.budget.nextCurrentInteractionAt()).toBe(1000);
  expect(f.onFailure).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1000);
  expect(f.budget.nextCurrentInteractionAt()).toBe(1000);
});
it('budgets only seven explicit share routes and shares the single current-values slot', async () => {
  const f = fixture(); const round = f.budget.beginRound('full');
  const base = '/api/v1/shares/11111111-1111-4111-8111-111111111111';
  await f.budget.fetch(round, `${base}/context`);
  await f.budget.fetch(round, `${base}/schema`);
  expect(f.budget.snapshot().attempts60s).toBe(2);
  await expect(f.budget.fetch(round, `${base}/revoke`, { method: 'POST' })).rejects.toMatchObject({ reason: 'route' });
  await expect(f.budget.fetch(round, `${base}/schema`, { method: 'POST' })).rejects.toMatchObject({ reason: 'route' });
  const response = deferred<Response>(); f.fetch.mockImplementationOnce(() => response.promise);
  const current = f.budget.fetch(round, `${base}/devices/current-values/query`, { method: 'POST' });
  expect(f.budget.snapshot().currentInFlight).toBe(1);
  response.resolve(new Response('{}')); await current;
  expect(f.budget.snapshot().currentInFlight).toBe(0);
});
