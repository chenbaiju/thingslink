import { isStrictJsonNumber, parseDashboardRuntimeResponse } from '@things-link/client-contracts/dashboard/v1';
import type { ShareSession } from '../share/session';
import type { SessionCoordinator, SessionRealtimeConnection } from '../auth/session';
export interface RealtimeDeviceKeys { readonly deviceId: string; readonly propertyKeys: readonly string[] }
export interface RealtimeAlarmGroup {
  readonly queryKey: string;
  readonly devices: readonly { readonly deviceId: string; readonly expectedModelVersionId: string }[];
  readonly conditionStates: readonly string[]; readonly ackStates: readonly string[]; readonly severities: readonly string[];
}
export interface RealtimeSubscription {
  readonly requestId: string;
  readonly devices: readonly RealtimeDeviceKeys[];
  readonly alarms?: readonly RealtimeAlarmGroup[];
  readonly runtimeContext: { readonly appKey: string; readonly applicationVersionId: string; readonly publicationRevision: string; readonly dashboardVersionId: string };
}
export type RealtimeState = 'IDLE' | 'CONNECTING' | 'SUBSCRIBED' | 'REST_READY' | 'CLOSED';
export type RealtimeControllerOptions = ({
  readonly kind?: 'app';
  readonly version?: 'v1' | 'v2';
  readonly session: Pick<SessionCoordinator, 'openDashboardRealtime'>;
  readonly subscription: RealtimeSubscription;
} | {
  readonly kind: 'share';
  readonly session: Pick<ShareSession, 'openShareRealtime'>;
  readonly subscription: Pick<RealtimeSubscription, 'requestId' | 'devices'>;
}) & {
  readonly deadline: number;
  now(): number;
  setTimer(callback: () => void, delay: number): unknown;
  clearTimer(timer: unknown): void;
  tryReserve(): boolean;
  onDirty(): void;
  onState(state: RealtimeState): void;
  onRevoked(): void;
}
export interface RealtimeController {
  start(): Promise<'SUBSCRIBED' | 'REST_READY'>;
  takeDirty(): readonly RealtimeDeviceKeys[];
  takeDirtyAlarms(): readonly string[];
  close(): void;
  state(): RealtimeState;
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function check(value: unknown): asserts value { if (!value) throw new Error('Invalid realtime control frame'); }
function exact(value: unknown, keys: string[]): asserts value is Record<string, unknown> {
  check(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(',') === [...keys].sort().join(','));
}
/** 只交换已声明键集合，消息不含值，也不借提示产生服务端水位或接受顺序。 */
export function createRealtimeController(options: RealtimeControllerOptions): RealtimeController {
  const allowed = new Map<string, Set<string>>();
  let count = 0;
  for (const device of options.subscription.devices) {
    check(UUID.test(device.deviceId) && !allowed.has(device.deviceId) && device.propertyKeys.length >= 1 && device.propertyKeys.length <= 50);
    const keys = new Set(device.propertyKeys); check(keys.size === device.propertyKeys.length);
    check([...keys].every(key => /^[A-Za-z0-9_-]{1,64}$/.test(key)));
    allowed.set(device.deviceId, keys); count += keys.size;
  }
  const v2 = options.kind !== 'share' && options.version === 'v2';
  const alarms = options.kind !== 'share' ? options.subscription.alarms ?? [] : [];
  check(v2 || alarms.length === 0);
  check(alarms.length <= 20);
  const alarmKeys = new Set<string>(); const mergedDevices = new Set(allowed.keys());
  let weight = count;
  const enums = (values: readonly string[], allowedValues: string[]): void => {
    check(Array.isArray(values) && values.length >= 1 && new Set(values).size === values.length
      && values.every(value => allowedValues.includes(value)));
  };
  for (const alarm of alarms) {
    exact(alarm, ['queryKey', 'devices', 'conditionStates', 'ackStates', 'severities']);
    check(/^[A-Za-z0-9_-]{1,64}$/.test(alarm.queryKey) && !alarmKeys.has(alarm.queryKey)); alarmKeys.add(alarm.queryKey);
    check(alarm.devices.length >= 1 && alarm.devices.length <= 20); const groupDevices = new Set<string>();
    for (const device of alarm.devices) {
      exact(device, ['deviceId', 'expectedModelVersionId']);
      check(UUID.test(device.deviceId) && UUID.test(device.expectedModelVersionId) && !groupDevices.has(device.deviceId));
      groupDevices.add(device.deviceId); mergedDevices.add(device.deviceId); weight++;
    }
    enums(alarm.conditionStates, ['PENDING', 'ACTIVE', 'CLEARED']);
    enums(alarm.ackStates, ['UNACKNOWLEDGED', 'ACKNOWLEDGED']);
    enums(alarm.severities, ['INFO', 'WARNING', 'MINOR', 'MAJOR', 'CRITICAL']);
  }
  check((allowed.size >= 1 || v2 && alarms.length >= 1) && mergedDevices.size <= 20 && weight <= 200);
  const requestId = options.subscription.requestId;
  if (options.kind !== 'share') {
  const context = options.subscription.runtimeContext;
  exact(context, ['appKey', 'applicationVersionId', 'publicationRevision', 'dashboardVersionId']);
  check(/^app_[0-9a-f]{32}$/.test(context.appKey) && UUID.test(context.applicationVersionId) && UUID.test(context.dashboardVersionId));
  check(/^[1-9][0-9]{0,18}$/.test(context.publicationRevision) && BigInt(context.publicationRevision) <= 9223372036854775807n);
  } else exact(options.subscription, ['requestId', 'devices']);
  const payload = JSON.stringify({ type: 'SUBSCRIBE', requestId, devices: options.subscription.devices, ...(options.kind === 'share' ? {} : { runtimeContext: options.subscription.runtimeContext, ...(v2 ? { alarms } : {}) }) });
  check(requestId.trim().length >= 1 && requestId.length <= 128 && new TextEncoder().encode(payload).length <= 32768);
  let state: RealtimeState = 'IDLE'; let connection: SessionRealtimeConnection | undefined; let timer: unknown;
  let subscriptionId: string | undefined; let pendingId: string | undefined; let controlCount = 0;
  let dirty = new Map<string, Set<string>>(); let dirtyAlarms = new Set<string>(); let dirtyNotified = false;
  let resolveStart: ((value: 'SUBSCRIBED' | 'REST_READY') => void) | undefined;
  let started: Promise<'SUBSCRIBED' | 'REST_READY'> | undefined;
  const transition = (next: RealtimeState): void => { state = next; options.onState(next); };
  const fallback = (revoked = false): void => {
    if (state === 'CLOSED' || state === 'REST_READY') return;
    options.clearTimer(timer); dirty.clear(); dirtyAlarms.clear(); transition('REST_READY'); connection?.close();
    resolveStart?.('REST_READY'); resolveStart = undefined;
    if (revoked) options.onRevoked();
  };
  const frame = (data: unknown): void => {
    if (state !== 'CONNECTING' && state !== 'SUBSCRIBED') return;
    try {
      check(typeof data === 'string' && data.length <= 32768); const bytes = new TextEncoder().encode(data); check(bytes.length <= 32768);
      const parsed = parseDashboardRuntimeResponse(bytes);
      check(parsed && typeof parsed === 'object' && !Array.isArray(parsed) && !isStrictJsonNumber(parsed));
      if (parsed.type === 'SUBSCRIBED') {
        exact(parsed, ['type', 'requestId', 'subscriptionId', 'count', ...(v2 ? ['alarmCount'] : [])]);
        if (v2) check(isStrictJsonNumber(parsed.alarmCount) && parsed.alarmCount.lexical === String(alarms.length));
        check(state === 'CONNECTING' && options.now() < Math.min(options.deadline, ackDeadline));
        check(++controlCount <= 16 && parsed.requestId === requestId && typeof parsed.subscriptionId === 'string' && UUID.test(parsed.subscriptionId));
        check(isStrictJsonNumber(parsed.count) && parsed.count.lexical === String(count) && (!pendingId || pendingId === parsed.subscriptionId));
        subscriptionId = parsed.subscriptionId; options.clearTimer(timer); transition('SUBSCRIBED');
        resolveStart?.('SUBSCRIBED'); resolveStart = undefined; if ((dirty.size || dirtyAlarms.size) && !dirtyNotified) { dirtyNotified = true; options.onDirty(); } return;
      }
      exact(parsed, ['type', 'subscriptionId', 'devices', ...(v2 ? ['alarmQueryKeys'] : [])]);
      check(parsed.type === 'INVALIDATE' && typeof parsed.subscriptionId === 'string' && UUID.test(parsed.subscriptionId));
      check(subscriptionId ? parsed.subscriptionId === subscriptionId : !pendingId || pendingId === parsed.subscriptionId);
      if (!subscriptionId) pendingId = parsed.subscriptionId;
      check(Array.isArray(parsed.devices) && parsed.devices.length <= allowed.size);
      const incomingAlarms = v2 ? parsed.alarmQueryKeys : [];
      check(Array.isArray(incomingAlarms) && incomingAlarms.length <= alarmKeys.size
        && new Set(incomingAlarms).size === incomingAlarms.length
        && incomingAlarms.every(key => typeof key === 'string' && alarmKeys.has(key)));
      check(parsed.devices.length + incomingAlarms.length >= 1);
      for (const key of incomingAlarms) dirtyAlarms.add(key as string);
      const seen = new Set<string>(); let keys = 0;
      for (const value of parsed.devices) {
        exact(value, ['deviceId', 'propertyKeys']);
        check(typeof value.deviceId === 'string' && !seen.has(value.deviceId) && allowed.has(value.deviceId)); seen.add(value.deviceId);
        check(Array.isArray(value.propertyKeys) && value.propertyKeys.length >= 1 && value.propertyKeys.length <= 50 && new Set(value.propertyKeys).size === value.propertyKeys.length);
        const pending = dirty.get(value.deviceId) ?? new Set<string>();
        for (const key of value.propertyKeys) { check(typeof key === 'string' && allowed.get(value.deviceId)!.has(key)); pending.add(key); keys++; }
        dirty.set(value.deviceId, pending);
      }
      check(keys <= 200); if (state === 'SUBSCRIBED' && !dirtyNotified) { dirtyNotified = true; options.onDirty(); }
    } catch { fallback(); }
  };
  let ackDeadline = 0;
  return {
    state: () => state,
    start: () => {
      if (started) return started;
      started = new Promise(resolve => { resolveStart = resolve; });
      if (state === 'CLOSED') { resolveStart?.('REST_READY'); return started; }
      if (options.now() >= options.deadline || !options.tryReserve()) { fallback(); return started; }
      ackDeadline = Math.min(options.deadline, options.now() + 5000); transition('CONNECTING');
      timer = options.setTimer(() => fallback(), Math.max(0, ackDeadline - options.now()));
      try {
        const open = options.kind === 'share' ? options.session.openShareRealtime.bind(options.session)
          : (handlers: Parameters<SessionCoordinator['openDashboardRealtime']>[0]) => options.session.openDashboardRealtime(handlers, v2 ? 'v2' : 'v1');
        connection = open({ onOpen: () => {
          if (state !== 'CONNECTING' || options.now() >= ackDeadline) { fallback(); return; }
          try { connection!.sendSubscribe(payload); } catch { fallback(); }
        }, onMessage: frame, onClose: event => {
          if (state === 'CLOSED' || state === 'REST_READY') return;
          fallback(event.code === 1008 || event.reason === 'identity' || event.reason === 'expired');
        } });
      } catch { fallback(); }
      return started;
    },
    takeDirty: () => {
      if (state !== 'SUBSCRIBED') return [];
      const taken = dirty; dirty = new Map(); dirtyNotified = dirtyAlarms.size > 0;
      return Object.freeze([...taken].map(([deviceId, keys]) => Object.freeze({ deviceId, propertyKeys: Object.freeze([...keys]) })));
    },
    takeDirtyAlarms: () => {
      if (state !== 'SUBSCRIBED') return [];
      const taken = dirtyAlarms; dirtyAlarms = new Set(); dirtyNotified = dirty.size > 0;
      return Object.freeze([...taken]);
    },
    close: () => { if (state === 'CLOSED') return; options.clearTimer(timer); dirty.clear(); dirtyAlarms.clear(); transition('CLOSED'); connection?.close(); resolveStart?.('REST_READY'); resolveStart = undefined; },
  };
}
