import { expect, it } from 'vitest'
import {
  isOtaBaselineBody,
  isOtaBaselineResponse,
  otaBaselineRevision
} from '@/features/ota/baseline-registration-model'
import { baseline, baselineScope, baselineSnapshot } from './helpers/otaBaselinePublicFixture'
const verifiedScope = { ...baselineScope, serverTenantId: baselineScope.tenantId }
it('项目租户与登录租户独立；首响应只校验UUID，已验证server tenant才精确绑定', () => {
  const target = {
    projectId: baselineScope.projectId,
    deviceTypeId: baselineScope.deviceTypeId,
    productKey: baselineScope.productKey
  }
  const value = { ...baseline(), tenantId: '44444444-4444-4444-8444-444444444444' }
  expect(isOtaBaselineResponse(baselineSnapshot(value), target)).toBe(true)
  expect(
    isOtaBaselineResponse(baselineSnapshot(value), { ...target, serverTenantId: value.tenantId })
  ).toBe(true)
  expect(isOtaBaselineResponse(baselineSnapshot(value), verifiedScope)).toBe(false)
  expect(isOtaBaselineResponse(baselineSnapshot({ ...value, tenantId: 'wrong' }), target)).toBe(
    false
  )
  expect(
    isOtaBaselineResponse(baselineSnapshot(value), { ...target, serverTenantId: 'wrong' })
  ).toBe(false)
})
it('精确22字段声明与五字段快照保留false/0，完整long修订与数值版本比较', () => {
  expect(isOtaBaselineBody(baseline(), verifiedScope)).toBe(true)
  expect(isOtaBaselineResponse(baselineSnapshot(), verifiedScope)).toBe(true)
  expect(
    isOtaBaselineResponse(baselineSnapshot(baseline(), '9223372036854775807'), verifiedScope)
  ).toBe(true)
  expect(otaBaselineRevision('0')).toBe(true)
})
it.each(['-0', '00', '1.0', '1e0', '9223372036854775808', '', 1, null])(
  '修订闭集Java long字符串 %j',
  (value) => {
    expect(otaBaselineRevision(value)).toBe(false)
  }
)
it.each([
  (b: any) => {
    b.tenantId = '44444444-4444-4444-8444-444444444444'
  },
  (b: any) => {
    b.projectId = 'wrong'
  },
  (b: any) => {
    b.deviceTypeId = 'wrong'
  },
  (b: any) => {
    b.productKey = 'other'
  },
  (b: any) => {
    b.privateKey = 'PRIVATE'
  },
  (b: any) => {
    b.contractVersion = 'other'
  },
  (b: any) => {
    b.baselineVersion = 0
  },
  (b: any) => {
    b.baselineVersion = 9007199254740992
  },
  (b: any) => {
    b.trustDomain = '../domain'
  },
  (b: any) => {
    b.rootFingerprint = 'A'.repeat(64)
  },
  (b: any) => {
    b.hardware.model = null
  },
  (b: any) => {
    b.hardware.unknown = true
  },
  (b: any) => {
    b.hardware.boardRevisionMin = 11
  },
  (b: any) => {
    b.hardware.boardRevisionMax = -1
  },
  (b: any) => {
    b.bootloader.minimumVersion = '1.10.0'
    b.bootloader.maximumVersion = '1.2.0'
  },
  (b: any) => {
    b.bootloader.minimumVersion = '01.0.0'
  },
  (b: any) => {
    b.bootloader.maximumVersion = '2147483648.0.0'
  },
  (b: any) => {
    b.signatureProfiles = []
  },
  (b: any) => {
    b.signatureProfiles = ['TC_OTA_ED25519_V1', 'TC_OTA_ED25519_V1']
  },
  (b: any) => {
    b.signatureProfiles.reverse()
  },
  (b: any) => {
    b.signatureProfiles = ['future']
  },
  (b: any) => {
    b.maximumArtifactBytes = 67108865
  },
  (b: any) => {
    b.maximumArtifactBytes = 0
  },
  (b: any) => {
    b.availableRamBytes = -1
  },
  (b: any) => {
    b.availableFlashBytes = 9007199254740992
  },
  (b: any) => {
    b.supportsAbSlots = 'false'
  },
  (b: any) => {
    b.supportsRangeDownload = 0
  },
  (b: any) => {
    b.supportsResumeDownload = true
  },
  (b: any) => {
    b.protectedSecurityCounterBits = 54
  },
  (b: any) => {
    b.compressionAlgorithms = []
  },
  (b: any) => {
    b.deltaModes = ['NONE', 'NONE']
  },
  (b: any) => {
    b.propertyProfile = 'future'
  },
  (b: any) => {
    b.evidenceReference = ' leading'
  },
  (b: any) => {
    b.evidenceReference = 'trailing\u00a0'
  },
  (b: any) => {
    b.evidenceReference = 'bad\u0085control'
  },
  (b: any) => {
    b.evidenceReference = '\ud800'
  },
  (b: any) => {
    b.evidenceReference = '😀'.repeat(257)
  }
])('拒绝范围、编码、边界、跨轴、缺能力冒充与未知字段', (change) => {
  const value = baseline()
  change(value)
  expect(isOtaBaselineBody(value, verifiedScope)).toBe(false)
})
it('声明任一字段缺失/null都不能默认提升；硬件与引导子闭集亦必填', () => {
  for (const name of Object.keys(baseline())) {
    const value: any = baseline()
    delete value[name]
    expect(isOtaBaselineBody(value, verifiedScope)).toBe(false)
    value[name] = null
    expect(isOtaBaselineBody(value, verifiedScope)).toBe(false)
  }
  for (const name of Object.keys(baselineSnapshot())) {
    const value: any = baselineSnapshot()
    delete value[name]
    expect(isOtaBaselineResponse(value, verifiedScope)).toBe(false)
  }
})
it('来源预算按码点而非UTF16长度，三轴按数值有序；摘要编码與UTC纳秒不能放宽', () => {
  const value = baseline()
  value.evidenceReference = '😀'.repeat(256)
  value.bootloader.maximumVersion = '2147483647.0.0'
  expect(isOtaBaselineBody(value, verifiedScope)).toBe(true)
  const state = baselineSnapshot()
  expect(
    isOtaBaselineResponse(
      {
        ...state,
        registeredAt: '2026-10-06T20:00:00.123Z',
        updatedAt: '2026-10-06T20:00:00.123000000Z'
      },
      baselineScope
    )
  ).toBe(true)
  expect(
    isOtaBaselineResponse(
      {
        ...state,
        registeredAt: '2026-10-06T20:00:00.999999999Z',
        updatedAt: '2026-10-06T20:00:01Z'
      },
      baselineScope
    )
  ).toBe(true)
  for (const change of [
    { updatedAt: '2026-10-06T20:00:00.123400000Z' },
    { registeredAt: '2026-02-30T00:00:00Z' },
    { updatedAt: '2026-10-06T20:00:00+00:00' },
    { revision: '0' },
    { baselineHash: 'a'.repeat(63) },
    { baselineHash: null },
    { privateKey: 'PRIVATE' }
  ])
    expect(isOtaBaselineResponse({ ...state, ...change }, verifiedScope)).toBe(false)
})
