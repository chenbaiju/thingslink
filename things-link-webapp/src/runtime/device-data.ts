import type { ShareContext, SharePublishedDashboard, ShareReadSession } from './share-published';
import { parseDashboardRuntimeResponse, isStrictJsonNumber, type StrictJsonValue, type DashboardSchemaV1, type DashboardSingleDeviceVariable, type DashboardMultiDeviceVariable, type DashboardVariable, type DashboardHistorySeriesBinding } from '@things-link/client-contracts/dashboard/v1';
import { readHistory, readAlarm, ObservationBusinessError, ObservationResponseError, utcTimestamp, type HistoryQuery, type HistoryBinding, type HistoryResult, type AlarmQuery, type AlarmBinding, type AlarmResult, type TextBinding } from './observation-data';
export type { HistoryResult, AlarmResult } from './observation-data';
import { createRuntimeNumber, parseCompositeValue, type RuntimeNumber, type RuntimeValue } from './composite-value';
export type { RuntimeNumber, RuntimeValue } from './composite-value';
import type { components } from '../types/api/schema';
import type { SessionCoordinator } from '../auth/session';
import type { CurrentApplication, PublishedDashboard } from './published';
export type DeviceSnapshot = components['schemas']['WebAppDeviceSnapshotItem'];
export type RuntimeProperty = Omit<components['schemas']['WebAppRuntimePropertyResponse'], 'minimumValue' | 'maximumValue'> & {
  readonly minimumValue: RuntimeNumber | null; readonly maximumValue: RuntimeNumber | null;
};
export type RuntimeModel = Omit<components['schemas']['WebAppRuntimeModelResponse'], 'properties'> & { readonly properties: readonly RuntimeProperty[] };
export type CurrentProperty = components['schemas']['WebAppEmptyCurrentProperty'] | {
  readonly propertyKey: string; readonly state: 'VALUE'; readonly value: RuntimeValue;
  readonly occurredAt: string; readonly reportedModelVersionId: string;
};
export interface CurrentDevice { readonly deviceId: string; readonly status: 'AVAILABLE' | 'NOT_AVAILABLE' | 'MODEL_MISMATCH'; readonly values: readonly CurrentProperty[] }
export type DeviceSelection = string | null | readonly string[];
export type DeviceVariable = DashboardSingleDeviceVariable | DashboardMultiDeviceVariable;
export type DeviceCatalogPage = components['schemas']['WebAppDeviceCatalogResponse'];
export interface DeviceBinding {
  readonly componentId: string;
  readonly kind: 'VALUE_CARD' | 'STATUS' | 'GAUGE' | 'DEVICE_SELECTOR' | 'TABLE' | 'JSON_VIEW' | 'LINE_CHART' | 'ALARM_LIST';
  readonly variableKey: string;
  readonly deviceId: string | null;
  readonly propertyKey?: string;
  readonly columnId?: string;
}
export interface DeviceQuery { readonly deviceId: string; readonly expectedModelVersionId: string; readonly propertyKeys: readonly string[] }
export interface ModelQuery { readonly versionId: string; readonly digestAlgorithm: string; readonly digest: string; readonly profile: string }
export interface DevicePagePlan {
  readonly to: string;
  readonly histories: readonly HistoryQuery[];
  readonly historyBindings: readonly HistoryBinding[];
  readonly alarmQueries: readonly AlarmQuery[];
  readonly alarmBindings: readonly AlarmBinding[];
  readonly textBindings: readonly TextBinding[];
  readonly pageId: string;
  readonly selections: Readonly<Record<string, DeviceSelection>>;
  readonly variables: readonly DashboardVariable[];
  readonly bindings: readonly DeviceBinding[];
  readonly missingRequired: readonly string[];
  readonly models: readonly ModelQuery[];
  readonly devices: readonly DeviceQuery[];
}
export interface DevicePageFacts {
  readonly history: readonly HistoryResult[];
  readonly alarms: readonly AlarmResult[];
  readonly pageId: string;
  readonly selections: Readonly<Record<string, DeviceSelection>>;
  readonly devices: readonly DeviceSnapshot[];
  readonly models: readonly RuntimeModel[];
  readonly current: readonly CurrentDevice[];
}
export interface AppDeviceReadContext {
  readonly kind?: 'app';
  readonly current: CurrentApplication;
  readonly published: PublishedDashboard;
  readonly session: Pick<SessionCoordinator, 'fetch' | 'checkCurrent'>;
  readonly signal?: AbortSignal;
}
export type DeviceReadContext = AppDeviceReadContext | {
  readonly kind: 'share'; readonly context: ShareContext; readonly published: SharePublishedDashboard;
  readonly session: ShareReadSession; readonly signal?: AbortSignal;
};
export class DeviceDataError extends Error {
  constructor(readonly reason: 'plan' | 'response' | 'identity' | 'unavailable' | 'budget') { super(`Device data ${reason}`); }
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const STATES = ['NO_VALUE', 'SOURCE_VERSION_UNKNOWN', 'SOURCE_MODEL_MISMATCH', 'CONTRACT_MISMATCH'];
function requireThat(value: unknown, reason: DeviceDataError['reason'] = 'response'): asserts value {
  if (!value) throw new DeviceDataError(reason);
}
function object(value: unknown, keys: string[]): asserts value is Record<string, unknown> {
  requireThat(value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).sort().join(',') === [...keys].sort().join(','));
}
function freeze<T>(value: T): T {
  if (value && typeof value === 'object') {
    for (const child of Object.values(value)) freeze(child);
    Object.freeze(value);
  }
  return value;
}
/** 本候选支持完整十组件与四变量；具体形状仍由共享Schema语义和领域元信息共同证明。 */
function requireSupportedSchema(schema: DashboardSchemaV1): void {
  requireThat(schema.variables.every(variable => ['DEVICE_SINGLE', 'DEVICE_MULTI', 'TIME_RANGE', 'TEXT_ENUM'].includes(variable.type)), 'plan');
  for (const page of schema.pages) for (const component of page.components) {
    requireThat(['TEXT', 'IMAGE', 'VALUE_CARD', 'STATUS', 'GAUGE', 'DEVICE_SELECTOR', 'TABLE', 'JSON_VIEW', 'LINE_CHART', 'ALARM_LIST'].includes(component.kind), 'plan');
    if (component.kind === 'IMAGE') requireThat(Object.keys(component.bindings).length === 0, 'plan');
    if (component.kind === 'TABLE') requireThat(component.props.mode === 'DEVICE_VALUES' ? 'columns' in component.bindings : component.props.mode === 'LIST_VALUE' && 'value' in component.bindings, 'plan');
  }
}
function selectionFor(variable: DashboardVariable, selections: Readonly<Record<string, DeviceSelection>>): DeviceSelection {
  const value = Object.hasOwn(selections, variable.key) ? selections[variable.key]
    : variable.type === 'DEVICE_SINGLE' ? variable.defaultDeviceId ?? null
      : variable.type === 'DEVICE_MULTI' ? variable.defaultDeviceIds
        : variable.type === 'TIME_RANGE' ? variable.defaultPreset : variable.defaultValue ?? null;
  if (variable.type === 'TIME_RANGE' || variable.type === 'TEXT_ENUM') {
    requireThat(value === null || typeof value === 'string' && (variable.type === 'TIME_RANGE'
      ? variable.allowedPresets.some(preset => preset === value) : variable.options.some(option => option.value === value)), 'plan');
    return value;
  }
  if (variable.type === 'DEVICE_SINGLE') {
    requireThat(value === null || typeof value === 'string' && UUID.test(value), 'plan');
    return value;
  }
  requireThat(Array.isArray(value) && value.length <= variable.maxItems && new Set(value).size === value.length
    && value.every(id => typeof id === 'string' && UUID.test(id)), 'plan');
  return [...value];
}
/** 保留变量身份后展开列与设备，再按设备去重；同设备不能被不同模型解释。 */
export function planDevicePage(schema: DashboardSchemaV1, pageId: string, selections: Readonly<Record<string, DeviceSelection>>, roundTo = new Date().toISOString()): DevicePagePlan {
  requireSupportedSchema(schema);
  requireThat(utcTimestamp(roundTo), 'plan');
  const page = schema.pages.find(candidate => candidate.id === pageId); requireThat(page, 'plan');
  const selected: Record<string, DeviceSelection> = Object.create(null);
  for (const key of Object.keys(selections)) requireThat(schema.variables.some(variable => variable.key === key), 'plan');
  const variables = new Map<string, DashboardVariable>();
  const histories = new Map<string, HistoryQuery>();
  const historyBindings: HistoryBinding[] = [];
  const alarmQueries = new Map<string, AlarmQuery>();
  const alarmBindings: AlarmBinding[] = [];
  const textBindings: TextBinding[] = [];
  const choose = (key: string): DashboardVariable => {
    const variable = schema.variables.find(entry => entry.key === key); requireThat(variable, 'plan');
    variables.set(key, variable); selected[key] = selectionFor(variable, selections); return variable;
  };
  const bindings: DeviceBinding[] = [];
  const devices = new Map<string, { deviceId: string; expectedModelVersionId: string; propertyKeys: string[] }>();
  const models = new Map<string, ModelQuery>();
  for (const component of page.components) {
    if (component.kind === 'TEXT') {
      if ('text' in component.bindings) {
        const variable = choose(component.bindings.text.variableKey); requireThat(variable.type === 'TEXT_ENUM', 'plan');
        const option = variable.options.find(option => option.value === selected[variable.key]);
        textBindings.push({ componentId: component.id, variableKey: variable.key, label: option?.label ?? null });
      }
      continue;
    }
    if (component.kind === 'IMAGE') continue;
    requireThat(component.kind === 'VALUE_CARD' || component.kind === 'GAUGE' || component.kind === 'STATUS'
      || component.kind === 'DEVICE_SELECTOR' || component.kind === 'TABLE' || component.kind === 'JSON_VIEW' || component.kind === 'LINE_CHART' || component.kind === 'ALARM_LIST', 'plan');
    const slots: { variableKey: string; propertyKey?: string; columnId?: string; seriesId?: string; history?: DashboardHistorySeriesBinding }[] = [];
    if (component.kind === 'LINE_CHART') {
      for (const series of component.bindings.series) slots.push({ variableKey: series.value.device.variableKey,
        propertyKey: series.value.propertyKey, seriesId: series.id, history: series.value });
    } else if (component.kind === 'ALARM_LIST') slots.push({ variableKey: component.bindings.alarms.devices.variableKey });
    else if (component.kind === 'TABLE' && component.props.mode === 'DEVICE_VALUES') {
      requireThat('columns' in component.bindings, 'plan');
      requireThat(new Set(component.bindings.columns.map(column => column.value.device.variableKey)).size === 1, 'plan');
      for (const column of component.bindings.columns) slots.push({ variableKey: column.value.device.variableKey, propertyKey: column.value.propertyKey, columnId: column.id });
    } else if (component.kind === 'DEVICE_SELECTOR') slots.push({ variableKey: component.bindings.directory.variableKey });
    else if (component.kind === 'STATUS') slots.push({ variableKey: component.bindings.status.device.variableKey });
    else { requireThat('value' in component.bindings, 'plan'); slots.push({ variableKey: component.bindings.value.device.variableKey, propertyKey: component.bindings.value.propertyKey }); }
    for (const slot of slots) {
      const variable = schema.variables.find(entry => entry.key === slot.variableKey);
      requireThat(variable?.type === 'DEVICE_SINGLE' || variable?.type === 'DEVICE_MULTI', 'plan');
      requireThat(component.kind === 'DEVICE_SELECTOR' || component.kind === 'ALARM_LIST' || (component.kind === 'TABLE' && component.props.mode === 'DEVICE_VALUES' ? variable.type === 'DEVICE_MULTI' : variable.type === 'DEVICE_SINGLE'), 'plan');
      const model = schema.models.find(entry => entry.key === variable.modelKey); requireThat(model, 'plan');
      variables.set(variable.key, variable);
      const selection = selectionFor(variable, selections); selected[variable.key] = selection;
      let ids: readonly string[] = selection === null ? [] : typeof selection === 'string' ? [selection] : selection;
      if (slot.history) {
        const time = choose(slot.history.timeRangeVariableKey); requireThat(time.type === 'TIME_RANGE', 'plan');
        const preset = selected[time.key];
        let queryId: string | null = null;
        if (ids.length && typeof preset === 'string') {
          const duration = preset === 'LAST_1_HOUR' ? 3600000 : preset === 'LAST_24_HOURS' ? 86400000 : 604800000;
          const shifted = new Date(Date.parse(roundTo) - duration).toISOString();
          const from = roundTo.includes('.') ? shifted.slice(0, 19) + roundTo.slice(19) : shifted;
          queryId = JSON.stringify([ids[0], model.versionId, slot.propertyKey, from, roundTo, slot.history.granularity, slot.history.aggregation]);
          histories.set(queryId, { queryId, deviceId: ids[0]!, expectedModelVersionId: model.versionId, propertyKey: slot.propertyKey!,
            from, to: roundTo, windowPreset: preset, granularity: slot.history.granularity, aggregation: slot.history.aggregation });
        } else ids = [];
        historyBindings.push({ componentId: component.id, seriesId: slot.seriesId!, queryId });
      }
      if (component.kind === 'ALARM_LIST') {
        let queryId: string | null = null;
        if (ids.length) {
          const alarms = component.bindings.alarms;
          const devices = [...ids].sort().map(deviceId => ({ deviceId, expectedModelVersionId: model.versionId }));
          const conditionStates = [...alarms.conditionStates].sort(); const ackStates = [...alarms.ackStates].sort(); const severities = [...alarms.severities].sort();
          queryId = JSON.stringify([devices, conditionStates, ackStates, severities, component.props.pageSize]);
          alarmQueries.set(queryId, { queryId, devices, conditionStates, ackStates, severities, limit: component.props.pageSize });
        }
        alarmBindings.push({ componentId: component.id, queryId });
      }
      // 空选择仍保留组件/列依赖，供未选择提示和保守预算使用，不伪造设备ID。
      for (const deviceId of ids.length ? ids : [null]) {
        bindings.push({ componentId: component.id, kind: component.kind, deviceId, ...slot });
        if (deviceId === null) continue;
        const previous = devices.get(deviceId);
        requireThat(!previous || previous.expectedModelVersionId === model.versionId, 'plan');
        const device = previous ?? { deviceId, expectedModelVersionId: model.versionId, propertyKeys: [] };
        if (slot.propertyKey && !device.propertyKeys.includes(slot.propertyKey)) device.propertyKeys.push(slot.propertyKey);
        requireThat(device.propertyKeys.length <= 50, 'budget'); devices.set(deviceId, device);
        const reference = { versionId: model.versionId, digestAlgorithm: model.digestAlgorithm, digest: model.digest, profile: model.profile };
        const oldModel = models.get(model.versionId);
        requireThat(!oldModel || (oldModel.digest === reference.digest && oldModel.profile === reference.profile && oldModel.digestAlgorithm === reference.digestAlgorithm), 'plan');
        models.set(model.versionId, reference);
      }
    }
  }
  requireThat(histories.size <= 10 && devices.size <= 20 && [...devices.values()].reduce((sum, device) => sum + device.propertyKeys.length, 0) <= 200, 'budget');
  return freeze({ pageId, to: roundTo, histories: [...histories.values()], historyBindings, alarmQueries: [...alarmQueries.values()], alarmBindings, textBindings, selections: selected, variables: [...variables.values()], bindings,
    missingRequired: [...variables.values()].filter(variable => variable.required
      && (selected[variable.key] === null || Array.isArray(selected[variable.key]) && selected[variable.key]!.length === 0)).map(variable => variable.key),
    models: [...models.values()], devices: [...devices.values()] });
}
function timestamp(value: unknown): boolean {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?Z$/.test(value)) return false;
  const date = new Date(value);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value.slice(0, 10);
}
function deviceStatus(value: unknown): boolean { return value === 'INACTIVE' || value === 'ONLINE' || value === 'OFFLINE'; }
function nullableText(value: unknown): boolean { return value === null || typeof value === 'string'; }
function exactNumber(value: unknown): RuntimeNumber {
  requireThat(isStrictJsonNumber(value as StrictJsonValue));
  return createRuntimeNumber(value);
}
function nullableNumber(value: unknown): boolean { return value === null || isStrictJsonNumber(value as StrictJsonValue); }
/** 四头取自同一已验收应用/看板上下文，不接受UI任意补入另一个版本。 */
function readScope(context: DeviceReadContext): { headers: Record<string, string>; fence: () => void } {
  if (context.kind === 'share') {
    const { session, published, context: identity } = context;
    const fence = (): void => { context.signal?.throwIfAborted();
      requireThat(session.checkCurrent().shareId === identity.shareId && session.shareId === identity.shareId, 'identity'); };
    fence();
    requireThat(identity.dashboardId === published.dashboardId && identity.dashboardVersionId === published.dashboardVersionId
      && identity.dashboardVersionNumber === published.dashboardVersionNumber, 'identity');
    return { headers: {}, fence };
  }
  const { current, published, session } = context;
  const before = session.checkCurrent();
  requireThat(before.status === 'authenticated' && current.identity.kind === 'APP' && before.appUserId === current.identity.appUserId
    && before.projectId === current.identity.projectId, 'identity');
  requireThat(current.applicationVersionId === published.applicationVersionId && current.publicationRevision === published.publicationRevision
    && current.dashboards.some(board => board.dashboardId === published.dashboardId && board.dashboardVersionId === published.dashboardVersionId
      && board.schemaDigest === published.schemaDigest && board.schemaDigestAlgorithm === published.schemaDigestAlgorithm), 'identity');
  return { headers: { 'X-Application-Key': current.application.appKey, 'X-Application-Version': published.applicationVersionId,
    'X-Application-Revision': published.publicationRevision, 'X-Dashboard-Version': published.dashboardVersionId },
    fence: () => { context.signal?.throwIfAborted(); const after = session.checkCurrent();
      requireThat(after.status === 'authenticated' && after.appUserId === before.appUserId && after.projectId === before.projectId, 'identity'); } };
}
async function readJson(context: DeviceReadContext, scope: ReturnType<typeof readScope>, path: string, body?: unknown, localErrors = false): Promise<unknown> {
  scope.fence();
  const response = await context.session.fetch(path, { method: body === undefined ? 'GET' : 'POST', signal: context.signal,
    headers: { ...scope.headers, Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  const buffer = new Uint8Array(4 * 1024 * 1024); let size = 0;
  const reader = response.body?.getReader(); requireThat(reader);
  try {
    while (true) {
      scope.fence(); const chunk = await reader.read(); if (chunk.done) break;
      if (chunk.value.length > buffer.length - size) { await reader.cancel(); throw new DeviceDataError('budget'); }
      buffer.set(chunk.value, size); size += chunk.value.length;
    }
  } finally { reader.releaseLock(); }
  scope.fence();
  let parsed: Record<string, unknown>;
  try { parsed = parseDashboardRuntimeResponse(buffer.subarray(0, size)); }
  catch { throw new DeviceDataError('response'); }
  if (response.status !== 200) {
    const code = isStrictJsonNumber(parsed.code as StrictJsonValue) ? (parsed.code as { lexical: string }).lexical : '';
    if (code === '60009' || code === '60010' || (context.kind === 'share' && code === '60053')) throw new DeviceDataError('identity');
    if (localErrors && response.status === 400 && (code === '10001' || code === '30058')) throw new ObservationBusinessError(Number(code) as 10001 | 30058);
    throw new DeviceDataError('unavailable');
  }
  return parsed;
}
function property(value: unknown): RuntimeProperty {
  object(value, ['propertyKey', 'dataType', 'unit', 'minimumValue', 'maximumValue', 'enumOptions', 'onLabel', 'offLabel']);
  requireThat(typeof value.propertyKey === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value.propertyKey)
    && ['NUMBER', 'TEXT', 'SWITCH', 'ENUM', 'OBJECT', 'LIST'].includes(value.dataType as string)
    && nullableText(value.unit) && nullableText(value.onLabel) && nullableText(value.offLabel)
    && nullableNumber(value.minimumValue) && nullableNumber(value.maximumValue));
  if (value.dataType === 'ENUM') requireThat(Array.isArray(value.enumOptions) && value.enumOptions.length > 0
    && value.enumOptions.every(option => typeof option === 'string') && new Set(value.enumOptions).size === value.enumOptions.length);
  else requireThat(value.enumOptions === null);
  return { ...value, minimumValue: value.minimumValue === null ? null : exactNumber(value.minimumValue),
    maximumValue: value.maximumValue === null ? null : exactNumber(value.maximumValue) } as RuntimeProperty;
}

/** 所有页面先按SINGLE=1、MULTI=maxItems保守展开，不能以默认设备碰巧相同或首页静态掩盖后页超限。 */
export function assertDeviceDashboardSupport(schema: DashboardSchemaV1, identityKind: 'app' | 'share' = 'app'): void {
  requireSupportedSchema(schema);
  for (const page of schema.pages) {
    const plan = planDevicePage(schema, page.id, {});
    const variables = new Set(plan.bindings.map(binding => binding.variableKey));
    // 冷目录只有精确模型与pageSize相同才能合并；变量同名不代表请求身份相同。
    const directories = new Set(page.components.filter(component => component.kind === 'DEVICE_SELECTOR').map(component => {
      const variable = schema.variables.find(entry => entry.key === component.bindings.directory.variableKey)!;
      requireThat(variable.type === 'DEVICE_SINGLE' || variable.type === 'DEVICE_MULTI', 'plan');
      const model = schema.models.find(entry => entry.key === variable.modelKey)!;
      return `${identityKind === 'share' ? variable.key : model.versionId}/${component.props.pageSize}`;
    }));
    const keys = new Map<string, Set<string>>();
    for (const binding of plan.bindings) if (binding.propertyKey) {
      const selected = keys.get(binding.variableKey) ?? new Set<string>(); selected.add(binding.propertyKey); keys.set(binding.variableKey, selected);
    }
    const maximum = (key: string): number => {
      const variable = plan.variables.find(entry => entry.key === key)!;
      return variable.type === 'DEVICE_MULTI' ? variable.maxItems : 1;
    };
    const historyShapes = new Set(page.components.flatMap(component => component.kind === 'LINE_CHART'
      ? component.bindings.series.map(series => JSON.stringify([series.value.device.variableKey, series.value.propertyKey,
        series.value.timeRangeVariableKey, series.value.granularity, series.value.aggregation])) : []));
    const alarmShapes = new Set(page.components.flatMap(component => component.kind === 'ALARM_LIST'
      ? [JSON.stringify([component.bindings.alarms.devices.variableKey, [...component.bindings.alarms.conditionStates].sort(),
        [...component.bindings.alarms.ackStates].sort(), [...component.bindings.alarms.severities].sort(), component.props.pageSize])] : []));
    const hasCurrent = plan.bindings.some(binding => binding.propertyKey && binding.kind !== 'LINE_CHART');
    requireThat(historyShapes.size <= 10 && [...variables].reduce((sum, key) => sum + maximum(key), 0) <= 20 && [...keys.values()].every(set => set.size <= 50)
      && [...keys].reduce((sum, [key, set]) => sum + set.size * maximum(key), 0) <= 200
      && (identityKind === 'share' ? 2 : 4) + (variables.size ? 1 : 0) + (hasCurrent ? 1 : 0) + directories.size + historyShapes.size + alarmShapes.size <= 20, 'budget');
  }
}
function decimalParts(lexical: string): { negative: boolean; digits: string; magnitude: bigint | null } {
  const [coefficient = '', exponent = '0'] = lexical.toLowerCase().split('e');
  const negative = coefficient.startsWith('-');
  const unsigned = negative ? coefficient.slice(1) : coefficient;
  const point = unsigned.indexOf('.');
  const scale = point < 0 ? 0 : unsigned.length - point - 1;
  const digits = unsigned.replace('.', '').replace(/^0+/, '') || '0';
  // 仅计算指数的整数差，不展开零填充，也不把JS下溢结果当实际0。
  const exponentValue = BigInt(exponent);
  return { negative: negative && digits !== '0', digits, magnitude: digits === '0' ? null : BigInt(digits.length - scale) + exponentValue };
}
function compareNumbers(left: RuntimeNumber, right: RuntimeNumber): number {
  const a = decimalParts(left.lexical); const b = decimalParts(right.lexical);
  if (a.negative !== b.negative) return a.negative ? -1 : 1;
  const direction = a.negative ? -1 : 1;
  if (a.magnitude !== b.magnitude) return (a.magnitude === null ? -1 : b.magnitude === null ? 1 : a.magnitude < b.magnitude ? -1 : 1) * direction;
  const length = Math.max(a.digits.length, b.digits.length);
  const x = a.digits.padEnd(length, '0'); const y = b.digits.padEnd(length, '0');
  return (x < y ? -1 : x > y ? 1 : 0) * direction;
}
function scalar(value: unknown, meta: RuntimeProperty): RuntimeValue {
  if (meta.dataType === 'OBJECT' || meta.dataType === 'LIST') {
    try { return parseCompositeValue(value, meta.dataType); } catch { throw new DeviceDataError('response'); }
  }
  if (meta.dataType === 'NUMBER') {
    const number = exactNumber(value);
    requireThat((meta.minimumValue === null || compareNumbers(number, meta.minimumValue) >= 0)
      && (meta.maximumValue === null || compareNumbers(number, meta.maximumValue) <= 0));
    return number;
  }
  if (meta.dataType === 'SWITCH') { requireThat(typeof value === 'boolean'); return value; }
  requireThat((meta.dataType === 'TEXT' || meta.dataType === 'ENUM') && typeof value === 'string');
  if (meta.dataType === 'ENUM') requireThat(meta.enumOptions?.includes(value));
  return value;
}
export interface CurrentSubscriptionDevice {
  readonly deviceId: string; readonly propertyKeys: readonly string[];
}
/** 订阅只包含实际CURRENT_VALUE依赖；历史专用键/设备状态不伪造为current键。 */
export function currentSubscription(plan: DevicePagePlan): readonly CurrentSubscriptionDevice[] {
  return freeze(plan.devices.map(device => ({ deviceId: device.deviceId, propertyKeys: device.propertyKeys.filter(key =>
    plan.bindings.some(binding => binding.deviceId === device.deviceId && binding.propertyKey === key && binding.kind !== 'LINE_CHART'))
  })).filter(device => device.propertyKeys.length > 0));
}

/** current自身复验授权与expected模型，响应解释只使用本轮已验证不可变模型元信息。 */
async function readCurrentValues(context: DeviceReadContext, scope: ReturnType<typeof readScope>, queries: readonly DeviceQuery[], models: readonly RuntimeModel[]): Promise<readonly CurrentDevice[]> {
  const current: CurrentDevice[] = [];
  if (queries.length) {
    const values = await readJson(context, scope, `${dataPrefix(context)}/devices/current-values/query`, { devices: queries });
    object(values, ['devices']); requireThat(Array.isArray(values.devices) && values.devices.length === queries.length);
    for (const [index, value] of values.devices.entries()) {
      object(value, ['deviceId', 'status', 'values']); const query = queries[index]!;
      requireThat(value.deviceId === query.deviceId && ['AVAILABLE', 'NOT_AVAILABLE', 'MODEL_MISMATCH'].includes(value.status as string) && Array.isArray(value.values));
      if (value.status !== 'AVAILABLE') {
        requireThat(value.values.length === 0); current.push({ deviceId: query.deviceId, status: value.status as 'NOT_AVAILABLE' | 'MODEL_MISMATCH', values: [] }); continue;
      }
      requireThat(value.values.length === query.propertyKeys.length);
      const properties = models.find(model => model.versionId === query.expectedModelVersionId)!.properties;
      const accepted: CurrentProperty[] = value.values.map((item, position) => {
        requireThat(item && typeof item === 'object'); const fact = item as Record<string, unknown>;
        requireThat(fact.propertyKey === query.propertyKeys[position]);
        if (fact.state !== 'VALUE') {
          object(fact, ['propertyKey', 'state']); requireThat(STATES.includes(fact.state as string));
          return fact as unknown as CurrentProperty;
        }
        object(fact, ['propertyKey', 'state', 'value', 'occurredAt', 'reportedModelVersionId']);
        requireThat(fact.reportedModelVersionId === query.expectedModelVersionId && timestamp(fact.occurredAt));
        const meta = properties.find(entry => entry.propertyKey === fact.propertyKey)!;
        return { propertyKey: fact.propertyKey as string, state: 'VALUE' as const, value: scalar(fact.value, meta), occurredAt: fact.occurredAt as string, reportedModelVersionId: query.expectedModelVersionId };
      });
      current.push({ deviceId: query.deviceId, status: 'AVAILABLE', values: accepted });
    }
  }
  scope.fence();
  return freeze(current);
}

/** dirty读取不重跑meta/history/alarm；任何设备负状态使根调度器清全页并重新确权。 */
export async function loadDeviceCurrent(context: DeviceReadContext & {
  readonly plan: DevicePagePlan; readonly facts: DevicePageFacts; readonly devices?: readonly CurrentSubscriptionDevice[];
}): Promise<DevicePageFacts> {
  const scope = readScope(context);
  const plan = planDevicePage(context.published.schema, context.plan.pageId, context.plan.selections, context.plan.to);
  requireSharePlan(context, plan);
  const facts = context.facts;
  requireThat(facts.pageId === plan.pageId && Object.keys(facts.selections).length === Object.keys(plan.selections).length, 'identity');
  for (const [key, selected] of Object.entries(plan.selections)) {
    const actual = facts.selections[key];
    requireThat(Array.isArray(selected) ? Array.isArray(actual) && selected.length === actual.length && selected.every((id, i) => id === actual[i]) : selected === actual, 'identity');
  }
  const allowed = currentSubscription(plan);
  const requested = context.devices ?? allowed;
  const seen = new Set<string>();
  const queries: DeviceQuery[] = requested.map(request => {
    const permitted = allowed.find(device => device.deviceId === request.deviceId);
    requireThat(permitted && !seen.has(request.deviceId) && request.propertyKeys.length > 0
      && new Set(request.propertyKeys).size === request.propertyKeys.length && request.propertyKeys.every(key => permitted.propertyKeys.includes(key)), 'plan');
    seen.add(request.deviceId);
    const planned = plan.devices.find(device => device.deviceId === request.deviceId)!;
    const previous = facts.devices.find(device => device.deviceId === request.deviceId);
    requireThat(previous?.status === 'AVAILABLE' && previous.currentModelVersionId === planned.expectedModelVersionId, 'identity');
    const model = facts.models.find(model => model.versionId === planned.expectedModelVersionId);
    const reference = plan.models.find(model => model.versionId === planned.expectedModelVersionId)!;
    requireThat(model && model.digest === reference.digest && model.digestAlgorithm === reference.digestAlgorithm && model.profile === reference.profile
      && request.propertyKeys.every(key => model.properties.some(property => property.propertyKey === key)), 'identity');
    return { deviceId: request.deviceId, expectedModelVersionId: planned.expectedModelVersionId, propertyKeys: [...request.propertyKeys] };
  });
  if (!queries.length) { scope.fence(); return facts; }
  const updates = await readCurrentValues(context, scope, queries, facts.models);
  requireThat(updates.every(device => device.status === 'AVAILABLE'), 'identity');
  const current = facts.current.map(previous => {
    const update = updates.find(device => device.deviceId === previous.deviceId);
    if (!update) return previous;
    requireThat(previous.status === 'AVAILABLE' && update.values.every(value => previous.values.some(old => old.propertyKey === value.propertyKey)), 'identity');
    return { ...previous, values: previous.values.map(value => update.values.find(next => next.propertyKey === value.propertyKey) ?? value) };
  });
  requireThat(updates.every(update => current.some(device => device.deviceId === update.deviceId)), 'identity');
  scope.fence();
  return freeze({ ...facts, current });
}

/** snapshots先复验可见性/模型/描述，再只为成功设备批量读当前值；不把不可用翻译成NO_VALUE。 */
export async function loadDevicePage(context: DeviceReadContext & { readonly plan: DevicePagePlan; readonly beforeCurrent?: (queries: readonly DeviceQuery[], available: readonly DeviceQuery[]) => Promise<void> }): Promise<DevicePageFacts> {
  const scope = readScope(context);
  const plan = planDevicePage(context.published.schema, context.plan.pageId, context.plan.selections, context.plan.to);
  requireSharePlan(context, plan);
  const empty = { pageId: plan.pageId, selections: plan.selections, devices: [], models: [], current: [], history: [],
    alarms: plan.alarmBindings.map(binding => ({ componentId: binding.componentId, queryId: binding.queryId, status: 'NOT_SELECTED' as const, items: [], nextCursor: null, hasMore: false })) };
  if (!plan.devices.length) return freeze(empty);
  const response = await readJson(context, scope, `${dataPrefix(context)}/devices/snapshots/query`, { models: plan.models, devices: plan.devices });
  object(response, ['devices', 'models']);
  requireThat(Array.isArray(response.devices) && response.devices.length === plan.devices.length && Array.isArray(response.models));
  const devices: DeviceSnapshot[] = response.devices.map((value, index) => {
    requireThat(value && typeof value === 'object'); const entry = value as Record<string, unknown>;
    const query = plan.devices[index]!; requireThat(entry.deviceId === query.deviceId);
    if (entry.status === 'NOT_AVAILABLE') object(entry, ['deviceId', 'status']);
    else if (entry.status === 'MODEL_MISMATCH') {
      object(entry, ['deviceId', 'status', 'currentModelVersionId']);
      requireThat(entry.currentModelVersionId === null || typeof entry.currentModelVersionId === 'string' && UUID.test(entry.currentModelVersionId));
      requireThat(entry.currentModelVersionId !== query.expectedModelVersionId);
    } else {
      object(entry, ['deviceId', 'status', 'name', 'deviceStatus', 'lastOnlineAt', 'currentModelVersionId']);
      requireThat(entry.status === 'AVAILABLE' && typeof entry.name === 'string' && deviceStatus(entry.deviceStatus)
        && (entry.lastOnlineAt === null || timestamp(entry.lastOnlineAt)) && entry.currentModelVersionId === query.expectedModelVersionId);
    }
    return entry as unknown as DeviceSnapshot;
  });
  const available = plan.devices.filter((_, index) => devices[index]!.status === 'AVAILABLE');
  const expectedModels = new Set(available.map(device => device.expectedModelVersionId));
  requireThat(response.models.length === expectedModels.size);
  const modelIds = new Set<string>();
  const models: RuntimeModel[] = response.models.map(value => {
    object(value, ['versionId', 'digestAlgorithm', 'digest', 'profile', 'properties']);
    requireThat(typeof value.versionId === 'string' && expectedModels.has(value.versionId) && !modelIds.has(value.versionId));
    modelIds.add(value.versionId);
    const expected = plan.models.find(model => model.versionId === value.versionId)!;
    requireThat(value.digest === expected.digest && value.digestAlgorithm === expected.digestAlgorithm && value.profile === expected.profile);
    const keys = new Set(available.filter(device => device.expectedModelVersionId === value.versionId).flatMap(device => [...device.propertyKeys]));
    requireThat(Array.isArray(value.properties) && value.properties.length === keys.size);
    const properties = value.properties.map(property); const observed = new Set<string>();
    for (const meta of properties) { requireThat(keys.has(meta.propertyKey) && !observed.has(meta.propertyKey)); observed.add(meta.propertyKey); }
    return { versionId: expected.versionId, digestAlgorithm: expected.digestAlgorithm, digest: expected.digest, profile: expected.profile, properties };
  });
  for (const binding of plan.bindings) {
    if (!binding.deviceId || !binding.propertyKey || !available.some(device => device.deviceId === binding.deviceId)) continue;
    const device = available.find(candidate => candidate.deviceId === binding.deviceId)!;
    const meta = models.find(model => model.versionId === device.expectedModelVersionId)!.properties.find(entry => entry.propertyKey === binding.propertyKey)!;
    const component = context.published.schema.pages.find(page => page.id === plan.pageId)!.components.find(entry => entry.id === binding.componentId)!;
    const compatible = binding.kind === 'LINE_CHART' ? true : binding.kind === 'GAUGE' ? meta.dataType === 'NUMBER'
      : binding.kind === 'JSON_VIEW' ? meta.dataType === 'OBJECT' || meta.dataType === 'LIST'
        : component.kind === 'TABLE' && component.props.mode === 'LIST_VALUE' ? meta.dataType === 'LIST'
          : ['NUMBER', 'TEXT', 'SWITCH', 'ENUM'].includes(meta.dataType);
    requireThat(compatible, 'plan');
  }
  const queries = available.map(device => ({ ...device, propertyKeys: device.propertyKeys.filter(key => plan.bindings.some(binding =>
    binding.deviceId === device.deviceId && binding.propertyKey === key && binding.kind !== 'LINE_CHART')) })).filter(device => device.propertyKeys.length > 0);
  if (context.beforeCurrent && (queries.length || context.kind !== 'share' && plan.alarmQueries.length)) {
    scope.fence(); await context.beforeCurrent(freeze(queries), freeze(available)); scope.fence();
  }
  const current = await readCurrentValues(context, scope, queries, models);
  scope.fence();
  const history: HistoryResult[] = [];
  for (const query of plan.histories) {
    const device = devices.find(device => device.deviceId === query.deviceId)!;
    if (device.status !== 'AVAILABLE') { history.push({ queryId: query.queryId, status: device.status, points: [] }); continue; }
    const meta = models.find(model => model.versionId === query.expectedModelVersionId)!.properties.find(meta => meta.propertyKey === query.propertyKey)!;
    if (meta.dataType !== 'NUMBER') { history.push({ queryId: query.queryId, status: 'NON_NUMERIC', points: [] }); continue; }
    history.push(await observation(() => readHistory(query, (path, body) => readJson(context, scope, path, body, true), context.kind === 'share' ? { shareId: context.context.shareId } : undefined)));
  }
  requireThat(history.reduce((sum, result) => sum + result.points.length, 0) <= 20000, 'budget');
  const alarmCache = new Map<string, AlarmResult>(); const alarms: AlarmResult[] = [];
  for (const binding of plan.alarmBindings) {
    if (binding.queryId === null) { alarms.push({ componentId: binding.componentId, queryId: null, status: 'NOT_SELECTED', items: [], nextCursor: null, hasMore: false }); continue; }
    let result = alarmCache.get(binding.queryId);
    if (!result) { result = await observation(() => readAlarm(plan.alarmQueries.find(query => query.queryId === binding.queryId)!, binding.componentId, undefined,
      (path, body) => readJson(context, scope, path, body, true), dataPrefix(context))); alarmCache.set(binding.queryId, result); }
    alarms.push({ ...result, componentId: binding.componentId });
  }
  scope.fence();
  return freeze({ pageId: plan.pageId, selections: plan.selections, devices, models, current, history, alarms });
}
/** 精确模型过滤的一页目录；不自动翻页、不从空页推断已选设备是否撤权。 */
export async function loadDeviceCatalog(context: DeviceReadContext & { readonly variableKey: string; readonly cursor?: string; readonly limit?: number }): Promise<DeviceCatalogPage> {
  const scope = readScope(context);
  const variable = context.published.schema.variables.find(entry => entry.key === context.variableKey);
  requireThat(variable?.type === 'DEVICE_SINGLE' || variable?.type === 'DEVICE_MULTI', 'plan');
  const model = context.published.schema.models.find(entry => entry.key === variable.modelKey); requireThat(model, 'plan');
  const limit = context.limit ?? 20;
  requireThat(Number.isInteger(limit) && limit >= 1 && limit <= 50, 'plan');
  requireThat(context.cursor === undefined || /^[\x21-\x7e]{1,2048}$/.test(context.cursor), 'plan');
  const params = new URLSearchParams({ ...(context.kind === 'share' ? { variableKey: variable.key } : { modelVersionId: model.versionId }), limit: String(limit) });
  if (context.cursor !== undefined) params.set('cursor', context.cursor);
  const response = await readJson(context, scope, `${dataPrefix(context)}/devices/catalog?${params}`);
  object(response, ['items', 'nextCursor', 'hasMore']);
  requireThat(Array.isArray(response.items) && response.items.length <= limit && typeof response.hasMore === 'boolean'
    && (response.hasMore ? typeof response.nextCursor === 'string' && /^[\x21-\x7e]{1,2048}$/.test(response.nextCursor) : response.nextCursor === null));
  const ids = new Set<string>();
  for (const item of response.items) {
    object(item, ['deviceId', 'name', 'deviceStatus', 'currentModelVersionId']);
    requireThat(typeof item.deviceId === 'string' && UUID.test(item.deviceId) && !ids.has(item.deviceId)
      && typeof item.name === 'string' && deviceStatus(item.deviceStatus) && item.currentModelVersionId === model.versionId);
    if (context.kind === 'share') requireThat(context.context.variableScopes.find(scope => scope.variableKey === variable.key)?.deviceIds.includes(item.deviceId), 'response');
    ids.add(item.deviceId);
  }
  scope.fence(); return freeze(response as unknown as DeviceCatalogPage);
}

/** 只映射确定的响应结构错误，不把身份失效或网络错误伪装为局部空结果。 */
async function observation<T>(action: () => Promise<T>): Promise<T> {
  try { return await action(); }
  catch (error) { if (error instanceof ObservationResponseError) throw new DeviceDataError('response'); throw error; }
}
/** 显式告警翻页沿原选择与时间窗口，只重读该组件的一页。 */
export async function loadAlarmPage(context: DeviceReadContext, componentId: string, cursor: string | undefined, original: DevicePagePlan): Promise<AlarmResult> {
  const scope = readScope(context);
  const plan = planDevicePage(context.published.schema, original.pageId, original.selections, original.to);
  requireSharePlan(context, plan);
  const binding = plan.alarmBindings.find(entry => entry.componentId === componentId); requireThat(binding, 'plan');
  if (binding.queryId === null) return freeze({ componentId, queryId: null, status: 'NOT_SELECTED', items: [], nextCursor: null, hasMore: false });
  const query = plan.alarmQueries.find(entry => entry.queryId === binding.queryId)!;
  const result = await observation(() => readAlarm(query, componentId, cursor, (path, body) => readJson(context, scope, path, body, true), dataPrefix(context)));
  scope.fence(); return result;
}

function dataPrefix(context: DeviceReadContext): string {
  return context.kind === 'share' ? `/api/v1/shares/${context.context.shareId}` : '/api/v1/app';
}
function requireSharePlan(context: DeviceReadContext, plan: DevicePagePlan): void {
  if (context.kind !== 'share') return;
  requireThat(plan.to === context.context.historyAnchorAt, 'plan');
  for (const variable of plan.variables) {
    if (variable.type !== 'DEVICE_SINGLE' && variable.type !== 'DEVICE_MULTI') continue;
    const scope = context.context.variableScopes.find(scope => scope.variableKey === variable.key);
    const selected = plan.selections[variable.key];
    const ids = selected == null ? [] : typeof selected === 'string' ? [selected] : selected;
    requireThat(scope && ids.every(id => scope.deviceIds.includes(id)), 'plan');
  }
}

/** 本连接局部短键只映射已冻结的查询计划，不能携带查询正文或跨连接复用水位。 */
export function alarmSubscription(plan: DevicePagePlan) {
  return freeze(plan.alarmQueries.map((query, index) => ({ queryKey: `a${index}`, queryId: query.queryId,
    devices: query.devices, conditionStates: query.conditionStates, ackStates: query.ackStates, severities: query.severities })));
}
/** 告警提示只替换命中组的首页；共享查询组件一次读取，不改历史/当前值和未命中页。 */
export async function loadDirtyAlarms(context: AppDeviceReadContext & {
  readonly plan: DevicePagePlan; readonly facts: DevicePageFacts; readonly queryIds: readonly string[];
}): Promise<DevicePageFacts> {
  const scope = readScope(context);
  const plan = planDevicePage(context.published.schema, context.plan.pageId, context.plan.selections, context.plan.to);
  requireThat(context.facts.pageId === plan.pageId && context.queryIds.length <= 20
    && new Set(context.queryIds).size === context.queryIds.length, 'plan');
  requireThat(Object.keys(context.facts.selections).length === Object.keys(plan.selections).length, 'identity');
  for (const [key, selected] of Object.entries(plan.selections)) {
    const actual = context.facts.selections[key];
    requireThat(Array.isArray(selected) ? Array.isArray(actual) && selected.length === actual.length
      && selected.every((id, index) => id === actual[index]) : selected === actual, 'identity');
  }
  requireThat(context.facts.alarms.length === plan.alarmBindings.length && plan.alarmBindings.every(binding =>
    context.facts.alarms.some(result => result.componentId === binding.componentId && result.queryId === binding.queryId)), 'identity');
  const updates = new Map<string, AlarmResult>();
  for (const queryId of context.queryIds) {
    const binding = plan.alarmBindings.find(entry => entry.queryId === queryId);
    requireThat(binding && plan.alarmQueries.some(query => query.queryId === queryId), 'plan');
    const result = await loadAlarmPage(context, binding.componentId, undefined, plan);
    scope.fence(); updates.set(queryId, result);
  }
  scope.fence();
  return freeze({ ...context.facts, alarms: context.facts.alarms.map(previous => {
    const update = previous.queryId === null ? undefined : updates.get(previous.queryId);
    return update ? { ...update, componentId: previous.componentId } : previous;
  }) });
}
