import test from 'node:test'
import assert from 'node:assert/strict'
import { ruleDeliveryQuery, validateRuleDeliveries } from '../../e2e/rule-notification-fixture'

const scope = {
  projectId: '11111111-1111-4111-8111-111111111111',
  deviceId: '22222222-2222-4222-8222-222222222222',
  ruleId: '33333333-3333-4333-8333-333333333333'
}
const row = {
  ...scope,
  id: '44444444-4444-4444-8444-444444444444',
  ruleVersionId: '55555555-5555-4555-8555-555555555555',
  messageId: '66666666-6666-4666-8666-666666666666',
  status: 'RETRY_SCHEDULED',
  attemptCount: 1,
  nextAttemptAt: '2026-10-01T12:01:00Z',
  deliveredAt: null,
  lastErrorCode: 'SMTP_FAILURE'
}

test('only an exact owned UUID tuple can generate the readonly bounded query', () => {
  const query = ruleDeliveryQuery(scope)
  assert.match(query, /^SELECT /)
  for (const id of Object.values(scope)) assert.ok(query.includes(`'${id}'::uuid`))
  assert.match(query, /LIMIT 101/)
  assert.doesNotMatch(query, /\b(?:recipient|subject|body|password|secret|INSERT|UPDATE|DELETE)\b/i)
  for (const key of Object.keys(scope)) {
    assert.throws(() =>
      ruleDeliveryQuery({ ...scope, [key]: "'; DELETE FROM rule_notification_delivery; --" })
    )
  }
})

test('foreign, duplicate, truncated and malformed delivery facts cannot qualify', () => {
  assert.deepEqual(validateRuleDeliveries([row], scope), [row])
  assert.deepEqual(validateRuleDeliveries([], scope), [])
  for (const key of ['projectId', 'deviceId', 'ruleId']) {
    assert.throws(() => validateRuleDeliveries([{ ...row, [key]: row.id }], scope))
  }
  assert.throws(() => validateRuleDeliveries([row, row], scope))
  assert.throws(() => validateRuleDeliveries(Array(101).fill(row), scope))
  for (const modified of [
    { ...row, attemptCount: true },
    { ...row, attemptCount: 1.5 },
    { ...row, attemptCount: 4 },
    { ...row, messageId: 'not-an-id' },
    { ...row, nextAttemptAt: 'not-an-instant' },
    { ...row, recipient: 'must-not-leak@example.test' }
  ])
    assert.throws(() => validateRuleDeliveries([modified], scope))
})

test('delivery retry and terminal evidence remain distinct from rule execution success', () => {
  assert.equal(validateRuleDeliveries([row], scope)[0]?.status, 'RETRY_SCHEDULED')
  const delivered = {
    ...row,
    status: 'DELIVERED',
    attemptCount: 2,
    nextAttemptAt: null,
    deliveredAt: '2026-10-01T12:01:01Z',
    lastErrorCode: null
  }
  assert.equal(validateRuleDeliveries([delivered], scope)[0]?.attemptCount, 2)
})
