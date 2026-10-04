#!/usr/bin/env node
/**
 * E2E-only controlled external signer stub implementing the ADR0139 contract.
 *
 * It exists so the real console can drive a real backend through
 * firmware publish -> READY -> campaign lifecycle on a real stack. It signs
 * exactly the `signingInput` bytes the platform provides (no canonical-JSON
 * reimplementation), using a throwaway Ed25519 release key committed under
 * `e2e/fixtures/` and explicitly test-only.
 *
 * Fail-closed guarantees:
 * - refuses to start without a >=32 byte bearer token or without a usable key;
 * - rejects unknown key versions, profile/fingerprint/trust-domain mismatches
 *   and malformed/duplicated/unknown request fields WITHOUT signing;
 * - logs only fixed classifications and the request id, never the token, the
 *   signing input, the request body or any key material.
 *
 * Only Node builtins are used.
 */
import { createPrivateKey, sign as edSign, timingSafeEqual } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:http'

const REQUEST_CONTRACT = 'tc-ota-sign-request/v1'
const RESPONSE_CONTRACT = 'tc-ota-sign-response/v1'
const REQUEST_FIELDS = [
  'contractVersion',
  'requestId',
  'trustDomain',
  'keyVersion',
  'signatureProfile',
  'keyFingerprint',
  'signingInput'
]
const MAX_BODY_BYTES = 262_144
const MAX_SIGNING_INPUT_BYTES = 65_536
const MAX_IDENTITY_CHARS = 1_024
const CANONICAL_BASE64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/

function parseArgs(argv) {
  const options = { port: 0, token: '', keys: '', portFile: '' }
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index]
    const value = argv[index + 1] ?? ''
    if (name === '--port') options.port = Number.parseInt(value, 10)
    else if (name === '--token') options.token = value
    else if (name === '--keys') options.keys = value
    else if (name === '--port-file') options.portFile = value
    else throw new Error(`unknown argument: ${name}`)
  }
  return options
}

/** Fixed classification only; never includes request or key material. */
function refuse(response, status, reason) {
  const body = Buffer.from(JSON.stringify({ error: reason }), 'utf8')
  response.writeHead(status, { 'content-type': 'application/json', 'content-length': body.length })
  response.end(body)
  process.stdout.write(`[signer-stub] refused ${reason}\n`)
}

function isPlainString(value) {
  return typeof value === 'string'
}

function decodeCanonicalBase64(value, maxBytes) {
  if (!isPlainString(value) || value.length === 0 || value.length % 4 !== 0) return null
  if (!CANONICAL_BASE64.test(value)) return null
  const decoded = Buffer.from(value, 'base64')
  if (decoded.length === 0 || decoded.length > maxBytes) return null
  if (decoded.toString('base64') !== value) return null
  return decoded
}

function main() {
  const options = parseArgs(process.argv.slice(2))
  // >=32 bytes (not characters) of entropy; fail closed otherwise.
  const tokenBytes = Buffer.from(options.token, 'utf8')
  if (tokenBytes.length < 32) throw new Error('signer token must be at least 32 bytes')
  if (options.keys === '') throw new Error('--keys is required')

  const fixture = JSON.parse(readFileSync(options.keys, 'utf8'))
  const keyVersion = fixture.releaseKeyVersion
  const profile = 'TC_OTA_ED25519_V1'
  const fingerprint = fixture.releaseFingerprint
  const spkiBase64 = fixture.releaseSpkiBase64
  const trustDomain = fixture.trustDomain
  if (!isPlainString(keyVersion) || !isPlainString(fingerprint) || !isPlainString(spkiBase64)) {
    throw new Error('key fixture is incomplete')
  }
  const privateKey = createPrivateKey({
    key: Buffer.from(fixture.releasePrivateKeyPkcs8Base64, 'base64'),
    format: 'der',
    type: 'pkcs8'
  })
  // The key map is intentionally closed: only the committed release key can sign.
  const keys = new Map([[keyVersion, { privateKey, spkiBase64, fingerprint, profile }]])

  const server = createServer((request, response) => {
    if (request.method !== 'POST' || request.url !== '/sign') {
      refuse(response, 404, 'NOT_FOUND')
      return
    }
    const authorization = request.headers.authorization ?? ''
    const expected = `Bearer ${options.token}`
    const provided = Buffer.from(authorization, 'utf8')
    const expectedBytes = Buffer.from(expected, 'utf8')
    if (provided.length !== expectedBytes.length || !timingSafeEqual(provided, expectedBytes)) {
      refuse(response, 401, 'UNAUTHORIZED')
      return
    }
    const chunks = []
    let total = 0
    request.on('data', (chunk) => {
      total += chunk.length
      if (total > MAX_BODY_BYTES) {
        request.destroy()
        refuse(response, 413, 'REQUEST_TOO_LARGE')
        return
      }
      chunks.push(chunk)
    })
    request.on('end', () => {
      let body
      try {
        body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
      } catch {
        refuse(response, 400, 'MALFORMED_REQUEST')
        return
      }
      if (body === null || typeof body !== 'object' || Array.isArray(body)) {
        refuse(response, 400, 'MALFORMED_REQUEST')
        return
      }
      const fields = Object.keys(body)
      if (
        fields.length !== REQUEST_FIELDS.length ||
        REQUEST_FIELDS.some((name) => !fields.includes(name))
      ) {
        refuse(response, 400, 'UNEXPECTED_FIELDS')
        return
      }
      if (body.contractVersion !== REQUEST_CONTRACT) {
        refuse(response, 400, 'CONTRACT_MISMATCH')
        return
      }
      if (
        ![
          body.requestId,
          body.trustDomain,
          body.keyVersion,
          body.signatureProfile,
          body.keyFingerprint
        ].every(
          (value) => isPlainString(value) && value.length > 0 && value.length <= MAX_IDENTITY_CHARS
        )
      ) {
        refuse(response, 400, 'INVALID_IDENTITY')
        return
      }
      const key = keys.get(body.keyVersion)
      if (key === undefined) {
        refuse(response, 400, 'UNKNOWN_KEY_VERSION')
        return
      }
      if (body.trustDomain !== trustDomain) {
        refuse(response, 400, 'UNKNOWN_TRUST_DOMAIN')
        return
      }
      if (body.signatureProfile !== key.profile) {
        refuse(response, 400, 'UNSUPPORTED_PROFILE')
        return
      }
      if (body.keyFingerprint !== key.fingerprint) {
        refuse(response, 400, 'FINGERPRINT_MISMATCH')
        return
      }
      const signingInput = decodeCanonicalBase64(body.signingInput, MAX_SIGNING_INPUT_BYTES)
      if (signingInput === null) {
        refuse(response, 400, 'INVALID_SIGNING_INPUT')
        return
      }
      const signature = edSign(null, signingInput, key.privateKey)
      const receipt = `e2e-signer-stub:${body.requestId}`.slice(0, 128)
      const payload = Buffer.from(
        JSON.stringify({
          contractVersion: RESPONSE_CONTRACT,
          requestId: body.requestId,
          keyVersion: body.keyVersion,
          signatureProfile: body.signatureProfile,
          spkiBase64: key.spkiBase64,
          signatureBase64: signature.toString('base64'),
          receipt
        }),
        'utf8'
      )
      response.writeHead(200, {
        'content-type': 'application/json',
        'content-length': payload.length
      })
      response.end(payload)
      process.stdout.write(`[signer-stub] signed requestId=${body.requestId}\n`)
    })
  })

  server.listen(options.port, '127.0.0.1', () => {
    const port = server.address().port
    if (options.portFile !== '') writeFileSync(options.portFile, `${port}\n`, 'utf8')
    process.stdout.write(`[signer-stub] listening on 127.0.0.1:${port}\n`)
  })
}

main()
