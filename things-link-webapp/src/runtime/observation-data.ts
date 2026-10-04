import { decodeHistoryResponse, decodeAlarmResponse, ObservationResponseError,
  type HistoryQuery, type HistoryResult, type AlarmQuery, type AlarmResult } from '@things-link/client-contracts/dashboard/v1';
export { ObservationResponseError, utcTimestamp } from '@things-link/client-contracts/dashboard/v1';
export type { HistoryQuery, HistoryPoint, HistoryResult, ObservationStatus, AlarmQuery, AlarmBinding, AlarmItem, AlarmResult } from '@things-link/client-contracts/dashboard/v1';
export interface HistoryBinding { readonly componentId: string; readonly seriesId: string; readonly queryId: string | null }
export interface TextBinding { readonly componentId: string; readonly variableKey: string; readonly label: string | null }
/** 唯一路径的业务拒绝，不携带服务端正文/trace或凭据。 */
export class ObservationBusinessError extends Error {
  constructor(readonly code: 10001 | 30058) { super(`Observation rejected ${code}`); }
}
export type ObservationReader = (path: string, body?: unknown) => Promise<unknown>;
function check(value: unknown): asserts value { if (!value) throw new ObservationResponseError(); }
function freeze<T>(value: T): T {
  if (value && typeof value === 'object') { for (const child of Object.values(value)) freeze(child); Object.freeze(value); }
  return value;
}
/** 返回服务端实际粒度与来源版本，不在读取层合并同ts点或套当前模型单位。 */
export async function readHistory(query: HistoryQuery, read: ObservationReader, share?: { readonly shareId: string }): Promise<HistoryResult> {
  try {
    const params = new URLSearchParams({ expectedModelVersionId: query.expectedModelVersionId,
      ...(share ? { windowPreset: query.windowPreset ?? '', anchorAt: query.to } : { from: query.from, to: query.to }),
      granularity: query.granularity, aggregation: query.aggregation });
    if (share) check(['LAST_1_HOUR', 'LAST_24_HOURS', 'LAST_7_DAYS'].includes(query.windowPreset ?? ''));
    const prefix = share ? `/api/v1/shares/${share.shareId}` : '/api/v1/app';
    const response = await read(`${prefix}/devices/${query.deviceId}/properties/${encodeURIComponent(query.propertyKey)}/history${share ? '' : '/versioned'}?${params}`);
    return decodeHistoryResponse(query, response, Boolean(share));
  } catch (error) {
    if (!(error instanceof ObservationBusinessError)) throw error;
    return freeze({ queryId: query.queryId, status: error.code === 30058 ? 'NON_NUMERIC' : 'CONFIGURATION_ERROR', points: [] });
  }
}
/** 一次精确分页只返回当前组件事实，不自动追cursor，也不追加旧页。 */
export async function readAlarm(query: AlarmQuery, componentId: string, cursor: string | undefined, read: ObservationReader, prefix = '/api/v1/app'): Promise<AlarmResult> {
  check(cursor === undefined || /^[\x21-\x7e]{1,2048}$/.test(cursor));
  try {
    const response = await read(`${prefix}/alarms/query`, { devices: query.devices, conditionStates: query.conditionStates,
      ackStates: query.ackStates, severities: query.severities, limit: query.limit, ...(cursor === undefined ? {} : { cursor }) });
    return decodeAlarmResponse(query, componentId, response);
  } catch (error) {
    if (!(error instanceof ObservationBusinessError) || error.code !== 10001) throw error;
    return freeze({ componentId, queryId: query.queryId, status: 'CONFIGURATION_ERROR', items: [], nextCursor: null, hasMore: false });
  }
}
