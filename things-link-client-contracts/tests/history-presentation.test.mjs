import assert from 'node:assert/strict';
import test from 'node:test';
import { decodeHistoryResponse, ObservationResponseError, parseDashboardRuntimeResponse,
  isRuntimeNumber, historySegments, historyGeometry, historyRange, interactionStateLabel,
} from '../dist/dashboard/v1/index.js';

const id = '11111111-1111-4111-8111-111111111111';
const other = '22222222-2222-4222-8222-222222222222';
const query = { queryId: 'history', deviceId: id, expectedModelVersionId: id, propertyKey: 'temperature',
  from: '2026-09-08T00:00:00.000000001Z', to: '2026-09-08T01:00:00Z', granularity: 'RAW', aggregation: 'AVG' };
const parse = text => parseDashboardRuntimeResponse(new TextEncoder().encode(text));
const point = (changes = {}) => ({ ts: '2026-09-08T00:01:00Z', value: 1, sampleCount: 1,
  thingModelVersionId: id, modelVersion: '1.0.0', ...changes });
const response = (points = [], changes = {}) => ({ requestedGranularity: 'RAW', actualGranularity: 'RAW',
  aggregation: 'AVG', points, ...changes });
const decode = (value, selection = query, explicitWindow = false) =>
  decodeHistoryResponse(selection, parse(JSON.stringify(value)), explicitWindow);

test('严格历史解码保留精确数字品牌、同刻不同版本与LEGACY，并冻结事实', () => {
  const raw = JSON.stringify(response([point(), point({ thingModelVersionId: other, modelVersion: '2.0.0' }),
    point({ thingModelVersionId: null, modelVersion: 'LEGACY_UNVERSIONED' })]))
    .replaceAll('"value":1', '"value":9007199254740993.123456789')
    .replaceAll('"sampleCount":1', '"sampleCount":9223372036854775807');
  const result = decodeHistoryResponse(query, parse(raw));
  assert.equal(result.points.length, 3);
  assert.equal(result.points[0].sampleCount, '9223372036854775807');
  assert.equal(result.points[0].value.lexical, '9007199254740993.123456789');
  assert.ok(isRuntimeNumber(result.points[0].value));
  assert.ok(Object.isFrozen(result) && Object.isFrozen(result.points) && Object.isFrozen(result.points[0]));
  assert.deepEqual(historySegments(result.points, 'RAW').map(segment => segment.length), [1, 1, 1]);
});

test('正文与点闭集拒绝额外字段、伪造数字对象及未经严格解析的JSON数值', () => {
  for (const value of [response([], { privateScope: id }), response([point({ privateScope: id })]),
    response([point({ value: { kind: 'NUMBER', lexical: '1' } })]),
    response([point({ sampleCount: '1' })])]) assert.throws(() => decode(value), ObservationResponseError);
  assert.throws(() => decodeHistoryResponse(query, response([point()])), ObservationResponseError);
});

test('允许2000点及实际升粒度，不截断2001点或接受降粒度和错误聚合', () => {
  assert.equal(decode(response(Array.from({ length: 2000 }, () => point()))).points.length, 2000);
  assert.throws(() => decode(response(Array.from({ length: 2001 }, () => point()))), ObservationResponseError);
  assert.equal(decode(response([], { actualGranularity: 'ONE_DAY' })).actualGranularity, 'ONE_DAY');
  assert.throws(() => decode(response([], { requestedGranularity: 'ONE_HOUR', actualGranularity: 'ONE_MINUTE' }),
    { ...query, granularity: 'ONE_HOUR' }), ObservationResponseError);
  assert.throws(() => decode(response([], { aggregation: 'MAX' })), ObservationResponseError);
});

test('窗口按纳秒执行左闭右开，非法日期、来源版本和样本数整序列拒绝', () => {
  assert.equal(decode(response([point({ ts: query.from })])).points.length, 1);
  for (const changes of [{ ts: '2026-09-08T00:00:00Z' }, { ts: query.to },
    { ts: '2026-02-30T00:01:00Z' }, { thingModelVersionId: null },
    { thingModelVersionId: id.toUpperCase().replace('11111111', 'ABCDEFAB') },
    { modelVersion: 'LEGACY_UNVERSIONED' }, { sampleCount: 0 }, { sampleCount: -1 }, { sampleCount: 1.5 }]) {
    assert.throws(() => decode(response([point(changes)])), ObservationResponseError);
  }
  const overflow = JSON.stringify(response([point()])).replace('"sampleCount":1', '"sampleCount":9223372036854775808');
  assert.throws(() => decodeHistoryResponse(query, parse(overflow)), ObservationResponseError);
});

test('显式窗口仅作为纯合同选项，缺少回显、窗口漂移或普通正文多窗口字段均拒绝', () => {
  const matching = response([], { from: query.from, to: query.to });
  assert.equal(decode(matching, query, true).status, 'READY');
  assert.throws(() => decode(response(), query, true), ObservationResponseError);
  assert.throws(() => decode({ ...matching, from: '2026-09-08T00:00:00Z' }, query, true), ObservationResponseError);
  assert.throws(() => decode(matching), ObservationResponseError);
});

test('实际聚合缺桶断线，RAW不猜采样周期且不补零', () => {
  const points = decode(response([point(), point({ ts: '2026-09-08T00:02:00Z' }),
    point({ ts: '2026-09-08T00:04:00Z' })])).points;
  assert.deepEqual(historySegments(points, 'ONE_MINUTE').map(segment => segment.length), [2, 1]);
  assert.deepEqual(historySegments(points, 'RAW').map(segment => segment.length), [3]);
  assert.equal(historyGeometry(points).length, 3);
});

test('几何近似保持有限且不覆盖词法事实；范围比较区分浮点无法区分的极值', () => {
  const raw = JSON.stringify(response([point(), point({ ts: '2026-09-08T00:02:00Z' })]))
    .replace('"value":1', '"value":-1e308').replace('"value":1', '"value":1e308');
  const points = decodeHistoryResponse(query, parse(raw)).points;
  assert.ok(historyGeometry(points).every(entry => Number.isFinite(entry.x) && Number.isFinite(entry.y)));
  const exact = JSON.stringify(response([point(), point()])).replace('"value":1', '"value":9007199254740993')
    .replace('"value":1', '"value":9007199254740992');
  assert.deepEqual(historyRange(decodeHistoryResponse(query, parse(exact)).points), {
    from: point().ts, to: point().ts, minimum: '9007199254740992', maximum: '9007199254740993' });
  assert.equal(historyRange([]), null);
  assert.deepEqual(historyGeometry([]), []);
});

test('共享中文展示保持非数值、配置失败与空数据区别', () => {
  assert.match(interactionStateLabel('30058'), /非数值/);
  assert.match(interactionStateLabel('10001'), /配置或查询预算/);
  assert.notEqual(interactionStateLabel('EMPTY'), interactionStateLabel('30058'));
});
