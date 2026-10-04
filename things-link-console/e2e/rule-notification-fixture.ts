import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

export interface RuleDeliveryScope {
  projectId: string
  deviceId: string
  ruleId: string
}
export interface RuleDeliveryFact extends RuleDeliveryScope {
  id: string
  ruleVersionId: string
  messageId: string
  status: string
  attemptCount: number
  nextAttemptAt: string | null
  deliveredAt: string | null
  lastErrorCode: string | null
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const validId = (value: unknown): value is string => typeof value === 'string' && uuid.test(value)

/** No message-rule notification read API exists. Read only the exact owned tuple; never expose mail content. */
export function ruleDeliveryQuery(scope: RuleDeliveryScope): string {
  if (![scope.projectId, scope.deviceId, scope.ruleId].every(validId))
    throw new Error('Invalid owned rule delivery identity')
  return `SELECT COALESCE(jsonb_agg(to_jsonb(facts)), '[]'::jsonb) FROM (
    SELECT id, project_id AS "projectId", device_id AS "deviceId", rule_id AS "ruleId",
      rule_version_id AS "ruleVersionId", message_id AS "messageId", status,
      attempt_count AS "attemptCount", next_attempt_at AS "nextAttemptAt",
      delivered_at AS "deliveredAt", last_error_code AS "lastErrorCode"
    FROM rule_notification_delivery
    WHERE project_id='${scope.projectId}'::uuid AND device_id='${scope.deviceId}'::uuid
      AND rule_id='${scope.ruleId}'::uuid ORDER BY created_at, id LIMIT 101
  ) facts`
}

export function validateRuleDeliveries(
  value: unknown,
  scope: RuleDeliveryScope
): RuleDeliveryFact[] {
  ruleDeliveryQuery(scope)
  if (!Array.isArray(value) || value.length > 100) throw new Error('Incomplete rule delivery facts')
  const keys = [
    'id',
    'projectId',
    'deviceId',
    'ruleId',
    'ruleVersionId',
    'messageId',
    'status',
    'attemptCount',
    'nextAttemptAt',
    'deliveredAt',
    'lastErrorCode'
  ].sort()
  for (const row of value) {
    if (
      !row ||
      typeof row !== 'object' ||
      Object.keys(row).sort().join() !== keys.join() ||
      ![row.id, row.ruleVersionId, row.messageId].every(validId) ||
      row.projectId !== scope.projectId ||
      row.deviceId !== scope.deviceId ||
      row.ruleId !== scope.ruleId ||
      typeof row.status !== 'string' ||
      !row.status ||
      !Number.isInteger(row.attemptCount) ||
      row.attemptCount < 0 ||
      row.attemptCount > 3 ||
      ![row.nextAttemptAt, row.deliveredAt].every(
        (value) =>
          value === null || (typeof value === 'string' && Number.isFinite(Date.parse(value)))
      ) ||
      !(row.lastErrorCode === null || typeof row.lastErrorCode === 'string')
    )
      throw new Error('Invalid or foreign rule delivery projection')
  }
  if (new Set(value.map((row) => row.id)).size !== value.length)
    throw new Error('Duplicate delivery projection')
  return value
}

export async function readRuleDeliveries(scope: RuleDeliveryScope): Promise<RuleDeliveryFact[]> {
  const sql = ruleDeliveryQuery(scope)
  const user = process.env.E2E_PG_USER,
    database = process.env.E2E_PG_DB
  if (!user || !database) throw new Error('Missing readonly rule delivery context')
  try {
    const { stdout } = await promisify(execFile)(
      'docker',
      [
        'exec',
        '-e',
        'PGOPTIONS=-c statement_timeout=3000 -c default_transaction_read_only=on',
        'tc-postgres',
        'psql',
        '-X',
        '-v',
        'ON_ERROR_STOP=1',
        '-qAt',
        '-U',
        user,
        '-d',
        database,
        '-c',
        sql
      ],
      { timeout: 5000, maxBuffer: 65536 }
    )
    return validateRuleDeliveries(JSON.parse(stdout), scope)
  } catch {
    throw new Error('Readonly rule delivery observation failed')
  }
}
