import { createHash } from 'node:crypto'
import type { PublicTrustMaterial } from '@/features/ota/trust-import-model'
import { trustBundleCanonicalBytes } from '@/features/ota/trust-import-model'

// 仅公开SPKI结构材料；不保存私钥，不称为根验真或真实签发资格。
export const publicSpki = Buffer.from('302a300506032b6570032100' + '01'.repeat(32), 'hex')
export const publicSha = (bytes: ArrayBuffer | Uint8Array) =>
  createHash('sha256').update(new Uint8Array(bytes)).digest('hex')
export const material = (revision: string | null = '0', version = 1): PublicTrustMaterial => ({
  ...(revision === null ? {} : { expectedRevision: revision }),
  bundle: {
    contractVersion: 'tc-ota-trust-bundle/v1',
    trustDomain: 'controlled.example',
    bundleVersion: version,
    keys: [
      {
        keyVersion: 'public/key-v1',
        signatureProfile: 'TC_OTA_ED25519_V1',
        spki: publicSpki.toString('base64'),
        fingerprint: publicSha(publicSpki),
        state: 'ACTIVE',
        notBefore: 1,
        notAfter: 253402300799
      }
    ]
  },
  signature: Buffer.alloc(64).toString('base64')
})
export const bytes = (value: unknown) =>
  new TextEncoder().encode(typeof value === 'string' ? value : JSON.stringify(value))
export const snapshot = (source = material(), revision = '1') => ({
  trustDomain: source.bundle.trustDomain,
  revision,
  bundleVersion: String(source.bundle.bundleVersion),
  policyRevision: '1',
  rootProfile: 'TC_OTA_ED25519_V1',
  rootFingerprint: 'a'.repeat(64),
  bundleSha256: publicSha(trustBundleCanonicalBytes(source)),
  createdAt: '2026-10-06T20:00:00.123456789Z',
  updatedAt: '2026-10-06T20:00:00.123456789Z'
})
