import { expect, test } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

test('项目列表在无选中项目时展示回收站，删除与恢复刷新两个目录', async ({ page }) => {
  test.setTimeout(180_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const project = await page.evaluate(async () => {
    const path = '/src/api/project.ts'
    return (await import(path)).fetchCreateProject({ name: `回收站-${Date.now()}`, region: 'sh-1' })
  })
  let restored = false
  try {
    await page.reload()
    await enterProject(page, project.name!)
    await page.goto('/#/project/list')
    const activeRow = page.locator('tr').filter({ hasText: project.name! })
    await activeRow
      .getByRole('button', { name: '删除项目', exact: true })
      .click({ timeout: 20_000 })
    const confirm = page.getByRole('dialog').filter({ hasText: '确定删除项目' })
    await expect(confirm).toContainText('项目回收站')
    await expect(confirm).not.toContainText('无法自助')
    await confirm.getByRole('button', { name: '确定', exact: true }).click()
    await expect(activeRow).toHaveCount(0, { timeout: 40_000 })
    await expect(page.getByRole('button', { name: '项目回收站', exact: true })).toBeVisible()
    const info = await page.evaluate(async () => {
      const path = '/src/store/modules/user.ts'
      return (await import(path)).useUserStore().info.currentProjectId
    })
    expect(info || '').toBe('')
    await page.getByRole('button', { name: '项目回收站', exact: true }).click()
    await expect(page).toHaveURL(/#\/project\/recycle-bin$/)
    const deletedRow = page.locator('.project-recycle-bin tr').filter({ hasText: project.name! })
    await expect(page.locator('.project-recycle-bin')).toContainText('恢复截止')
    await expect(deletedRow).toContainText('可恢复')
    const actionTops = await deletedRow
      .locator('button')
      .evaluateAll((buttons) =>
        buttons.map((button) => Math.round(button.getBoundingClientRect().top))
      )
    expect(new Set(actionTops).size).toBe(1)
    await deletedRow.getByRole('button', { name: '留存导出', exact: true }).click()
    const exportDialog = page.getByRole('dialog', { name: '留存导出', exact: true })
    await expect(exportDialog).toBeVisible()
    await expect(exportDialog.locator('.el-dialog')).toHaveCSS('width', '760px')
    await expect(exportDialog.locator('.project-retention-export')).toBeVisible()
    await exportDialog.getByLabel('关闭此对话框').click()
    await expect(exportDialog).toBeHidden()
    await deletedRow.getByRole('button', { name: '恢复项目', exact: true }).click()
    const restoreDialog = page.getByRole('dialog').filter({ hasText: '确认恢复项目' })
    await expect(restoreDialog).toContainText('旧设备凭据')
    await restoreDialog.getByRole('button', { name: '确认恢复', exact: true }).click()
    await expect(deletedRow).toHaveCount(0)
    restored = true
    await expect(page.locator('.project-recycle-bin')).toContainText('项目已恢复')
    await page.goto('/#/project/list')
    await expect(activeRow).toBeVisible()
    await enterProject(page, project.name!)
    await expect(page.getByRole('menuitem', { name: '设备开发', exact: true })).toBeVisible()
  } finally {
    if (restored)
      await page.evaluate(async (id) => {
        const path = '/src/api/project.ts'
        await (await import(path)).fetchDeleteProject(id!)
      }, project.id)
  }
})
