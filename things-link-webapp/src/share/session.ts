import type { SessionSocket, SessionRealtimeConnection, SessionRealtimeHandlers } from '../auth/session';
export interface ShareSessionPorts {
  origin: string;
  now?(): number;
  setTimer?(callback: () => void, delay: number): unknown;
  clearTimer?(timer: unknown): void;
  fetch(input: string, init?: RequestInit): Promise<Response>;
  captureFetch?(): ShareSessionPorts['fetch'];
  createSocket?(url: string, protocols: string[]): SessionSocket;
}
export interface ShareSession {
  readonly shareId: string;
  fetch(path: string, init?: RequestInit): Promise<Response>;
  checkCurrent(): { readonly shareId: string };
  openShareRealtime(handlers: SessionRealtimeHandlers): SessionRealtimeConnection;
  dispose(): void;
}
/** 固定错误不携带链接、响应或秘密。 */
export class ShareSessionError extends Error {
  constructor(readonly reason: 'invalid' | 'disposed' | 'unavailable') { super(`Share session ${reason}`); }
}
/** 仅入口消费闭包调用；身份不来自App状态，且不允许调用方替换凭据头。 */
export function createShareSession(shareId: string, secret: string, ports: ShareSessionPorts): ShareSession {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(shareId)
    || !/^sh_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(secret)) throw new ShareSessionError('invalid');
  const origin = new URL(ports.origin);
  if (origin.origin !== ports.origin || !['http:', 'https:'].includes(origin.protocol)) throw new ShareSessionError('invalid');
  const prefix = `/api/v1/shares/${shareId}`;
  let alive = true; let closeSocket: (() => void) | undefined;
  const checkCurrent = (): { readonly shareId: string } => { if (!alive) throw new ShareSessionError('disposed'); return Object.freeze({ shareId }); };
  // 分享合同§5：REST与握手共享4次/秒，开始间隔保守260ms；队列不持HTTP槽。
  const now = ports.now ?? (() => performance.now());
  const setTimer = ports.setTimer ?? ((callback: () => void, delay: number) => globalThis.setTimeout(callback, delay));
  const clearTimer = ports.clearTimer ?? ((timer: unknown) => globalThis.clearTimeout(timer as ReturnType<typeof setTimeout>));
  type Waiting = { start(): Promise<void>; cancel(): void };
  const waiting: Waiting[] = []; let timer: unknown; let nextStart = now() + 1100; let running = false;
  const pump = (): void => {
    if (running || timer !== undefined || !waiting.length || !alive) return;
    const delay = nextStart - now();
    if (delay > 0) { timer = setTimer(() => { timer = undefined; pump(); }, delay); return; }
    const task = waiting.shift()!; running = true;
    // budget内部也可能等待HTTP槽；等完整Response终态后再留260ms，避免真实发送被压成突发。
    void task.start().finally(() => { running = false; nextStart = now() + 260; pump(); });
  };
  const schedule = <T>(action: () => T | Promise<T>, signal?: AbortSignal): Promise<T> => new Promise<T>((resolve, reject) => {
    if (!alive || signal?.aborted || waiting.length >= 32) { reject(new ShareSessionError(alive ? 'unavailable' : 'disposed')); return; }
    const abort = (): void => {
      const index = waiting.indexOf(task); if (index < 0) return;
      waiting.splice(index, 1); task.cancel();
      if (!waiting.length && timer !== undefined) { clearTimer(timer); timer = undefined; }
    };
    const task: Waiting = {
      start: async () => { signal?.removeEventListener('abort', abort); try { checkCurrent(); const result = await action(); resolve(result); } catch (error) { reject(error); } },
      cancel: () => { signal?.removeEventListener('abort', abort); reject(new ShareSessionError(alive ? 'unavailable' : 'disposed')); },
    };
    signal?.addEventListener('abort', abort, { once: true }); waiting.push(task); pump();
  });
  return Object.freeze({ shareId, checkCurrent,
    fetch: async (path: string, init: RequestInit = {}): Promise<Response> => {
      checkCurrent(); const fetcher = ports.captureFetch?.() ?? ports.fetch;
      let url: URL; try { url = new URL(path, origin); } catch { throw new ShareSessionError('invalid'); }
      if (url.origin !== origin.origin || url.username || url.password || url.hash || !url.pathname.startsWith(`${prefix}/`)) throw new ShareSessionError('invalid');
      const route = url.pathname.slice(prefix.length); const method = (init.method ?? 'GET').toUpperCase();
      const post = ['/devices/snapshots/query', '/devices/current-values/query', '/alarms/query'].includes(route);
      let query: string[] = [];
      if (route === '/devices/catalog') query = ['variableKey', 'cursor', 'limit'];
      else if (/^\/devices\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/properties\/[A-Za-z0-9_-]{1,64}\/history$/.test(route)) query = ['expectedModelVersionId', 'windowPreset', 'anchorAt', 'granularity', 'aggregation'];
      else if (!post && !['/context', '/schema'].includes(route)) throw new ShareSessionError('invalid');
      if (method !== (post ? 'POST' : 'GET')) throw new ShareSessionError('invalid');
      const seen = new Set<string>(); for (const key of url.searchParams.keys()) {
        if (!query.includes(key) || seen.has(key)) throw new ShareSessionError('invalid'); seen.add(key);
      }
      if (init.referrer !== undefined || (!post && init.body != null) || (post && (typeof init.body !== 'string' || init.body.length > 65536 || new TextEncoder().encode(init.body).length > 65536))) throw new ShareSessionError('invalid');
      const headers = new Headers(init.headers);
      for (const key of headers.keys()) if (!['accept', 'content-type'].includes(key)) throw new ShareSessionError('invalid');

      let response: Response;
      try { response = await schedule(() => { headers.set('X-Share-Token', secret); return fetcher(url.href, { ...init, method, headers, credentials: 'omit', mode: 'same-origin', redirect: 'error', cache: 'no-store', referrerPolicy: 'same-origin' }); }, init.signal ?? undefined); }
      catch { checkCurrent(); throw new ShareSessionError('unavailable'); }
      checkCurrent(); return response;
    },
    openShareRealtime: (handlers: SessionRealtimeHandlers): SessionRealtimeConnection => {
      checkCurrent(); if (!ports.createSocket) throw new ShareSessionError('unavailable'); closeSocket?.();
      let socket: SessionSocket | undefined; const pending = new AbortController();
      let closed = false; let sent = false;
      const finish = (reason: 'local' | 'transport' | 'protocol' | 'identity', code = 1000): void => {
        if (closed) return; closed = true; pending.abort();
        for (const [event, handler] of listeners) socket?.removeEventListener(event, handler);
        if (closeSocket === close) closeSocket = undefined;
        try { socket?.close(); } catch { /* 原始异常不出凭据闭包。 */ }
        try { handlers.onClose({ code, reason }); } catch { /* 已关闭身份不能由回调恢复。 */ }
      };
      const close = (): void => finish(alive ? 'local' : 'identity');
      const valid = (): boolean => { if (closed) return false; if (!alive) { finish('identity', 1008); return false; } return true; };
      const listeners: [Parameters<SessionSocket['addEventListener']>[0], (event: {data?: unknown; code?: number}) => void][] = [
        ['open', () => { if (!valid()) return; if (socket?.protocol !== 'tc.share.properties.v1') { finish('protocol', 1008); return; } handlers.onOpen(); }],
        ['message', event => { if (valid()) handlers.onMessage(event.data); }],
        ['close', event => { if (valid()) finish('transport', event.code ?? 1006); }],
        ['error', () => { if (valid()) finish('transport', 1006); }],
      ];
      closeSocket = close;
      void schedule(() => {
        if (!valid()) return;
        socket = ports.createSocket!(`${origin.protocol === 'https:' ? 'wss:' : 'ws:'}//${origin.host}/ws/shares/${shareId}/properties`, ['tc.share.properties.v1', `share.${secret}`]);
        for (const [event, handler] of listeners) socket.addEventListener(event, handler);
      }, pending.signal).catch(() => { if (!closed) finish(alive ? 'transport' : 'identity', 1006); });
      return Object.freeze({ close, sendSubscribe: (frame: string): void => {
        if (!valid()) throw new ShareSessionError('disposed');
        if (sent || typeof frame !== 'string' || frame.length > 32768 || new TextEncoder().encode(frame).length > 32768) { finish('protocol', 1008); throw new ShareSessionError('invalid'); }
        if (!socket) throw new ShareSessionError('unavailable');
        sent = true; try { socket.send(frame); } catch { finish('transport', 1006); throw new ShareSessionError('unavailable'); }
      } });
    },
    dispose: (): void => { if (!alive) return; alive = false; secret = ''; closeSocket?.(); if (timer !== undefined) clearTimer(timer); timer = undefined; for (const task of waiting.splice(0)) task.cancel(); },
  });
}
