import { expect, test } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { login, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

test('删除项目留存任务刷新找回，显式下载真实私有ZIP并核对摘要', async ({ page }, testInfo) => {
  test.skip(
    !process.env.E2E_RETENTION_EXPORT_PROJECT_NAME,
    '需要独立历史合同夹具，不得关闭实际套餐的存储额度'
  )
  test.setTimeout(180_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const project = await page.evaluate(async (fixtureName) => {
    const path = '/src/api/project.ts'
    const api = await import(path)
    let result = (await api.fetchProjects()).find(
      (row: { name?: string }) => row.name === fixtureName
    )
    if (!result) {
      result = (await api.fetchProjectRecycleBin()).find(
        (row: { name?: string }) => row.name === fixtureName
      )
      if (result?.id) await api.fetchRestoreProject(result.id)
    }
    if (!result?.id) throw new Error('独立留存夹具项目不存在')
    await api.fetchDeleteProject(result.id!)
    return result
  }, process.env.E2E_RETENTION_EXPORT_PROJECT_NAME)
  let requests = 0
  let signings = 0
  page.on('request', (request) => {
    if (request.method() !== 'POST') return
    if (request.url().endsWith(`/projects/${project.id}/exports`)) requests++
    if (
      request.url().includes(`/projects/${project.id}/exports/`) &&
      request.url().endsWith('/download-url')
    )
      signings++
  })
  await page.goto('/#/project/list')
  const openExport = async () => {
    await page.goto('/#/project/recycle-bin')
    const row = page.locator('.project-recycle-bin tr').filter({ hasText: project.name! })
    await row.getByRole('button', { name: '留存导出', exact: true }).click()
    return page
      .getByRole('dialog', { name: '留存导出', exact: true })
      .locator('.project-retention-export')
  }
  let panel = await openExport()
  await expect(panel).toContainText('尚无本人申请的导出任务')
  await panel.getByRole('button', { name: '申请留存导出', exact: true }).click()
  await expect(panel).toContainText('状态：已生成', { timeout: 90_000 })
  const task = await page.evaluate(async (id) => {
    const path = '/src/api/project-exports.ts'
    return (await import(path)).fetchLatestProjectExport(id!)
  }, project.id)
  expect(task?.id).toBeTruthy()
  expect(task?.status).toBe('SUCCEEDED')
  expect(task?.objectSha256).toMatch(/^[a-f0-9]{64}$/)
  expect(task).not.toHaveProperty('objectKey')
  expect(task).not.toHaveProperty('url')
  expect(requests).toBe(1)
  expect(signings).toBe(0)
  await page.reload()
  panel = await openExport()
  await expect(panel).toContainText(task!.id!)
  await expect(panel).toContainText('状态：已生成')
  expect(requests).toBe(1)
  expect(signings).toBe(0)
  const downloadEvent = page.waitForEvent('download')
  await panel.getByRole('button', { name: '下载留存包', exact: true }).click()
  const download = await downloadEvent
  expect(await download.failure()).toBeNull()
  const archivePath = testInfo.outputPath('retention.zip')
  await download.saveAs(archivePath)
  const bytes = readFileSync(archivePath)
  expect(bytes.length).toBe(task?.objectSize)
  expect(createHash('sha256').update(bytes).digest('hex')).toBe(task?.objectSha256)
  const entries = execFileSync('unzip', ['-Z1', archivePath], { encoding: 'utf8' })
    .trim()
    .split('\n')
  expect(entries).toEqual([
    'manifest.json',
    'project.json',
    'members.jsonl',
    'devices.jsonl',
    'property-points.jsonl',
    'alarm-instances.jsonl',
    'alarm-events.jsonl',
    'audit-logs.jsonl'
  ])
  expect(signings).toBe(1)
  await expect(panel).not.toContainText('X-Amz-')
})

test('启用存储额度的套餐拒绝新导出，页面保留明确错误且不自动重试', async ({ page }) => {
  test.skip(!process.env.E2E_RETENTION_EXPORT_QUOTA_EMAIL, '需要独立已启用存储额度的套餐夹具')
  await login(page, process.env.E2E_RETENTION_EXPORT_QUOTA_EMAIL!, OWNER_PASSWORD)
  const project = await page.evaluate(async () => {
    const path = '/src/api/project.ts'
    const api = await import(path)
    const created = await api.fetchCreateProject({
      name: `额度拒绝导出-${Date.now()}`,
      region: 'sh-1'
    })
    await api.fetchDeleteProject(created.id!)
    return created
  })
  let requests = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith(`/projects/${project.id}/exports`))
      requests++
  })
  await page.goto('/#/project/list')
  await page.getByRole('button', { name: '项目回收站', exact: true }).click()
  const row = page.locator('.project-recycle-bin tr').filter({ hasText: project.name! })
  await row.getByRole('button', { name: '留存导出', exact: true }).click()
  const panel = page
    .getByRole('dialog', { name: '留存导出', exact: true })
    .locator('.project-retention-export')
  await expect(panel).toContainText('尚无本人申请的导出任务')
  const response = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().endsWith(`/projects/${project.id}/exports`)
  )
  await panel.getByRole('button', { name: '申请留存导出', exact: true }).click()
  expect((await (await response).json()).code).toBe(50019)
  await expect(panel).toContainText('导出存储额度暂不可用')
  await expect(panel).not.toContainText('结果未确认')
  await expect(panel.getByRole('button', { name: '下载留存包', exact: true })).toHaveCount(0)
  expect(requests).toBe(1)
  const latest = await page.evaluate(async (id) => {
    const path = '/src/api/project-exports.ts'
    return (await import(path)).fetchLatestProjectExport(id!)
  }, project.id)
  expect(latest).toBeUndefined()
})
