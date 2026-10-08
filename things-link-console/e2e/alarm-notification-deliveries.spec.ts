import { expect, test } from '@playwright/test'
import { readFile, writeFile, mkdir, copyFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

test('告警详情读取真实评估生成的21条脱敏终态记录并分页', async ({ page }) => {
  test.skip(!process.env.E2E_ALARM_RUNTIME, '需要显式专用栈与冻结候选')
  test.setTimeout(180_000)
  const runtime = JSON.parse(await readFile(process.env.E2E_ALARM_RUNTIME!, 'utf8'))
  if (
    !/^tc_console_/.test(runtime.database) ||
    runtime.postgresPort !== 5547 ||
    runtime.backendPort !== 8088 ||
    runtime.redisDatabase !== 14
  )
    throw new Error('拒绝非专用栈')
  const hash = createHash('sha256')
    .update(await readFile(runtime.frozenJar))
    .digest('hex')
  if (hash !== runtime.jarSha) throw new Error('冻结JAR已漂移')
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, process.env.E2E_END_USER_PROJECT_NAME!)
  const fixture = await page.evaluate(async () => {
    const devicesPath = '/src/api/device.ts',
      alarmPath = '/src/api/alarm.ts',
      userPath = '/src/store/modules/user.ts'
    const devices = await import(devicesPath),
      alarm = await import(alarmPath),
      info = (await import(userPath)).useUserStore().info
    const projectId = info.currentProjectId!,
      suffix = Date.now(),
      key = `delivery_${suffix}`
    const type = await devices.fetchCreateDeviceType(projectId, {
      typeKey: key,
      name: key,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    await devices.fetchCreateDevicePropertyDefinition(projectId, type.id!, {
      propertyKey: 'temperature',
      name: '温度',
      dataType: 'NUMBER',
      accessType: 'REPORT',
      sortOrder: 0
    })
    await devices.fetchPublishDeviceType(projectId, type.id!)
    const device = await devices.fetchCreateDevice(projectId, {
      deviceTypeId: type.id!,
      deviceKey: key,
      name: key
    })
    const alarmType = `DELIVERY_FIXTURE_${suffix}_`.padEnd(48, 'X')
    const rule = await alarm.fetchCreateAlarmRule(projectId, {
      name: key,
      alarmType,
      deviceId: device.id!,
      propertyKey: 'temperature',
      triggerOperator: 'GT',
      triggerThreshold: 30,
      triggerDurationSeconds: 0,
      clearOperator: 'LT',
      clearThreshold: 25,
      clearDurationSeconds: 0,
      severity: 'MAJOR',
      enabled: true
    })
    const group = await alarm.fetchCreateAlarmNotificationGroup(projectId, {
      name: key,
      enabled: true
    })
    // 合成夹具遵守真实REST写速率，避免快速循环触发套餐保护。
    await new Promise((ok) => setTimeout(ok, 1100))
    for (let i = 0; i < 21; i++) {
      await alarm.fetchCreateAlarmNotificationRecipient(projectId, group.id!, {
        channel: 'EMAIL',
        target: `synthetic-${suffix}-${i}@example.invalid`,
        enabled: true
      })
      await new Promise((ok) => setTimeout(ok, 220))
    }
    const template = await alarm.fetchCreateAlarmNotificationTemplate(projectId, {
      name: key,
      channel: 'EMAIL',
      subjectTemplate: '${alarm.type}'.repeat(6),
      bodyTemplate: '受控投递查询夹具',
      enabled: true
    })
    await alarm.fetchCreateAlarmNotificationBinding(projectId, rule.id!, {
      groupId: group.id!,
      templateId: template.id!,
      channel: 'EMAIL',
      enabled: true
    })
    return {
      projectId,
      tenantId: info.tenantId,
      accountId: info.userId,
      deviceId: device.id,
      ruleId: rule.id,
      alarmType
    }
  })
  const directory = resolve(process.env.E2E_RUN_DIR || 'logs/014-a-browser')
  await mkdir(directory, { recursive: true })
  const fixtureClasses = resolve(directory, 'fixture-classes')
  const fixturePackage = 'com/things/link/bootstrap/alarm/fixture'
  await mkdir(resolve(fixtureClasses, fixturePackage), { recursive: true })
  // 仅加载当前夹具类，防止其他JUnit配置进入生产Bean装配。
  await copyFile(
    resolve(
      '../things-link/things-link-bootstrap/target/test-classes',
      fixturePackage,
      'AlarmDeliveryFixtureProcess.class'
    ),
    resolve(fixtureClasses, fixturePackage, 'AlarmDeliveryFixtureProcess.class')
  )
  const fixturePath = resolve(directory, 'evaluation-fixture.json')
  await writeFile(fixturePath, JSON.stringify(fixture))
  const env = {
    ...process.env,
    SERVER_PORT: '0',
    SERVER_ADDRESS: '127.0.0.1',
    SPRING_KAFKA_ADMIN_AUTO_CREATE: 'false',
    SPRING_DATASOURCE_URL: `jdbc:postgresql://localhost:5547/${runtime.database}`,
    SPRING_DATA_REDIS_DATABASE: '14',
    SPRING_KAFKA_LISTENER_AUTO_STARTUP: 'false',
    THINGS_LINK_INGRESS_HANDOFF_ENABLED: 'false',
    THINGS_LINK_OUTBOX_PUBLISHER_ENABLED: 'false',
    THINGS_LINK_DASHBOARD_HOST_REGISTRY_DIRECTORY: runtime.hostRegistry,
    LOG_PATH: resolve(directory, 'evaluation-service'),
    SPRING_APPLICATION_JSON: JSON.stringify({
      'things-link': {
        assistant: {
          credentials: {
            'active-key-id': 'fixture',
            keys: { fixture: Buffer.alloc(32, 14).toString('base64') }
          }
        },
        notification: { retry: { enabled: false } }
      }
    })
  }
  const values = Object.fromEntries(
    (await readFile(resolve('../deploy/.env'), 'utf8'))
      .split('\n')
      .filter((line) => line && !line.startsWith('#') && line.includes('='))
      .map((line) => {
        const at = line.indexOf('=')
        return [
          line.slice(0, at),
          line
            .slice(at + 1)
            .trim()
            .replace(/^["']|["']$/g, '')
        ]
      })
  )
  Object.assign(env, {
    THINGS_LINK_STORAGE_ACCESS_KEY: values.MINIO_ROOT_USER,
    THINGS_LINK_STORAGE_SECRET_KEY: values.MINIO_ROOT_PASSWORD
  })
  await new Promise<void>((ok, fail) => {
    const child = spawn(
      'java',
      [
        '-DsocksProxyHost=',
        '-Dhttp.proxyHost=',
        '-Dhttps.proxyHost=',
        `-Dloader.path=${fixtureClasses}`,
        '-Dloader.main=com.things.link.bootstrap.alarm.fixture.AlarmDeliveryFixtureProcess',
        `-Dtc.alarm.fixture=${fixturePath}`,
        '-cp',
        runtime.frozenJar,
        'org.springframework.boot.loader.launch.PropertiesLauncher'
      ],
      { env, stdio: ['ignore', 'ignore', 'pipe'] }
    )
    let error = ''
    child.stderr.on('data', (bytes) => {
      error = (error + bytes.toString()).slice(-2000)
    })
    const timer = setTimeout(() => {
      child.kill('SIGTERM')
      fail(new Error('真实评估进程超时'))
    }, 90_000)
    child.on('error', fail)
    child.on('close', (code) => {
      clearTimeout(timer)
      if (code === 0) ok()
      else fail(new Error(`真实评估进程失败${code}: ${error}`))
    })
  })
  const result = JSON.parse(await readFile(fixturePath, 'utf8'))
  expect(result.count).toBe(21)
  await page.goto('/#/alarm/history')
  const row = page.locator('tr').filter({ hasText: fixture.alarmType })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: '事件', exact: true }).click()
  const panel = page.locator('.alarm-notification-deliveries')
  await expect(panel.locator('tbody tr')).toHaveCount(20)
  await expect(panel).toContainText('TEMPLATE_INVALID')
  await expect(panel).toContainText('***')
  await expect(panel).not.toContainText('example.invalid')
  await expect(panel).not.toContainText('受控投递查询夹具')
  await panel.getByRole('button', { name: '加载更多投递记录', exact: true }).click()
  await expect(panel.locator('tbody tr')).toHaveCount(21)
  await expect(panel.getByRole('button', { name: '加载更多投递记录', exact: true })).toHaveCount(0)
  await panel.getByRole('button', { name: '刷新投递记录', exact: true }).click()
  await expect(panel.locator('tbody tr')).toHaveCount(20)
  await expect(panel.getByRole('button', { name: /重发/ })).toHaveCount(0)
  const facts = await page.evaluate(
    async ({ projectId, instanceId }) => {
      const path = '/src/api/alarm.ts'
      return (await import(path)).fetchAlarmNotificationDeliveries(projectId, instanceId)
    },
    { projectId: fixture.projectId, instanceId: result.instanceId }
  )
  expect(
    facts.items.every(
      (r: any) => r.target === '***' && r.status === 'TEMPLATE_INVALID' && r.nextAttemptAt === null
    )
  ).toBe(true)
  await page.locator('.el-drawer__close-btn').click()
  await expect(panel).toHaveCount(0)
})
