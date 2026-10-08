import type { OtaTrustImportRequest, OtaTrustResponse } from '@/api/ota'
import { canonicalPublicJson, parseStrictPublicJson } from './strict-public-json'
export type PublicTrustMaterial = Omit<OtaTrustImportRequest, 'expectedRevision'> & {
  expectedRevision?: string
}
export const trustImportDomain = (value: unknown): value is string =>
  typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value)
export const trustImportRevision = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^(0|[1-9][0-9]{0,18})$/.test(value) &&
  BigInt(value) <= 9223372036854775807n
const object = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value)
const fields = (value: unknown, names: string[]): value is Record<string, unknown> =>
  object(value) && Object.keys(value).sort().join(',') === names.sort().join(',')
const integer = (value: unknown, min: number, max: number): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
const fingerprint = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const profile = (value: unknown) =>
  value === 'TC_OTA_ED25519_V1' || value === 'TC_OTA_ES256_P1363_V1'
export function publicBase64(value: unknown, maxBytes: number): Uint8Array | undefined {
  if (typeof value !== 'string' || !value || value.length > Math.ceil(maxBytes / 3) * 4) return
  try {
    const raw = atob(value)
    if (!raw.length || raw.length > maxBytes || btoa(raw) !== value) return
    return Uint8Array.from(raw, (character) => character.charCodeAt(0))
  } catch {
    return
  }
}
/** 只接受公开SPKI的严格编码形状，排除PKCS8/PEM秘密；数学有效性与根验真仍由后端负责。 */
function publicSpki(value: unknown, selectedProfile: unknown): boolean {
  const bytes = publicBase64(value, 128)
  const prefix =
    selectedProfile === 'TC_OTA_ED25519_V1'
      ? '302a300506032b6570032100'
      : '3059301306072a8648ce3d020106082a8648ce3d03010703420004'
  const hex =
    bytes &&
    Array.from(bytes)
      .map((byte) => byte.toString(16).padStart(2, '0'))
      .join('')
  return (
    !!bytes &&
    bytes.length === prefix.length / 2 + (selectedProfile === 'TC_OTA_ED25519_V1' ? 32 : 64) &&
    !!hex?.startsWith(prefix)
  )
}
function key(value: unknown): boolean {
  return (
    fields(value, [
      'keyVersion',
      'signatureProfile',
      'spki',
      'fingerprint',
      'state',
      'notBefore',
      'notAfter'
    ]) &&
    typeof value.keyVersion === 'string' &&
    /^[A-Za-z0-9._:/-]{1,256}$/.test(value.keyVersion) &&
    profile(value.signatureProfile) &&
    publicSpki(value.spki, value.signatureProfile) &&
    fingerprint(value.fingerprint) &&
    ['PREPARED', 'ACTIVE', 'VERIFY_ONLY', 'REVOKED'].includes(String(value.state)) &&
    typeof value.state === 'string' &&
    integer(value.notBefore, 0, 253402300799) &&
    integer(value.notAfter, 0, 253402300799) &&
    value.notAfter > value.notBefore
  )
}
/** 输入必须是两种明确的公开封套；不生成签名、不接收根配置，也不洗掉重复键或非法整数原文。 */
export function parsePublicTrustMaterial(bytes: Uint8Array): PublicTrustMaterial {
  const value = parseStrictPublicJson(bytes)
  const invalid = () => new Error('离线公开签包不符合受控合同')
  if (
    !(
      fields(value, ['bundle', 'signature']) ||
      fields(value, ['expectedRevision', 'bundle', 'signature'])
    ) ||
    (Object.hasOwn(value, 'expectedRevision') && !trustImportRevision(value.expectedRevision)) ||
    publicBase64(value.signature, 64)?.length !== 64 ||
    !fields(value.bundle, ['contractVersion', 'trustDomain', 'bundleVersion', 'keys']) ||
    value.bundle.contractVersion !== 'tc-ota-trust-bundle/v1' ||
    !trustImportDomain(value.bundle.trustDomain) ||
    !integer(value.bundle.bundleVersion, 1, Number.MAX_SAFE_INTEGER) ||
    !Array.isArray(value.bundle.keys) ||
    value.bundle.keys.length < 1 ||
    value.bundle.keys.length > 64 ||
    !value.bundle.keys.every(key)
  )
    throw invalid()
  const keys = value.bundle.keys as Record<string, unknown>[]
  if (
    new Set(keys.map((entry) => entry.keyVersion)).size !== keys.length ||
    new Set(keys.map((entry) => entry.fingerprint)).size !== keys.length ||
    keys.filter((entry) => entry.state === 'ACTIVE').length !== 1
  )
    throw invalid()
  return value as unknown as PublicTrustMaterial
}
/** 规范包摘要供公开快照核对；不称为根签名验真或发布资格。 */
export function trustBundleCanonicalBytes(material: PublicTrustMaterial): Uint8Array {
  return new TextEncoder().encode(canonicalPublicJson(material.bundle))
}
/** 最终三字段正文仍须在服务端完整预算内，源文件刚好合格不能覆盖额外封套开销。 */
export function trustImportBody(
  material: PublicTrustMaterial,
  revision: string
): OtaTrustImportRequest {
  if (
    !trustImportRevision(revision) ||
    (material.expectedRevision !== undefined && material.expectedRevision !== revision)
  )
    throw new Error('材料修订与当前登记不匹配')
  const body = {
    expectedRevision: revision,
    bundle: material.bundle,
    signature: material.signature
  }
  parseStrictPublicJson(new TextEncoder().encode(JSON.stringify(body)))
  const freeze = (value: unknown) => {
    if (value && typeof value === 'object') {
      Object.values(value).forEach(freeze)
      Object.freeze(value)
    }
  }
  freeze(body)
  return body
}
const time = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) &&
  Number.isFinite(Date.parse(value)) &&
  new Date(value).toISOString().slice(0, 19) === value.slice(0, 19)
/** 实际Java DTO的九个必填公开字段，未知能力或缺失值一律拒绝。 */
export function isOtaTrustState(
  value: unknown,
  domain: string
): value is Required<OtaTrustResponse> {
  const nanoseconds = (value: string) =>
    value.slice(0, 19) + value.slice(19, -1).replace(/^\./, '').padEnd(9, '0')
  return (
    trustImportDomain(domain) &&
    fields(value, [
      'trustDomain',
      'revision',
      'bundleVersion',
      'policyRevision',
      'rootProfile',
      'rootFingerprint',
      'bundleSha256',
      'createdAt',
      'updatedAt'
    ]) &&
    value.trustDomain === domain &&
    trustImportRevision(value.revision) &&
    value.revision !== '0' &&
    trustImportRevision(value.bundleVersion) &&
    value.bundleVersion !== '0' &&
    BigInt(value.bundleVersion) <= 9007199254740991n &&
    trustImportRevision(value.policyRevision) &&
    value.policyRevision !== '0' &&
    BigInt(value.policyRevision) <= 9007199254740991n &&
    profile(value.rootProfile) &&
    fingerprint(value.rootFingerprint) &&
    fingerprint(value.bundleSha256) &&
    time(value.createdAt) &&
    time(value.updatedAt) &&
    nanoseconds(value.updatedAt) >= nanoseconds(value.createdAt)
  )
}
