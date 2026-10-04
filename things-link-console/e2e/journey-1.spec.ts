import { expect, test } from '@playwright/test'
import {
  acceptProjectInvitationFromInbox,
  enterProject,
  login,
  resetSession,
  MEMBER_EMAIL,
  MEMBER_PASSWORD,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'

/**
 * 旅程 1：登录/创建项目/邀请成员/切换项目/权限集合刷新。
 *
 * 真实浏览器 + 真实后端；不 mock 核心 API。角色变化（成员被授予角色）后，
 * 前端在切换项目时必须重新拉取权限集合，表现为写入口随角色出现/消失。
 */
test('旅程1：OWNER 建项目并邀请成员，VIEWER 成员看不到写入口', async ({ page }) => {
  const suffix = Date.now()
  const projectName = `E2E项目-${suffix}`

  // 1. OWNER 登录并创建项目
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await page.goto('/#/project/list')
  await page.getByRole('button', { name: '创建项目' }).click()
  await page.getByPlaceholder('例如：厂区环境监测').fill(projectName)
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText(projectName).first()).toBeVisible({ timeout: 15_000 })

  // 2. 进入刚建的项目，再邀请成员（VIEWER）
  await enterProject(page, projectName)
  await page.goto('/#/project/members')
  await page.getByRole('button', { name: '邀请成员' }).click()
  await page.getByPlaceholder('接收邀请的邮箱').fill(MEMBER_EMAIL)
  // 角色下拉显式选 VIEWER（默认可能是 OPERATOR）
  await page.locator('.el-dialog:visible .el-select').first().click()
  // 下拉选项在选中框与 teleported popper 各渲染一份，取最后一个（popper 内）点击
  await page.getByText('VIEWER — 只读').last().click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText(MEMBER_EMAIL).first()).toBeVisible({ timeout: 15_000 })

  // 3. 重置会话 → 成员登录 → 能看到被邀请的项目
  await resetSession(page)
  await login(page, MEMBER_EMAIL, MEMBER_PASSWORD)
  await page.goto('/#/project/list')
  await expect(page.getByText(projectName, { exact: true })).toHaveCount(0)
  await acceptProjectInvitationFromInbox(page, projectName)
  await page.goto('/#/project/list')
  await expect(page.getByText(projectName).first()).toBeVisible({ timeout: 15_000 })

  // 4. 成员进入共享项目 → 权限刷新后设备类型页没有「创建设备类型」（VIEWER 无 device:create）
  await enterProject(page, projectName)
  await page.goto('/#/device/types')
  await expect(page.getByText('设备类型').first()).toBeVisible({ timeout: 15_000 })
  await expect(page.getByRole('button', { name: '创建设备类型' })).toHaveCount(0)
})
