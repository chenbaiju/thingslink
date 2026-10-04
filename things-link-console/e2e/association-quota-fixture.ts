import { execFile } from 'node:child_process'
import { existsSync } from 'node:fs'
import { promisify } from 'node:util'
import {
  installQuotaSql,
  setQuotaSql,
  restoreAutomationQuota,
  validateScope,
  type QuotaFixtureScope
} from './automation-quota-fixture'
import { currentValueMessage } from './current-value-mqtt'

/** Only policy setup uses SQL; directory rows and execution history must use real public APIs. */
export function assertAssociationFixture(env: NodeJS.ProcessEnv, isolated: boolean) {
  if (
    !isolated ||
    env.E2E_SPEC !== 'device-associations-populated.spec.ts' ||
    env.E2E_ASSOCIATION_FIXTURE !== '1' ||
    !/^e2e-owner-[a-zA-Z0-9-]+@example\.com$/.test(env.E2E_OWNER_EMAIL ?? '') ||
    !env.E2E_AUTOMATION_ADMIN_USER ||
    !env.E2E_AUTOMATION_ADMIN_DB
  )
    throw new Error('Populated association fixture requires exact isolated selection')
}
async function query(sql: string): Promise<unknown> {
  assertAssociationFixture(process.env, existsSync('/.thingslink-local-ci'))
  try {
    const { stdout } = await promisify(execFile)(
      'docker',
      [
        'exec',
        '-e',
        'PGOPTIONS=-c statement_timeout=3000 -c lock_timeout=2000',
        'tc-postgres',
        'psql',
        '-X',
        '-v',
        'ON_ERROR_STOP=1',
        '-qAt',
        '-U',
        process.env.E2E_AUTOMATION_ADMIN_USER!,
        '-d',
        process.env.E2E_AUTOMATION_ADMIN_DB!,
        '-c',
        sql
      ],
      { timeout: 7000, maxBuffer: 65536 }
    )
    return stdout.trim() ? JSON.parse(stdout.trim()) : null
  } catch {
    throw new Error('Owned association quota operation failed')
  }
}
export async function prepareAssociationQuota(projectId: string): Promise<QuotaFixtureScope> {
  assertAssociationFixture(process.env, existsSync('/.thingslink-local-ci'))
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(projectId))
    throw new Error('Invalid owned project')
  const ownership = await query(
    `SELECT jsonb_build_object('tenantId',p.tenant_id,'originalPolicyId',t.quota_policy_id) FROM sys_project p JOIN sys_tenant t ON t.id=p.tenant_id JOIN sys_project_member m ON m.project_id=p.id JOIN sys_account a ON a.id=m.account_id WHERE p.id='${projectId}' AND m.role='OWNER' AND m.status='ACTIVE' AND a.email='${process.env.E2E_OWNER_EMAIL}' AND p.deleted_at IS NULL AND t.deleted_at IS NULL`
  )
  if (!ownership || typeof ownership !== 'object') throw new Error('Owned project not found')
  const policyId = JSON.parse(currentValueMessage('{}', '1.0.0')).messageId as string
  const scope = {
    projectId,
    ...ownership,
    policyId,
    role: `aq_e2e_${policyId.replaceAll('-', '')}`
  } as QuotaFixtureScope
  validateScope(scope)
  return scope
}
export async function installAssociationQuota(scope: QuotaFixtureScope) {
  await query(installQuotaSql(scope))
  // A single daily execution budget permits publishing; this scenario sends no telemetry.
  const operationId = JSON.parse(currentValueMessage('{}', '1.0.0')).messageId as string
  await query(setQuotaSql(scope, operationId, 1, 1))
}
export const restoreAssociationQuota = (scope: QuotaFixtureScope) =>
  restoreAutomationQuota(scope, query)
