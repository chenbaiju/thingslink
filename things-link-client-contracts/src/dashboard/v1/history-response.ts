import { isStrictJsonNumber, type StrictJsonValue } from "./strict-json.js";
import { createRuntimeNumber, type RuntimeNumber } from "./composite-value.js";
import type { DashboardHistoryAggregation, DashboardHistoryGranularity } from "./types.js";

/** 宿主无关的历史请求与观察事实；不包含HTTP路径、身份凭据或页面状态。 */
export interface HistoryQuery {
  readonly queryId: string; readonly deviceId: string; readonly expectedModelVersionId: string; readonly propertyKey: string;
  readonly windowPreset?: string;
  readonly from: string; readonly to: string; readonly granularity: DashboardHistoryGranularity; readonly aggregation: DashboardHistoryAggregation;
}
/** 原始数值保留严格数字品牌；样本数保持Long十进制文本，不经浮点转换。 */
export interface HistoryPoint {
  readonly ts: string; readonly value: RuntimeNumber; readonly sampleCount: string;
  readonly thingModelVersionId: string | null; readonly modelVersion: string;
}
/** 观察状态本身不触发请求、身份刷新或重试。 */
export type ObservationStatus = 'READY' | 'NOT_SELECTED' | 'CONFIGURATION_ERROR' | 'NON_NUMERIC' | 'NOT_AVAILABLE' | 'MODEL_MISMATCH';
/** 网络观察允许失败结果无粒度；不可与必有粒度的HistoryPresentationResult混用。 */
export interface HistoryResult {
  readonly queryId: string; readonly status: ObservationStatus;
  readonly requestedGranularity?: DashboardHistoryGranularity; readonly actualGranularity?: DashboardHistoryGranularity;
  readonly aggregation?: DashboardHistoryAggregation; readonly points: readonly HistoryPoint[];
}

/** 两个宿主及原告警读取共同使用的纯响应拒绝类型，不保存服务器正文。 */
export class ObservationResponseError extends Error { constructor() { super('Invalid observation response'); } }
function check(value: unknown): asserts value { if (!value) throw new ObservationResponseError(); }
function object(value: unknown, keys: string[]): asserts value is Record<string, unknown> {
  check(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(',') === [...keys].sort().join(','));
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const GRANULARITIES: readonly DashboardHistoryGranularity[] = ['RAW', 'ONE_MINUTE', 'ONE_HOUR', 'ONE_DAY'];
/** 接受纳秒精度UTC时间并拒绝Date自动修正的非法日历日期。 */
export function utcTimestamp(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?Z$/.test(value)) return false;
  const date = new Date(value); return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value.slice(0, 10);
}
/** 窗口比较保留纳秒，展示几何的毫秒近似不得反向改变数据资格。 */
function instant(value: string): bigint {
  const fraction = /\.(\d+)Z$/.exec(value)?.[1] ?? '';
  return BigInt(Date.parse(value.slice(0, 19) + 'Z')) * 1000000n + BigInt(fraction.padEnd(9, '0'));
}
function integral(value: unknown, maximum: bigint): string {
  check(isStrictJsonNumber(value as StrictJsonValue));
  const lexical = (value as { lexical: string }).lexical;
  check(/^(0|[1-9][0-9]*)$/.test(lexical) && lexical.length <= 19 && BigInt(lexical) <= maximum);
  return lexical;
}
function freeze<T>(value: T): T {
  if (value && typeof value === 'object') { for (const child of Object.values(value)) freeze(child); Object.freeze(value); }
  return value;
}
/** 校验严格JSON历史正文；explicitWindow表示正文必须回显并精确匹配from/to。 */
export function decodeHistoryResponse(query: HistoryQuery, response: unknown, explicitWindow = false): HistoryResult {
  check(utcTimestamp(query.from) && utcTimestamp(query.to) && instant(query.from) < instant(query.to));
  object(response, [...(explicitWindow ? ['from', 'to'] : []), 'requestedGranularity', 'actualGranularity', 'aggregation', 'points']);
  if (explicitWindow) check(utcTimestamp(response.from) && utcTimestamp(response.to)
    && instant(response.from) === instant(query.from) && instant(response.to) === instant(query.to));
  check(response.requestedGranularity === query.granularity && response.aggregation === query.aggregation
    && GRANULARITIES.includes(response.actualGranularity as DashboardHistoryGranularity)
    && GRANULARITIES.indexOf(response.actualGranularity as DashboardHistoryGranularity) >= GRANULARITIES.indexOf(query.granularity)
    && Array.isArray(response.points) && response.points.length <= 2000);
  // 当前PG聚合按bucket>=from且bucket<to过滤，未对齐from也不额外接纳前一个桶。
  const points = response.points.map(value => {
    object(value, ['ts', 'value', 'sampleCount', 'thingModelVersionId', 'modelVersion']);
    check(utcTimestamp(value.ts) && instant(value.ts) >= instant(query.from) && instant(value.ts) < instant(query.to)
      && typeof value.modelVersion === 'string' && value.modelVersion.length > 0
      && (value.modelVersion === 'LEGACY_UNVERSIONED' ? value.thingModelVersionId === null : typeof value.thingModelVersionId === 'string' && UUID.test(value.thingModelVersionId)));
    const sampleCount = integral(value.sampleCount, 9223372036854775807n); check(sampleCount !== '0');
    check(isStrictJsonNumber(value.value as StrictJsonValue));
    return { ts: value.ts, value: createRuntimeNumber(value.value), sampleCount,
      thingModelVersionId: value.thingModelVersionId as string | null, modelVersion: value.modelVersion };
  });
  return freeze({ queryId: query.queryId, status: 'READY', requestedGranularity: query.granularity,
    actualGranularity: response.actualGranularity as DashboardHistoryGranularity, aggregation: query.aggregation, points });
}
