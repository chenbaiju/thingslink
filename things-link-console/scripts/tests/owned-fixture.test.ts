import test from 'node:test'
import assert from 'node:assert/strict'
import { OwnedFixture } from '../../e2e/owned-fixture'

test('partial creation cleans only registered ownership in reverse dependency order', async () => {
  const ledger = new OwnedFixture()
  const actions: string[] = []
  ledger.own('type', async () => actions.push('type'))
  ledger.own('device', async () => actions.push('device'))
  const creationFailure = new Error('fixture create failed')
  await assert.rejects(
    ledger.finish({ error: creationFailure }),
    (error) => error === creationFailure
  )
  assert.deepEqual(actions, ['device', 'type'])
  await ledger.finish()
  assert.deepEqual(actions, ['device', 'type'])
})

test('cleanup failures do not suppress other cleanup or the original verification failure', async () => {
  const ledger = new OwnedFixture()
  const actions: string[] = []
  ledger.own('type', async () => actions.push('type'))
  ledger.own('device', async () => {
    actions.push('device')
    throw new Error('private request data')
  })
  ledger.own('credential', async () => {
    actions.push('credential')
    throw new Error('secret')
  })
  const primary = new Error('real assertion failure')
  await assert.rejects(ledger.finish({ error: primary }), (error: unknown) => {
    assert.ok(error instanceof AggregateError)
    assert.equal(error.errors[0], primary)
    assert.deepEqual(
      error.errors.slice(1).map((entry: Error) => entry.message),
      ['Owned fixture cleanup failed: credential', 'Owned fixture cleanup failed: device']
    )
    return true
  })
  assert.deepEqual(actions, ['credential', 'device', 'type'])
})

test('a passed body cannot hide a cleanup failure', async () => {
  const ledger = new OwnedFixture()
  ledger.own('device', async () => {
    throw new Error('secret')
  })
  await assert.rejects(ledger.finish(), AggregateError)
})
