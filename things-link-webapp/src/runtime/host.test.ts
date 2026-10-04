import { describe, expect, it } from 'vitest'
import resources from '../../resources/registry.json'
import { validateHostCandidate } from './host'

function descriptor() {
  return { formatVersion: 'tc.webapp-host/v1', hostVersion: '1.0.0', artifactDigestAlgorithm: 'SHA-256',
    artifactDigest: 'a'.repeat(64), supportedApplicationFormats: ['tc.application/v1'], supportedSchemas: ['tc.dashboard/v1'],
    components: ['TEXT', 'IMAGE', 'DEVICE_SELECTOR', 'VALUE_CARD', 'STATUS', 'GAUGE', 'TABLE', 'JSON_VIEW', 'LINE_CHART', 'ALARM_LIST'].map(kind => ({ kind, componentVersion: '1.0.0' })),
    resources: structuredClone(resources), manifest: { id: '/app/', startUrl: '/app/', scope: '/app/' } }
}
describe('unpublished host candidate', () => {
  it('accepts only the exact built-in capability and resource registration', () => {
    expect(validateHostCandidate(descriptor()).hostVersion).toBe('1.0.0')
    const altered = descriptor(); altered.resources[0].digest = 'b'.repeat(64)
    expect(() => validateHostCandidate(altered)).toThrow()
  })
  it('rejects fake capabilities and malformed artifact identity', () => {
    const unsupported = descriptor(); unsupported.components.push({ kind: 'COMMAND_BUTTON', componentVersion: '1.0.0' })
    expect(() => validateHostCandidate(unsupported)).toThrow()
    expect(() => validateHostCandidate({ ...descriptor(), artifactDigest: 'not-verified' })).toThrow()
    expect(() => validateHostCandidate({ ...descriptor(), additional: true })).toThrow()
  })
})

import { vi } from 'vitest'
import { loadExactHostCandidate } from './host'
it('freezes one discovered artifact and refuses a different source receipt before business use', async () => {
  const source = 'c'.repeat(64)
  const fetcher = vi.fn(async (path: RequestInfo | URL) => {
    if (path === '/app/host-candidate.json') return Response.json(descriptor())
    if (String(path).endsWith('/source-receipt.json')) return Response.json({ sourceDigest: source, artifactDigest: 'a'.repeat(64) })
    return Response.json(descriptor())
  })
  expect((await loadExactHostCandidate(source, fetcher)).artifactDigest).toBe('a'.repeat(64))
  expect(fetcher.mock.calls.map(call => call[0])).toEqual(['/app/host-candidate.json', `/app/releases/${'a'.repeat(64)}/source-receipt.json`, `/app/releases/${'a'.repeat(64)}/host-candidate.json`])
  await expect(loadExactHostCandidate('d'.repeat(64), fetcher)).rejects.toThrow('重新打开')
})
it('controlled code never reads latest and rejects descriptor identity drift', async () => {
  const source = 'c'.repeat(64)
  const fetcher = vi.fn(async (path: RequestInfo | URL) => String(path).endsWith('/source-receipt.json')
    ? Response.json({ sourceDigest: source, artifactDigest: 'b'.repeat(64) }) : Response.json(descriptor()))
  await expect(loadExactHostCandidate(source, fetcher, 'b'.repeat(64))).rejects.toThrow('重新打开')
  expect(fetcher.mock.calls.every(call => String(call[0]).startsWith(`/app/releases/${'b'.repeat(64)}/`))).toBe(true)
})
it('rejects oversized or extra-field source receipts and never guesses a fallback candidate', async () => {
  const source = 'c'.repeat(64)
  for (const response of [new Response('x'.repeat(65537)), Response.json({ sourceDigest: source, artifactDigest: 'a'.repeat(64), fallback: true })]) {
    const fetcher = vi.fn(async () => response)
    await expect(loadExactHostCandidate(source, fetcher, 'a'.repeat(64))).rejects.toThrow()
    expect(fetcher).toHaveBeenCalledTimes(1)
  }
})
it('aborts a stalled public identity lookup within its own bound without retry', async () => {
  vi.useFakeTimers()
  try {
    const fetcher = vi.fn((_path: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new Error('aborted public identity')), { once: true })
    }))
    const pending = expect(loadExactHostCandidate('c'.repeat(64), fetcher)).rejects.toThrow('aborted')
    await vi.advanceTimersByTimeAsync(8000)
    await pending
    expect(fetcher).toHaveBeenCalledOnce()
  } finally { vi.useRealTimers() }
})

it.each(['1.1.0', '1.1.1'])('recognizes explicit host %s with its exact patch components', hostVersion => {
  expect(validateHostCandidate({ ...descriptor(), hostVersion, components: descriptor().components.map(c => ({ ...c, componentVersion: '1.0.1' })) }).hostVersion).toBe(hostVersion)
  expect(() => validateHostCandidate({ ...descriptor(), hostVersion })).toThrow()
  expect(() => validateHostCandidate({ ...descriptor(), hostVersion: '1.2.0' })).toThrow()
})
