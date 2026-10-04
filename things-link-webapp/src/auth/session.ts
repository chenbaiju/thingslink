/** S12-0a§2.3：同一key仅存随机代次及pending非凭据标记；令牌始终只存在本页闭包。 */
export const SESSION_EPOCH_KEY = 'tc.app.browser.epoch';
const LOCK_NAME = 'tc.app.browser.auth';
/** 只有四字段身份响应；64KiB给现有JWT留充足余量，超限停止累积但不提前释放身份锁。 */
const MAX_IDENTITY_RESPONSE_BYTES = 64 * 1024;
const EPOCH = /^be_[A-Za-z0-9_-]{21}[AQgw]$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export type SessionStatus = 'idle' | 'authenticating' | 'authenticated' | 'anonymous' | 'invalidated' | 'unknown' | 'unsupported' | 'disposed';
export interface SessionSnapshot {
  status: SessionStatus;
  appUserId?: string;
  projectId?: string;
  accessExpiresAt?: string;
}
export interface LoginCredentials { projectKey: string; username: string; password: string }
interface Issued { accessToken: string; accessExpiresAt: string; appUserId: string; projectId: string }
export interface SessionSocket {
  readonly protocol: string;
  send(frame: string): void;
  close(): void;
  addEventListener(type: 'open' | 'message' | 'close' | 'error', listener: (event: { data?: unknown; code?: number }) => void): void;
  removeEventListener(type: 'open' | 'message' | 'close' | 'error', listener: (event: { data?: unknown; code?: number }) => void): void;
}
export interface SessionRealtimeHandlers {
  onOpen(): void;
  onMessage(data: unknown): void;
  onClose(event: { code: number; reason: 'transport' | 'protocol' | 'identity' | 'expired' | 'local' }): void;
}
export interface SessionRealtimeConnection { sendSubscribe(frame: string): void; close(): void }
export interface SessionPorts {
  origin: string;
  /** 仅基础设施工厂可见协议凭据，业务调用方不得提供URL或读取原socket。 */
  createSocket?(url: string, protocols: string[]): SessionSocket;
  setTimer?(callback: () => void, delay: number): unknown;
  clearTimer?(timer: unknown): void;
  fetch(input: string, init?: RequestInit): Promise<Response>;
  /** 在等待共同锁之前冻结恢复轮次；旧调用不能借用后来的新轮次预算。 */
  captureFetch?(): SessionPorts['fetch'];
  locks?: { request<T>(name: string, callback: () => Promise<T>): Promise<T> };
  storage?: Pick<Storage, 'getItem' | 'setItem'>;
  randomEpoch(): string;
  now(): number;
  onInvalidate(): void;
  notifyInvalidation(): void;
  subscribeInvalidation(callback: () => void): () => void;
}
export interface SessionCoordinator {
  login(credentials: LoginCredentials, expectedProjectId?: string): Promise<SessionSnapshot>;
  restore(expectedProjectId?: string): Promise<SessionSnapshot>;
  refresh(): Promise<SessionSnapshot>;
  logout(): Promise<void>;
  visible(expectedProjectId?: string): Promise<SessionSnapshot>;
  fetch(path: string, init?: RequestInit): Promise<Response>;
  fetchDeviceControl(path: string, init: RequestInit, transport: SessionPorts['fetch']): Promise<Response>;
  openDashboardRealtime(handlers: SessionRealtimeHandlers, version?: 'v1' | 'v2'): SessionRealtimeConnection;
  snapshot(): SessionSnapshot;
  checkCurrent(): SessionSnapshot;
  subscribeSnapshot(listener: (snapshot: SessionSnapshot) => void): () => void;
  dispose(): void;
}
/** 固定错误原因不携带HTTP正文、密码或令牌。 */
export class SessionError extends Error {
  constructor(readonly reason: 'unsupported' | 'invalidated' | 'unknown' | 'anonymous' | 'rejected' | 'invalid-response' | 'invalid-request' | 'disposed') {
    super(`App session ${reason}`);
  }
}

/** S12-0a§2.3框架无关会话围栏；返回成功只确认所发代次，不采用其他标签新身份。 */
export function createSessionCoordinator(ports: SessionPorts): SessionCoordinator {
  let status: SessionStatus = 'idle';
  let epoch: string | undefined;
  let issued: Issued | undefined;
  let ownPending: string | undefined;
  let refreshFlight: Promise<SessionSnapshot> | undefined;
  let disposed = false;
  let closeRealtime: ((reason: 'identity' | 'expired' | 'local') => void) | undefined;
  const listeners = new Set<(snapshot: SessionSnapshot) => void>();
  const captureFetch = (): SessionPorts['fetch'] => ports.captureFetch?.() ?? ports.fetch;
  const notify = (): void => { for (const listener of listeners) { try { listener(snapshot()); } catch { /* UI不能覆盖会话状态。 */ } } };
  const snapshot = (): SessionSnapshot => issued && status === 'authenticated'
    ? { status, appUserId: issued.appUserId, projectId: issued.projectId, accessExpiresAt: issued.accessExpiresAt }
    : { status };
  const clear = (next: SessionStatus): void => {
    issued = undefined;
    status = next;
    closeRealtime?.('identity');
    notify();
    try { ports.onInvalidate(); } catch { /* UI失效回调不能恢复已清除身份或中止安全收束。 */ }
  };
  const readEpoch = (): string | null => {
    if (!ports.locks || !ports.storage) { clear('unsupported'); throw new SessionError('unsupported'); }
    try { return ports.storage.getItem(SESSION_EPOCH_KEY); }
    catch { clear('unsupported'); throw new SessionError('unsupported'); }
  };
  const active = (): void => {
    if (disposed) throw new SessionError('disposed');
    if (status === 'unsupported') throw new SessionError('unsupported');
    if (status === 'unknown') throw new SessionError('unknown');
  };
  const fence = (expected: string): void => {
    if (disposed) throw new SessionError('disposed');
    if (epoch !== expected) throw new SessionError('invalidated');
    const stored = readEpoch();
    if (stored !== expected && stored !== ownPending) {
      // 同代次另一标签正在轮换：同步调用不可展示业务，也不可永久丢弃未变身份。
      if (stored === `pending:${expected}`) {
        try { ports.onInvalidate(); } catch { /* 同步围栏仍拒绝当前业务结果。 */ }
        throw new SessionError('invalidated');
      }
      clear('invalidated'); throw new SessionError('invalidated');
    }
  };
  const locked = async <T>(operation: () => Promise<T>): Promise<T> => {
    if (disposed) throw new SessionError('disposed');
    readEpoch();
    let entered = false;
    try {
      return await ports.locks!.request(LOCK_NAME, async () => {
        entered = true;
        if (disposed) throw new SessionError('disposed');
        const current = readEpoch();
        if (current !== null) {
          // 存量代次可读不代表存储可写；锁内同值写不产生新身份，失败禁止自动轮换Cookie。
          try { ports.storage!.setItem(SESSION_EPOCH_KEY, current); }
          catch { clear('unsupported'); throw new SessionError('unsupported'); }
        }
        return operation();
      });
    } catch (failure) {
      if (!entered) { clear('unsupported'); throw new SessionError('unsupported'); }
      throw failure;
    }
  };
  const foreignPending = (expected: string): boolean => {
    const stored = readEpoch();
    return stored === `pending:${expected}` && stored !== ownPending;
  };
  const settlePending = (expected: string): Promise<void> => locked(async () => {
    active();
    if (epoch !== expected) throw new SessionError('invalidated');
    // 已拿到共同锁仍pending，说明前一身份操作没有完整验收；不得自动补发刷新。
    if (foreignPending(expected)) { clear('unknown'); throw new SessionError('unknown'); }
    fence(expected);
  });
  const publish = (): string => {
    const next = ports.randomEpoch();
    if (!EPOCH.test(next)) { clear('unsupported'); throw new SessionError('unsupported'); }
    ownPending = undefined;
    clear('authenticating');
    try { ports.storage!.setItem(SESSION_EPOCH_KEY, next); }
    catch { clear('unsupported'); throw new SessionError('unsupported'); }
    epoch = next;
    fence(next);
    try { ports.notifyInvalidation(); } catch { /* 广播仅提示，所有边界仍同步检查持久代次。 */ }
    return next;
  };
  const beginPending = (expected: string): void => {
    fence(expected);
    const pending = `pending:${expected}`;
    try { ports.storage!.setItem(SESSION_EPOCH_KEY, pending); }
    catch { clear('unsupported'); throw new SessionError('unsupported'); }
    ownPending = pending;
    fence(expected);
    clear('authenticating');
    try { ports.notifyInvalidation(); } catch { /* 通知可能漏收，其他页面仍在同步围栏拒绝pending。 */ }
  };
  const completePending = (expected: string): void => {
    fence(expected);
    try { ports.storage!.setItem(SESSION_EPOCH_KEY, expected); }
    catch { clear('unsupported'); throw new SessionError('unsupported'); }
    ownPending = undefined;
    fence(expected);
  };
  const identityRequest = async (operation: string, body: object, expected: string, fetcher: SessionPorts['fetch']): Promise<Response> => {
    beginPending(expected);
    try {
      // 没有AbortSignal：调用者不得取消已发身份请求后提前释放共同WebLock。
      const response = await fetcher(`${ports.origin}/api/v1/app/browser-auth/${operation}`, {
        method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      });
      const chunks = new Uint8Array(MAX_IDENTITY_RESPONSE_BYTES);
      let count = 0;
      let oversized = false;
      const reader = response.body?.getReader();
      if (reader) {
        try {
          while (true) {
            const chunk = await reader.read();
            if (chunk.done) break;
            if (oversized || chunk.value.byteLength > MAX_IDENTITY_RESPONSE_BYTES - count) oversized = true;
            else { chunks.set(chunk.value, count); count += chunk.value.byteLength; }
            // 超限后仅消费并丢弃；Cookie副作用可能已发生，不能主动取消响应来提前释放共同锁。
          }
        } finally { reader.releaseLock(); }
      }
      if (oversized) { clear('unknown'); throw new SessionError('invalid-response'); }
      const responseBody = new TextDecoder('utf-8', { fatal: true }).decode(chunks.subarray(0, count));
      fence(expected);
      return new Response(response.status === 204 ? null : responseBody, { status: response.status, headers: response.headers });
    } catch (failure) {
      if (failure instanceof SessionError) throw failure;
      clear('unknown'); throw new SessionError('unknown');
    }
  };
  const accept = async (response: Response, expected: string, project?: string, user?: string): Promise<SessionSnapshot> => {
    if (!response.ok) {
      fence(expected); clear(response.status >= 500 ? 'unknown' : 'anonymous');
      throw new SessionError(response.status >= 500 ? 'unknown' : 'rejected');
    }
    let value: unknown;
    try { value = await response.json(); }
    catch { clear('unknown'); throw new SessionError('unknown'); }
    fence(expected);
    if (!value || typeof value !== 'object') { clear('unknown'); throw new SessionError('invalid-response'); }
    const data = value as Record<string, unknown>;
    if (Object.keys(data).sort().join(',') !== 'accessExpiresAt,accessToken,appUserId,projectId'
      || typeof data.accessToken !== 'string' || !data.accessToken || data.accessToken.length > 16384
      || typeof data.accessExpiresAt !== 'string' || !Number.isFinite(Date.parse(data.accessExpiresAt))
      || Date.parse(data.accessExpiresAt) <= ports.now()
      || typeof data.appUserId !== 'string' || !UUID.test(data.appUserId)
      || typeof data.projectId !== 'string' || !UUID.test(data.projectId)
      || (project !== undefined && data.projectId !== project) || (user !== undefined && data.appUserId !== user)) {
      clear('unknown'); throw new SessionError('invalid-response');
    }
    completePending(expected);
    issued = data as unknown as Issued;
    status = 'authenticated';
    notify();
    return snapshot();
  };
  const refreshWithin = (fetcher: SessionPorts['fetch']): Promise<SessionSnapshot> => {
    if (refreshFlight) return refreshFlight;
    const captured = epoch;
    const previous = issued;
    if (!captured || !previous || status !== 'authenticated') return Promise.reject(new SessionError(status === 'unknown' ? 'unknown' : 'anonymous'));
    const operation = locked(async () => {
      active();
      if (foreignPending(captured)) { clear('unknown'); throw new SessionError('unknown'); }
      fence(captured);
      return accept(await identityRequest('refresh', { browserEpoch: captured }, captured, fetcher), captured, previous.projectId, previous.appUserId);
    });
    refreshFlight = operation;
    void operation.finally(() => { if (refreshFlight === operation) refreshFlight = undefined; }).catch(() => undefined);
    return operation;
  };
  const unsubscribe = ports.subscribeInvalidation(() => {
    if (!epoch || disposed) return;
    try {
      const stored = readEpoch();
      if (stored === `pending:${epoch}` && stored !== ownPending) {
        // 正常刷新不改变身份代次；仅清业务，异步边界待共同锁释放后再裁决。
        try { ports.onInvalidate(); } catch { /* 业务边界还会再次围栏。 */ }
      } else if (stored !== epoch && stored !== ownPending) clear(status === 'unknown' ? 'unknown' : 'invalidated');
    }
    catch { /* readEpoch已经关闭不支持的会话模式。 */ }
  });
  return {
    snapshot,
    openDashboardRealtime: (handlers, version = 'v1') => {
      if (version !== 'v1' && version !== 'v2') throw new SessionError('invalid-request');
      active();
      const captured = epoch; const identity = issued;
      if (!captured || !identity || status !== 'authenticated') throw new SessionError('anonymous');
      fence(captured);
      if (!ports.createSocket || !ports.setTimer || !ports.clearTimer) throw new SessionError('unsupported');
      if (Date.parse(identity.accessExpiresAt) <= ports.now()) throw new SessionError('anonymous');
      closeRealtime?.('local');
      const origin = new URL(ports.origin);
      if (origin.origin !== ports.origin || !['https:', 'http:'].includes(origin.protocol)) throw new SessionError('invalid-request');
      const protocol = version === 'v2' ? 'tc.app.dashboard.v2' : 'tc.app.properties.v1';
      const endpoint = version === 'v2' ? '/ws/app/dashboard' : '/ws/app/properties';
      const url = `${origin.protocol === 'https:' ? 'wss:' : 'ws:'}//${origin.host}${endpoint}`;
      let socket: SessionSocket;
      try { socket = ports.createSocket(url, [protocol, `bearer.${identity.accessToken}`]); }
      catch { throw new SessionError('unknown'); }
      let closed = false; let sent = false; let expiry: unknown;
      const finish = (reason: 'transport' | 'protocol' | 'identity' | 'expired' | 'local', code = 1000): void => {
        if (closed) return; closed = true;
        ports.clearTimer!(expiry);
        for (const [type, callback] of listeners) socket.removeEventListener(type, callback);
        if (closeRealtime === close) closeRealtime = undefined;
        try { socket.close(); } catch { /* 不把底层错误或协议头传播到界面。 */ }
        try { handlers.onClose({ code, reason }); } catch { /* 回调不能恢复已关闭的凭据边界。 */ }
      };
      const close = (reason: 'identity' | 'expired' | 'local'): void => finish(reason);
      const authorized = (): boolean => {
        if (closed) return false;
        try { active(); fence(captured); if (issued !== identity || status !== 'authenticated') throw new SessionError('invalidated'); }
        catch { finish('identity', 1008); return false; }
        if (Date.parse(identity.accessExpiresAt) <= ports.now()) { finish('expired', 1008); return false; }
        return true;
      };
      const listeners: [Parameters<SessionSocket['addEventListener']>[0], (event: { data?: unknown; code?: number }) => void][] = [
        ['open', () => { if (!authorized()) return; if (socket.protocol !== protocol) { finish('protocol', 1008); return; } handlers.onOpen(); }],
        ['message', event => { if (authorized()) handlers.onMessage(event.data); }],
        ['close', event => { if (authorized()) finish('transport', event.code ?? 1006); }],
        ['error', () => { if (authorized()) finish('transport', 1006); }],
      ];
      closeRealtime = close;
      for (const [type, callback] of listeners) socket.addEventListener(type, callback);
      const expire = (): void => {
        const remaining = Date.parse(identity.accessExpiresAt) - ports.now();
        if (remaining <= 0) { finish('expired', 1008); return; }
        expiry = ports.setTimer!(expire, Math.min(2147483647, remaining));
      };
      expire();
      return Object.freeze({ close: () => finish('local'), sendSubscribe: (frame: string) => {
        if (!authorized()) throw new SessionError('invalidated');
        if (sent || typeof frame !== 'string' || frame.length > 32768 || new TextEncoder().encode(frame).length > 32768) { finish('protocol', 1008); throw new SessionError('invalid-request'); }
        sent = true;
        try { socket.send(frame); } catch { finish('transport', 1006); throw new SessionError('unknown'); }
      } });
    },
    checkCurrent: () => {
      active();
      if (!epoch || !issued || status !== 'authenticated') throw new SessionError('anonymous');
      fence(epoch);
      return snapshot();
    },
    subscribeSnapshot: (listener) => { listeners.add(listener); listener(snapshot()); return () => { listeners.delete(listener); }; },
    login: (credentials, project) => {
      const fetcher = captureFetch();
      return locked(async () => {
        const next = publish();
        return accept(await identityRequest('login', { ...credentials, browserEpoch: next }, next, fetcher), next, project);
      });
    },
    restore: (project) => {
      const fetcher = captureFetch();
      return locked(async () => {
        active();
        // 只有新页面可以首次绑定现有代次；旧标签不允许通过restore借用另一标签新账号。
        if (status !== 'idle' || epoch !== undefined) throw new SessionError('invalidated');
        const stored = readEpoch();
        if (stored?.startsWith('pending:')) { clear('unknown'); throw new SessionError('unknown'); }
        if (!stored || !EPOCH.test(stored)) { clear('anonymous'); throw new SessionError('anonymous'); }
        epoch = stored;
        return accept(await identityRequest('refresh', { browserEpoch: stored }, stored, fetcher), stored, project);
      });
    },
    refresh: () => refreshWithin(captureFetch()),
    logout: () => {
      const fetcher = captureFetch();
      return locked(async () => {
        active();
        const captured = epoch;
        if (!captured) { clear('anonymous'); return; }
        fence(captured);
        const next = publish();
        const response = await identityRequest('logout', { browserEpoch: captured }, next, fetcher);
        if (response.status !== 204) { clear(response.status >= 500 ? 'unknown' : 'anonymous'); throw new SessionError(response.status >= 500 ? 'unknown' : 'rejected'); }
        completePending(next);
        clear('anonymous');
      });
    },
    visible: async (project) => {
      const fetcher = captureFetch();
      active();
      const before = issued;
      if (!epoch || !before) throw new SessionError('anonymous');
      if (foreignPending(epoch)) await settlePending(epoch);
      fence(epoch);
      // 壳必须同步清业务并在此成功后重核appKey/current/grant，刷新本身不证明路由授权。
      try { ports.onInvalidate(); } catch { /* 不允许UI回调替代权威刷新。 */ }
      if (project !== undefined && before.projectId !== project) { clear('invalidated'); throw new SessionError('invalidated'); }
      return refreshWithin(fetcher);
    },
    // 独立控制不借看板恢复轮，也不自动刷新/重发。所有网络额度由调用方同一控制视图维护。
    fetchDeviceControl: async (path, init, transport) => {
      active();
      const url = new URL(path, ports.origin); const method = (init.method ?? 'GET').toUpperCase();
      const id = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
      const read = url.pathname === '/api/v1/app/devices' || new RegExp(`^/api/v1/app/devices/${id}/(?:command-definitions|commands/${id})$`).test(url.pathname);
      const write = new RegExp(`^/api/v1/app/devices/${id}/commands$`).test(url.pathname);
      if (url.origin !== ports.origin || url.username || url.password || url.hash
        || !(method === 'GET' && read || method === 'POST' && write)) throw new SessionError('invalid-request');
      const captured = epoch; const identity = issued;
      if (!captured || !identity || status !== 'authenticated') throw new SessionError('anonymous');
      init.signal?.throwIfAborted(); fence(captured);
      const headers = new Headers(init.headers); headers.set('Authorization', `Bearer ${identity.accessToken}`);
      const result = await transport(url.toString(), { ...init, method, headers, credentials: 'omit', cache: 'no-store', redirect: 'error' });
      init.signal?.throwIfAborted(); active(); fence(captured);
      if (issued !== identity || status !== 'authenticated') throw new SessionError('invalidated');
      return result;
    },
    fetch: async (path, init = {}) => {
      const fetcher = captureFetch();
      active();
      const url = new URL(path, ports.origin);
      if (url.origin !== ports.origin || !url.pathname.startsWith('/api/v1/app/')
        || url.pathname.startsWith('/api/v1/app/browser-auth') || url.pathname.startsWith('/api/v1/app/auth/') || url.username || url.password || url.hash) throw new SessionError('invalid-request');
      const captured = epoch;
      if (!captured || !issued || status !== 'authenticated') throw new SessionError('anonymous');
      if (foreignPending(captured)) await settlePending(captured);
      fence(captured);
      const method = (init.method ?? 'GET').toUpperCase();
      const sentToken = issued.accessToken;
      const send = async (): Promise<Response> => {
        init.signal?.throwIfAborted(); fence(captured);
        if (!issued) throw new SessionError('anonymous');
        const headers = new Headers(init.headers); headers.set('Authorization', `Bearer ${issued.accessToken}`);
        const result = await fetcher(url.toString(), { ...init, method, headers, credentials: 'omit', cache: 'no-store', redirect: 'error' });
        // 轮次截止可能发生在网络或共同锁等待中；不能只检查发出请求之前。
        init.signal?.throwIfAborted();
        if (foreignPending(captured)) await settlePending(captured);
        fence(captured);
        // 同页刷新中的旧业务可等待共享刷新，但身份未知/登录替换后绝不能交付旧Response。
        if (refreshFlight) await refreshFlight;
        init.signal?.throwIfAborted();
        active(); fence(captured);
        if (!issued || status !== 'authenticated') throw new SessionError('anonymous');
        return result;
      };
      const result = await send();
      if (result.status !== 401 || method !== 'GET') return result;
      if (issued?.accessToken === sentToken) await refreshWithin(fetcher);
      init.signal?.throwIfAborted(); fence(captured);
      return send();
    },
    dispose: () => { if (disposed) return; disposed = true; unsubscribe(); clear('disposed'); },
  };
}

/** 原生端口：广播只发送固定失效通知，storage永远不写账号、项目或令牌。 */
export function createBrowserSessionPorts(window: Window, onInvalidate: () => void): SessionPorts {
  let storage: Storage | undefined;
  try { storage = window.localStorage; } catch { /* 协调器在第一次操作时明确unsupported。 */ }
  let channel: BroadcastChannel | undefined;
  try { channel = new BroadcastChannel('tc.app.browser.invalidated'); } catch { /* storage事件与同步围栏仍有效。 */ }
  return {
    origin: window.location.origin,
    fetch: window.fetch.bind(window),
    createSocket: (url, protocols) => new WebSocket(url, protocols) as unknown as SessionSocket,
    setTimer: (callback, delay) => window.setTimeout(callback, delay),
    clearTimer: timer => window.clearTimeout(timer as number),
    locks: window.navigator.locks,
    storage,
    randomEpoch: () => {
      const bytes = window.crypto.getRandomValues(new Uint8Array(16));
      return `be_${window.btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')}`;
    },
    now: Date.now,
    onInvalidate,
    notifyInvalidation: () => { channel?.postMessage('invalidate'); },
    subscribeInvalidation: (callback) => {
      const changed = (event: StorageEvent): void => { if (event.key === SESSION_EPOCH_KEY || event.key === null) callback(); };
      window.addEventListener('storage', changed);
      channel?.addEventListener('message', callback);
      return () => { window.removeEventListener('storage', changed); channel?.removeEventListener('message', callback); channel?.close(); };
    },
  };
}
