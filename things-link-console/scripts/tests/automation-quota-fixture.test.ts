import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import {
  installQuotaSql,
  setQuotaSql,
  restoreQuotaSql,
  automationFactsSql,
  validateAutomationFacts,
  prepareAutomationQuota,
  removeOwnedRoleSql,
  restoreAutomationQuota,
  type QuotaFixtureScope
} from '../../e2e/automation-quota-fixture'
const projectId = '0198c000-0000-7000-8000-000000000001'
const tenantId = '0198c000-0000-7000-8000-000000000002'
const originalPolicyId = '0198c000-0000-7000-8000-000000000003'
const policyId = '0198c000-0000-7000-8000-000000000004'
const operationId = '0198c000-0000-7000-8000-000000000005'
const scope: QuotaFixtureScope = {
  projectId,
  tenantId,
  originalPolicyId,
  policyId,
  role: `aq_e2e_${policyId.replaceAll('-', '')}`
}
test('owned CLI tenant v4 is accepted while API resources and SQL identifiers stay fenced', () => {
  const cliTenant = 'bda96e82-1c9a-4b7d-a208-4db8d9082960'
  const cli = { ...scope, tenantId: cliTenant }
  assert.ok(installQuotaSql(cli).includes(`WHERE id='${cliTenant}' FOR UPDATE`))
  assert.ok(restoreQuotaSql(cli).includes(`WHERE id='${cliTenant}' AND quota_policy_id=`))
  for (const tenantId of [
    "' OR TRUE --",
    `${cliTenant}suffix`,
    cliTenant.toUpperCase(),
    'bda96e82-1c9a-1b7d-a208-4db8d9082960'
  ]) {
    assert.throws(() => installQuotaSql({ ...scope, tenantId }))
  }
  assert.throws(() => installQuotaSql({ ...scope, projectId: cliTenant }))
  assert.throws(() => installQuotaSql({ ...scope, policyId: cliTenant }))
  assert.throws(() => setQuotaSql(scope, cliTenant, 1, 1))
})
test('quota fixture refuses foreign identifiers and unbounded budget', () => {
  for (const method of [installQuotaSql, restoreQuotaSql]) {
    assert.throws(() => method({ ...scope, tenantId: "' OR TRUE --" }))
    assert.throws(() => method({ ...scope, policyId: originalPolicyId }))
    assert.throws(() => method({ ...scope, role: 'thingslink_app' }))
  }
  for (const limit of [-1, 3, Infinity, 0.5])
    assert.throws(() => setQuotaSql(scope, operationId, 1, limit))
  assert.throws(() => setQuotaSql(scope, operationId, 0, 1))
})
test('fixture copies complete existing limits, leaves default zero and guards licensing/assignment', () => {
  const sql = installQuotaSql(scope)
  assert.match(sql, /BEGIN;[\s\S]*COMMIT;/)
  assert.match(sql, /entitlement_mode='COMMERCIAL'/)
  assert.doesNotMatch(sql, /UPDATE sys_deployment|UPDATE sys_quota_policy|DISABLE TRIGGER|TRUNCATE/)
  assert.match(sql, /to_jsonb\(q\)/)
  assert.match(sql, /'automation_execution_daily_limit',0/)
  assert.match(sql, /CREATE ROLE aq_e2e_[a-f0-9]+ NOLOGIN/)
  assert.match(sql, /GRANT thingslink_quota_operator TO aq_e2e_/)
  assert.ok(sql.includes(`WHERE id='${tenantId}' AND quota_policy_id='${originalPolicyId}'`))
})
test('real operator CAS and cleanup preserve immutable audit; unknown assignment is refused', () => {
  const sql = setQuotaSql(scope, operationId, 1, 1)
  assert.match(sql, /SET SESSION AUTHORIZATION aq_e2e_/)
  assert.ok(sql.includes(`automation_quota_set('${operationId}','${policyId}',1,1)`))
  const restore = restoreQuotaSql(scope)
  assert.match(restore, /refuse unknown assignment/)
  assert.ok(restore.includes(`WHERE id='${tenantId}' AND quota_policy_id='${policyId}'`))
  assert.doesNotMatch(
    restore,
    /sys_automation_quota_operation|sys_audit_log|sys_automation_quota_reservation|DISABLE TRIGGER/
  )
})
test('execution projection is read only, tightly scoped and bounded; reject duplicate or private fields', () => {
  const sql = automationFactsSql(projectId, operationId)
  assert.match(sql, /LIMIT 101/)
  assert.doesNotMatch(sql, /UPDATE|DELETE|INSERT|recipient|input_snapshot/)
  assert.ok(sql.includes(`e.project_id='${projectId}' AND e.automation_id='${operationId}'`))
  const row = {
    id: operationId,
    sourceEventId: policyId,
    status: 'DISPATCHED',
    reasonCode: null,
    attemptCount: 1,
    reservations: 1
  }
  assert.deepEqual(validateAutomationFacts([row]), [row])
  for (const rows of [
    [row, row],
    [{ ...row, reservations: 2 }],
    [{ ...row, sourceEventId: 'bad' }],
    [{ ...row, recipient: 'private' }],
    Array(101).fill(row)
  ])
    assert.throws(() => validateAutomationFacts(rows))
})
test('missing explicit fixture authority fails before invoking Docker', async () => {
  const before = process.env.E2E_AUTOMATION_FIXTURE
  delete process.env.E2E_AUTOMATION_FIXTURE
  const email = process.env.E2E_OWNER_EMAIL
  process.env.E2E_OWNER_EMAIL = 'e2e-owner-low-contract@example.com'
  try {
    await assert.rejects(prepareAutomationQuota(projectId), /isolated selected runner/)
  } finally {
    if (before === undefined) delete process.env.E2E_AUTOMATION_FIXTURE
    else process.env.E2E_AUTOMATION_FIXTURE = before
    if (email === undefined) delete process.env.E2E_OWNER_EMAIL
    else process.env.E2E_OWNER_EMAIL = email
  }
})

test('owned role deletion requires exact ownership marker; cleanup independently attempts both resources', async () => {
  const roleSql = removeOwnedRoleSql(scope)
  assert.match(roleSql, /refuse unknown role/)
  assert.ok(roleSql.includes(`owned-automation:${projectId}:${policyId}`))
  const seen: string[] = []
  await assert.rejects(
    restoreAutomationQuota(scope, async (sql) => {
      seen.push(sql)
      throw new Error('underlying private error')
    }),
    (error: unknown) => {
      assert.ok(error instanceof AggregateError)
      assert.equal(error.errors.length, 2)
      assert.doesNotMatch(JSON.stringify(error.errors), /underlying private/)
      return true
    }
  )
  assert.deepEqual(seen, [restoreQuotaSql(scope), roleSql])
})
