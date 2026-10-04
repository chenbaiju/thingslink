import type {
  DashboardCurrentValueBinding,
  DashboardHistorySeriesBinding,
  DashboardResponsiveSchemaV1,
} from "../src/dashboard/v1/index.js";
import {
  DashboardContractViolation,
  validateDashboardSchemaV1,
  type DashboardSchemaV1,
  type DashboardSchemaV1ValidationResult,
} from "@things-link/client-contracts/dashboard/v1";

const singleCurrentValue: DashboardCurrentValueBinding = {
  source: "CURRENT_VALUE",
  device: { variableKey: "device" },
  propertyKey: "temperature",
};

const multiCurrentValue: DashboardCurrentValueBinding = {
  source: "CURRENT_VALUE",
  device: { variableKey: "devices" },
  propertyKey: "temperature",
};

const historyValue: DashboardHistorySeriesBinding = {
  source: "HISTORY_SERIES",
  device: { variableKey: "device" },
  propertyKey: "temperature",
  timeRangeVariableKey: "range",
  granularity: "ONE_MINUTE",
  aggregation: "AVG",
};

const schema: DashboardResponsiveSchemaV1 = {
  schemaVersion: "tc.dashboard/v1",
  presentation: { mode: "RESPONSIVE_GRID", theme: "LIGHT", columns: 24, rowHeight: 8, gap: 8 },
  models: [{
    key: "thermostat",
    versionId: "11111111-1111-4111-8111-111111111111",
    digestAlgorithm: "PG_JSONB_TEXT_V1_SHA256",
    digest: "a".repeat(64),
    profile: "TC_PROPERTY_COMPOSITE_V1",
  }],
  variables: [
    { key: "device", type: "DEVICE_SINGLE", title: "设备", required: true, modelKey: "thermostat" },
    { key: "devices", type: "DEVICE_MULTI", title: "设备组", required: true, modelKey: "thermostat", maxItems: 20, defaultDeviceIds: [] },
    { key: "range", type: "TIME_RANGE", title: "时间", required: true, defaultPreset: "LAST_1_HOUR", allowedPresets: ["LAST_1_HOUR"] },
    { key: "state", type: "TEXT_ENUM", title: "状态", required: true, options: [{ value: "on", label: "开启" }] },
  ],
  pages: [{
    id: "main",
    title: "主页",
    components: [
      {
        id: "text_static",
        kind: "TEXT",
        componentVersion: "1.0.0",
        layout: { x: 0, y: 0, w: 1, h: 1 },
        props: { content: "概览", align: "LEFT", size: "MEDIUM", tone: "REGULAR" },
        bindings: {},
      },
      {
        id: "text_dynamic",
        kind: "TEXT",
        componentVersion: "1.0.0",
        layout: { x: 1, y: 0, w: 1, h: 1 },
        props: { align: "CENTER", size: "SMALL", tone: "SECONDARY" },
        bindings: { text: { source: "ENUM_TEXT", variableKey: "state" } },
      },
      {
        id: "image",
        kind: "IMAGE",
        componentVersion: "1.0.0",
        layout: { x: 2, y: 0, w: 1, h: 1 },
        props: { resourceId: "logo", resourceDigest: "b".repeat(64), alt: "Logo", fit: "CONTAIN" },
        bindings: {},
      },
      {
        id: "value",
        kind: "VALUE_CARD",
        componentVersion: "1.0.0",
        layout: { x: 3, y: 0, w: 1, h: 1 },
        props: { precision: 2, unitMode: "MODEL" },
        bindings: { value: singleCurrentValue },
      },
      {
        id: "status",
        kind: "STATUS",
        componentVersion: "1.0.0",
        layout: { x: 4, y: 0, w: 1, h: 1 },
        props: { showLastOnlineAt: true },
        bindings: { status: { source: "DEVICE_STATUS", device: { variableKey: "device" } } },
      },
      {
        id: "gauge",
        kind: "GAUGE",
        componentVersion: "1.0.0",
        layout: { x: 5, y: 0, w: 1, h: 1 },
        props: { scaleMode: "EXPLICIT", min: 0, max: 100, precision: 2, unitMode: "MODEL" },
        bindings: { value: singleCurrentValue },
      },
      {
        id: "chart",
        kind: "LINE_CHART",
        componentVersion: "1.0.0",
        layout: { x: 6, y: 0, w: 1, h: 1 },
        props: { showLegend: true, series: [{ id: "temperature", label: "温度" }] },
        bindings: { series: [{ id: "temperature", value: historyValue }] },
      },
      {
        id: "table_list",
        kind: "TABLE",
        componentVersion: "1.0.0",
        layout: { x: 7, y: 0, w: 1, h: 1 },
        props: { mode: "LIST_VALUE", rowLimit: 20 },
        bindings: { value: singleCurrentValue },
      },
      {
        id: "table_devices",
        kind: "TABLE",
        componentVersion: "1.0.0",
        layout: { x: 8, y: 0, w: 1, h: 1 },
        props: { mode: "DEVICE_VALUES", rowLimit: 20, columns: [{ id: "temperature", label: "温度" }] },
        bindings: { columns: [{ id: "temperature", value: multiCurrentValue }] },
      },
      {
        id: "json",
        kind: "JSON_VIEW",
        componentVersion: "1.0.0",
        layout: { x: 9, y: 0, w: 1, h: 1 },
        props: { initialExpandDepth: 1 },
        bindings: { value: singleCurrentValue },
      },
      {
        id: "alarms",
        kind: "ALARM_LIST",
        componentVersion: "1.0.0",
        layout: { x: 10, y: 0, w: 1, h: 1 },
        props: { pageSize: 20, showClearedAt: true },
        bindings: {
          alarms: {
            source: "ALARM_LIST",
            devices: { variableKey: "devices" },
            conditionStates: ["ACTIVE"],
            ackStates: ["UNACKNOWLEDGED"],
            severities: ["CRITICAL"],
          },
        },
      },
      {
        id: "selector",
        kind: "DEVICE_SELECTOR",
        componentVersion: "1.0.0",
        layout: { x: 11, y: 0, w: 1, h: 1 },
        props: { placeholder: "请选择设备", pageSize: 20 },
        bindings: { directory: { source: "DEVICE_DIRECTORY", variableKey: "devices" } },
      },
    ],
  }],
};

// @ts-expect-error 共享Schema必须阻止调用方改写只读集合。
schema.models.push({});

// @ts-expect-error 组件版本只接受冻结的1.0.0。
schema.pages[0]!.components[0]!.componentVersion = "1.0.1";

void schema;

const publicResult: DashboardSchemaV1ValidationResult = validateDashboardSchemaV1(new Uint8Array());
const publicSchema: DashboardSchemaV1 = publicResult.schema;
const publicViolation: Error = new DashboardContractViolation("INVALID_JSON", "$", "测试");

// @ts-expect-error 公开结果的Schema页面集合必须保持深只读。
publicResult.schema.pages.push(schema.pages[0]);

// @ts-expect-error 未决需求集合不得由调用方增删。
publicResult.unresolvedRequirements.push({});

void publicSchema;
void publicViolation;


import {
  createRuntimeNumber, parseDashboardRuntimeResponse, parseCompositeValue,
  runtimeChildren, gaugePosition, localTablePage, serializeRuntimeValue,
  type RuntimeNumber, type RuntimeValue,
} from "@things-link/client-contracts/dashboard/v1";
const currentResponse = parseDashboardRuntimeResponse(new Uint8Array());
const exactNumber: RuntimeNumber = createRuntimeNumber(currentResponse.value);
const compound: RuntimeValue = parseCompositeValue(currentResponse.value, "OBJECT");
const children = runtimeChildren(compound);
// @ts-expect-error 共享复合子项列表禁止增删。
children.push({ label: "x", value: exactNumber });
// @ts-expect-error 原数值词法不能被呈现层改写。
exactNumber.lexical = "0";
const table = localTablePage(children, 0, 20);
// @ts-expect-error 本地页行保持只读。
table.rows.push(children[0]);
void gaugePosition(exactNumber, 0, 10);
void serializeRuntimeValue(compound);

import { decodeHistoryResponse, historySegments, historyGeometry, historyRange,
  type HistoryQuery, type HistoryPoint, type HistoryResult, type HistoryPresentationResult,
} from "@things-link/client-contracts/dashboard/v1";
const historyQuery: HistoryQuery = { queryId: "history", deviceId: "device", expectedModelVersionId: "model",
  propertyKey: "temperature", from: "2026-09-08T00:00:00Z", to: "2026-09-08T01:00:00Z",
  granularity: "RAW", aggregation: "AVG" };
const decodedHistory: HistoryResult = decodeHistoryResponse(historyQuery, currentResponse);
const historyPoint: HistoryPoint = decodedHistory.points[0]!;
// @ts-expect-error 历史结果点集合只读，宿主不能原地拼入另一轮数据。
decodedHistory.points.push(historyPoint);
// @ts-expect-error 带失败状态的网络结果不可冒充粒度字段必有的展示结果。
const incompletePresentation: HistoryPresentationResult = decodedHistory;
void incompletePresentation;
void historySegments(decodedHistory.points, "RAW");
void historyGeometry(decodedHistory.points);
void historyRange(decodedHistory.points);

import { decodeAlarmResponse, type AlarmQuery, type AlarmBinding, type AlarmItem, type AlarmResult } from "@things-link/client-contracts/dashboard/v1";
const alarmQuery: AlarmQuery = { queryId: "alarm", devices: [{ deviceId: "device", expectedModelVersionId: "model" }],
  conditionStates: ["ACTIVE"], ackStates: ["UNACKNOWLEDGED"], severities: ["MAJOR"], limit: 20 };
const alarmBinding: AlarmBinding = { componentId: "component", queryId: alarmQuery.queryId };
const alarmResult: AlarmResult = decodeAlarmResponse(alarmQuery, alarmBinding.componentId, currentResponse);
const alarmItem: AlarmItem = alarmResult.items[0]!;
// @ts-expect-error 告警结果不可原地拼页。
alarmResult.items.push(alarmItem);
// @ts-expect-error 已解码的版本事实只读。
alarmItem.version = 1;
// @ts-expect-error 告警响应的三轴状态为闭合联合，不允许未知值。
const unknownSeverity: AlarmItem["severity"] = "UNKNOWN";
void unknownSeverity;
