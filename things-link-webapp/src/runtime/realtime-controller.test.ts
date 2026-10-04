import { describe, expect, it, vi } from 'vitest';
import type { SessionRealtimeHandlers } from '../auth/session';
import { createRealtimeController } from './realtime-controller';
const id = '11111111-1111-4111-8111-111111111111';
const sub = '22222222-2222-4222-8222-222222222222';
function fixture(deadline = 30000, reserve = true, version: 'v1' | 'v2' = 'v1', pureAlarm = false) {
  let now = 0; let handlers!: SessionRealtimeHandlers;
  const timers = new Map<number, { at: number; callback: () => void }>(); let sequence = 0;
  const send = vi.fn(); const close = vi.fn(); const dirty = vi.fn(); const revoked = vi.fn();
  const open = vi.fn((value: SessionRealtimeHandlers, _version?: 'v1' | 'v2') => { handlers = value; return { sendSubscribe: send, close }; });
  const controller = createRealtimeController({ session: { openDashboardRealtime: open }, deadline, version,
    subscription: { requestId: 'request', devices: pureAlarm ? [] : [{ deviceId: id, propertyKeys: ['phase-a', '1st'] }],
      ...(version === 'v2' ? { alarms: [{ queryKey: 'a0', devices: [{ deviceId: id, expectedModelVersionId: sub }],
        conditionStates: ['ACTIVE'], ackStates: ['UNACKNOWLEDGED'], severities: ['MINOR', 'WARNING'] }] } : {}),
      runtimeContext: { appKey: `app_${'a'.repeat(32)}`, applicationVersionId: id, publicationRevision: '1', dashboardVersionId: id } },
    now: () => now, setTimer: (callback, delay) => { const key = ++sequence; timers.set(key, { at: now + delay, callback }); return key; },
    clearTimer: key => { timers.delete(key as number); }, tryReserve: () => reserve,
    onDirty: dirty, onState: vi.fn(), onRevoked: revoked });
  return { controller, open, send, close, dirty, revoked, handlers: () => handlers,
    frame: (value: unknown) => handlers.onMessage(JSON.stringify(value)),
    advance: (value: number) => { now += value; for (const [key, timer] of timers) if (timer.at <= now) { timers.delete(key); timer.callback(); } } };
}
const ack = { type: 'SUBSCRIBED', requestId: 'request', subscriptionId: sub, count: 2 };
const hint = { type: 'INVALIDATE', subscriptionId: sub, devices: [{ deviceId: id, propertyKeys: ['phase-a'] }] };
describe('bounded realtime control', () => {
  it('waits for correlated ACK, holds early dirty keys and swaps bounded sets', async () => {
    const f = fixture(); const start = f.controller.start(); expect(f.controller.start()).toBe(start);
    f.handlers().onOpen(); expect(JSON.parse(f.send.mock.calls[0]![0]).runtimeContext.appKey).toMatch(/^app_/);
    f.frame(hint); expect(f.dirty).not.toHaveBeenCalled(); f.frame(ack); expect(await start).toBe('SUBSCRIBED');
    f.frame(hint); expect(f.dirty).toHaveBeenCalledTimes(1);
    expect(f.controller.takeDirty()).toEqual(hint.devices); expect(f.controller.takeDirty()).toEqual([]);
    f.frame(hint); expect(f.dirty).toHaveBeenCalledTimes(2); expect(f.open).toHaveBeenCalledTimes(1);
  });
  it.each([5000, 1200])('falls back at ACK limit constrained by round %s', async deadline => {
    const f = fixture(deadline); const start = f.controller.start(); f.advance(deadline);
    expect(await start).toBe('REST_READY'); expect(f.close).toHaveBeenCalled(); expect(f.revoked).not.toHaveBeenCalled();
  });
  it('does not create a socket when quota is unavailable', async () => {
    const f = fixture(30000, false); expect(await f.controller.start()).toBe('REST_READY'); expect(f.open).not.toHaveBeenCalled();
  });
  it.each([{ ...ack, count: 1 }, { ...ack, requestId: 'old' }, { ...ack, extra: true }])('rejects incorrect ACK %j', async frame => {
    const f = fixture(); const start = f.controller.start(); f.frame(frame); expect(await start).toBe('REST_READY');
  });
  it.each([{ ...hint, subscriptionId: id }, { ...hint, devices: [{ deviceId: id, propertyKeys: ['secret'] }] }, { ...hint, values: {} }])('clears dirty and falls back on forbidden hint %j', async frame => {
    const f = fixture(); const start = f.controller.start(); f.frame(ack); await start; f.frame(hint); f.frame(frame);
    expect(f.controller.state()).toBe('REST_READY'); expect(f.controller.takeDirty()).toEqual([]);
  });
  it('rejects duplicate JSON fields, binary and oversize frames', async () => {
    for (const frame of ['{"type":"SUBSCRIBED","type":"SUBSCRIBED"}', new Uint8Array(2), ' '.repeat(32769)]) {
      const f = fixture(); const start = f.controller.start(); f.handlers().onMessage(frame); expect(await start).toBe('REST_READY');
    }
  });
  it('distinguishes revoked identity and ignores callbacks after local closure', async () => {
    const f = fixture(); const start = f.controller.start(); f.frame(ack); await start;
    f.handlers().onClose({ code: 1008, reason: 'transport' }); expect(f.revoked).toHaveBeenCalledTimes(1);
    f.frame(hint); expect(f.dirty).not.toHaveBeenCalled();
    const old = fixture(); const pending = old.controller.start(); old.controller.close(); old.frame(ack);
    expect(await pending).toBe('REST_READY'); expect(old.controller.state()).toBe('CLOSED');
  });
});

it('encodes share subscription without App runtime identity', async () => {
  let handlers!: SessionRealtimeHandlers; const send = vi.fn();
  const controller = createRealtimeController({ kind: 'share', session: { openShareRealtime: value => { handlers = value; return { sendSubscribe: send, close: vi.fn() }; } },
    subscription: { requestId: 'share', devices: [{ deviceId: id, propertyKeys: ['phase-a'] }] }, deadline: 30000,
    now: () => 0, setTimer: vi.fn(), clearTimer: vi.fn(), tryReserve: () => true, onDirty: vi.fn(), onState: vi.fn(), onRevoked: vi.fn() });
  const start = controller.start(); handlers.onOpen();
  expect(JSON.parse(send.mock.calls[0]![0])).toEqual({ type: 'SUBSCRIBE', requestId: 'share', devices: [{ deviceId: id, propertyKeys: ['phase-a'] }] });
  handlers.onMessage(JSON.stringify({ ...ack, requestId: 'share', count: 1 })); expect(await start).toBe('SUBSCRIBED');
});

describe('App dashboard v2 alarm invalidations', () => {
  const alarmHint = { type: 'INVALIDATE', subscriptionId: sub, devices: [], alarmQueryKeys: ['a0'] };
  it('supports pure alarm and buffers early hints until the dual-domain ACK', async () => {
    const f = fixture(30000, true, 'v2', true); const start = f.controller.start(); f.handlers().onOpen();
    expect(f.open.mock.calls[0]![1]).toBe('v2');
    expect(JSON.parse(f.send.mock.calls[0]![0])).toMatchObject({ devices: [], alarms: [{ queryKey: 'a0' }] });
    f.frame(alarmHint); expect(f.dirty).not.toHaveBeenCalled();
    f.frame({ ...ack, count: 0, alarmCount: 1 }); expect(await start).toBe('SUBSCRIBED');
    expect(f.dirty).toHaveBeenCalledTimes(1); expect(f.controller.takeDirty()).toEqual([]);
    expect(f.controller.takeDirtyAlarms()).toEqual(['a0']);
    f.frame(alarmHint); f.frame(alarmHint); expect(f.controller.takeDirtyAlarms()).toEqual(['a0']);
    expect(f.dirty).toHaveBeenCalledTimes(2);
  });
  it('drains both domains without losing another hint while REST is in flight', async () => {
    const f = fixture(30000, true, 'v2'); const start = f.controller.start(); f.frame({ ...ack, alarmCount: 1 }); await start;
    f.frame({ ...hint, alarmQueryKeys: ['a0'] });
    expect(f.controller.takeDirty()).toEqual(hint.devices); expect(f.controller.takeDirtyAlarms()).toEqual(['a0']);
    f.frame(alarmHint); expect(f.dirty).toHaveBeenCalledTimes(2); expect(f.controller.takeDirtyAlarms()).toEqual(['a0']);
    f.controller.close(); f.frame(alarmHint); expect(f.controller.takeDirtyAlarms()).toEqual([]);
  });
  it.each([{ ...ack }, { ...ack, alarmCount: 0 }, { ...ack, alarmCount: 1, cursor: 'forbidden' }])('rejects invalid v2 ACK %j', async frame => {
    const f = fixture(30000, true, 'v2'); const start = f.controller.start(); f.frame(frame); expect(await start).toBe('REST_READY');
  });
  it.each([{ ...alarmHint, alarmQueryKeys: ['unknown'] }, { ...alarmHint, alarmQueryKeys: ['a0', 'a0'] },
    { ...alarmHint, alarmQueryKeys: [] }, { ...alarmHint, values: {} }, { ...hint }])('rejects unauthorized/empty/legacy v2 hints %j', async frame => {
    const f = fixture(30000, true, 'v2'); const start = f.controller.start(); f.frame({ ...ack, alarmCount: 1 }); await start;
    f.frame(alarmHint); f.frame(frame); expect(f.controller.state()).toBe('REST_READY'); expect(f.controller.takeDirtyAlarms()).toEqual([]);
  });
  it('rejects v2 fields on the old protocol', async () => {
    const f = fixture(); const start = f.controller.start(); f.frame({ ...ack, alarmCount: 0 }); expect(await start).toBe('REST_READY');
  });
});
