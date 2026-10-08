import type { OtaTypeBaselineBody, OtaTypeBaselineResponse } from '@/api/ota'
import { otaBaselineCanonicalBytes } from '@/features/ota/baseline-registration-model'
import { publicSha } from './otaTrustPublicFixture'
export const baselineScope = {
  tenantId: '11111111-1111-4111-8111-111111111111',
  projectId: '22222222-2222-4222-8222-222222222222',
  deviceTypeId: '33333333-3333-4333-8333-333333333333',
  productKey: 'public_product'
}
export const baseline = (version = 2): OtaTypeBaselineBody => ({
  contractVersion: 'tc-ota-type-baseline/v1',
  ...baselineScope,
  baselineVersion: version,
  trustDomain: 'controlled.example',
  rootFingerprint: 'a'.repeat(64),
  hardware: { model: 'controlled-board', boardRevisionMin: 0, boardRevisionMax: 10 },
  bootloader: { minimumVersion: '1.2.0', maximumVersion: '1.10.0' },
  signatureProfiles: ['TC_OTA_ED25519_V1', 'TC_OTA_ES256_P1363_V1'],
  maximumArtifactBytes: 67108864,
  availableRamBytes: 0,
  availableFlashBytes: 9007199254740991,
  supportsAbSlots: false,
  supportsRangeDownload: false,
  supportsResumeDownload: false,
  protectedSecurityCounterBits: 0,
  compressionAlgorithms: ['NONE'],
  deltaModes: ['NONE'],
  propertyProfile: 'TC_PROPERTY_COMPOSITE_V1',
  evidenceReference: '受控测试来源/公开索引'
})
export const baselineSnapshot = (value = baseline(), revision = '2'): OtaTypeBaselineResponse => ({
  revision,
  baseline: value,
  baselineHash: publicSha(otaBaselineCanonicalBytes(value)),
  registeredAt: '2026-10-06T20:00:00.123456789Z',
  updatedAt: '2026-10-06T20:00:00.123456789Z'
})
export const publishedType = (changes = {}) => ({
  id: baselineScope.deviceTypeId,
  projectId: baselineScope.projectId,
  typeKey: 'public_type',
  name: '受控已发布类型',
  networkType: 'WIFI',
  payloadProtocol: 'MQTT',
  deviceKind: 'DIRECT',
  status: 'PUBLISHED',
  version: 1,
  productKey: baselineScope.productKey,
  createdAt: '2026-10-06T20:00:00Z',
  ...changes
})
