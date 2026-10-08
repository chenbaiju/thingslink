import { randomUUID } from 'node:crypto'
import type { Page } from '@playwright/test'
import { enterProject, login, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { executeDatabaseFixture } from './database-fixture'

export interface AlarmSummaryProject {
  id: string
  devices: { id: string; name: string; deviceKey: string }[]
}

/** 隔离栈的事实读取测试：只播种自建项目，不声称验证 MQTT 告警触发链。 */
function seedAlarmFacts(project: AlarmSummaryProject) {
  const facts = [
    { device: 0, severity: 'CRITICAL', state: 'PENDING' },
    { device: 0, severity: 'MAJOR', state: 'CLEARED' },
    { device: 1, severity: 'CRITICAL', state: 'ACTIVE' },
    { device: 1, severity: 'CRITICAL', state: 'ACTIVE' },
    { device: 1, severity: 'MINOR', state: 'ACTIVE' },
    { device: 2, severity: 'MAJOR', state: 'ACTIVE', ack: true },
    { device: 3, severity: 'MINOR', state: 'ACTIVE' },
    { device: 4, severity: 'WARNING', state: 'ACTIVE' },
    { device: 5, severity: 'INFO', state: 'ACTIVE' }
  ].map((fact) => ({
    ...fact,
    device: project.devices[fact.device]!.id,
    rule: randomUUID(),
    instance: randomUUID(),
    ack: fact.ack ?? false
  }))
  const input = `SET statement_timeout='10s';
SET lock_timeout='5s';
BEGIN;
CREATE TEMP TABLE fixture_owner ON COMMIT DROP AS
SELECT p.id, p.tenant_id, a.id AS account_id
FROM sys_project p JOIN sys_project_member m ON m.project_id=p.id AND m.role='OWNER' AND m.status='ACTIVE'
JOIN sys_account a ON a.id=m.account_id
WHERE p.id=:'project'::uuid AND p.status='ACTIVE' AND p.name LIKE '告警摘要-%' AND a.email=:'email';
CREATE TEMP TABLE fixture_alarm ON COMMIT DROP AS
SELECT r.* FROM jsonb_to_recordset(:'facts'::jsonb)
AS r(device uuid,rule uuid,instance uuid,severity text,state text,ack boolean)
JOIN dev_device d ON d.id=r.device AND d.project_id=:'project'::uuid AND d.deleted_at IS NULL;
INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity,enabled)
SELECT r.rule,p.tenant_id,p.id,'摘要-'||r.rule,'SUMMARY_E2E',r.device,'temperature','GT',30,'LT',25,r.severity,false
FROM fixture_owner p CROSS JOIN fixture_alarm r;
INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,condition_state,ack_state,first_condition_at,activated_at,cleared_at,clear_reason,acknowledged_at,acknowledged_by,last_received_at,last_value)
SELECT r.instance,p.tenant_id,p.id,r.rule,'DEVICE',r.device,'SUMMARY_E2E',r.severity,r.state,
CASE WHEN r.ack THEN 'ACKNOWLEDGED' ELSE 'UNACKNOWLEDGED' END,clock_timestamp(),
CASE WHEN r.state<>'PENDING' THEN clock_timestamp() END,
CASE WHEN r.state='CLEARED' THEN clock_timestamp() END,
CASE WHEN r.state='CLEARED' THEN 'AUTO_RECOVERY' END,
CASE WHEN r.ack THEN clock_timestamp() END,CASE WHEN r.ack THEN p.account_id END,clock_timestamp(),31
FROM fixture_owner p CROSS JOIN fixture_alarm r;
SELECT count(*) FROM alarm_instance WHERE project_id=:'project'::uuid;
COMMIT;`
  const count = executeDatabaseFixture({
    legacy: 'deploy',
    statement: input,
    variables: { project: project.id, email: OWNER_EMAIL, facts: JSON.stringify(facts) },
    timeout: 20_000,
    maxBuffer: 1024 * 1024
  })
  if (count !== '9') throw new Error(`自有告警摘要夹具需要九条事实，实际 ${count}`)
}

/** 项目、物模型和设备走真实 API；finally 只通过正式删除入口清理本次创建的项目。 */
export async function withAlarmSummaryProject(
  page: Page,
  run: (project: AlarmSummaryProject) => Promise<void>
) {
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const created = await page.evaluate(async () => {
    const path = '/src/api/project.ts'
    const api = await import(path)
    return (await api.fetchCreateProject({ name: `告警摘要-${Date.now()}`, region: 'sh-1' })) as {
      id: string
      name: string
    }
  })
  let primaryFailure: unknown, cleanupFailure: unknown
  try {
    await page.reload()
    await enterProject(page, created.name)
    const devices = await page.evaluate(async (projectId) => {
      const path = '/src/api/device.ts'
      const api = await import(path)
      const suffix = Date.now()
      const type = await api.fetchCreateDeviceType(projectId, {
        typeKey: `summary_${suffix}`,
        name: `摘要模型-${suffix}`,
        deviceKind: 'DIRECT',
        payloadProtocol: 'STANDARD',
        networkType: 'WIFI'
      })
      await api.fetchCreateDevicePropertyDefinition(projectId, type.id, {
        propertyKey: 'temperature',
        name: '温度',
        dataType: 'NUMBER',
        accessType: 'REPORT',
        sortOrder: 0
      })
      await api.fetchPublishDeviceType(projectId, type.id)
      const devices: AlarmSummaryProject['devices'] = []
      for (const [index, label] of ['正常', '严重', '主要', '次要', '警告', '提示'].entries()) {
        devices.push(
          await api.fetchCreateDevice(projectId, {
            deviceTypeId: type.id,
            deviceKey: `summary_${suffix}_${index}`,
            name: `摘要-${label}`
          })
        )
      }
      return devices
    }, created.id)
    const project = { id: created.id, devices }
    seedAlarmFacts(project)
    await run(project)
  } catch (error) {
    primaryFailure = error
  } finally {
    try {
      await page.evaluate(async (id) => {
        const path = '/src/api/project.ts'
        await (await import(path)).fetchDeleteProject(id)
      }, created.id)
    } catch (error) {
      cleanupFailure = error
    }
  }
  if (primaryFailure || cleanupFailure)
    throw new AggregateError(
      [primaryFailure, cleanupFailure].filter(Boolean),
      '告警摘要浏览器或自有项目清理失败'
    )
}
