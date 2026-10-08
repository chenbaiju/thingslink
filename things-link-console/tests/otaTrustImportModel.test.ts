import { expect, it } from 'vitest'
import {
  isOtaTrustState,
  parsePublicTrustMaterial,
  trustImportBody,
  trustImportRevision
} from '@/features/ota/trust-import-model'
import { bytes, material, snapshot } from './helpers/otaTrustPublicFixture'

it('两种明确公开封套接受完整long修订，最终只产生冻结的三字段正文', () => {
  const source = parsePublicTrustMaterial(bytes(material(null)))
  const body = trustImportBody(source, '9223372036854775807')
  expect(Object.keys(body).sort()).toEqual(['bundle', 'expectedRevision', 'signature'])
  expect(body.expectedRevision).toBe('9223372036854775807')
  expect(Object.isFrozen(body)).toBe(true)
  expect(Object.isFrozen(body.bundle.keys[0])).toBe(true)
  expect(() => trustImportBody(parsePublicTrustMaterial(bytes(material('2'))), '1')).toThrow()
  expect(parsePublicTrustMaterial(bytes(material('9223372036854775807'))).expectedRevision).toBe(
    '9223372036854775807'
  )
})
it.each(['-0', '00', '1.0', '1e0', '9223372036854775808', '', ' 1', 1, null])(
  '修订必须是闭集Java long十进制字符串 %j',
  (value) => {
    expect(trustImportRevision(value)).toBe(false)
    expect(() =>
      parsePublicTrustMaterial(bytes({ ...material(), expectedRevision: value }))
    ).toThrow()
  }
)
it.each([
  (m: any) => {
    m.privateKey = 'PRIVATE_SECRET'
  },
  (m: any) => {
    m.bundle.rootFingerprint = 'a'.repeat(64)
  },
  (m: any) => {
    m.signature = null
  },
  (m: any) => {
    m.signature = m.signature.slice(0, -1)
  },
  (m: any) => {
    m.bundle.bundleVersion = 0
  },
  (m: any) => {
    m.bundle.bundleVersion = 9007199254740992
  },
  (m: any) => {
    m.bundle.trustDomain = '../root'
  },
  (m: any) => {
    m.bundle.contractVersion = 'future'
  },
  (m: any) => {
    m.bundle.keys = []
  },
  (m: any) => {
    m.bundle.keys[0].state = 'VERIFY_ONLY'
  },
  (m: any) => {
    m.bundle.keys[0].notAfter = m.bundle.keys[0].notBefore
  },
  (m: any) => {
    m.bundle.keys[0].notAfter = 253402300800
  },
  (m: any) => {
    m.bundle.keys[0].fingerprint = 'A'.repeat(64)
  },
  (m: any) => {
    m.bundle.keys[0].spki = '-----BEGIN PRIVATE KEY-----'
  },
  (m: any) => {
    m.bundle.keys[0].spki = Buffer.from(
      '302e020100300506032b657004220420' + '01'.repeat(32),
      'hex'
    ).toString('base64')
  },
  (m: any) => {
    m.bundle.keys[0].signatureProfile = 'TC_OTA_ES256_P1363_V1'
  },
  (m: any) => {
    m.bundle.keys.push({ ...m.bundle.keys[0], state: 'PREPARED' })
  },
  (m: any) => {
    m.bundle.keys.push({ ...m.bundle.keys[0], keyVersion: 'second', fingerprint: 'a'.repeat(64) })
  }
])('闭集签包拒绝秘密、编码、范围和重复/多ACTIVE结构', (change) => {
  const source = material()
  change(source)
  expect(() => parsePublicTrustMaterial(bytes(source))).toThrow()
})
it('模型不能通过JSON重新序列化洗掉重复字段、指数或负零', () => {
  const raw = JSON.stringify(material())
  expect(() =>
    parsePublicTrustMaterial(bytes(raw.replace('"bundleVersion":1', '"bundleVersion":1e0')))
  ).toThrow()
  expect(() =>
    parsePublicTrustMaterial(bytes(raw.replace('"notBefore":1', '"notBefore":-0')))
  ).toThrow()
  expect(() =>
    parsePublicTrustMaterial(
      bytes(
        raw.replace('"expectedRevision":"0"', '"expectedRevision":"0","\\u0065xpectedRevision":"0"')
      )
    )
  ).toThrow()
})
it('最终正文执行额外封套预算，不能绕过整包上限', () => {
  expect(() => trustImportBody({ ...material(null), signature: 'a'.repeat(65536) }, '0')).toThrow()
})
it('实际九字段公开DTO接受精确纳秒同instant与跨秒，拒绝倒序、超safe policy及未知能力', () => {
  const state = snapshot()
  expect(isOtaTrustState(state, 'controlled.example')).toBe(true)
  expect(
    isOtaTrustState(
      {
        ...state,
        createdAt: '2026-10-06T20:00:00.123Z',
        updatedAt: '2026-10-06T20:00:00.123000000Z'
      },
      state.trustDomain
    )
  ).toBe(true)
  expect(
    isOtaTrustState(
      { ...state, createdAt: '2026-10-06T20:00:00.999999999Z', updatedAt: '2026-10-06T20:00:01Z' },
      state.trustDomain
    )
  ).toBe(true)
  for (const change of [
    { updatedAt: '2026-10-06T20:00:00.123400000Z' },
    { policyRevision: '9007199254740992' },
    { bundleVersion: '9007199254740992' },
    { revision: '0' },
    { revision: 1 },
    { rootFingerprint: null },
    { privateKey: 'SECRET' },
    { createdAt: '2026-02-30T00:00:00Z' },
    { updatedAt: '2026-10-06T20:00:00+00:00' },
    { trustDomain: 'other' }
  ])
    expect(isOtaTrustState({ ...state, ...change }, state.trustDomain)).toBe(false)
  for (const name of Object.keys(state)) {
    const missing: any = { ...state }
    delete missing[name]
    expect(isOtaTrustState(missing, state.trustDomain)).toBe(false)
  }
  expect(isOtaTrustState({ ...state, trustDomain: '../invalid' }, '../invalid')).toBe(false)
  expect(
    isOtaTrustState(
      { ...state, revision: '9223372036854775807', policyRevision: '9007199254740991' },
      state.trustDomain
    )
  ).toBe(true)
})
