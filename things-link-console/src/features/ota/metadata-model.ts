import type { DeviceTypeResponse } from '@/api/device'
import type {
  OtaTrustDomainSummaryResponse,
  OtaTrustKeyResponse,
  OtaTypeBaselineVersionResponse
} from '@/api/ota'

/** 只接受公开合同的闭集字段，未知字段不能进入只读表或错误提示。 */
function fields(value: unknown, names: string[]): value is Record<string, unknown> {
  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    Object.keys(value).sort().join(',') === [...names].sort().join(',')
  )
}

const text = (value: unknown): value is string => typeof value === 'string' && value.length > 0
const hash = (value: unknown) => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const time = (value: unknown) =>
  text(value) &&
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value) &&
  Number.isFinite(Date.parse(value))
const profile = (value: unknown) =>
  value === 'TC_OTA_ED25519_V1' || value === 'TC_OTA_ES256_P1363_V1'
const revision = (value: unknown) =>
  typeof value === 'string' &&
  /^(0|[1-9][0-9]{0,18})$/.test(value) &&
  BigInt(value) <= 9223372036854775807n
const keyVersion = (value: unknown) =>
  typeof value === 'string' && /^[A-Za-z0-9._:/-]{1,256}$/.test(value)

export const otaMetadataUuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)

export function isOtaTrustDomain(value: unknown): value is OtaTrustDomainSummaryResponse {
  return (
    fields(value, [
      'trustDomain',
      'revision',
      'bundleVersion',
      'policyRevision',
      'rootProfile',
      'rootFingerprint',
      'bundleSha256',
      'activeKeyVersion',
      'activeKeyFingerprint',
      'createdAt',
      'updatedAt'
    ]) &&
    typeof value.trustDomain === 'string' &&
    /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value.trustDomain) &&
    revision(value.revision) &&
    revision(value.bundleVersion) &&
    revision(value.policyRevision) &&
    profile(value.rootProfile) &&
    hash(value.rootFingerprint) &&
    hash(value.bundleSha256) &&
    keyVersion(value.activeKeyVersion) &&
    hash(value.activeKeyFingerprint) &&
    time(value.createdAt) &&
    time(value.updatedAt)
  )
}

export function isOtaTrustKey(value: unknown): value is OtaTrustKeyResponse {
  return (
    fields(value, [
      'keyVersion',
      'state',
      'signatureProfile',
      'fingerprint',
      'notBefore',
      'notAfter'
    ]) &&
    keyVersion(value.keyVersion) &&
    typeof value.state === 'string' &&
    ['PREPARED', 'ACTIVE', 'VERIFY_ONLY', 'REVOKED'].includes(value.state) &&
    profile(value.signatureProfile) &&
    hash(value.fingerprint) &&
    typeof value.notBefore === 'number' &&
    Number.isSafeInteger(value.notBefore) &&
    typeof value.notAfter === 'number' &&
    Number.isSafeInteger(value.notAfter) &&
    value.notBefore >= 0 &&
    value.notAfter <= 253402300799 &&
    value.notAfter > value.notBefore
  )
}

export function isOtaBaselineVersion(value: unknown): value is OtaTypeBaselineVersionResponse {
  return (
    fields(value, ['baselineVersion', 'baselineHash', 'registeredAt']) &&
    typeof value.baselineVersion === 'number' &&
    Number.isSafeInteger(value.baselineVersion) &&
    value.baselineVersion >= 1 &&
    hash(value.baselineHash) &&
    time(value.registeredAt)
  )
}

/** 类型目录只用于选择父类型；不把产品身份或能力字段带入基线历史。 */
export function isOtaMetadataType(value: unknown, projectId: string): value is DeviceTypeResponse {
  return (
    fields(value, [
      'id',
      'projectId',
      'typeKey',
      'name',
      'deviceKind',
      'payloadProtocol',
      'networkType',
      'version',
      'status',
      'productKey',
      'createdAt'
    ]) &&
    otaMetadataUuid(value.id) &&
    value.projectId === projectId &&
    text(value.typeKey) &&
    text(value.name) &&
    text(value.networkType) &&
    text(value.payloadProtocol) &&
    typeof value.deviceKind === 'string' &&
    ['DIRECT', 'GATEWAY', 'SUB_DEVICE'].includes(value.deviceKind) &&
    typeof value.status === 'string' &&
    ['DRAFT', 'PUBLISHED'].includes(value.status) &&
    typeof value.version === 'number' &&
    Number.isSafeInteger(value.version) &&
    value.version >= 1 &&
    (value.productKey === null ||
      (typeof value.productKey === 'string' &&
        /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(value.productKey))) &&
    time(value.createdAt)
  )
}

/** 实际HTTP分页必须完整；坏页不能被模型默认值转换成成功空集。 */
export function publicOtaPage<T>(
  value: unknown,
  valid: (item: unknown) => item is T
): {
  items: T[]
  nextCursor: string | null
  hasMore: boolean
} {
  if (
    !fields(value, ['items', 'nextCursor', 'hasMore']) ||
    !Array.isArray(value.items) ||
    !value.items.every(valid) ||
    typeof value.hasMore !== 'boolean' ||
    (value.hasMore ? !text(value.nextCursor) : value.nextCursor !== null) ||
    (value.hasMore && value.items.length === 0)
  )
    throw new Error('公开分页响应不符合合同')
  return value as { items: T[]; nextCursor: string | null; hasMore: boolean }
}
