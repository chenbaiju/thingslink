import { execFile } from 'node:child_process'
import { existsSync } from 'node:fs'
import { promisify } from 'node:util'
import { currentValueMessage } from './current-value-mqtt'
import { timeFactsSql, validateTimeFacts, type TimeFixtureScope } from './time-automation-fixture'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
// add-console-account.sh provisions owned tenants with PostgreSQL gen_random_uuid (v4).
// API-created resources and fixture-owned policy/operation IDs remain strictly v7.
const tenantUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[47][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const id = (value: string) => {
  if (!uuid.test(value)) throw new Error('Invalid owned automation UUID')
  return value
}
const newId = (): string => JSON.parse(currentValueMessage('{}', '1.0.0')).messageId
export interface QuotaFixtureScope {
  projectId: string
  tenantId: string
  originalPolicyId: string
  policyId: string
  role: string
}
export function validateScope(s: QuotaFixtureScope) {
  ;[s.projectId, s.originalPolicyId, s.policyId].forEach(id)
  if (!tenantUuid.test(s.tenantId)) throw new Error('Invalid owned tenant UUID')
  if (s.policyId === s.originalPolicyId || s.role !== `aq_e2e_${s.policyId.replaceAll('-', '')}`)
    throw new Error('Invalid owned quota identity')
}
/** Called only in a fresh disposable Dind run. No credential or recipient is returned by psql. */
async function query(sql: string, readonly = false): Promise<unknown> {
  if (
    !['property-smtp', 'time-smtp'].includes(process.env.E2E_AUTOMATION_FIXTURE ?? '') ||
    !existsSync('/.thingslink-local-ci')
  )
    throw new Error('Quota fixture requires isolated selected runner')
  const user = process.env.E2E_AUTOMATION_ADMIN_USER,
    db = process.env.E2E_AUTOMATION_ADMIN_DB
  if (!user || !db) throw new Error('Missing dedicated automation fixture authority')
  try {
    const { stdout } = await promisify(execFile)(
      'docker',
      [
        'exec',
        '-e',
        `PGOPTIONS=-c statement_timeout=3000 -c lock_timeout=2000${readonly ? ' -c default_transaction_read_only=on' : ''}`,
        'tc-postgres',
        'psql',
        '-X',
        '-v',
        'ON_ERROR_STOP=1',
        '-qAt',
        '-U',
        user,
        '-d',
        db,
        '-c',
        sql
      ],
      { timeout: 7000, maxBuffer: 65536 }
    )
    return stdout.trim() ? JSON.parse(stdout.trim()) : null
  } catch {
    throw new Error('Owned automation quota fixture operation failed')
  }
}
export async function prepareAutomationQuota(projectId: string): Promise<QuotaFixtureScope> {
  id(projectId)
  const email = process.env.E2E_OWNER_EMAIL
  if (!email || !/^e2e-owner-[a-zA-Z0-9-]+@example\.com$/.test(email))
    throw new Error('Only this run owner account may own quota fixture')
  const value = (await query(
    `SELECT jsonb_build_object('tenantId',p.tenant_id,'originalPolicyId',t.quota_policy_id) FROM sys_project p JOIN sys_tenant t ON t.id=p.tenant_id JOIN sys_project_member m ON m.project_id=p.id JOIN sys_account a ON a.id=m.account_id WHERE p.id='${projectId}' AND m.role='OWNER' AND a.email='${email}' AND p.deleted_at IS NULL AND t.deleted_at IS NULL`,
    true
  )) as { tenantId: string; originalPolicyId: string }
  if (!value) throw new Error('Owned project not found')
  const policyId = newId(),
    scope = { projectId, ...value, policyId, role: `aq_e2e_${policyId.replaceAll('-', '')}` }
  validateScope(scope)
  return scope
}
export function installQuotaSql(s: QuotaFixtureScope): string {
  validateScope(s)
  return `BEGIN;
  DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM sys_deployment_automation_entitlement WHERE singleton AND entitlement_mode='COMMERCIAL') THEN RAISE EXCEPTION 'fixture will not change licensing mode'; END IF;
    IF (SELECT quota_policy_id FROM sys_tenant WHERE id='${s.tenantId}' FOR UPDATE) IS DISTINCT FROM '${s.originalPolicyId}'::uuid THEN RAISE EXCEPTION 'assignment changed'; END IF;
  END $$;
  CREATE ROLE ${s.role} NOLOGIN;
  COMMENT ON ROLE ${s.role} IS 'owned-automation:${s.projectId}:${s.policyId}';
  GRANT thingslink_quota_operator TO ${s.role};
  INSERT INTO sys_quota_policy SELECT (jsonb_populate_record(NULL::sys_quota_policy, to_jsonb(q) || jsonb_build_object('id','${s.policyId}','code','AQ${s.policyId.replaceAll('-', '').slice(0, 28)}','version',1,'automation_execution_daily_limit',0,'created_at',clock_timestamp(),'updated_at',clock_timestamp()))).* FROM sys_quota_policy q WHERE q.id='${s.originalPolicyId}';
  UPDATE sys_tenant SET quota_policy_id='${s.policyId}',quota_policy_assignment_version=quota_policy_assignment_version+1 WHERE id='${s.tenantId}' AND quota_policy_id='${s.originalPolicyId}';
  COMMIT;`
}
export async function installAutomationQuota(s: QuotaFixtureScope) {
  await query(installQuotaSql(s))
}
export function setQuotaSql(
  s: QuotaFixtureScope,
  operationId: string,
  expectedVersion: number,
  limit: number
): string {
  validateScope(s)
  id(operationId)
  if (
    !Number.isSafeInteger(expectedVersion) ||
    expectedVersion < 1 ||
    !Number.isSafeInteger(limit) ||
    limit < 0 ||
    limit > 2
  )
    throw new Error('Only small bounded quota is allowed')
  return `SET SESSION AUTHORIZATION ${s.role}; SELECT row_to_json(x) FROM automation_quota_set('${operationId}','${s.policyId}',${expectedVersion},${limit}) x;`
}
export async function enableOneAutomation(s: QuotaFixtureScope) {
  const operationId = newId()
  const sql = setQuotaSql(s, operationId, 1, 1)
  const result = await query(sql)
  const replay = await query(sql)
  if (JSON.stringify(result) !== JSON.stringify(replay))
    throw new Error('Quota operation replay changed receipt')
  return result
}
export function restoreQuotaSql(s: QuotaFixtureScope): string {
  validateScope(s)
  return `BEGIN;
  DO $$ BEGIN
    IF (SELECT quota_policy_id FROM sys_tenant WHERE id='${s.tenantId}' FOR UPDATE) NOT IN ('${s.originalPolicyId}'::uuid,'${s.policyId}'::uuid) THEN RAISE EXCEPTION 'refuse unknown assignment'; END IF;
  END $$;
  UPDATE sys_tenant SET quota_policy_id='${s.originalPolicyId}',quota_policy_assignment_version=quota_policy_assignment_version+1 WHERE id='${s.tenantId}' AND quota_policy_id='${s.policyId}';
  DELETE FROM sys_quota_policy WHERE id='${s.policyId}' AND code='AQ${s.policyId.replaceAll('-', '').slice(0, 28)}';
  COMMIT;`
}
export function removeOwnedRoleSql(s: QuotaFixtureScope): string {
  validateScope(s)
  return `DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='${s.role}') THEN
      IF (SELECT shobj_description(oid,'pg_authid') FROM pg_roles WHERE rolname='${s.role}') IS DISTINCT FROM 'owned-automation:${s.projectId}:${s.policyId}' THEN RAISE EXCEPTION 'refuse unknown role'; END IF;
      DROP ROLE ${s.role};
    END IF;
  END $$;`
}
export async function restoreAutomationQuota(
  s: QuotaFixtureScope,
  execute: (sql: string) => Promise<unknown> = query
) {
  validateScope(s)
  const errors: Error[] = []
  try {
    await execute(restoreQuotaSql(s))
  } catch {
    errors.push(new Error('Owned quota assignment restore failed'))
  }
  try {
    await execute(removeOwnedRoleSql(s))
  } catch {
    errors.push(new Error('Owned quota role removal failed'))
  }
  if (errors.length) throw new AggregateError(errors, 'Automation fixture cleanup incomplete')
}
export interface AutomationFact {
  id: string
  sourceEventId: string
  status: string
  reasonCode: string | null
  attemptCount: number
  reservations: number
}
export function automationFactsSql(projectId: string, automationId: string): string {
  id(projectId)
  id(automationId)
  return `SELECT COALESCE(jsonb_agg(to_jsonb(f)), '[]'::jsonb) FROM (SELECT e.id,e.source_event_id AS "sourceEventId",e.status,e.reason_code AS "reasonCode",e.attempt_count AS "attemptCount",(SELECT count(*) FROM sys_automation_quota_reservation q WHERE q.tenant_id=e.tenant_id AND q.project_id=e.project_id AND q.execution_id=e.id)::int AS reservations FROM rule_automation_execution e WHERE e.project_id='${projectId}' AND e.automation_id='${automationId}' ORDER BY e.created_at,e.id LIMIT 101) f`
}
export function validateAutomationFacts(value: unknown): AutomationFact[] {
  if (!Array.isArray(value) || value.length > 100) throw new Error('Incomplete automation facts')
  for (const row of value) {
    if (
      !row ||
      Object.keys(row).sort().join() !==
        ['id', 'sourceEventId', 'status', 'reasonCode', 'attemptCount', 'reservations']
          .sort()
          .join() ||
      !uuid.test(row.id) ||
      !uuid.test(row.sourceEventId) ||
      typeof row.status !== 'string' ||
      !(row.reasonCode === null || typeof row.reasonCode === 'string') ||
      !Number.isInteger(row.attemptCount) ||
      row.attemptCount < 0 ||
      row.attemptCount > 3 ||
      ![0, 1].includes(row.reservations)
    )
      throw new Error('Invalid automation facts')
  }
  if (new Set(value.map((row) => row.id)).size !== value.length)
    throw new Error('Duplicate automation facts')
  return value
}

export async function readAutomationFacts(
  projectId: string,
  automationId: string
): Promise<AutomationFact[]> {
  return validateAutomationFacts(await query(automationFactsSql(projectId, automationId), true))
}

/** Production database clock and read-only schedule provenance; no clock/lease mutation. */
export async function readAutomationDatabaseTime(): Promise<string> {
  const value = await query('SELECT to_json(clock_timestamp())', true)
  if (typeof value !== 'string' || !Number.isFinite(Date.parse(value)))
    throw new Error('Invalid database time')
  return value
}
export async function readTimeAutomationFacts(scope: TimeFixtureScope) {
  return validateTimeFacts(await query(timeFactsSql(scope), true), scope)
}
