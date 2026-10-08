import type { OtaTypeBaselineBody, OtaTypeBaselineResponse } from '@/api/ota'
import { otaMetadataUuid } from './metadata-model'
import { canonicalPublicJson } from './strict-public-json'

export type OtaBaselineScope = {
  /** 已经从同类型权威响应校验的项目租户；不得取登录账号租户。 */
  serverTenantId?: string
  projectId: string
  deviceTypeId: string
  productKey: string
}
const fields = (value: unknown, names: string[]): value is Record<string, unknown> =>
  !!value &&
  typeof value === 'object' &&
  !Array.isArray(value) &&
  Object.keys(value).sort().join(',') === [...names].sort().join(',')
const integer = (value: unknown, min: number, max: number): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
const identifier = (value: unknown): value is string =>
  typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value)
export const otaBaselineProductKey = (value: unknown): value is string =>
  typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(value)
export const otaBaselineRevision = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^(0|[1-9][0-9]{0,18})$/.test(value) &&
  BigInt(value) <= 9223372036854775807n
const fingerprint = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const profile = (value: unknown) =>
  value === 'TC_OTA_ED25519_V1' || value === 'TC_OTA_ES256_P1363_V1'
function version(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^(0|[1-9][0-9]{0,9})\.(0|[1-9][0-9]{0,9})\.(0|[1-9][0-9]{0,9})$/.test(value) &&
    value.split('.').every((axis) => Number(axis) <= 2147483647)
  )
}
function versionOrdered(left: string, right: string) {
  const first = left.split('.').map(Number),
    second = right.split('.').map(Number)
  for (let index = 0; index < 3; index++)
    if (first[index] !== second[index]) return first[index]! < second[index]!
  return true
}
function evidence(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const points = [...value]
  return (
    points.length >= 1 &&
    points.length <= 256 &&
    !/^\p{Z}$/u.test(points[0]!) &&
    !/^\p{Z}$/u.test(points.at(-1)!) &&
    points.every((point) => {
      const code = point.codePointAt(0)!
      return code > 31 && !(code >= 127 && code <= 159) && !(code >= 0xd800 && code <= 0xdfff)
    })
  )
}
function utc(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) &&
    Number.isFinite(Date.parse(value)) &&
    new Date(value).toISOString().slice(0, 19) === value.slice(0, 19)
  )
}
const nanos = (value: string) =>
  value.slice(0, 19) + value.slice(19, -1).replace(/^\./, '').padEnd(9, '0')
/** 完整受控声明只能从服务端读取，严格范围/闭集不允许响应偷偷引入可写能力。 */
export function isOtaBaselineBody(
  value: unknown,
  scope: OtaBaselineScope
): value is OtaTypeBaselineBody {
  if (
    !fields(value, [
      'contractVersion',
      'tenantId',
      'projectId',
      'deviceTypeId',
      'productKey',
      'baselineVersion',
      'trustDomain',
      'rootFingerprint',
      'hardware',
      'bootloader',
      'signatureProfiles',
      'maximumArtifactBytes',
      'availableRamBytes',
      'availableFlashBytes',
      'supportsAbSlots',
      'supportsRangeDownload',
      'supportsResumeDownload',
      'protectedSecurityCounterBits',
      'compressionAlgorithms',
      'deltaModes',
      'propertyProfile',
      'evidenceReference'
    ])
  )
    return false
  return (
    value.contractVersion === 'tc-ota-type-baseline/v1' &&
    otaMetadataUuid(value.tenantId) &&
    (scope.serverTenantId === undefined ||
      (otaMetadataUuid(scope.serverTenantId) && value.tenantId === scope.serverTenantId)) &&
    otaMetadataUuid(scope.projectId) &&
    otaMetadataUuid(scope.deviceTypeId) &&
    otaBaselineProductKey(scope.productKey) &&
    value.projectId === scope.projectId &&
    value.deviceTypeId === scope.deviceTypeId &&
    value.productKey === scope.productKey &&
    integer(value.baselineVersion, 1, Number.MAX_SAFE_INTEGER) &&
    identifier(value.trustDomain) &&
    fingerprint(value.rootFingerprint) &&
    fields(value.hardware, ['model', 'boardRevisionMin', 'boardRevisionMax']) &&
    identifier(value.hardware.model) &&
    integer(value.hardware.boardRevisionMin, 0, Number.MAX_SAFE_INTEGER) &&
    integer(value.hardware.boardRevisionMax, 0, Number.MAX_SAFE_INTEGER) &&
    value.hardware.boardRevisionMin <= value.hardware.boardRevisionMax &&
    fields(value.bootloader, ['minimumVersion', 'maximumVersion']) &&
    version(value.bootloader.minimumVersion) &&
    version(value.bootloader.maximumVersion) &&
    versionOrdered(value.bootloader.minimumVersion, value.bootloader.maximumVersion) &&
    Array.isArray(value.signatureProfiles) &&
    value.signatureProfiles.length >= 1 &&
    value.signatureProfiles.length <= 2 &&
    value.signatureProfiles.every(profile) &&
    new Set(value.signatureProfiles).size === value.signatureProfiles.length &&
    value.signatureProfiles.join(',') === [...value.signatureProfiles].sort().join(',') &&
    integer(value.maximumArtifactBytes, 1, 67108864) &&
    integer(value.availableRamBytes, 0, Number.MAX_SAFE_INTEGER) &&
    integer(value.availableFlashBytes, 0, Number.MAX_SAFE_INTEGER) &&
    typeof value.supportsAbSlots === 'boolean' &&
    typeof value.supportsRangeDownload === 'boolean' &&
    typeof value.supportsResumeDownload === 'boolean' &&
    (!value.supportsResumeDownload || value.supportsRangeDownload) &&
    integer(value.protectedSecurityCounterBits, 0, 53) &&
    Array.isArray(value.compressionAlgorithms) &&
    value.compressionAlgorithms.length === 1 &&
    value.compressionAlgorithms[0] === 'NONE' &&
    Array.isArray(value.deltaModes) &&
    value.deltaModes.length === 1 &&
    value.deltaModes[0] === 'NONE' &&
    value.propertyProfile === 'TC_PROPERTY_COMPOSITE_V1' &&
    evidence(value.evidenceReference)
  )
}
/** 五字段权威快照及纳秒时序；摘要还须由调用组件对完整规范声明复核。 */
export function isOtaBaselineResponse(
  value: unknown,
  scope: OtaBaselineScope
): value is OtaTypeBaselineResponse {
  return (
    fields(value, ['revision', 'baselineHash', 'baseline', 'registeredAt', 'updatedAt']) &&
    otaBaselineRevision(value.revision) &&
    value.revision !== '0' &&
    fingerprint(value.baselineHash) &&
    isOtaBaselineBody(value.baseline, scope) &&
    utc(value.registeredAt) &&
    utc(value.updatedAt) &&
    nanos(value.updatedAt) >= nanos(value.registeredAt)
  )
}
export const otaBaselineCanonicalBytes = (value: OtaTypeBaselineBody) =>
  new TextEncoder().encode(canonicalPublicJson(value))
