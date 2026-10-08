import { expect, test, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { existsSync } from 'node:fs'
import {
  login,
  enterProject,
  OWNER_EMAIL,
  OWNER_PASSWORD,
  MEMBER_EMAIL,
  MEMBER_PASSWORD
} from './helpers'

// 合成存储事实仅用于显式的一次性栈；普通矩阵不据跳过取得资格。
test.skip(process.env.AGENT_EVIDENCE_FACT_FIXTURE !== 'disposable-dind', '需显式专属事实夹具')
test.use({ trace: 'off', video: 'off', screenshot: 'off' })

function seedFacts(project: string, device: string, model: string) {
  if (
    !existsSync('/.thingslink-local-ci') ||
    process.env.DOCKER_HOST !== 'unix:///var/run/docker.sock' ||
    !/^[0-9a-f]{64}$/.test(process.env.LOCAL_CI_SOURCE_SHA256 || '') ||
    !process.env.E2E_PG_USER ||
    !process.env.E2E_PG_DB
  )
    throw new Error('拒绝非专属daemon或不完整候选的事实写入')
  const base = Date.now() - 600000
  const points = [new Date(base).toISOString(), new Date(base + 60000).toISOString()]
  const incidents = Array.from({ length: 21 }, (_, index) => ({
    id: randomUUID(),
    rule: randomUUID(),
    at: new Date(base + index * 1000).toISOString()
  }))
  const input = `SET statement_timeout='10s'; SET lock_timeout='5s'; BEGIN;
CREATE TEMP TABLE fixture_scope ON COMMIT DROP AS
SELECT p.id,p.tenant_id,d.id AS device_id,v.id AS model_id,v.version_number
FROM sys_project p JOIN sys_project_member m ON m.project_id=p.id AND m.role='OWNER' AND m.status='ACTIVE'
JOIN sys_account a ON a.id=m.account_id
JOIN dev_device d ON d.project_id=p.id AND d.id=:'device'::uuid AND d.deleted_at IS NULL
JOIN dev_thing_model_version v ON v.id=d.thing_model_version_id AND v.project_id=p.id
WHERE p.id=:'project'::uuid AND p.status='ACTIVE' AND p.name LIKE '工具事实-%'
AND a.email=:'email' AND v.id=:'model'::uuid;
DO $$ BEGIN IF (SELECT count(*) FROM fixture_scope)<>1 THEN RAISE EXCEPTION '自有事实围栏不匹配'; END IF; END $$;
INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,data_type,
thing_model_version_id,model_version,value_double,value_text,quality)
SELECT id,device_id,'temperature',:'knownAt'::timestamptz,:'knownMessage'::uuid,'NUMBER',model_id,version_number,0,NULL,1 FROM fixture_scope
UNION ALL
SELECT id,device_id,'temperature',:'unknownAt'::timestamptz,:'unknownMessage'::uuid,NULL,NULL,NULL,99,NULL,1 FROM fixture_scope;
INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity,enabled)
SELECT r.rule,p.tenant_id,p.id,'不可外发规则文本-'||r.rule,'PRIVATE_FREE_TEXT',p.device_id,'temperature','GT',30,'LT',25,'WARNING',false
FROM fixture_scope p CROSS JOIN jsonb_to_recordset(:'incidents'::jsonb) AS r(id uuid,rule uuid,at timestamptz);
INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,
condition_state,ack_state,first_condition_at,activated_at,last_received_at,last_value,created_at,updated_at)
SELECT r.id,p.tenant_id,p.id,r.rule,'DEVICE',p.device_id,'PRIVATE_FREE_TEXT','WARNING','ACTIVE','UNACKNOWLEDGED',
r.at,r.at,r.at,99,r.at,r.at FROM fixture_scope p CROSS JOIN jsonb_to_recordset(:'incidents'::jsonb) AS r(id uuid,rule uuid,at timestamptz);
SELECT (SELECT count(*) FROM ts_property_point_internal WHERE project_id=:'project'::uuid AND device_id=:'device'::uuid)||':'||
(SELECT count(*) FROM alarm_instance WHERE project_id=:'project'::uuid AND originator_id=:'device'::uuid);
COMMIT;`
  const variables = {
    project,
    device,
    model,
    email: OWNER_EMAIL,
    knownAt: points[0],
    unknownAt: points[1],
    knownMessage: randomUUID(),
    unknownMessage: randomUUID(),
    incidents: JSON.stringify(incidents)
  }
  const count = execFileSync(
    'docker',
    [
      'exec',
      '-i',
      'tc-postgres',
      'psql',
      '--no-psqlrc',
      '-q',
      '-t',
      '-A',
      '-v',
      'ON_ERROR_STOP=1',
      '-U',
      process.env.E2E_PG_USER,
      '-d',
      process.env.E2E_PG_DB,
      ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])
    ],
    { input, encoding: 'utf8', timeout: 20000, maxBuffer: 1024 * 1024 }
  ).trim()
  if (count !== '2:21') throw new Error('自有事实数量不匹配')
  return { points, ids: incidents.map((row) => row.id).reverse() }
}

test('非空历史告警：四角色零值、未知来源与手动替换分页', async ({ page, browser }) => {
  test.setTimeout(240000)
  page.setDefaultTimeout(20000)
  page.setDefaultNavigationTimeout(20000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const suffix = Date.now(),
    name = `工具事实-${suffix}`
  const project = await page.evaluate(async (name) => {
    const path = '/src/api/project.ts'
    return await (await import(path)).fetchCreateProject({ name, region: 'sh-1' })
  }, name)
  const context = await browser.newContext({ baseURL: new URL(page.url()).origin })
  context.setDefaultTimeout(20000)
  let device = '',
    model = '',
    memberId = '',
    primary: unknown,
    checkpoint = 'fixture'
  const reads: string[] = [],
    writes: string[] = []
  const observe = (target: Page) =>
    target.on('request', (request) => {
      const path = new URL(request.url()).pathname
      if (!path.includes('/assistant/')) return
      if (request.method() !== 'GET') writes.push(request.method() + ' ' + path)
      else if (path.endsWith('/history') || path.endsWith('/alarms')) reads.push(request.url())
    })
  observe(page)
  try {
    await page.reload()
    await enterProject(page, name)
    const created = await page.evaluate(
      async ({ project, suffix, email }) => {
        const devicePath = '/src/api/device.ts',
          projectPath = '/src/api/project.ts',
          bindingPath = '/src/api/dashboard-binding.ts'
        const api = await import(devicePath),
          projects = await import(projectPath)
        const pause = () => new Promise((r) => setTimeout(r, 250))
        const type = await api.fetchCreateDeviceType(project, {
          typeKey: `tool_${suffix}`,
          name: '事实测试类型',
          deviceKind: 'DIRECT',
          payloadProtocol: 'STANDARD',
          networkType: 'WIFI'
        })
        await pause()
        await api.fetchCreateDevicePropertyDefinition(project, type.id, {
          propertyKey: 'temperature',
          name: '温度',
          dataType: 'NUMBER',
          accessType: 'REPORT',
          sortOrder: 0
        })
        await pause()
        await api.fetchPublishDeviceType(project, type.id)
        await pause()
        const device = await api.fetchCreateDevice(project, {
          deviceTypeId: type.id,
          deviceKey: `tool_${suffix}`,
          name: '合成事实设备'
        })
        await pause()
        const member = await projects.fetchInviteMember(project, { email, role: 'ADMIN' })
        const binding = await (await import(bindingPath)).fetchBindingMetadata(project, device.id)
        return {
          device: device.id as string,
          model: binding.model.versionId as string,
          member: member.accountId as string
        }
      },
      { project: project.id, suffix, email: MEMBER_EMAIL }
    )
    device = created.device
    model = created.model
    memberId = created.member
    const fixture = seedFacts(project.id, device, model)
    const exercise = async (target: Page) => {
      await target.goto(`/#/device/list?deviceId=${device}`)
      await target.getByRole('tab', { name: '诊断证据', exact: true }).click()
      const panel = target.getByTestId('agent-evidence'),
        tools = target.getByTestId('device-evidence-tools')
      const before = reads.length
      await panel.getByRole('button', { name: '加载属性目录', exact: true }).click()
      await expect(tools.getByLabel('历史数值属性')).toBeEnabled()
      expect(reads).toHaveLength(before)
      await tools.getByLabel('历史数值属性').selectOption('temperature')
      const range = await target.evaluate(() => {
        const local = (time: number) =>
          new Date(time - new Date(time).getTimezoneOffset() * 60000).toISOString().slice(0, 16)
        return { from: local(Date.now() - 3600000), to: local(Date.now() - 120000) }
      })
      await tools.getByLabel('历史起点').fill(range.from)
      await tools.getByLabel('历史终点').fill(range.to)
      const response = (tail: string) =>
        target.waitForResponse(
          (r) =>
            r.request().method() === 'GET' &&
            new URL(r.url()).pathname ===
              `/api/v1/projects/${project.id}/assistant/devices/${device}/${tail}`
        )
      const h = response('history')
      await tools.getByRole('button', { name: '读取历史', exact: true }).click()
      const history = await h
      expect(history.status()).toBe(200)
      expect(history.headers()['cache-control']).toContain('no-store')
      const body = await history.json()
      expect(body.state).toBe('HAS_POINTS')
      expect(body.actualGranularity).toBe('RAW')
      expect(body.points).toHaveLength(2)
      expect(body.points.map((p: { at: string }) => Date.parse(p.at))).toEqual(
        fixture.points.map(Date.parse)
      )
      expect(body.points[0]).toMatchObject({
        value: 0,
        sampleCount: '1',
        source: 'CURRENT_MODEL',
        sourceModelVersionId: model
      })
      expect(body.points[1]).toMatchObject({
        value: null,
        sampleCount: '1',
        source: 'SOURCE_UNKNOWN',
        sourceModelVersionId: null
      })
      const rows = tools.getByTestId('history-evidence-result').locator('tbody tr')
      await expect(rows).toHaveCount(2)
      await expect(rows.first().locator('td').nth(1)).toHaveText('0')
      await expect(rows.last()).toContainText('来源未知，未提供数值')
      const overview = tools.getByTestId('history-evidence-overview')
      const groups = overview.getByTestId('history-source-group')
      await expect(groups).toHaveCount(2)
      await expect(groups.first()).toContainText(`当前模型来源；模型 ${model}`)
      await expect(groups.first()).toContainText('返回 1 个点；样本计数合计 1')
      await expect(groups.first()).toContainText('原始点值范围： 0 至 0')
      await expect(groups.last()).toContainText('来源未知；模型 未提供')
      await expect(groups.last()).toContainText('数值未提供，不计算范围')
      await expect(overview).toContainText('不计算完整率、异常或趋势')
      const first = response('alarms')
      await tools.getByRole('button', { name: '读取告警首页', exact: true }).click()
      const firstHttp = await first
      expect(firstHttp.status()).toBe(200)
      expect(firstHttp.headers()['cache-control']).toContain('no-store')
      const page1 = await firstHttp.json()
      expect(page1.items.map((r: { id: string }) => r.id)).toEqual(fixture.ids.slice(0, 20))
      expect(page1.limit).toBe(20)
      expect(page1.hasMore).toBe(true)
      expect(page1.sourceModelState).toBe('NOT_PROVIDED')
      for (const row of page1.items) {
        expect(row).toMatchObject({
          severity: 'WARNING',
          conditionState: 'ACTIVE',
          ackState: 'UNACKNOWLEDGED'
        })
        for (const key of ['alarmType', 'ruleId', 'name', 'lastValue', 'tenantId'])
          expect(row).not.toHaveProperty(key)
      }
      expect(JSON.stringify(page1)).not.toContain('PRIVATE_FREE_TEXT')
      await expect(tools.getByTestId('alarm-evidence-result').locator('tbody tr')).toHaveCount(20)
      const next = response('alarms')
      await tools.getByRole('button', { name: '读取下一页', exact: true }).click()
      const nextHttp = await next
      expect(nextHttp.status()).toBe(200)
      expect(new URL(nextHttp.url()).searchParams.get('cursor')).toBe(page1.nextCursor)
      const page2 = await nextHttp.json()
      expect(page2.items.map((r: { id: string }) => r.id)).toEqual(fixture.ids.slice(20))
      expect(page2.hasMore).toBe(false)
      expect(page2.nextCursor).toBeNull()
      const result = tools.getByTestId('alarm-evidence-result')
      await expect(result.locator('tbody tr')).toHaveCount(1)
      await expect(result).toContainText(fixture.ids[20]!)
      for (const id of fixture.ids.slice(0, 20)) await expect(result).not.toContainText(id)
      await expect(tools.getByRole('button', { name: '读取下一页', exact: true })).toBeDisabled()
      await tools.getByLabel('历史终点').fill(range.from)
      await expect(tools.getByTestId('history-evidence-result')).toHaveCount(0)
      const count = reads.length
      await target.reload()
      await expect(target.getByRole('menuitem', { name: '设备开发', exact: true })).toBeVisible()
      await expect(target.getByTestId('history-evidence-result')).toHaveCount(0)
      await expect(target.getByTestId('history-evidence-overview')).toHaveCount(0)
      await expect(target.getByTestId('alarm-evidence-result')).toHaveCount(0)
      expect(reads).toHaveLength(count)
    }
    checkpoint = 'OWNER'
    await exercise(page)
    const member = await context.newPage()
    observe(member)
    await login(member, MEMBER_EMAIL, MEMBER_PASSWORD)
    await enterProject(member, name)
    for (const role of ['ADMIN', 'OPERATOR', 'VIEWER']) {
      checkpoint = role
      if (role !== 'ADMIN')
        await page.evaluate(
          async ({ project, account, role }) => {
            const path = '/src/api/project.ts'
            await (await import(path)).fetchUpdateMemberRole(project, account, role)
          },
          { project: project.id, account: memberId, role }
        )
      await member.reload()
      await expect(member.getByRole('menuitem', { name: '设备开发', exact: true })).toBeVisible()
      await exercise(member)
    }
    expect(reads).toHaveLength(12)
    expect(writes).toEqual([])
    checkpoint = 'revocation'
    await page.evaluate(
      async ({ project, account }) => {
        const path = '/src/api/project.ts'
        await (await import(path)).fetchRemoveMember(project, account)
      },
      { project: project.id, account: memberId }
    )
    await member.reload()
    await expect(member.getByRole('textbox', { name: '邮箱' })).toBeVisible()
    await expect(member.getByTestId('device-evidence-tools')).toHaveCount(0)
  } catch (error) {
    primary = error
    console.error(`agent-history-alarm failed checkpoint=${checkpoint}`)
  } finally {
    try {
      await context.close()
      await page.evaluate(
        async ({ project, device }) => {
          const dp = '/src/api/device.ts',
            pp = '/src/api/project.ts'
          if (device) await (await import(dp)).fetchDeleteDevice(project, device)
          await new Promise((r) => setTimeout(r, 250))
          await (await import(pp)).fetchDeleteProject(project)
        },
        { project: project.id, device }
      )
    } catch {
      if (primary === undefined) primary = new Error('自有工具事实项目清理失败')
    }
  }
  if (primary !== undefined) throw primary
})
