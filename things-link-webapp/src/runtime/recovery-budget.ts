/** 数据运行合同§4/5：预算属于单标签进程，取消或新恢复轮都不退还已发尝试/已收字节。 */
export type RecoveryRoundKind = 'full' | 'interaction';
export interface RecoveryRound { readonly id: number; readonly deadline: number }
export type RecoveryFailure = 'deadline' | 'attempts' | 'bytes' | 'queue' | 'network';
export class RecoveryBudgetError extends Error {
  constructor(readonly reason: RecoveryFailure | 'cancelled' | 'route') { super(`Recovery budget ${reason}`); }
}
export interface RecoveryBudgetPorts {
  /** 浏览器实际可信同源，不能从请求或Schema提供。 */
  origin: string;
  fetch(input: string, init?: RequestInit): Promise<Response>;
  /** 必须是单调毫秒时钟，例如performance.now。 */
  now(): number;
  setTimer(callback: () => void, delayMs: number): unknown;
  clearTimer(timer: unknown): void;
  onFailure(round: RecoveryRound, reason: RecoveryFailure): void;
  /** 单轮聚合等待；null清除，未知释放时间不能伪造成可用时间。 */
  onWait?(round: RecoveryRound, state: { nextAvailableAt: number | null } | null): void;
}
export interface RecoveryBudgetSnapshot {
  readonly inFlight: number;
  readonly currentInFlight: number;
  readonly queued: number;
  readonly attempts60s: number;
  readonly interactionAttempts60s: number;
  readonly receivedBytes60s: number;
  readonly interactionBytes60s: number;
  readonly reservedBytes: number;
  readonly retryAfter: number;
  readonly handshakes60s: number;
}
export interface RecoveryBudget {
  beginRound(kind: RecoveryRoundKind): RecoveryRound;
  endRound(round: RecoveryRound): void;
  cancelRound(round: RecoveryRound): void;
  /** 调用方须捕获身份操作开始时的round，不能在WebLock等待后改借新的轮。 */
  fetch(round: RecoveryRound, input: string, init?: RequestInit): Promise<Response>;
  snapshot(): RecoveryBudgetSnapshot;
  /** 实时握手独立于REST计数，实际申请不退款；额度不足只降级。 */
  tryReserveWebSocket(round: RecoveryRound): boolean;
  /** 自动current轮发前资格；null等待在途释放，不用新轮试探额度。 */
  nextCurrentInteractionAt(): number | null;
  /** 完整业务解析后接纳前再次检查原轮截止；不会创建或延长轮。 */
  assertRound(round: RecoveryRound): void;
}
interface RoundState {
  handle: RecoveryRound; kind: RecoveryRoundKind; attempts: number; bytes: number;
  handshake: boolean; stopped: boolean; failure?: RecoveryFailure; timer?: unknown; controllers: Set<AbortController>;
}
interface Charge { at: number; amount: number; interaction: boolean }
interface Reservation { remaining: number; interaction: boolean }
const MiB = 1024 * 1024;
/** 路由响应上限在发前预留；静态公开制品不得从此API通道绕入外域。 */
function route(input: string, init: RequestInit, origin: string): { identity: boolean; current: boolean; maximum: number } {
  // 与session的绝对同源URL兼容；协议相对地址及反斜线变体不作为合法API输入。
  if (input.startsWith('//') || /[\\\u0000-\u0020\u007f]/.test(input)) throw new RecoveryBudgetError('route');
  let url: URL;
  try { url = new URL(input, origin); } catch { throw new RecoveryBudgetError('route'); }
  if (url.origin !== origin || !['https:', 'http:'].includes(url.protocol)) throw new RecoveryBudgetError('route');
  const path = url.pathname;
  const method = (init.method ?? 'GET').toUpperCase();
  if (url.username || url.password || url.hash) throw new RecoveryBudgetError('route');
  if (path.startsWith('/api/v1/shares/')) {
    const matched = /^\/api\/v1\/shares\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(\/.*)$/.exec(path);
    if (!matched) throw new RecoveryBudgetError('route');
    const suffix = matched[1];
    if (method === 'GET' && suffix === '/context') return { identity: false, current: false, maximum: 16 * 1024 };
    if (method === 'GET' && suffix === '/schema') return { identity: false, current: false, maximum: 768 * 1024 };
    if (method === 'POST' && ['/devices/snapshots/query', '/devices/current-values/query', '/alarms/query'].includes(suffix)
      || method === 'GET' && (suffix === '/devices/catalog' || /^\/devices\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/properties\/[A-Za-z0-9_-]{1,64}\/history$/.test(suffix))) {
      return { identity: false, current: suffix === '/devices/current-values/query', maximum: 4 * MiB };
    }
    throw new RecoveryBudgetError('route');
  }
  if (!path.startsWith('/api/v1/app/')) throw new RecoveryBudgetError('route');
  const identity = /^\/api\/v1\/app\/browser-auth\/(login|refresh|logout)$/.test(path) && method === 'POST';
  if (path.startsWith('/api/v1/app/browser-auth/') && !identity) throw new RecoveryBudgetError('route');
  if (/^\/api\/v1\/app\/applications\/app_[a-f0-9]{32}\/resolve$/.test(path) && method === 'GET') return { identity, current: false, maximum: 2048 };
  if (/^\/api\/v1\/app\/applications\/app_[a-f0-9]{32}\/current$/.test(path) && method === 'GET') return { identity, current: false, maximum: 64 * 1024 };
  if (/^\/api\/v1\/app\/applications\/app_[a-f0-9]{32}\/versions\/[^/]+\/dashboards\/[^/]+\/schema$/.test(path) && method === 'GET') return { identity, current: false, maximum: 768 * 1024 };
  return { identity, current: path === '/api/v1/app/devices/current-values/query', maximum: identity ? 64 * 1024 : 4 * MiB };
}

/** 所有API先计次/预留，完整消费响应后才释放槽；401返回时不会再占槽等待共享刷新。 */
export function createRecoveryBudget(ports: RecoveryBudgetPorts): RecoveryBudget {
  let sequence = 0;
  let activeRound: RoundState | undefined;
  let inFlight = 0;
  let currentInFlight = 0;
  let queued = 0;
  let retryAfter = 0;
  const rounds = new WeakMap<RecoveryRound, RoundState>();
  const attempts: Charge[] = [];
  const handshakes: number[] = [];
  const received: Charge[] = [];
  const receivedBuckets = new Map<string, Charge>();
  const reservations = new Set<Reservation>();
  const waiters = new Set<() => void>();
  const waiting = new Map<RoundState, Map<object, number | null>>();
  const notifyWaiting = (state: RoundState): void => {
    const entries = [...(waiting.get(state)?.values() ?? [])];
    const value = entries.length === 0 ? null : {
      nextAvailableAt: entries.some(entry => entry === null) ? null : Math.min(...entries as number[]),
    };
    try { ports.onWait?.(state.handle, value); } catch { /* 提示不能改变预算或排队生命周期。 */ }
  };
  const clearWaiting = (state: RoundState, request: object): void => {
    const entries = waiting.get(state);
    if (!entries?.delete(request)) return;
    if (entries.size === 0) waiting.delete(state);
    notifyWaiting(state);
  };
  const wake = (): void => { for (const waiter of [...waiters]) waiter(); };
  const prune = (): void => {
    const start = ports.now() - 60000;
    while (handshakes[0] !== undefined && handshakes[0] <= start) handshakes.shift();
    while (attempts[0] && attempts[0].at <= start) attempts.shift();
    while (received[0] && received[0].at <= start) {
      const old = received.shift()!; receivedBuckets.delete(`${old.at}/${old.interaction}`);
    }
  };
  const sum = (charges: readonly Charge[], interaction = false): number => charges.reduce((total, entry) => total + (!interaction || entry.interaction ? entry.amount : 0), 0);
  const reserved = (interaction = false): number => [...reservations].reduce((total, entry) => total + (!interaction || entry.interaction ? entry.remaining : 0), 0);
  const stop = (state: RoundState, failure?: RecoveryFailure): void => {
    if (state.stopped) return;
    state.stopped = true; state.failure = failure;
    if (waiting.delete(state)) notifyWaiting(state);
    if (state.timer !== undefined) ports.clearTimer(state.timer);
    for (const controller of state.controllers) controller.abort();
    if (failure) { try { ports.onFailure(state.handle, failure); } catch { /* UI不能撤销硬截止或影响资源回收。 */ } }
    wake();
  };
  const guard = (state: RoundState): void => {
    if (!state.stopped && ports.now() >= state.handle.deadline) stop(state, 'deadline');
    if (state.stopped) throw new RecoveryBudgetError(state.failure ?? 'cancelled');
  };
  const get = (handle: RecoveryRound): RoundState => {
    const state = rounds.get(handle);
    if (!state) throw new RecoveryBudgetError('cancelled');
    return state;
  };
  // 这里计算达到全部已知窗口条件的时刻，区别于wait的“下一次重新检查”定时器。
  const countAvailable = (charges: readonly Charge[], limit: number, window: number, now: number): number => {
    const active = charges.filter(entry => entry.at + window > now);
    return active.length < limit ? now : active[active.length - limit].at + window;
  };
  const bytesAvailable = (charges: readonly Charge[], maximum: number, limit: number, now: number): number => {
    let total = sum(charges) + maximum;
    if (total <= limit) return now;
    for (const charge of charges) {
      total -= charge.amount;
      if (total <= limit) return charge.at + 60000;
    }
    return now;
  };
  const markWaiting = (state: RoundState, request: object, limits: { current: boolean; maximum: number }): void => {
    const now = ports.now();
    const interaction = state.kind !== 'full';
    const needsReservationRelease = reserved() > 0 && sum(received) + reserved() + limits.maximum > 64 * MiB
      || interaction && reserved(true) > 0 && sum(received, true) + reserved(true) + limits.maximum > 56 * MiB;
    const nextAvailableAt = inFlight >= 4 || limits.current && currentInFlight > 0 || needsReservationRelease ? null : Math.max(
      now, retryAfter, countAvailable(attempts, 4, 1000, now), countAvailable(attempts, 120, 60000, now),
      interaction ? countAvailable(attempts.filter(entry => entry.interaction), 100, 60000, now) : now,
      bytesAvailable(received, limits.maximum, 64 * MiB, now),
      interaction ? bytesAvailable(received.filter(entry => entry.interaction), limits.maximum, 56 * MiB, now) : now,
    );
    let entries = waiting.get(state);
    if (!entries) { entries = new Map(); waiting.set(state, entries); }
    entries.set(request, nextAvailableAt);
    notifyWaiting(state);
  };
  const wait = (state: RoundState, signal?: AbortSignal): Promise<void> => new Promise((resolve, reject) => {
    let timer: unknown;
    const done = (): void => { waiters.delete(done); ports.clearTimer(timer); signal?.removeEventListener('abort', aborted); resolve(); };
    const aborted = (): void => { waiters.delete(done); ports.clearTimer(timer); signal?.removeEventListener('abort', aborted); reject(signal!.reason); };
    waiters.add(done); signal?.addEventListener('abort', aborted, { once: true });
    // 每次只等待最近窗口边界或本轮截止，不忙轮询，也不延长deadline。
    const now = ports.now();
    const boundaries = [state.handle.deadline, retryAfter, ...attempts.map((entry) => entry.at + 1000),
      ...attempts.map((entry) => entry.at + 60000), ...received.map((entry) => entry.at + 60000)].filter((value) => value > now);
    timer = ports.setTimer(done, Math.max(1, boundaries.reduce((earliest, value) => Math.min(earliest, value), state.handle.deadline) - now));
    if (signal?.aborted) aborted();
  });
  const snapshot = (): RecoveryBudgetSnapshot => {
    prune();
    return { inFlight, currentInFlight, queued, attempts60s: attempts.length, interactionAttempts60s: sum(attempts, true),
      receivedBytes60s: sum(received), interactionBytes60s: sum(received, true), reservedBytes: reserved(), retryAfter, handshakes60s: handshakes.length };
  };
  return {
    snapshot,
    nextCurrentInteractionAt: () => {
      prune();
      if (inFlight >= 4 || currentInFlight > 0 || reserved() > 0) return null;
      const now = ports.now();
      return Math.max(now, retryAfter,
        countAvailable(attempts, 4, 1000, now), countAvailable(attempts, 120, 60000, now),
        countAvailable(attempts.filter(entry => entry.interaction), 100, 60000, now),
        bytesAvailable(received, 4 * MiB, 64 * MiB, now),
        bytesAvailable(received.filter(entry => entry.interaction), 4 * MiB, 56 * MiB, now));
    },
    tryReserveWebSocket: handle => {
      const state = get(handle); guard(state); prune();
      if (state.handshake || handshakes.length >= 4) return false;
      state.handshake = true; handshakes.push(ports.now());
      return true;
    },
    assertRound: (handle) => guard(get(handle)),
    beginRound: (kind) => {
      if (activeRound) stop(activeRound);
      const handle = Object.freeze({ id: ++sequence, deadline: ports.now() + 30000 });
      const state: RoundState = { handle, kind, attempts: 0, bytes: 0, handshake: false, stopped: false, controllers: new Set() };
      rounds.set(handle, state); activeRound = state;
      state.timer = ports.setTimer(() => stop(state, 'deadline'), 30000);
      return handle;
    },
    endRound: (handle) => stop(get(handle)),
    cancelRound: (handle) => stop(get(handle)),
    fetch: async (handle, input, init = {}) => {
      const state = get(handle);
      const limits = route(input, init, ports.origin);
      const interaction = state.kind !== 'full';
      const signal = limits.identity ? undefined : init.signal ?? undefined;
      guard(state); signal?.throwIfAborted();
      if (queued >= 64) { stop(state, 'queue'); throw new RecoveryBudgetError('queue'); }
      queued++;
      const waitingRequest = {};
      try {
        while (true) {
          guard(state); signal?.throwIfAborted(); prune();
          if (state.attempts >= 20) { stop(state, 'attempts'); throw new RecoveryBudgetError('attempts'); }
          if (state.bytes >= 8 * MiB) { stop(state, 'bytes'); throw new RecoveryBudgetError('bytes'); }
          const now = ports.now();
          if (inFlight < 4 && (!limits.current || currentInFlight === 0)
            && attempts.filter((entry) => entry.at > now - 1000).length < 4 && attempts.length < 120
            && (!interaction || sum(attempts, true) < 100) && now >= retryAfter
            && sum(received) + reserved() + limits.maximum <= 64 * MiB
            && (!interaction || sum(received, true) + reserved(true) + limits.maximum <= 56 * MiB)) break;
          markWaiting(state, waitingRequest, limits);
          await wait(state, signal);
        }
      } finally { queued--; clearWaiting(state, waitingRequest); }
      // 从最终资格检查到占槽/预留没有await，避免并发检查各自看到同一个空槽。
      state.attempts++; attempts.push({ at: ports.now(), amount: 1, interaction });
      inFlight++; if (limits.current) currentInFlight++;
      const reservation: Reservation = { remaining: limits.maximum, interaction };
      reservations.add(reservation);
      const controller = new AbortController();
      if (!limits.identity) state.controllers.add(controller);
      const abort = (): void => controller.abort(signal?.reason);
      signal?.addEventListener('abort', abort, { once: true });
      const buffer = new Uint8Array(limits.maximum);
      let size = 0;
      try {
        const response = await ports.fetch(input, { ...init, mode: 'same-origin', redirect: 'error', signal: limits.identity ? undefined : controller.signal });
        // 服务端等待从headers到达时登记，旧轮截止不能抹掉跨轮Retry-After。
        if (response.status === 429) {
          const text = response.headers.get('Retry-After');
          const delay = text && /^\d+$/.test(text) ? Number(text) * 1000 : text ? Math.max(0, Date.parse(text) - Date.now()) : 0;
          if (Number.isFinite(delay)) retryAfter = Math.max(retryAfter, ports.now() + delay);
        }
        const reader = response.body?.getReader();
        if (reader) {
          try {
            while (true) {
              const chunk = await reader.read();
              if (chunk.done) break;
              // 已就绪流的微任务可能延后timer；每个chunk都执行单调时钟硬围栏。
              if (!state.stopped && ports.now() >= state.handle.deadline) stop(state, 'deadline');
              const length = chunk.value.byteLength;
              prune();
              // 毫秒桶最多保留60秒×两类别，避免碎片流制造无界逐chunk对象。
              const at = Math.ceil(ports.now()); const key = `${at}/${interaction}`;
              const existing = receivedBuckets.get(key);
              if (existing) existing.amount += length;
              else { const charge = { at, amount: length, interaction }; receivedBuckets.set(key, charge); received.push(charge); }
              reservation.remaining = Math.max(0, reservation.remaining - length);
              state.bytes += length; size += length;
              if (size > limits.maximum || state.bytes > 8 * MiB || sum(received) > 64 * MiB || (interaction && sum(received, true) > 56 * MiB)) stop(state, 'bytes');
              if (state.stopped || controller.signal.aborted) {
                if (!limits.identity) { await reader.cancel(); guard(state); signal?.throwIfAborted(); throw new RecoveryBudgetError('cancelled'); }
                // 已发身份即使截止/超限也继续消费并丢弃，等待终态才允许WebLock释放。
              } else buffer.set(chunk.value, size - length);
            }
          } finally { reader.releaseLock(); }
        }
        guard(state); signal?.throwIfAborted();
        const bytes = buffer.subarray(0, size);
        const headers = new Headers(response.headers);
        // fetch已解压；新Response不再宣称原传输编码/长度，调用方只解析这份已计量正文。
        headers.delete('Content-Encoding'); headers.delete('Content-Length');
        return new Response([204, 205, 304].includes(response.status) ? null : bytes, { status: response.status, statusText: response.statusText, headers });
      } catch (error) {
        if (!state.stopped && !signal?.aborted) stop(state, 'network');
        if (state.stopped) throw new RecoveryBudgetError(state.failure ?? 'cancelled');
        throw error;
      } finally {
        signal?.removeEventListener('abort', abort); state.controllers.delete(controller);
        reservations.delete(reservation); inFlight--; if (limits.current) currentInFlight--;
        wake();
      }
    },
  };
}
