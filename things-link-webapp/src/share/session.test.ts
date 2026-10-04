import { describe, expect, it, vi } from 'vitest';
import { createShareSession } from './session';
const id = '11111111-1111-4111-8111-111111111111';
const secret = `sh_${'A'.repeat(43)}`; const prefix = `/api/v1/shares/${id}`;
describe('anonymous capability transport', () => {
  it('forces anonymous same-origin transport and injects one private credential', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({})); const session = createShareSession(id, secret, { origin: 'https://app.test', fetch });
    await session.fetch(`${prefix}/context`, { credentials: 'include', redirect: 'follow' });
    const init = fetch.mock.calls[0]![1] as RequestInit;
    expect(init).toMatchObject({ credentials: 'omit', redirect: 'error', cache: 'no-store', mode: 'same-origin', referrerPolicy: 'same-origin' });
    expect(new Headers(init.headers).get('X-Share-Token')).toBe(secret); expect(new Headers(init.headers).has('Authorization')).toBe(false);
    expect(JSON.stringify(session)).not.toContain(secret);
  });
  it.each(['/api/v1/app/current', `${prefix}/context?token=bad`, `https://else.test${prefix}/context`, `${prefix}/devices/catalog?cursor=a&cursor=b`])('rejects paths outside exact capability route %s', async path => {
    const fetch = vi.fn(); const session = createShareSession(id, secret, { origin: 'https://app.test', fetch });
    await expect(session.fetch(path)).rejects.toMatchObject({ reason: 'invalid' }); expect(fetch).not.toHaveBeenCalled();
  });
  it('rejects App authorization, forged referrer, writes and oversized body', async () => {
    const fetch = vi.fn(); const session = createShareSession(id, secret, { origin: 'https://app.test', fetch });
    for (const init of [{ headers: { Authorization: 'Bearer old' } }, { referrer: 'https://app.test/' }, { method: 'DELETE' }]) await expect(session.fetch(`${prefix}/context`, init)).rejects.toMatchObject({ reason: 'invalid' });
    await expect(session.fetch(`${prefix}/alarms/query`, { method: 'POST', body: 'x'.repeat(65537) })).rejects.toMatchObject({ reason: 'invalid' }); expect(fetch).not.toHaveBeenCalled();
  });
  it('fences a late HTTP response and sanitizes underlying transport failures', async () => {
    let done!: (r: Response) => void; const fetch = vi.fn(() => new Promise<Response>(resolve => { done = resolve; }));
    const session = createShareSession(id, secret, { origin: 'https://app.test', fetch }); const pending = session.fetch(`${prefix}/context`); await vi.waitFor(() => expect(fetch).toHaveBeenCalled(), { timeout: 2000 }); session.dispose(); done(Response.json({}));
    await expect(pending).rejects.toMatchObject({ reason: 'disposed' }); expect(() => session.checkCurrent()).toThrow();
  });
  it('confines WS secret to selected protocols and blocks stale callbacks', async () => {
    const listeners = new Map<string, (e: { data?: unknown; code?: number }) => void>();
    const socket = { protocol: 'tc.share.properties.v1', send: vi.fn(), close: vi.fn(), addEventListener: (name: string, handler: (e: { data?: unknown; code?: number }) => void) => { listeners.set(name, handler); }, removeEventListener: (name: string) => { listeners.delete(name); } };
    const createSocket = vi.fn(() => socket); const session = createShareSession(id, secret, { origin: 'https://app.test', fetch: vi.fn(), createSocket });
    const handlers = { onOpen: vi.fn(), onMessage: vi.fn(), onClose: vi.fn() }; const connection = session.openShareRealtime(handlers);
    await vi.waitFor(() => expect(createSocket).toHaveBeenCalled(), { timeout: 2000 });
    expect(createSocket).toHaveBeenCalledWith(`wss://app.test/ws/shares/${id}/properties`, ['tc.share.properties.v1', `share.${secret}`]);
    listeners.get('open')!({}); expect(handlers.onOpen).toHaveBeenCalledOnce(); const late = listeners.get('message')!; connection.sendSubscribe('{}'); expect(() => connection.sendSubscribe('{}')).toThrow();
    const second = session.openShareRealtime(handlers); session.dispose(); late({ data: '{}' });
    expect(handlers.onMessage).not.toHaveBeenCalled(); expect(() => second.sendSubscribe('{}')).toThrow();
  });
});


describe('share source admission pacing', () => {
  function clock() {
    let time = 0; let sequence = 0;
    const timers = new Map<number, { due: number; run: () => void }>();
    return { now: () => time,
      setTimer: (run: () => void, delay: number) => { const id = ++sequence; timers.set(id, { due: time + delay, run }); return id; },
      clearTimer: (id: unknown) => { timers.delete(id as number); },
      advance: (amount: number) => { time += amount; for (const [id, task] of timers) if (task.due <= time) { timers.delete(id); task.run(); } } };
  }
  it('shares one 260ms start gate between REST and native handshake', async () => {
    const time = clock(); const starts: number[] = [];
    const fetch = vi.fn(async () => { starts.push(time.now()); return Response.json({}); });
    const socket = { protocol: 'tc.share.properties.v1', send: vi.fn(), close: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn() };
    const createSocket = vi.fn(() => { starts.push(time.now()); return socket; });
    const session = createShareSession(id, secret, { origin: 'https://app.test', fetch, createSocket, ...time });
    const first = session.fetch(`${prefix}/context`); const second = session.fetch(`${prefix}/schema`);
    const connection = session.openShareRealtime({ onOpen: vi.fn(), onClose: vi.fn(), onMessage: vi.fn() });
    expect(starts).toEqual([]); time.advance(1100); await first; await Promise.resolve(); await Promise.resolve(); expect(starts).toEqual([1100]); time.advance(259); expect(starts).toEqual([1100]); time.advance(1); expect(starts).toEqual([1100, 1360]);
    await second; await Promise.resolve(); await Promise.resolve(); time.advance(260); expect(starts).toEqual([1100, 1360, 1620]); connection.close(); session.dispose();
  });
  it('cancels queued requests and queued native socket before dispose returns', async () => {
    const time = clock(); const fetch = vi.fn().mockResolvedValue(Response.json({})); const createSocket = vi.fn();
    const session = createShareSession(id, secret, { origin: 'https://app.test', fetch, createSocket, ...time });
    const initial = session.fetch(`${prefix}/context`); time.advance(1100); await initial; await Promise.resolve(); await Promise.resolve();
    const queued = session.fetch(`${prefix}/schema`); const rejected = expect(queued).rejects.toMatchObject({ reason: 'disposed' });
    session.openShareRealtime({ onOpen: vi.fn(), onClose: vi.fn(), onMessage: vi.fn() }); session.dispose(); time.advance(1000);
    await rejected; expect(fetch).toHaveBeenCalledTimes(1); expect(createSocket).not.toHaveBeenCalled();
  });
  it('retains captured round rejection after admission waiting and never retries', async () => {
    const time = clock(); let active = true;
    const network = vi.fn().mockResolvedValue(Response.json({}));
    const fetch = vi.fn((path: string, init?: RequestInit) => { if (!active) return Promise.reject(new Error('expired original round')); return network(path, init); });
    const session = createShareSession(id, secret, { origin: 'https://app.test', fetch, captureFetch: () => fetch, ...time });
    const initial = session.fetch(`${prefix}/context`); time.advance(1100); await initial; await Promise.resolve(); await Promise.resolve(); const waiting = session.fetch(`${prefix}/schema`);
    const rejected = expect(waiting).rejects.toMatchObject({ reason: 'unavailable' }); active = false; time.advance(260); await rejected;
    expect(network).toHaveBeenCalledTimes(1); time.advance(2000); expect(network).toHaveBeenCalledTimes(1); session.dispose();
  });
});


it('waits for slow REST terminal state before spacing queued REST and handshake', async () => {
  let now = 0; let callback: (() => void) | undefined; let done!: (response: Response) => void;
  const starts: number[] = []; const socketStarts: number[] = [];
  const fetch = vi.fn(() => { starts.push(now); return starts.length === 1 ? new Promise<Response>(resolve => { done = resolve; }) : Promise.resolve(Response.json({})); });
  const socket = { protocol: 'tc.share.properties.v1', send: vi.fn(), close: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn() };
  const session = createShareSession(id, secret, { origin: 'https://app.test', fetch, now: () => now,
    setTimer: (run) => { callback = run; return run; }, clearTimer: () => { callback = undefined; },
    createSocket: () => { socketStarts.push(now); return socket; } });
  const first = session.fetch(`${prefix}/context`); const second = session.fetch(`${prefix}/schema`);
  session.openShareRealtime({ onOpen: vi.fn(), onClose: vi.fn(), onMessage: vi.fn() });
  expect(starts).toEqual([]); now = 1100; const initialCallback = callback!; callback = undefined; initialCallback();
  now = 2000; expect(starts).toEqual([1100]); expect(callback).toBeUndefined();
  done(Response.json({})); await first; await Promise.resolve(); await Promise.resolve();
  expect(starts).toEqual([1100]); now = 2260; callback!(); await second; await Promise.resolve(); await Promise.resolve();
  expect(starts).toEqual([1100, 2260]); expect(socketStarts).toEqual([]);
  now = 2520; callback!(); expect(socketStarts).toEqual([2520]); session.dispose();
});
