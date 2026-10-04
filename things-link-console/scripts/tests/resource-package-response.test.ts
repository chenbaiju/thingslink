import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { APIResponse, Page, Request, Response, Route } from '@playwright/test'
import { captureResourcePackageQuota } from '../../e2e/resource-package-response'

const quotaUrl = 'http://127.0.0.1:43123/api/v1/projects/owned-project/quota'
async function fixture(fetchError?: Error, body?: () => Promise<Buffer>, timeoutMs = 1000) {
  let match!: RegExp
  let forward!: (route: Route) => Promise<void>
  const stages: Array<[string, number | undefined]> = []
  const page = {
    route: async (m: RegExp, handler: typeof forward) => {
      match = m
      forward = handler
    }
  }
  const capture = await captureResourcePackageQuota(
    page as unknown as Page,
    quotaUrl,
    (stage, status) => stages.push([stage, status]),
    timeoutMs
  )
  const request = { method: () => 'GET' } as Request
  const bytes = Buffer.from(' { "limit": 5, "label": "原文" } \n')
  const response = {
    status: () => 207,
    body: body ?? (async () => bytes),
    headers: () => ({ 'content-type': 'application/json', 'x-original': 'preserved' })
  } as unknown as APIResponse
  let fulfillment: unknown
  let fetchOptions: unknown
  let continued = 0
  const route = {
    request: () => request,
    fetch: async (options: unknown) => {
      fetchOptions = options
      if (fetchError) throw fetchError
      return response
    },
    fulfill: async (options: unknown) => {
      fulfillment = options
    },
    continue: async () => {
      continued++
    }
  } as unknown as Route
  const browserResponse = { request: () => request, status: () => 207 } as Response
  return {
    match,
    forward,
    capture,
    route,
    response,
    bytes,
    browserResponse,
    stages,
    fulfillment: () => fulfillment,
    fetchOptions: () => fetchOptions,
    continued: () => continued
  }
}

test('native URL matching excludes other projects, APIs, hosts and query variants', async () => {
  const { match } = await fixture()
  assert.equal(match.test(quotaUrl), true)
  for (const other of [
    quotaUrl.replace('owned-project', 'other-project'),
    quotaUrl + '/history',
    quotaUrl.replace('/quota', '/devices'),
    quotaUrl.replace('43123', '43124'),
    quotaUrl + '?other=1'
  ]) {
    assert.equal(match.test(other), false)
  }
})

test('same real request keeps status, headers and exact body bytes; other response cannot borrow it', async () => {
  const f = await fixture()
  await f.forward(f.route)
  assert.deepEqual(f.fulfillment(), { response: f.response, body: f.bytes })
  assert.deepEqual(f.fetchOptions(), { maxRedirects: 0, timeout: 1000 })
  assert.deepEqual(f.capture.read(f.browserResponse), { limit: 5, label: '原文' })
  assert.deepEqual(f.stages, [['response', 207]])
  assert.throws(() => f.capture.read({ request: () => ({}), status: () => 207 } as Response))
  assert.throws(() =>
    f.capture.read({ request: () => f.browserResponse.request(), status: () => 200 } as Response)
  )
})

test('non-GET continues without capture or response replacement', async () => {
  const f = await fixture()
  await f.forward({ ...f.route, request: () => ({ method: () => 'POST' }) } as Route)
  assert.equal(f.continued(), 1)
  assert.equal(f.fulfillment(), undefined)
  assert.deepEqual(f.stages, [])
})

test('fetch failure and body read failure remain failures without leaking raw error details', async () => {
  for (const f of [
    await fixture(new Error('secret request headers')),
    await fixture(undefined, async () => {
      throw new Error('secret body')
    })
  ]) {
    await assert.rejects(f.forward(f.route), {
      message: 'Owned quota response capture failed or timed out'
    })
    assert.equal(f.fulfillment(), undefined)
    assert.deepEqual(f.stages, [['capture-failed', undefined]])
    assert.throws(() => f.capture.read(f.browserResponse))
  }
})

test('bounded body timeout fails and invalid JSON is never substituted', async () => {
  const stalled = await fixture(undefined, () => new Promise(() => {}), 10)
  await assert.rejects(stalled.forward(stalled.route), /failed or timed out/)
  assert.equal(stalled.fulfillment(), undefined)
  assert.deepEqual(stalled.stages, [['capture-failed', undefined]])
  const invalid = await fixture(undefined, async () => Buffer.from('invalid private body'))
  await invalid.forward(invalid.route)
  assert.throws(() => invalid.capture.read(invalid.browserResponse), {
    message: 'Real quota response is not valid JSON'
  })
})

test('late body completion after timeout cannot be recorded or fulfilled as success', async () => {
  let finish!: (bytes: Buffer) => void
  const pending = new Promise<Buffer>((resolve) => {
    finish = resolve
  })
  const f = await fixture(undefined, () => pending, 10)
  await assert.rejects(f.forward(f.route), /failed or timed out/)
  finish(Buffer.from('{"late":true}'))
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.equal(f.fulfillment(), undefined)
  assert.deepEqual(f.stages, [['capture-failed', undefined]])
  assert.throws(() => f.capture.read(f.browserResponse))
})
