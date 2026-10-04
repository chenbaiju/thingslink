import assert from 'node:assert/strict';
import test from 'node:test';
import { decodeAlarmResponse, ObservationResponseError, parseDashboardRuntimeResponse } from '../dist/dashboard/v1/index.js';

const id = '11111111-1111-4111-8111-111111111111';
const other = '22222222-2222-4222-8222-222222222222';
const query = { queryId: 'alarm-query', devices: [{ deviceId: id, expectedModelVersionId: other }],
  conditionStates: ['ACTIVE'], ackStates: ['UNACKNOWLEDGED'], severities: ['MAJOR'], limit: 20 };
const item = (changes = {}) => ({ id, deviceId: id, alarmType: 'RULE', severity: 'MAJOR', conditionState: 'ACTIVE',
  ackState: 'UNACKNOWLEDGED', firstConditionAt: '2026-09-08T00:00:00.123456789Z',
  activatedAt: '2026-09-08T00:00:01Z', clearedAt: null, acknowledgedAt: null,
  lastReceivedAt: '2026-09-08T00:01:00Z', version: 0, ...changes });
const page = (items = [item()], changes = {}) => ({ items, nextCursor: null, hasMore: false, ...changes });
const parse = text => parseDashboardRuntimeResponse(new TextEncoder().encode(text));
const decode = (value, selection = query) => decodeAlarmResponse(selection, 'component', parse(JSON.stringify(value)));

test('告警闭合投影保持精确int32版本、UTC原文与组件查询身份且深层只读', () => {
  const result = decode(page([item(), item({ id: other, version: 2147483647 })]));
  assert.equal(result.componentId, 'component'); assert.equal(result.queryId, query.queryId);
  assert.equal(result.status, 'READY'); assert.equal(result.items[0].version, 0);
  assert.equal(result.items[1].version, 2147483647);
  assert.equal(result.items[0].firstConditionAt, item().firstConditionAt);
  assert.ok(Object.isFrozen(result) && Object.isFrozen(result.items) && Object.isFrozen(result.items[0]));
  assert.throws(() => { result.items[0].version = 1; }, TypeError);
  assert.equal(decode(page([])).items.length, 0);
});

test('版本先校验严格数字品牌及整数词法，拒绝浮点伪整数、溢出及伪品牌', () => {
  for (const lexical of ['-1', '-0', '1.0', '1e0', '2147483648', '2147483647.00000001', '9007199254740993']) {
    const raw = JSON.stringify(page()).replace('"version":0', `"version":${lexical}`);
    assert.throws(() => decodeAlarmResponse(query, 'component', parse(raw)), ObservationResponseError);
  }
  for (const version of ['1', null, { lexical: '1', kind: 'NUMBER' }]) {
    assert.throws(() => decode(page([item({ version })])), ObservationResponseError);
  }
  assert.throws(() => decodeAlarmResponse(query, 'component', page()), ObservationResponseError);
});

test('正文和每项闭集拒绝额外字段与缺失nullable字段，不静默丢弃污染', () => {
  assert.throws(() => decode(page([], { tenantId: id })), ObservationResponseError);
  assert.throws(() => decode(page([item({ tenantId: id })])), ObservationResponseError);
  const missing = item(); delete missing.clearedAt;
  assert.throws(() => decode(page([missing])), ObservationResponseError);
  assert.throws(() => decode({ items: [], hasMore: false }), ObservationResponseError);
});

test('设备集合与三轴过滤同时闭合，跨设备或任一轴漂移拒绝整页', () => {
  for (const changes of [{ deviceId: other }, { severity: 'MINOR' }, { conditionState: 'CLEARED' },
    { ackState: 'ACKNOWLEDGED' }]) assert.throws(() => decode(page([item(), item({ id: other, ...changes })])), ObservationResponseError);
  for (const [field, filter] of [['severity', 'severities'], ['conditionState', 'conditionStates'], ['ackState', 'ackStates']]) {
    assert.throws(() => decode(page([item({ [field]: 'UNKNOWN' })]), { ...query, [filter]: ['UNKNOWN'] }), ObservationResponseError);
  }
});

test('重复告警ID、非规范UUID和超页结果均拒绝，不代替服务端去重或截断', () => {
  assert.throws(() => decode(page([item(), item()])), ObservationResponseError);
  for (const badId of ['not-a-uuid', 'ABCDEFAB-1111-4111-8111-111111111111']) {
    assert.throws(() => decode(page([item({ id: badId })])), ObservationResponseError);
  }
  const items = Array.from({ length: 21 }, (_, index) => item({ id: `${String(index).padStart(8, '0')}-1111-4111-8111-111111111111` }));
  assert.equal(decode(page(items.slice(0, 20))).items.length, 20);
  assert.throws(() => decode(page(items)), ObservationResponseError);
});

test('分页游标与hasMore闭合且仅允许最多2048个可打印ASCII字符', () => {
  const cursor = '!'.repeat(2048);
  assert.equal(decode(page([], { nextCursor: cursor, hasMore: true })).nextCursor, cursor);
  for (const nextCursor of [null, '', 'a b', '中文', '\n', '!'.repeat(2049)]) {
    assert.throws(() => decode(page([], { nextCursor, hasMore: true })), ObservationResponseError);
  }
  assert.throws(() => decode(page([], { nextCursor: 'cursor' })), ObservationResponseError);
  assert.throws(() => decode(page([], { hasMore: 'false' })), ObservationResponseError);
});

test('全部时间字段校验真实UTC日期，nullable只限三个生命周期字段', () => {
  for (const field of ['firstConditionAt', 'activatedAt', 'clearedAt', 'acknowledgedAt', 'lastReceivedAt']) {
    for (const value of ['2026-02-30T00:00:00Z', '2026-09-08T00:00:00+08:00', '2026-09-08T00:00:00.1234567890Z']) {
      assert.throws(() => decode(page([item({ [field]: value })])), ObservationResponseError);
    }
  }
  for (const field of ['firstConditionAt', 'lastReceivedAt']) {
    assert.throws(() => decode(page([item({ [field]: null })])), ObservationResponseError);
  }
});
