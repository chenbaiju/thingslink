import { describe, expect, it, vi } from 'vitest';
import { createSessionCoordinator, SESSION_EPOCH_KEY, type SessionPorts } from './session';

const project = '11111111-1111-4111-8111-111111111111';
const user = '22222222-2222-4222-8222-222222222222';
const credentials = { projectKey: 'project', username: 'alice', password: 'private-password' };
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
function response(token = 'private-access', uid = user, pid = project) {
  return Response.json({ accessToken: token, accessExpiresAt: '2030-01-01T00:00:00Z', appUserId: uid, projectId: pid });
}
function environment() {
  const storage = new Map<string, string>();
  const notifications = new Set<() => void>();
  let serial = Promise.resolve();
  let sequence = 0;
  const fetch = vi.fn<SessionPorts['fetch']>();
  const invalidated = vi.fn();
  const ports: SessionPorts = {
    origin: 'https://app.test', fetch,
    locks: { request: <T>(_name: string, action: () => Promise<T>): Promise<T> => {
      const next = serial.then(action); serial = next.then(() => undefined, () => undefined); return next;
    } },
    storage: { getItem: (key) => storage.get(key) ?? null, setItem: (key, value) => { storage.set(key, value); } },
    randomEpoch: () => `be_${String.fromCharCode(65 + sequence++)}${'A'.repeat(21)}`,
    now: () => Date.parse('2026-09-07T00:00:00Z'), onInvalidate: invalidated,
    notifyInvalidation: () => { for (const callback of notifications) callback(); },
    subscribeInvalidation: (callback) => { notifications.add(callback); return () => { notifications.delete(callback); }; },
  };
  return { ports, fetch, storage, invalidated };
}

describe('browser session generation fence', () => {
  it('keeps all credentials in memory and changes epoch before sending login', async () => {
    const env = environment(); const session = createSessionCoordinator(env.ports);
    env.fetch.mockImplementation(async (_url, init) => {
      expect([...env.storage.keys()]).toEqual([SESSION_EPOCH_KEY]);
      expect(env.invalidated).toHaveBeenCalled();
      expect(init?.credentials).toBe('same-origin');
      expect(init?.signal).toBeUndefined();
      expect(new Headers(init?.headers).has('Authorization')).toBe(false);
      expect(env.storage.get(SESSION_EPOCH_KEY)).toBe(`pending:${JSON.parse(String(init?.body)).browserEpoch}`);
      return response();
    });
    await session.login(credentials);
    expect(JSON.stringify([...env.storage])).not.toContain('private');
    expect(JSON.stringify(session.snapshot())).not.toContain('private');
    expect(session.snapshot()).toMatchObject({ status: 'authenticated', projectId: project, appUserId: user });
  });

  it('holds the shared WebLock through complete identity HTTP response body', async () => {
    const env = environment(); const a = createSessionCoordinator(env.ports); const b = createSessionCoordinator(env.ports);
    const body = deferred<void>(); const entered = deferred<void>();
    env.fetch.mockImplementationOnce(async () => {
      entered.resolve();
      return new Response(new ReadableStream({ async start(controller) {
        await body.promise;
        controller.enqueue(new TextEncoder().encode(JSON.stringify({ accessToken: 'a', accessExpiresAt: '2030-01-01T00:00:00Z', appUserId: user, projectId: project })));
        controller.close();
      } }));
    }).mockResolvedValueOnce(response('b'));
    const first = a.login(credentials); await entered.promise;
    const second = b.login(credentials); await Promise.resolve();
    expect(env.fetch).toHaveBeenCalledTimes(1);
    body.resolve(); await first; await second;
    expect(a.snapshot().status).toBe('invalidated');
    expect(b.snapshot().status).toBe('authenticated');
  });

  it('never adopts a different tabs epoch even when notification is lost', async () => {
    const env = environment(); env.ports.notifyInvalidation = () => undefined;
    const a = createSessionCoordinator(env.ports); const b = createSessionCoordinator(env.ports);
    env.fetch.mockImplementation(async () => response());
    await a.login(credentials); await b.login(credentials);
    await expect(a.refresh()).rejects.toMatchObject({ reason: 'invalidated' });
    await expect(a.restore()).rejects.toMatchObject({ reason: 'invalidated' });
    await expect(a.visible()).rejects.toBeDefined();
    expect(env.fetch).toHaveBeenCalledTimes(2);
  });

  it('does not clear a new local identity when an old business response arrives', async () => {
    const env = environment(); const session = createSessionCoordinator(env.ports);
    env.fetch.mockResolvedValueOnce(response('old'));
    await session.login(credentials);
    const old = deferred<Response>(); env.fetch.mockReturnValueOnce(old.promise);
    const business = session.fetch('/api/v1/app/devices');
    env.fetch.mockResolvedValueOnce(response('new'));
    await session.login(credentials);
    old.resolve(Response.json({ old: true }));
    await expect(business).rejects.toMatchObject({ reason: 'invalidated' });
    expect(session.snapshot().status).toBe('authenticated');
  });

  it('single-flights refresh and permits only one GET replay, with no cancellation of auth', async () => {
    const env = environment(); const session = createSessionCoordinator(env.ports);
    env.fetch.mockResolvedValueOnce(response()); await session.login(credentials);
    const refreshing = deferred<Response>(); const entered = deferred<void>();
    env.fetch.mockImplementation(async (url, init) => {
      if (url.endsWith('/refresh')) { expect(init?.signal).toBeUndefined(); entered.resolve(); return refreshing.promise; }
      return new Headers(init?.headers).get('Authorization') === 'Bearer rotated'
        ? Response.json({ ok: true }) : new Response(null, { status: 401 });
    });
    const abort = new AbortController();
    const first = session.fetch('/api/v1/app/devices', { signal: abort.signal });
    const second = session.fetch('/api/v1/app/devices');
    await entered.promise; abort.abort(); refreshing.resolve(response('rotated'));
    await expect(first).rejects.toHaveProperty('name', 'AbortError');
    expect((await second).status).toBe(200);
    expect(env.fetch.mock.calls.filter(([url]) => url.endsWith('/refresh'))).toHaveLength(1);
    env.fetch.mockResolvedValue(new Response(null, { status: 401 }));
    expect((await session.fetch('/api/v1/app/commands', { method: 'POST', body: '{}' })).status).toBe(401);
    expect(env.fetch.mock.calls.filter(([url]) => url.endsWith('/refresh'))).toHaveLength(1);
  });

  it('unknown identity result blocks retry and business until explicit fresh login', async () => {
    const env = environment(); const session = createSessionCoordinator(env.ports);
    env.fetch.mockRejectedValueOnce(new TypeError('network private details'));
    await expect(session.login(credentials)).rejects.toMatchObject({ reason: 'unknown' });
    const oldEpoch = env.storage.get(SESSION_EPOCH_KEY);
    await expect(session.restore()).rejects.toMatchObject({ reason: 'unknown' });
    await expect(session.fetch('/api/v1/app/devices')).rejects.toMatchObject({ reason: 'unknown' });
    expect(env.fetch).toHaveBeenCalledTimes(1);
    env.fetch.mockResolvedValueOnce(response()); await session.login(credentials);
    expect(env.storage.get(SESSION_EPOCH_KEY)).not.toBe(oldEpoch);
  });

  it('logout sends captured old epoch while publishing new epoch before side effects', async () => {
    const env = environment(); const session = createSessionCoordinator(env.ports);
    await session.logout(); expect(env.fetch).not.toHaveBeenCalled();
    env.fetch.mockResolvedValueOnce(response()); await session.login(credentials);
    const before = env.storage.get(SESSION_EPOCH_KEY);
    env.fetch.mockImplementationOnce(async (_url, init) => {
      expect(JSON.parse(String(init?.body)).browserEpoch).toBe(before);
      expect(env.storage.get(SESSION_EPOCH_KEY)).not.toBe(before);
      expect(session.snapshot()).toEqual({ status: 'authenticating' });
      return new Response(null, { status: 204 });
    });
    await session.logout(); expect(session.snapshot()).toEqual({ status: 'anonymous' });
  });

  it('fails closed without WebLocks or writable storage before sending credentials', async () => {
    const env = environment();
    const noLocks = createSessionCoordinator({ ...env.ports, locks: undefined });
    await expect(noLocks.login(credentials)).rejects.toMatchObject({ reason: 'unsupported' });
    const noStorage = createSessionCoordinator({ ...env.ports, storage: { getItem: () => null, setItem: () => { throw new Error('denied'); } } });
    await expect(noStorage.login(credentials)).rejects.toMatchObject({ reason: 'unsupported' });
    expect(env.fetch).not.toHaveBeenCalled();
  });

  it('allows a new page to restore once but never persists the resulting identity', async () => {
    const env = environment();
    env.storage.set(SESSION_EPOCH_KEY, `be_${'A'.repeat(22)}`);
    const session = createSessionCoordinator(env.ports);
    env.fetch.mockResolvedValueOnce(response());
    await session.restore(project);
    expect(session.snapshot().status).toBe('authenticated');
    await expect(session.restore(project)).rejects.toMatchObject({ reason: 'invalidated' });
    expect([...env.storage.values()]).toEqual([`be_${'A'.repeat(22)}`]);
    expect(env.fetch).toHaveBeenCalledTimes(1);
  });

  it('rejects restore when an existing epoch is readable but storage is not writable', async () => {
    const env = environment();
    const writes = vi.fn(() => { throw new Error('read-only storage'); });
    const session = createSessionCoordinator({ ...env.ports, storage: {
      getItem: () => `be_${'A'.repeat(22)}`, setItem: writes,
    } });
    await expect(session.restore()).rejects.toMatchObject({ reason: 'unsupported' });
    expect(writes).toHaveBeenCalledWith(SESSION_EPOCH_KEY, `be_${'A'.repeat(22)}`);
    expect(session.snapshot()).toEqual({ status: 'unsupported' });
    expect(env.fetch).not.toHaveBeenCalled();
    const missingWrites = vi.fn();
    const missing = createSessionCoordinator({ ...env.ports, storage: { getItem: () => null, setItem: missingWrites } });
    await expect(missing.restore()).rejects.toMatchObject({ reason: 'anonymous' });
    expect(missingWrites).not.toHaveBeenCalled();
  });

  it('bounds identity response accumulation without cancelling the HTTP or releasing its lock early', async () => {
    const env = environment(); const first = createSessionCoordinator(env.ports); const second = createSessionCoordinator(env.ports);
    const entered = deferred<void>(); const finish = deferred<void>(); const cancelled = vi.fn();
    env.fetch.mockImplementationOnce(async () => new Response(new ReadableStream({
      async start(controller) {
        controller.enqueue(new Uint8Array(65537)); entered.resolve();
        await finish.promise; controller.close();
      }, cancel: cancelled,
    }))).mockResolvedValueOnce(response());
    const rejected = expect(first.login(credentials)).rejects.toMatchObject({ reason: 'invalid-response' });
    await entered.promise;
    const next = second.login(credentials); await Promise.resolve();
    expect(env.fetch).toHaveBeenCalledTimes(1);
    expect(cancelled).not.toHaveBeenCalled();
    finish.resolve(); await rejected; await next;
    expect(cancelled).not.toHaveBeenCalled();
    expect(first.snapshot().status).toBe('unknown');
  });

  it('checks persistent generation synchronously after business body parsing despite lost notifications', async () => {
    const env = environment(); env.ports.notifyInvalidation = () => undefined;
    const session = createSessionCoordinator(env.ports);
    env.fetch.mockResolvedValueOnce(response()); await session.login(credentials);
    expect(session.checkCurrent().status).toBe('authenticated');
    env.storage.set(SESSION_EPOCH_KEY, `be_B${'A'.repeat(21)}`);
    expect(() => session.checkCurrent()).toThrowError('App session invalidated');
    expect(env.fetch).toHaveBeenCalledTimes(1);
  });

  it('leaves pending through unknown failure and reload, requiring an explicit new login', async () => {
    const env = environment(); const first = createSessionCoordinator(env.ports);
    env.fetch.mockRejectedValueOnce(new TypeError('network'));
    await expect(first.login(credentials)).rejects.toMatchObject({ reason: 'unknown' });
    expect(env.storage.get(SESSION_EPOCH_KEY)).toMatch(/^pending:be_/);
    const reloaded = createSessionCoordinator(env.ports);
    await expect(reloaded.restore()).rejects.toMatchObject({ reason: 'unknown' });
    expect(env.fetch).toHaveBeenCalledTimes(1);
    env.fetch.mockResolvedValueOnce(response()); await reloaded.login(credentials);
    expect(env.storage.get(SESSION_EPOCH_KEY)).toMatch(/^be_/);
    expect(reloaded.snapshot().status).toBe('authenticated');
  });

  it('persists pending before HTTP so a destroyed in-flight page cannot auto-restore', async () => {
    const env = environment(); const first = createSessionCoordinator(env.ports);
    const pending = deferred<Response>(); const entered = deferred<void>();
    env.fetch.mockImplementationOnce(async () => { entered.resolve(); return pending.promise; });
    const rejected = expect(first.login(credentials)).rejects.toMatchObject({ reason: 'disposed' });
    await entered.promise;
    expect(env.storage.get(SESSION_EPOCH_KEY)).toMatch(/^pending:be_/);
    first.dispose();
    // 页面销毁会释放该文档持有的原生锁；新文档仍只能读取持久pending，不能猜旧HTTP终态。
    const reloaded = createSessionCoordinator({ ...env.ports, locks: { request: async (_name, action) => action() } });
    await expect(reloaded.restore()).rejects.toMatchObject({ reason: 'unknown' });
    expect(env.fetch).toHaveBeenCalledTimes(1);
    pending.resolve(response()); await rejected;
    expect(env.storage.get(SESSION_EPOCH_KEY)).toMatch(/^pending:be_/);
  });

  it('rejects queued login after disposal without replacing another tabs generation', async () => {
    const env = environment(); const holder = createSessionCoordinator(env.ports); const queued = createSessionCoordinator(env.ports);
    const pending = deferred<Response>(); const entered = deferred<void>();
    env.fetch.mockImplementationOnce(async () => { entered.resolve(); return pending.promise; });
    const current = holder.login(credentials); await entered.promise;
    const rejected = expect(queued.login(credentials)).rejects.toMatchObject({ reason: 'disposed' });
    queued.dispose(); pending.resolve(response()); await current; await rejected;
    expect(holder.checkCurrent().status).toBe('authenticated');
    expect(env.fetch).toHaveBeenCalledTimes(1);
  });

  it('never delivers an already in-flight business success after identity becomes unknown', async () => {
    const env = environment(); const session = createSessionCoordinator(env.ports);
    env.fetch.mockResolvedValueOnce(response()); await session.login(credentials);
    const result = deferred<Response>(); env.fetch.mockReturnValueOnce(result.promise);
    const rejected = expect(session.fetch('/api/v1/app/devices')).rejects.toMatchObject({ reason: 'unknown' });
    env.fetch.mockRejectedValueOnce(new TypeError('network'));
    await expect(session.refresh()).rejects.toMatchObject({ reason: 'unknown' });
    result.resolve(Response.json({ stale: true })); await rejected;
    expect(session.snapshot().status).toBe('unknown');
  });

  it('does not send credentials if writing pending fails, even after normal epoch was writable', async () => {
    const env = environment();
    const original = env.ports.storage!;
    const session = createSessionCoordinator({ ...env.ports, storage: {
      getItem: original.getItem,
      setItem: (key, value) => { if (value.startsWith('pending:')) throw new Error('quota'); original.setItem(key, value); },
    } });
    await expect(session.login(credentials)).rejects.toMatchObject({ reason: 'unsupported' });
    expect(env.fetch).not.toHaveBeenCalled();
  });

  it('keeps two established tabs usable after a same-epoch refresh completes', async () => {
    const env = environment();
    const a = createSessionCoordinator(env.ports); const b = createSessionCoordinator(env.ports);
    env.fetch.mockImplementation(async () => response());
    await a.login(credentials); await b.restore();
    expect(a.snapshot().status).toBe('authenticated');
    const entered = deferred<void>(); const held = deferred<Response>();
    env.fetch.mockImplementationOnce(async () => { entered.resolve(); return held.promise; });
    const rotating = b.refresh(); await entered.promise;
    expect(() => a.checkCurrent()).toThrow();
    expect(a.snapshot().status).toBe('authenticated');
    const calls = env.fetch.mock.calls.length;
    const business = a.fetch('/api/v1/app/devices');
    await Promise.resolve();
    expect(env.fetch).toHaveBeenCalledTimes(calls);
    held.resolve(response('rotated')); await rotating;
    expect((await business).status).toBe(200);
    expect(a.checkCurrent().status).toBe('authenticated');
    expect(b.checkCurrent().status).toBe('authenticated');
  });

  it('blocks another established tab when same-epoch pending survives the shared lock', async () => {
    const env = environment();
    const a = createSessionCoordinator(env.ports); const b = createSessionCoordinator(env.ports);
    env.fetch.mockImplementation(async () => response());
    await a.login(credentials); await b.restore();
    const entered = deferred<void>(); const held = deferred<Response>();
    env.fetch.mockImplementationOnce(async () => { entered.resolve(); return held.promise; });
    const rotating = b.refresh(); await entered.promise;
    const business = a.fetch('/api/v1/app/devices');
    const rejected = expect(rotating).rejects.toMatchObject({ reason: 'unknown' });
    const blocked = expect(business).rejects.toMatchObject({ reason: 'unknown' });
    held.resolve(new Response(null, { status: 503 }));
    await rejected; await blocked;
    expect(a.snapshot().status).toBe('unknown');
    await expect(a.visible()).rejects.toMatchObject({ reason: 'unknown' });
    expect(env.fetch).toHaveBeenCalledTimes(3);
    expect(env.storage.get(SESSION_EPOCH_KEY)).toMatch(/^pending:be_/);
  });

  it('rejects unexpected refreshed identity and stops exposing old metadata', async () => {
    const env = environment(); const session = createSessionCoordinator(env.ports);
    env.fetch.mockResolvedValueOnce(response()); await session.login(credentials);
    env.fetch.mockResolvedValueOnce(response('other', '33333333-3333-4333-8333-333333333333'));
    await expect(session.refresh()).rejects.toMatchObject({ reason: 'invalid-response' });
    expect(session.snapshot()).toEqual({ status: 'unknown' });
  });

  it('rejects a business response that arrives after the round signal ends without invalidating identity', async () => {
    const env = environment(); const session = createSessionCoordinator(env.ports);
    env.fetch.mockResolvedValueOnce(response()); await session.login(credentials);
    const controller = new AbortController(); const late = deferred<Response>();
    env.fetch.mockReturnValueOnce(late.promise);
    const business = session.fetch('/api/v1/app/devices', { signal: controller.signal });
    const rejected = expect(business).rejects.toMatchObject({ name: 'AbortError' });
    controller.abort(); late.resolve(Response.json({ stale: true }));
    await rejected;
    expect(session.snapshot().status).toBe('authenticated');
    expect(env.fetch).toHaveBeenCalledTimes(2);
  });

  it('counts the foreign pending WebLock wait in the round and never sends an expired business request', async () => {
    const env = environment();
    const a = createSessionCoordinator(env.ports); const b = createSessionCoordinator(env.ports);
    env.fetch.mockImplementation(async () => response());
    await a.login(credentials); await b.restore();
    const entered = deferred<void>(); const held = deferred<Response>();
    env.fetch.mockImplementationOnce(async () => { entered.resolve(); return held.promise; });
    const rotating = b.refresh(); await entered.promise;
    const controller = new AbortController();
    const business = a.fetch('/api/v1/app/devices', { signal: controller.signal });
    const rejected = expect(business).rejects.toMatchObject({ name: 'AbortError' });
    controller.abort(); held.resolve(response('rotated'));
    await rotating; await rejected;
    expect(env.fetch).toHaveBeenCalledTimes(3);
    expect(a.snapshot().status).toBe('authenticated');
  });

  it('does not hand back a business response after the deadline while waiting for a local refresh', async () => {
    const env = environment(); const session = createSessionCoordinator(env.ports);
    env.fetch.mockResolvedValueOnce(response()); await session.login(credentials);
    const controller = new AbortController(); const body = deferred<Response>();
    env.fetch.mockReturnValueOnce(body.promise);
    const business = session.fetch('/api/v1/app/devices', { signal: controller.signal });
    const entered = deferred<void>(); const held = deferred<Response>();
    env.fetch.mockImplementationOnce(async (_url, init) => {
      expect(init?.signal).toBeUndefined(); entered.resolve(); return held.promise;
    });
    const rotating = session.refresh(); await entered.promise;
    body.resolve(Response.json({ stale: true }));
    await Promise.resolve(); await Promise.resolve();
    const rejected = expect(business).rejects.toMatchObject({ name: 'AbortError' });
    controller.abort(); held.resolve(response('rotated'));
    await rotating; await rejected;
    expect(session.snapshot().status).toBe('authenticated');
  });


  it('captures the identity round before waiting for the shared lock instead of borrowing a later round', async () => {
    const env = environment(); const held = deferred<void>(); const entered = deferred<void>();
    const blocker = env.ports.locks!.request('tc.app.browser.auth', async () => { entered.resolve(); await held.promise; });
    await entered.promise;
    const expiredRound = vi.fn<SessionPorts['fetch']>().mockRejectedValue(new Error('round ended'));
    const nextRound = vi.fn<SessionPorts['fetch']>().mockResolvedValue(response('new'));
    let round: SessionPorts['fetch'] = expiredRound;
    env.ports.captureFetch = () => round;
    const session = createSessionCoordinator(env.ports);
    const login = session.login(credentials);
    const rejected = expect(login).rejects.toMatchObject({ reason: 'unknown' });
    round = nextRound; held.resolve(); await blocker; await rejected;
    expect(expiredRound).toHaveBeenCalledTimes(1);
    expect(nextRound).not.toHaveBeenCalled();
    expect(env.fetch).not.toHaveBeenCalled();
    expect(session.snapshot().status).toBe('unknown');
  });

  it('keeps 401 refresh and replay on the captured business round when a newer round is selected', async () => {
    const env = environment(); const session = createSessionCoordinator(env.ports);
    env.fetch.mockResolvedValueOnce(response()); await session.login(credentials);
    const delayed = deferred<Response>();
    const oldRound = vi.fn<SessionPorts['fetch']>().mockReturnValueOnce(delayed.promise)
      .mockResolvedValueOnce(response('rotated')).mockResolvedValueOnce(Response.json({ valid: true }));
    const nextRound = vi.fn<SessionPorts['fetch']>();
    let round: SessionPorts['fetch'] = oldRound;
    env.ports.captureFetch = () => round;
    const business = session.fetch('/api/v1/app/devices');
    round = nextRound; delayed.resolve(new Response(null, { status: 401 }));
    expect((await business).status).toBe(200);
    expect(oldRound).toHaveBeenCalledTimes(3);
    expect(oldRound.mock.calls[1][0]).toContain('/browser-auth/refresh');
    expect(oldRound.mock.calls[1][1]?.signal).toBeUndefined();
    expect(nextRound).not.toHaveBeenCalled();
  });

});

describe('credential confined dashboard socket', () => {
  async function socketFixture(version: 'v1' | 'v2' = 'v1') {
    const env = environment();
    const callbacks = new Map<string, (event: { data?: unknown; code?: number }) => void>();
    const timers: (() => void)[] = [];
    let now = Date.parse('2029-12-31T23:59:00Z');
    const socket = { protocol: 'tc.app.properties.v1', send: vi.fn(), close: vi.fn(),
      addEventListener: (type: string, callback: (event: { data?: unknown; code?: number }) => void) => { callbacks.set(type, callback); },
      removeEventListener: (type: string) => { callbacks.delete(type); } };
    const factory = vi.fn(() => socket);
    Object.assign(env.ports, { createSocket: factory, setTimer: (callback: () => void) => { timers.push(callback); return callback; }, clearTimer: vi.fn(), now: () => now });
    const session = createSessionCoordinator(env.ports); env.fetch.mockResolvedValue(response()); await session.login(credentials);
    const handlers = { onOpen: vi.fn(), onMessage: vi.fn(), onClose: vi.fn() };
    const connection = session.openDashboardRealtime(handlers, version);
    return { env, session, handlers, connection, factory, socket, callbacks, expire: () => { now += 60000; timers.at(-1)!(); } };
  }
  it('uses only fixed same origin path and confines token to protocol creation', async () => {
    const f = await socketFixture();
    expect(f.factory).toHaveBeenCalledWith('wss://app.test/ws/app/properties', ['tc.app.properties.v1', 'bearer.private-access']);
    expect(JSON.stringify(f.session.snapshot())).not.toContain('private-access');
    f.callbacks.get('open')!({}); expect(f.handlers.onOpen).toHaveBeenCalledTimes(1);
    f.connection.sendSubscribe('{"type":"SUBSCRIBE"}'); expect(f.socket.send).toHaveBeenCalledTimes(1);
    expect(() => f.connection.sendSubscribe('{}')).toThrow(); expect(f.socket.close).toHaveBeenCalled();
  });
  it('explicitly negotiates dashboard v2 without falling back to old credentials protocol', async () => {
    const f = await socketFixture('v2');
    expect(f.factory).toHaveBeenCalledWith('wss://app.test/ws/app/dashboard', ['tc.app.dashboard.v2', 'bearer.private-access']);
    f.callbacks.get('open')!({}); expect(f.handlers.onOpen).not.toHaveBeenCalled();
    expect(f.handlers.onClose).toHaveBeenCalledWith({ code: 1008, reason: 'protocol' });
    const valid = await socketFixture('v2'); valid.socket.protocol = 'tc.app.dashboard.v2';
    valid.callbacks.get('open')!({}); expect(valid.handlers.onOpen).toHaveBeenCalledTimes(1);
  });
  it('rejects unnegotiated protocol and fences missed epoch notifications', async () => {
    const protocol = await socketFixture(); protocol.socket.protocol = 'bearer.private-access'; protocol.callbacks.get('open')!({});
    expect(protocol.handlers.onOpen).not.toHaveBeenCalled(); expect(protocol.handlers.onClose).toHaveBeenCalledWith({ code: 1008, reason: 'protocol' });
    const f = await socketFixture(); f.env.storage.set(SESSION_EPOCH_KEY, `be_${'B'.repeat(21)}A`);
    f.callbacks.get('message')!({ data: '{}' }); expect(f.handlers.onMessage).not.toHaveBeenCalled(); expect(f.socket.close).toHaveBeenCalled();
  });
  it('closes on token expiry and suppresses saved late callbacks after disposal', async () => {
    const f = await socketFixture(); f.expire(); expect(f.handlers.onClose).toHaveBeenCalledWith({ code: 1008, reason: 'expired' });
    const old = await socketFixture(); const late = old.callbacks.get('message')!;
    old.session.dispose(); late({ data: '{}' }); expect(old.handlers.onMessage).not.toHaveBeenCalled(); expect(old.socket.close).toHaveBeenCalled();
  });
});

it('isolates device control transport from read recovery and never retries a command 401', async () => {
  const env = environment(); const session = createSessionCoordinator(env.ports);
  env.fetch.mockResolvedValue(response()); await session.login(credentials); env.fetch.mockClear();
  env.ports.captureFetch = () => { throw new Error('read recovery must not be borrowed'); };
  const transport = vi.fn(async () => new Response(null, { status: 401 }));
  const path = '/api/v1/app/devices/11111111-1111-4111-8111-111111111111/commands';
  expect((await session.fetchDeviceControl(path, { method: 'POST', body: '{}' }, transport)).status).toBe(401);
  expect(transport).toHaveBeenCalledTimes(1); expect(env.fetch).not.toHaveBeenCalled();
  const sent = transport.mock.calls[0] as unknown as [string, RequestInit];
  expect(sent[1]).toMatchObject({ credentials: 'omit', cache: 'no-store', redirect: 'error' });
  expect(new Headers(sent[1].headers).get('Authorization')).toBe('Bearer private-access');
  await expect(session.fetchDeviceControl('/api/v1/app/devices/x/share-tokens', { method: 'POST' }, transport)).rejects.toMatchObject({ reason: 'invalid-request' });
  await expect(session.fetchDeviceControl('/api/v1/projects/x', {}, transport)).rejects.toMatchObject({ reason: 'invalid-request' });
  expect(transport).toHaveBeenCalledTimes(1);
});
