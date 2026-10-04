import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import {
  timeTriggerConfig,
  timeFactsSql,
  validateTimeFacts,
  type TimeFacts,
  type TimeFixtureScope
} from '../../e2e/time-automation-fixture'
const projectId = '0198c000-0000-7000-8000-000000000001',
  automationId = '0198c000-0000-7000-8000-000000000002',
  versionId = '0198c000-0000-7000-8000-000000000003'
const s: TimeFixtureScope = { projectId, automationId, versionId, type: 'ONE_SHOT' }
test('actual due time is computed from supplied database clock with setup headroom', () => {
  const c = timeTriggerConfig('ONE_SHOT', projectId, '2026-10-01T23:59:45.125Z')
  assert.deepEqual(c, {
    deviceId: projectId,
    payload: { temperature: 27 },
    runAt: '2026-10-02T00:00:31.000Z'
  })
  assert.throws(() => timeTriggerConfig('ONE_SHOT', projectId, 'not-a-date'))
  assert.throws(() => timeTriggerConfig('ONE_SHOT', "' OR TRUE --", '2026-10-01T00:00:00Z'))
})
test('cron contains one seconds value per minute and explicit UTC, with no accelerated schedule', () => {
  const c = timeTriggerConfig('CRON', projectId, '2026-10-01T23:59:45.125Z')
  assert.deepEqual(c, {
    deviceId: projectId,
    payload: { temperature: 27 },
    cronExpression: '31 * * * * *',
    timezone: 'UTC'
  })
})
test('time facts read exact project/automation/version with bounded scope and no state mutation', () => {
  const sql = timeFactsSql(s)
  assert.match(sql, /LIMIT 101/)
  assert.doesNotMatch(sql, /UPDATE|INSERT|DELETE|input_snapshot|recipient|lease_token/)
  for (const value of [projectId, automationId, versionId]) assert.ok(sql.includes(value))
  assert.throws(() => timeFactsSql({ ...s, versionId: "' OR TRUE --" }))
})
const good: TimeFacts = {
  databaseNow: '2026-10-02T00:00:40Z',
  nextFireAt: null,
  state: { consumed: true, floor: '2026-10-02T00:00:31.000001Z' },
  executions: [
    {
      id: versionId,
      sourceEventId: null,
      status: 'DISPATCHED',
      reasonCode: null,
      attemptCount: 1,
      reservations: 1,
      triggerType: 'ONE_SHOT',
      scheduledFireAt: '2026-10-02T00:00:31Z',
      occurredAt: '2026-10-02T00:00:31Z'
    }
  ]
}
test('durable time receipt is separate from a property source; rejects wrong type, source or schedule provenance', () => {
  assert.deepEqual(validateTimeFacts(good, s), good)
  for (const row of [
    { ...good.executions[0], sourceEventId: projectId },
    { ...good.executions[0], triggerType: 'CRON' },
    { ...good.executions[0], occurredAt: '2026-10-02T00:00:32Z' },
    { ...good.executions[0], reservations: 2 }
  ])
    assert.throws(() => validateTimeFacts({ ...good, executions: [row] }, s))
})
test('partial, extra/private, malformed or duplicate facts cannot qualify a time journey', () => {
  for (const value of [
    null,
    { ...good, recipient: 'private' },
    { ...good, state: { consumed: true, floor: 'bad' } },
    { ...good, executions: [good.executions[0], good.executions[0]] },
    { ...good, executions: Array(101).fill(good.executions[0]) }
  ])
    assert.throws(() => validateTimeFacts(value, s))
})
