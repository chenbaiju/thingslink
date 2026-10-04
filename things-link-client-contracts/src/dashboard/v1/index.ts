export {
  dashboardV1Contract,
  type DashboardCanvasMode,
  type DashboardComponentKind,
  type DashboardParseRejectionReason,
  type DashboardPropertyDataType,
  type DashboardRejectionReason,
  type DashboardValidationRejectionReason,
  type DashboardV1Contract,
} from "./generated.js";
export {
  compareNumericSemVer,
  isHostVersionCompatible,
  parseNumericSemVer,
  type HostVersionRange,
  type NumericSemVer,
} from "./semver.js";
export { DashboardContractViolation } from "./violation.js";
export {
  DASHBOARD_SCHEMA_SCOPE,
  validateDashboardSchemaV1,
  type AdapterCapability,
  type BuiltinResourceRequirement,
  type DashboardSchemaV1ValidationResult,
  type DashboardUnresolvedRequirement,
  type DataAdapterRequirement,
  type DefaultDeviceRequirement,
  type HistoricalPropertyRequirement,
  type HostComponentRequirement,
  type ModelGaugeRangeRequirement,
  type ModelPropertyRequirement,
  type ModelReferenceRequirement,
} from "./schema-validator.js";
export type * from "./types.js";
export {
  parseDashboardSchemaEnvelope,
  parseDashboardRuntimeResponse,
  isStrictJsonNumber,
  type StrictJsonNumber,
  type DashboardSchemaEnvelope,
  type StrictJsonObject,
  type StrictJsonValue,
} from "./strict-json.js";

export { formatDecimal, formatScalar, type ScalarNumber, type ScalarProperty } from "./scalar-presentation.js";

export { CompositeValueError, createRuntimeNumber, isRuntimeNumber, parseCompositeValue, serializeRuntimeValue, type RuntimeNumber, type RuntimeValue } from "./composite-value.js";
export { runtimeChildren, runtimeContainer, initiallyExpanded } from "./json-presentation.js";
export { gaugePosition, localTablePage } from "./current-presentation.js";

export { decodeHistoryResponse, ObservationResponseError, utcTimestamp,
  type HistoryQuery, type HistoryPoint, type HistoryResult, type ObservationStatus } from "./history-response.js";
export { historySegments, historyGeometry, historyRange, interactionStateLabel,
  type HistoryPresentationResult, type HistorySeriesView } from "./history-presentation.js";

export { decodeAlarmResponse, type AlarmQuery, type AlarmBinding, type AlarmItem, type AlarmResult } from "./alarm-response.js";
