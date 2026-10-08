import { randomUUID } from 'node:crypto'
import { executeDatabaseFixture } from './database-fixture'
import { alarmInboxConfiguration } from './alarm-inbox-configuration'

interface ProjectIdentity {
  accountId: string
  projectId: string
  tenantId: string
  name: string
}
interface EventIdentity {
  id: string
  instance: string
  rule: string
}
interface ProjectFacts {
  project: ProjectIdentity
  type: string
  device: string
  events: EventIdentity[]
}

/** SQL通过stdin传递；所有动态值使用psql引用变量，不拼接shell或SQL字面量。 */
function sql(statement: string, variables: Record<string, string> = {}) {
  return executeDatabaseFixture({
    legacy: 'deploy',
    variables,
    statement: `SET statement_timeout='10s';\nSET lock_timeout='5s';\n${statement}`,
    timeout: 20_000,
    maxBuffer: 8 * 1024 * 1024
  })
}

/** 精确选本轮邮箱的OWNER项目；歧义或不满足runner约定时拒绝种子写入。 */
function identity(email: string, expectedName: string, projectKey = ''): ProjectIdentity {
  const values = JSON.parse(
    sql(
      `SELECT coalesce(jsonb_agg(jsonb_build_object('accountId',a.id,'projectId',p.id,'tenantId',p.tenant_id,'name',p.name)),'[]') FROM sys_account a JOIN sys_project_member m ON m.account_id=a.id AND m.role='OWNER' AND m.status='ACTIVE' JOIN sys_project p ON p.id=m.project_id AND p.status='ACTIVE' WHERE a.email=:'email' AND p.name=:'name' AND (:'key'='' OR p.project_key=:'key');`,
      { email, name: expectedName, key: projectKey }
    )
  ) as ProjectIdentity[]
  if (values.length !== 1)
    throw new Error(`通知E2E需要唯一的${expectedName} OWNER项目，实际${values.length}个`)
  return values[0]
}

/** 每次只拥有明确ID；不删除预置账号、项目、历史成员或审计。 */
export class AlarmInboxFixture {
  readonly configuration = alarmInboxConfiguration()
  readonly owner = identity(
    this.configuration.owner.email,
    this.configuration.owner.projectName,
    this.configuration.owner.projectKey
  )
  readonly member = identity(
    this.configuration.member.email,
    this.configuration.member.projectName,
    this.configuration.member.projectKey
  )
  private archived = false
  private readonly memberships = [randomUUID(), randomUUID()]
  private readonly facts: ProjectFacts[] = [
    this.factsFor(this.owner, 22),
    this.factsFor(this.member, 1)
  ]

  private factsFor(project: ProjectIdentity, count: number): ProjectFacts {
    return {
      project,
      type: randomUUID(),
      device: randomUUID(),
      events: Array.from({ length: count }, () => ({
        id: randomUUID(),
        instance: randomUUID(),
        rule: randomUUID()
      }))
    }
  }

  /** 用非空真实事件组成两个项目；跨项目VIEWER只能添加本轮新关系。 */
  create() {
    const existing = Number(
      sql(
        `SELECT count(*) FROM sys_project_member WHERE (project_id=:'ownerProject'::uuid AND account_id=:'memberAccount'::uuid) OR (project_id=:'memberProject'::uuid AND account_id=:'ownerAccount'::uuid);`,
        this.identities()
      )
    )
    if (existing !== 0) throw new Error('通知E2E存在历史交叉成员，拒绝覆盖或复用')
    if (
      this.configuration.owned &&
      sql(
        "SELECT count(*) FROM alarm_event WHERE project_id IN (:'ownerProject'::uuid,:'memberProject'::uuid);",
        this.identities()
      ) !== '0'
    )
      throw new Error('专用通知项目已有告警事实，拒绝混用')
    const variables: Record<string, string> = {
      ...this.identities(),
      memberOne: this.memberships[0],
      memberTwo: this.memberships[1]
    }
    let statements = `BEGIN; INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (:'memberOne'::uuid,:'ownerProject'::uuid,:'memberAccount'::uuid,'VIEWER'),(:'memberTwo'::uuid,:'memberProject'::uuid,:'ownerAccount'::uuid,'VIEWER');\n`
    for (const [index, f] of this.facts.entries()) {
      const prefix = `f${index}`
      for (const [key, value] of Object.entries({
        tenant: f.project.tenantId,
        project: f.project.projectId,
        type: f.type,
        device: f.device,
        rows: JSON.stringify(f.events)
      }))
        variables[`${prefix}_${key}`] = value
      statements += `INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES (:'${prefix}_type'::uuid,:'${prefix}_tenant'::uuid,:'${prefix}_project'::uuid,:'${prefix}_type','通知E2E设备类型','DIRECT','STANDARD','WIFI','PUBLISHED');
INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (:'${prefix}_device'::uuid,:'${prefix}_tenant'::uuid,:'${prefix}_project'::uuid,:'${prefix}_type'::uuid,:'${prefix}_device','通知E2E设备','OFFLINE');
INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity,enabled) SELECT r.rule,:'${prefix}_tenant'::uuid,:'${prefix}_project'::uuid,'通知E2E-'||r.rule,'INBOX_E2E',:'${prefix}_device'::uuid,'temperature','GT',30,'LT',25,'MAJOR',false FROM jsonb_to_recordset(:'${prefix}_rows'::jsonb) r(id uuid,instance uuid,rule uuid);
INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,condition_state,ack_state,first_condition_at,activated_at,last_received_at,last_value) SELECT r.instance,:'${prefix}_tenant'::uuid,:'${prefix}_project'::uuid,r.rule,'DEVICE',:'${prefix}_device'::uuid,'INBOX_E2E','MAJOR','ACTIVE','UNACKNOWLEDGED',clock_timestamp(),clock_timestamp(),clock_timestamp(),31 FROM jsonb_to_recordset(:'${prefix}_rows'::jsonb) r(id uuid,instance uuid,rule uuid);
INSERT INTO alarm_event(id,tenant_id,project_id,instance_id,event_type,source_message_id,trace_id,value,received_at,condition_state,ack_state) SELECT r.id,:'${prefix}_tenant'::uuid,:'${prefix}_project'::uuid,r.instance,'ACTIVATED',r.id,'alarm-inbox-e2e',31,clock_timestamp()-interval '1 minute','ACTIVE','UNACKNOWLEDGED' FROM jsonb_to_recordset(:'${prefix}_rows'::jsonb) r(id uuid,instance uuid,rule uuid);\n`
    }
    sql(`${statements}COMMIT;`, variables)
  }

  private identities() {
    return {
      ownerProject: this.owner.projectId,
      memberProject: this.member.projectId,
      ownerAccount: this.owner.accountId,
      memberAccount: this.member.accountId
    }
  }

  /** 完整项目事故/事件/投递行前后相等，个人阅读不能改变任何共享告警事实。 */
  snapshot() {
    return sql(
      `SELECT jsonb_build_object('instances',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY id),'[]') FROM alarm_instance r WHERE project_id IN (:'ownerProject'::uuid,:'memberProject'::uuid)),'events',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY id),'[]') FROM alarm_event r WHERE project_id IN (:'ownerProject'::uuid,:'memberProject'::uuid)),'deliveries',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY id),'[]') FROM alarm_notification_delivery r WHERE project_id IN (:'ownerProject'::uuid,:'memberProject'::uuid)));`,
      this.identities()
    )
  }

  /** 只归档本轮OWNER预置项目，退出finally恢复原ACTIVE状态。 */
  archiveOwner() {
    const changed = sql(
      "UPDATE sys_project SET status='ARCHIVED' WHERE id=:'project'::uuid AND status='ACTIVE' RETURNING id;",
      { project: this.owner.projectId }
    )
    if (changed !== this.owner.projectId) throw new Error('通知E2E项目归档前提已变化')
    this.archived = true
  }

  /** 先回执后事件父行，只清本fixture的UUID；不清认证会话、账号审计或预置项目。 */
  dispose() {
    const variables: Record<string, string> = { membershipIds: JSON.stringify(this.memberships) }
    let statements = 'BEGIN;\n'
    if (this.archived) {
      variables.ownerProject = this.owner.projectId
      statements +=
        "UPDATE sys_project SET status='ACTIVE' WHERE id=:'ownerProject'::uuid AND status='ARCHIVED';\n"
    }
    for (const [index, f] of this.facts.entries()) {
      const prefix = `f${index}`
      for (const [key, value] of Object.entries({
        project: f.project.projectId,
        type: f.type,
        device: f.device,
        rows: JSON.stringify(f.events)
      }))
        variables[`${prefix}_${key}`] = value
      statements += `DELETE FROM alarm_notification_read WHERE project_id=:'${prefix}_project'::uuid AND alarm_event_id IN (SELECT id FROM jsonb_to_recordset(:'${prefix}_rows'::jsonb) r(id uuid));
DELETE FROM alarm_event WHERE project_id=:'${prefix}_project'::uuid AND id IN (SELECT id FROM jsonb_to_recordset(:'${prefix}_rows'::jsonb) r(id uuid));
DELETE FROM alarm_instance WHERE project_id=:'${prefix}_project'::uuid AND id IN (SELECT instance FROM jsonb_to_recordset(:'${prefix}_rows'::jsonb) r(instance uuid));
DELETE FROM alarm_rule WHERE project_id=:'${prefix}_project'::uuid AND id IN (SELECT rule FROM jsonb_to_recordset(:'${prefix}_rows'::jsonb) r(rule uuid));
DELETE FROM dev_device WHERE project_id=:'${prefix}_project'::uuid AND id=:'${prefix}_device'::uuid;
DELETE FROM dev_type WHERE project_id=:'${prefix}_project'::uuid AND id=:'${prefix}_type'::uuid;\n`
    }
    statements += `DELETE FROM sys_project_member WHERE id IN (SELECT value::uuid FROM jsonb_array_elements_text(:'membershipIds'::jsonb)); COMMIT;`
    sql(statements, variables)
  }
}
