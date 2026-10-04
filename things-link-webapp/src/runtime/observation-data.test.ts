import { describe, expect, it, vi } from 'vitest';
import { parseDashboardRuntimeResponse } from '@things-link/client-contracts/dashboard/v1';
import { readHistory, readAlarm, ObservationBusinessError, type HistoryQuery, type AlarmQuery } from './observation-data';
const id = '11111111-1111-4111-8111-111111111111';
const other = '22222222-2222-4222-8222-222222222222';
const query: HistoryQuery = { queryId: 'history', deviceId: id, expectedModelVersionId: id, propertyKey: 'temperature',
  from: '2026-09-07T01:00:30.000Z', to: '2026-09-07T02:00:30.000Z', granularity: 'RAW', aggregation: 'AVG' };
const alarm: AlarmQuery = { queryId: 'alarm', devices: [{ deviceId: id, expectedModelVersionId: id }], conditionStates: ['ACTIVE'], ackStates: ['UNACKNOWLEDGED'], severities: ['MAJOR'], limit: 20 };
const parsed = (value: unknown) => parseDashboardRuntimeResponse(new TextEncoder().encode(JSON.stringify(value)));
function history(points: unknown[] = []) { return { requestedGranularity: 'RAW', actualGranularity: 'RAW', aggregation: 'AVG', points }; }
function point(overrides: object = {}) { return { ts: '2026-09-07T01:01:00Z', value: 0, sampleCount: 1, thingModelVersionId: id, modelVersion: 'v1', ...overrides }; }
function alarmItem(overrides: object = {}) { return { id, deviceId: id, alarmType: '温度告警', severity: 'MAJOR', conditionState: 'ACTIVE', ackState: 'UNACKNOWLEDGED',
  firstConditionAt: '2026-09-07T01:01:00Z', activatedAt: null, clearedAt: null, acknowledgedAt: null, lastReceivedAt: '2026-09-07T01:01:01Z', version: 0, ...overrides }; }
describe('bounded history and alarm observations', () => {
  it('preserves same-time distinct versions and legacy points without coercing int64 sample counts', async () => {
    const response = history([point(), point({ thingModelVersionId: other, modelVersion: 'v2' }), point({ thingModelVersionId: null, modelVersion: 'LEGACY_UNVERSIONED' })]);
    const raw = JSON.stringify(response).replaceAll('"sampleCount":1', '"sampleCount":9007199254740993');
    const result = await readHistory(query, async () => parseDashboardRuntimeResponse(new TextEncoder().encode(raw)));
    expect(result.points).toHaveLength(3); expect(result.points[0]!.sampleCount).toBe('9007199254740993');
    expect(result.points[0]!.value).toMatchObject({ kind: 'NUMBER', lexical: '0' });
    expect(result.points[2]!.thingModelVersionId).toBeNull();
  });
  it('accepts legal coarsening but rejects a earlier bucket outside actual API filtering', async () => {
    const result = await readHistory(query, async () => parsed({ ...history([point()]), actualGranularity: 'ONE_MINUTE' }));
    expect(result.actualGranularity).toBe('ONE_MINUTE');
    await expect(readHistory(query, async () => parsed({ ...history([point({ ts: '2026-09-07T01:00:00Z' })]), actualGranularity: 'ONE_MINUTE' }))).rejects.toThrow();
    await expect(readHistory({ ...query, granularity: 'ONE_HOUR' }, async () => parsed({ ...history(), requestedGranularity: 'ONE_HOUR', actualGranularity: 'ONE_MINUTE' }))).rejects.toThrow();
  });
  it('checks 2000/2001 points, UTC range, model identity and count lexical type', async () => {
    expect((await readHistory(query, async () => parsed(history(Array.from({ length: 2000 }, () => point()))))).points).toHaveLength(2000);
    await expect(readHistory(query, async () => parsed(history(Array.from({ length: 2001 }, () => point()))))).rejects.toThrow();
    for (const changes of [{ ts: query.to }, { thingModelVersionId: null }, { sampleCount: 1.5 }, { ts: '2026-02-30T01:01:00Z' }]) {
      await expect(readHistory(query, async () => parsed(history([point(changes)])))).rejects.toThrow();
    }
  });
  it('keeps only 10001 and 30058 as local history states', async () => {
    expect((await readHistory(query, async () => { throw new ObservationBusinessError(10001); })).status).toBe('CONFIGURATION_ERROR');
    expect((await readHistory(query, async () => { throw new ObservationBusinessError(30058); })).status).toBe('NON_NUMERIC');
    await expect(readHistory(query, async () => { throw new Error('network'); })).rejects.toThrow();
  });
  it('reads only one selected alarm page and returns its component identity', async () => {
    const read = vi.fn(async () => parsed({ items: [alarmItem()], nextCursor: 'next', hasMore: true }));
    const result = await readAlarm(alarm, 'alarms_a', 'prior', read);
    expect(result.componentId).toBe('alarms_a'); expect(result.nextCursor).toBe('next');
    expect(read).toHaveBeenCalledTimes(1); expect(read).toHaveBeenCalledWith('/api/v1/app/alarms/query', expect.objectContaining({ cursor: 'prior', limit: 20 }));
  });
  it('rejects alarm facts outside the precise device/filters and inconsistent cursor or int32', async () => {
    for (const changes of [{ deviceId: other }, { severity: 'INFO' }, { version: 2147483648 }, { version: -1 }, { firstConditionAt: null }]) {
      await expect(readAlarm(alarm, 'alarms', undefined, async () => parsed({ items: [alarmItem(changes)], nextCursor: null, hasMore: false }))).rejects.toThrow();
    }
    await expect(readAlarm(alarm, 'alarms', undefined, async () => parsed({ items: [], nextCursor: null, hasMore: true }))).rejects.toThrow();
    await expect(readAlarm(alarm, 'alarms', '', async () => parsed({}))).rejects.toThrow();
  });
  it('maps configuration failure only to the affected alarm component', async () => {
    const result = await readAlarm(alarm, 'one', undefined, async () => { throw new ObservationBusinessError(10001); });
    expect(result).toMatchObject({ componentId: 'one', status: 'CONFIGURATION_ERROR', items: [], nextCursor: null, hasMore: false });
    await expect(readAlarm(alarm, 'one', undefined, async () => { throw new ObservationBusinessError(30058); })).rejects.toThrow();
  });
});
