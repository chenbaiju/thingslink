export type TimeTrigger = 'ONE_SHOT' | 'CRON'
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const validTime = (v: unknown): v is string =>
  typeof v === 'string' && Number.isFinite(Date.parse(v))
export function timeTriggerConfig(type: TimeTrigger, deviceId: string, databaseNow: string) {
  if (!['ONE_SHOT', 'CRON'].includes(type) || !uuid.test(deviceId) || !validTime(databaseNow))
    throw new Error('Invalid time fixture input')
  const due = new Date(Math.ceil((Date.parse(databaseNow) + 45_000) / 1000) * 1000)
  const common = { deviceId, payload: { temperature: 27 } }
  return type === 'ONE_SHOT'
    ? { ...common, runAt: due.toISOString() }
    : { ...common, cronExpression: `${due.getUTCSeconds()} * * * * *`, timezone: 'UTC' }
}
export interface TimeFixtureScope {
  projectId: string
  automationId: string
  versionId: string
  type: TimeTrigger
}
export interface TimeFacts {
  databaseNow: string
  nextFireAt: string | null
  state: { consumed: boolean; floor: string } | null
  executions: {
    id: string
    sourceEventId: null
    status: string
    reasonCode: string | null
    attemptCount: number
    reservations: number
    triggerType: TimeTrigger
    scheduledFireAt: string
    occurredAt: string
  }[]
}
export function timeFactsSql(s: TimeFixtureScope): string {
  if (
    ![s.projectId, s.automationId, s.versionId].every((v) => uuid.test(v)) ||
    !['ONE_SHOT', 'CRON'].includes(s.type)
  )
    throw new Error('Invalid time fixture scope')
  return `SELECT jsonb_build_object('databaseNow',clock_timestamp(),
  'nextFireAt',(SELECT next_fire_at FROM rule_automation_schedule WHERE project_id='${s.projectId}' AND automation_id='${s.automationId}' AND automation_version_id='${s.versionId}'),
  'state',(SELECT jsonb_build_object('consumed',one_shot_consumed,'floor',next_floor_at) FROM rule_automation_schedule_state WHERE project_id='${s.projectId}' AND automation_id='${s.automationId}' AND automation_version_id='${s.versionId}'),
  'executions',(SELECT COALESCE(jsonb_agg(to_jsonb(f)),'[]'::jsonb) FROM (SELECT e.id,e.source_event_id AS "sourceEventId",e.status,e.reason_code AS "reasonCode",e.attempt_count AS "attemptCount",e.trigger_type AS "triggerType",e.scheduled_fire_at AS "scheduledFireAt",e.occurred_at AS "occurredAt",(SELECT count(*)::int FROM sys_automation_quota_reservation q WHERE q.tenant_id=e.tenant_id AND q.project_id=e.project_id AND q.execution_id=e.id) AS reservations FROM rule_automation_execution e WHERE e.project_id='${s.projectId}' AND e.automation_id='${s.automationId}' AND e.automation_version_id='${s.versionId}' ORDER BY e.created_at,e.id LIMIT 101) f))`
}
export function validateTimeFacts(v: unknown, s: TimeFixtureScope): TimeFacts {
  timeFactsSql(s)
  if (!v || typeof v !== 'object') throw new Error('Missing time fixture facts')
  const o = v as TimeFacts
  if (
    Object.keys(o).sort().join() !==
      ['databaseNow', 'nextFireAt', 'state', 'executions'].sort().join() ||
    !validTime(o.databaseNow) ||
    !(o.nextFireAt === null || validTime(o.nextFireAt)) ||
    !Array.isArray(o.executions) ||
    o.executions.length > 100
  )
    throw new Error('Invalid time fixture projection')
  if (
    o.state !== null &&
    (!o.state ||
      Object.keys(o.state).sort().join() !== 'consumed,floor' ||
      typeof o.state.consumed !== 'boolean' ||
      !validTime(o.state.floor))
  )
    throw new Error('Invalid time schedule state')
  for (const e of o.executions) {
    if (
      !e ||
      Object.keys(e).sort().join() !==
        [
          'id',
          'sourceEventId',
          'status',
          'reasonCode',
          'attemptCount',
          'reservations',
          'triggerType',
          'scheduledFireAt',
          'occurredAt'
        ]
          .sort()
          .join() ||
      !uuid.test(e.id) ||
      e.sourceEventId !== null ||
      e.triggerType !== s.type ||
      typeof e.status !== 'string' ||
      !(e.reasonCode === null || typeof e.reasonCode === 'string') ||
      !Number.isInteger(e.attemptCount) ||
      e.attemptCount < 0 ||
      e.attemptCount > 3 ||
      ![0, 1].includes(e.reservations) ||
      !validTime(e.scheduledFireAt) ||
      !validTime(e.occurredAt) ||
      Date.parse(e.scheduledFireAt) !== Date.parse(e.occurredAt)
    )
      throw new Error('Invalid time execution provenance')
  }
  if (new Set(o.executions.map((e) => e.id)).size !== o.executions.length)
    throw new Error('Duplicate time execution')
  return o
}
