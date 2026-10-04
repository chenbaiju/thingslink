import { parseDashboardRuntimeResponse } from '@things-link/client-contracts/dashboard/v1';
import type { SessionCoordinator } from '../auth/session';
import type { components } from '../types/api/schema';
export interface ControlDevice { readonly id: string; readonly name: string }
type CatalogCommand = components['schemas']['AppCommandCatalogResponse']['commands'][number];
export type ControlCommand = Readonly<CatalogCommand>;
export type CommandStatus = 'ACCEPTED' | 'DISPATCHED' | 'ACKNOWLEDGED' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT';
export interface ControlResult { readonly commandId: string; readonly status: CommandStatus; readonly commandKey: string; readonly text: string }
export interface ControlSnapshot {
  readonly busy: boolean; readonly state: 'IDLE' | 'UNKNOWN' | 'RESULT' | 'REJECTED' | 'CLOSED';
  readonly devices: readonly ControlDevice[]; readonly nextCursor: string | null;
  readonly deviceId: string | null; readonly commands: readonly ControlCommand[]; readonly commandKey: string | null;
  readonly input: string; readonly result: ControlResult | null; readonly message: string;
}
interface Intent { readonly deviceId: string; readonly commandKey: string; readonly body: string; readonly key: string }
export interface ControlBudget { readonly starts: number[]; pending: boolean }
export interface ControlPorts {
  readonly budget?: ControlBudget;
  readonly session: Pick<SessionCoordinator, 'fetchDeviceControl' | 'checkCurrent'>;
  fetch(input: string, init?: RequestInit): Promise<Response>;
  now(): number; randomId(): string; available(): boolean;
  setTimer(callback: () => void, delay: number): unknown; clearTimer(timer: unknown): void;
  changed(snapshot: ControlSnapshot): void;
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const encoder = new TextEncoder();
const states: readonly string[] = ['ACCEPTED', 'DISPATCHED', 'ACKNOWLEDGED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT'];
class ControlError extends Error { constructor(readonly reason: 'invalid' | 'limit' | 'unavailable' | 'rejected' | 'cancelled', readonly code?: number, readonly unauthorized = false) { super(reason); } }
function check(condition: unknown): asserts condition { if (!condition) throw new ControlError('invalid'); }
function exact(value: unknown, keys: string[]): asserts value is Record<string, unknown> {
  check(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join() === [...keys].sort().join());
}
function json(text: string): unknown { parseDashboardRuntimeResponse(encoder.encode(text)); return JSON.parse(text); }
/** ADR0110：内存意图和独立等待预算；取消等待绝不等于撤销设备操作。 */
export function createDeviceControl(ports: ControlPorts) {
  let state: ControlSnapshot = { busy: false, state: 'IDLE', devices: [], nextCursor: null, deviceId: null,
    commands: [], commandKey: null, input: '{}', result: null, message: '' };
  let intent: Intent | undefined; let generation = 0; let closed = false; let abort: AbortController | undefined;
  const budget = ports.budget ?? { starts: [], pending: false };
  const starts = budget.starts;
  const emit = (patch: Partial<ControlSnapshot>) => { state = Object.freeze({ ...state, ...patch }); ports.changed(state); };
  const identity = ports.session.checkCurrent();
  const fence = (captured: number) => {
    if (closed || generation !== captured || !ports.available()) throw new ControlError('cancelled');
    const current = ports.session.checkCurrent();
    if (current.appUserId !== identity.appUserId || current.projectId !== identity.projectId || current.status !== 'authenticated') throw new ControlError('cancelled');
  };
  async function request(path: string, init: RequestInit, captured: number): Promise<{ value: unknown; text: string }> {
    fence(captured); if (budget.pending) throw new ControlError('limit');
    const now = ports.now();
    while (starts.length && starts[0]! <= now - 60000) starts.shift();
    if (starts.length >= 20) throw new ControlError('limit');
    starts.push(now); budget.pending = true; const controller = new AbortController(); abort = controller;
    const deadline = now + 10000;
    const requestFence = () => { fence(captured);
      if (ports.now() >= deadline || controller.signal.aborted) { controller.abort(); throw new ControlError('unavailable'); }
    };
    let timer: unknown;
    const cancelled = new Promise<never>((_, reject) => {
      controller.signal.addEventListener('abort', () => reject(new ControlError('unavailable')), { once: true });
      timer = ports.setTimer(() => controller.abort(), 10000);
    });
    const action = async () => {
      const response = await ports.session.fetchDeviceControl(path, { ...init, signal: controller.signal }, ports.fetch);
      requestFence(); const chunks: Uint8Array[] = []; let size = 0;
      const reader = response.body?.getReader();
      const cancelBody = () => { void reader?.cancel().catch(() => {}); };
      controller.signal.addEventListener('abort', cancelBody, { once: true });
      try {
        if (reader) for (;;) { const part = await reader.read(); if (part.done) break;
          size += part.value.byteLength; if (size > 262144) { await reader.cancel(); throw new ControlError('invalid'); }
          chunks.push(part.value); requestFence();
        }
      } finally { controller.signal.removeEventListener('abort', cancelBody); reader?.releaseLock(); }
      const bytes = new Uint8Array(size); let position = 0;
      for (const chunk of chunks) { bytes.set(chunk, position); position += chunk.byteLength; }
      requestFence();
      const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes); const value = json(text);
      requestFence(); if (!response.ok) {
        const code = value && typeof value === 'object' && 'code' in value && typeof value.code === 'number' ? value.code : undefined;
        throw new ControlError(response.status >= 500 ? 'unavailable' : 'rejected', code, response.status === 401 || response.status === 403);
      }
      check(init.method === 'POST' ? response.status === 202 : response.status === 200);
      return { value, text };
    };
    try { return await Promise.race([action().finally(() => { budget.pending = false; }), cancelled]); }
    finally { ports.clearTimer(timer); if (abort === controller) abort = undefined; }
  }
  async function run(action: (captured: number) => Promise<void>, write = false) {
    if (closed || state.busy || !ports.available()) return;
    if (budget.pending) { emit({ message: '上一请求正在释放传输资源，请稍后明确重试。' }); return; }
    const captured = generation; emit({ busy: true, message: '' });
    try { fence(captured); await action(captured); }
    catch (error) {
      if (closed || captured !== generation) return;
      if (error instanceof ControlError && (error.unauthorized || error.code && [60009, 60010, 60011].includes(error.code))) {
        intent = undefined; emit({ state: 'REJECTED', devices: [], nextCursor: null, commands: [], commandKey: null, deviceId: null, input: '{}', result: null, message: error.code ? `控制权限未通过（${error.code}），已清除本地数据。` : '控制身份未通过，已清除本地数据，请重新登录。' });
      } else if (write && intent && !(error instanceof ControlError && error.reason === 'rejected'))
        emit({ state: 'UNKNOWN', message: '结果未知，原请求可能已受理。只能重试原请求，或明确放弃后重新编辑。' });
      else emit({ ...(write ? { state: 'REJECTED' as const } : {}), message: error instanceof ControlError && error.reason === 'limit'
        ? '请求过于频繁，请稍后再操作。' : error instanceof ControlError && error.code
          ? `操作未通过（${error.code}），请重新确认权限、命令定义或登录状态。` : '操作未完成，请明确重试；不会自动发送命令。' });
    } finally { if (!closed && captured === generation) emit({ busy: false }); }
  }
  const locked = () => state.busy || state.state === 'UNKNOWN';
  async function send(captured: number) {
    const original = intent!;
    const { value, text } = await request(`/api/v1/app/devices/${original.deviceId}/commands`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': original.key }, body: original.body }, captured);
    const result = parseResult(value, text, original.commandKey); fence(captured);
    if (intent !== original) throw new ControlError('cancelled'); emit({ state: 'RESULT', result });
  }
  function parseResult(value: unknown, text: string, commandKey: string): ControlResult {
    exact(value, ['commandId', 'status', 'commandKey', 'response', 'acceptedAt', 'failureCode', 'failureMessage']);
    check(typeof value.commandId === 'string' && UUID.test(value.commandId) && value.commandKey === commandKey
      && typeof value.status === 'string' && states.includes(value.status));
    check((value.acceptedAt === null || typeof value.acceptedAt === 'string' && Number.isFinite(Date.parse(value.acceptedAt)))
      && (value.failureCode === null || typeof value.failureCode === 'string')
      && (value.failureMessage === null || typeof value.failureMessage === 'string'));
    return Object.freeze({ commandId: value.commandId, status: value.status as CommandStatus, commandKey, text });
  }
  return {
    snapshot: () => state,
    devices: (cursor?: string) => {
      if (locked()) return Promise.resolve();
      return run(async captured => {
        const params = new URLSearchParams({ limit: '20' }); if (cursor) params.set('cursor', cursor);
        const { value } = await request(`/api/v1/app/devices?${params}`, {}, captured);
        exact(value, ['items', 'nextCursor', 'hasMore']); check(Array.isArray(value.items) && value.items.length <= 20);
        check(typeof value.hasMore === 'boolean' && (value.hasMore ? typeof value.nextCursor === 'string' && /^[\x21-\x7e]{1,2048}$/.test(value.nextCursor) : value.nextCursor === null));
        const seen = new Set<string>(); const devices = value.items.map(item => {
          exact(item, ['id', 'deviceKey', 'name', 'description', 'status', 'location', 'lastOnlineAt', 'createdAt']);
          check(typeof item.id === 'string' && UUID.test(item.id) && !seen.has(item.id) && typeof item.name === 'string'); seen.add(item.id);
          return Object.freeze({ id: item.id, name: item.name });
        });
        emit({ devices, nextCursor: value.nextCursor as string | null });
      });
    },
    selectDevice: (deviceId: string) => {
      if (locked() || !state.devices.some(device => device.id === deviceId)) return Promise.resolve();
      intent = undefined; emit({ deviceId, commands: [], commandKey: null, input: '{}', state: 'IDLE', result: null });
      return run(async captured => {
        const { value } = await request(`/api/v1/app/devices/${deviceId}/command-definitions`, {}, captured);
        exact(value, ['deviceId', 'commands']); check(value.deviceId === deviceId && Array.isArray(value.commands) && value.commands.length <= 100);
        const keys = new Set<string>(); const commands = value.commands.map(command => {
          exact(command, ['commandKey', 'name', 'description', 'inputSchema', 'outputSchema', 'timeoutSeconds']);
          check(typeof command.commandKey === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(command.commandKey) && !keys.has(command.commandKey)); keys.add(command.commandKey);
          check(typeof command.name === 'string' && (command.description === null || typeof command.description === 'string')
            && typeof command.timeoutSeconds === 'number' && Number.isInteger(command.timeoutSeconds) && command.timeoutSeconds > 0 && command.timeoutSeconds <= 86400);
          for (const field of ['inputSchema', 'outputSchema']) check(command[field] === null || typeof command[field] === 'string' && encoder.encode(command[field]).length <= 65536);
          return Object.freeze(command as unknown as ControlCommand);
        }); emit({ commands });
      });
    },
    selectCommand: (commandKey: string) => { if (locked() || !state.commands.some(command => command.commandKey === commandKey)) return;
      intent = undefined; emit({ commandKey, input: '{}', state: 'IDLE', result: null }); },
    edit: (input: string) => { if (locked()) return;
      if (encoder.encode(input).length > 65536) { emit({ message: '输入超过 64KiB，未采用本次编辑。' }); return; }
      intent = undefined; emit({ input, state: 'IDLE', result: null }); },
    submit: () => {
      if (locked() || !state.deviceId || !state.commandKey) return Promise.resolve();
      try {
        const input = json(state.input); check(input && typeof input === 'object' && !Array.isArray(input));
        const body = `{"commandKey":${JSON.stringify(state.commandKey)},"input":${state.input}}`; check(encoder.encode(body).length <= 65536);
        const key = ports.randomId(); check(UUID.test(key)); intent = Object.freeze({ deviceId: state.deviceId, commandKey: state.commandKey, body, key });
        emit({ result: null, state: 'IDLE' });
      } catch { emit({ message: '请输入不超过 64KiB 请求上限的合法 JSON 对象。' }); return Promise.resolve(); }
      return run(send, true);
    },
    retry: () => state.state === 'UNKNOWN' && intent ? run(send, true) : Promise.resolve(),
    abandon: () => { if (state.busy || !intent) return; intent = undefined;
      emit({ state: 'IDLE', result: null, message: '已放弃本地意图；原请求可能已受理，这不是撤销设备操作。' }); },
    refresh: () => {
      const result = state.result; const original = intent;
      if (state.state !== 'RESULT' || !result || !original || state.busy || result.commandKey !== original.commandKey) return Promise.resolve();
      return run(async captured => {
        const response = await request(`/api/v1/app/devices/${original.deviceId}/commands/${result.commandId}`, {}, captured);
        const next = parseResult(response.value, response.text, original.commandKey); check(next.commandId === result.commandId); emit({ result: next });
      });
    },
    dispose: () => { if (closed) return; closed = true; generation++; abort?.abort(); intent = undefined;
      emit({ state: 'CLOSED', busy: false, devices: [], nextCursor: null, commands: [], deviceId: null, commandKey: null, input: '', result: null, message: '' }); },
  };
}
