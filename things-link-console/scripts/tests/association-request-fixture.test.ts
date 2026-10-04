import { test } from 'node:test'
import assert from 'node:assert/strict'
import { AssociationRequester } from '../../e2e/association-request-fixture'
import { OwnedFixture } from '../../e2e/owned-fixture'

test('bulk fixture calls, including queued calls, start at least 250ms apart', async () => {
  let elapsed = 0
  const waits: number[] = [],
    starts: number[] = []
  const requests = new AssociationRequester(async (milliseconds) => {
    waits.push(milliseconds)
    elapsed += milliseconds
  })
  const values = await Promise.all(
    Array.from({ length: 21 }, (_, index) =>
      requests.request('task.fetchCreateTaskJob', async () => {
        starts.push(elapsed)
        return { ok: true, value: index }
      })
    )
  )
  assert.deepEqual(
    values,
    Array.from({ length: 21 }, (_, index) => index)
  )
  assert.deepEqual(waits, Array(21).fill(250))
  assert.deepEqual(
    starts,
    Array.from({ length: 21 }, (_, index) => (index + 1) * 250)
  )
})

test('denial remains failure without retry; all independently paced cleanups retain primary first', async () => {
  const calls: string[] = [],
    waits: number[] = []
  const requests = new AssociationRequester(async (milliseconds) => {
    waits.push(milliseconds)
  })
  const owned = new OwnedFixture()
  for (const label of ['last', 'middle', 'first'])
    owned.own(label, () =>
      requests.request(label, async () => {
        calls.push(label)
        return label === 'middle'
          ? { ok: false, code: 10029, status: 429 }
          : { ok: true, value: undefined }
      })
    )
  let primary: Error | undefined
  try {
    await requests.request('command.create', async () => {
      calls.push('command.create')
      return { ok: false, code: 10029, status: 429 }
    })
  } catch (failure) {
    primary = failure as Error
  }
  assert.ok(primary)
  const receipt = { ...requests.failure }
  await assert.rejects(owned.finish({ error: primary }), (failure: AggregateError) => {
    assert.equal(failure.errors[0], primary)
    assert.equal(failure.errors[1].message, 'Owned fixture cleanup failed: middle')
    return true
  })
  assert.deepEqual(calls, ['command.create', 'first', 'middle', 'last'])
  assert.deepEqual(waits, [250, 250, 250, 250])
  assert.deepEqual(receipt, { operation: 'command.create', code: 10029, status: 429 })
})

test('safe failure receipt drops nonnumeric or out-of-range data and never includes payloads', async () => {
  const requests = new AssociationRequester(async () => {})
  await assert.rejects(
    requests.request('task.create', async () => ({
      ok: false,
      code: 'secret@example.test',
      status: 99999,
      password: 'never-serialize-this',
      body: { token: 'private-proof' }
    })),
    { message: 'Controlled association API failed: task.create; code=null; status=null' }
  )
  assert.deepEqual(requests.failure, { operation: 'task.create', code: null, status: null })
})
