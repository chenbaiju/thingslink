import { isStrictJsonNumber, type StrictJsonValue } from "./strict-json.js";
import { ObservationResponseError, utcTimestamp, type ObservationStatus } from "./history-response.js";
import type { DashboardAlarmAckState, DashboardAlarmConditionState, DashboardAlarmSeverity } from "./types.js";

/** 宿主无关的告警查询；实际身份和请求路径由宿主负责，不进入共享合同。 */
export interface AlarmQuery {
  readonly queryId: string;
  readonly devices: readonly { readonly deviceId: string; readonly expectedModelVersionId: string }[];
  readonly conditionStates: readonly string[]; readonly ackStates: readonly string[];
  readonly severities: readonly string[]; readonly limit: number;
}
/** 组件绑定只引用查询身份，不持有可跨轮复用的服务端事实。 */
export interface AlarmBinding { readonly componentId: string; readonly queryId: string | null }
/** 与运行告警合同一致的闭合投影；版本是经过精确整数资格检查后的int32。 */
export interface AlarmItem {
  readonly id: string; readonly deviceId: string; readonly alarmType: string;
  readonly severity: DashboardAlarmSeverity; readonly conditionState: DashboardAlarmConditionState;
  readonly ackState: DashboardAlarmAckState; readonly firstConditionAt: string;
  readonly activatedAt: string | null; readonly clearedAt: string | null;
  readonly acknowledgedAt: string | null; readonly lastReceivedAt: string; readonly version: number;
}
/** 单次分页结果；不自动追页，也不拼接其他组件或其他轮次。 */
export interface AlarmResult {
  readonly componentId: string; readonly queryId: string | null; readonly status: ObservationStatus;
  readonly items: readonly AlarmItem[]; readonly nextCursor: string | null; readonly hasMore: boolean;
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const CONDITIONS: readonly string[] = ['PENDING', 'ACTIVE', 'CLEARED'];
const ACKS: readonly string[] = ['UNACKNOWLEDGED', 'ACKNOWLEDGED'];
const SEVERITIES: readonly string[] = ['CRITICAL', 'MAJOR', 'MINOR', 'WARNING', 'INFO'];
function check(value: unknown): asserts value { if (!value) throw new ObservationResponseError(); }
function object(value: unknown, keys: string[]): asserts value is Record<string, unknown> {
  check(value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).sort().join(',') === [...keys].sort().join(','));
}
/** 严格数字品牌和原始词法先证明int32资格，再转Number，不接受浮点取整后的伪整数。 */
function version(value: unknown): number {
  check(isStrictJsonNumber(value as StrictJsonValue));
  const lexical = (value as { lexical: string }).lexical;
  check(/^(0|[1-9][0-9]*)$/.test(lexical) && lexical.length <= 10 && BigInt(lexical) <= 2147483647n);
  return Number(lexical);
}
/** 三轴过滤与设备集合必须同时满足；任何污染拒绝整页，不在宿主内补裁服务端结果。 */
export function decodeAlarmResponse(query: AlarmQuery, componentId: string, response: unknown): AlarmResult {
  object(response, ['items', 'nextCursor', 'hasMore']);
  check(Array.isArray(response.items) && response.items.length <= query.limit && typeof response.hasMore === 'boolean'
    && (response.hasMore ? typeof response.nextCursor === 'string' && /^[\x21-\x7e]{1,2048}$/.test(response.nextCursor) : response.nextCursor === null));
  const ids = new Set<string>();
  const items = response.items.map(value => {
    object(value, ['id', 'deviceId', 'alarmType', 'severity', 'conditionState', 'ackState', 'firstConditionAt',
      'activatedAt', 'clearedAt', 'acknowledgedAt', 'lastReceivedAt', 'version']);
    check(typeof value.id === 'string' && UUID.test(value.id) && !ids.has(value.id)); ids.add(value.id);
    check(typeof value.deviceId === 'string' && UUID.test(value.deviceId)
      && query.devices.some(device => device.deviceId === value.deviceId) && typeof value.alarmType === 'string'
      && typeof value.severity === 'string' && SEVERITIES.includes(value.severity) && query.severities.includes(value.severity)
      && typeof value.conditionState === 'string' && CONDITIONS.includes(value.conditionState) && query.conditionStates.includes(value.conditionState)
      && typeof value.ackState === 'string' && ACKS.includes(value.ackState) && query.ackStates.includes(value.ackState)
      && utcTimestamp(value.firstConditionAt) && utcTimestamp(value.lastReceivedAt)
      && ['activatedAt', 'clearedAt', 'acknowledgedAt'].every(field => value[field] === null || utcTimestamp(value[field])));
    return Object.freeze({ ...value, version: version(value.version) }) as unknown as AlarmItem;
  });
  return Object.freeze({ componentId, queryId: query.queryId, status: 'READY', items: Object.freeze(items),
    nextCursor: response.nextCursor as string | null, hasMore: response.hasMore });
}
