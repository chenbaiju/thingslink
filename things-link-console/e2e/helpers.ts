import { expect, type Locator, type Page } from '@playwright/test'

/** 由 run-e2e-tests.sh 注入的预置账号（add-console-account.sh 已建、已验证）。 */
export const OWNER_EMAIL = process.env.E2E_OWNER_EMAIL ?? 'e2e-owner@example.com'
export const OWNER_PASSWORD = process.env.E2E_OWNER_PASSWORD ?? 'contract-pass-123'
export const MEMBER_EMAIL = process.env.E2E_MEMBER_EMAIL ?? 'e2e-member@example.com'
export const MEMBER_PASSWORD = process.env.E2E_MEMBER_PASSWORD ?? 'contract-pass-123'

/**
 * 完成登录页的拖拽验证（ArtDragVerify）：把滑块拖到阈值即触发 passVerify。
 * 登录前必须通过它，否则 handleSubmit 只置 isClickPass 不发起请求。
 */
export async function dragToPass(page: Page) {
  const handler = page.locator('.dv_handler')
  const container = page.locator('.drag_verify')
  await expect(handler).toBeVisible()
  // 视口宽度变化会让容器宽度不同（阈值 = 容器宽 - 滑块高）：固定 420px 在某些宽度下
  // 拖不到阈值；按容器右缘外推 60px 保证 _x 一定超过阈值。
  const cbox = await container.boundingBox()
  if (!cbox) throw new Error('拖拽容器不可见')
  // 首屏加载时主线程可能被 Vite 编译占住，事件处理被推迟导致未通过；重试数次。
  for (let attempt = 0; attempt < 5; attempt++) {
    const box = await handler.boundingBox()
    if (!box) throw new Error('拖拽滑块不可见')
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(cbox.x + cbox.width + 60, box.y + box.height / 2, { steps: 40 })
    await page.mouse.up()
    try {
      // passVerify 通过 v-model 写回 isPassing，Vue 更新是异步的；等滑块进入成功态
      // （文案变「验证成功」）再放行，否则点「登录」时 handleSubmit 仍读到 false。
      await expect(page.locator('.dv_text')).toContainText('验证成功', { timeout: 2_000 })
      return
    } catch {
      // 未通过：组件会把滑块复位，下一轮重试
    }
  }
  throw new Error('拖拽验证 5 次仍未通过')
}

/** 邮箱+密码登录（含拖拽验证），登录成功会离开 /auth/login。 */
export async function login(page: Page, email: string, password: string) {
  await page.goto('/#/auth/login')
  await page.getByPlaceholder('请输入邮箱').fill(email)
  await page.getByPlaceholder('请输入密码').fill(password)
  await dragToPass(page)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL((url) => !url.hash.includes('/auth/login'), { timeout: 30_000 })
}

/**
 * 重置会话（不是产品意义上的「退出登录」）：清刷新令牌 Cookie（HttpOnly，脚本读不到只能
 * clearCookies）与持久化的用户状态（localStorage，含 isLogin/info），再整页重载回登录页。
 *
 * 访问令牌只存 Pinia 内存、不落盘，随页面卸载自然消失。它**不调用 /auth/logout 作废服务端
 * 刷新令牌族**——本测试不覆盖登出行为，只是为「成员登录」提供一个干净初始态；
 * 若要测真实登出，应操作顶栏退出入口并断言后端会话撤销，另行成用例。
 */
export async function resetSession(page: Page) {
  await page.context().clearCookies()
  await page.evaluate(() => localStorage.clear())
  await page.goto('/#/auth/login')
  await page.reload()
}

/**
 * 进入项目：backend 权限模式下，设备/成员等菜单只在进入项目后才注册。
 *
 * 步骤：项目列表点「进入」→ 切换项目接口换发项目作用域令牌 → `window.location.assign('/')`
 * 整页重载回首页（重载时才按项目角色重新注册动态路由）。
 *
 * **必须等重载后的引导真正完成再返回**：「未进入项目」按钮消失是响应式更新，且引导期间
 * 该按钮根本还没渲染（count=0 会瞬间通过）；提前返回会让后续 `page.goto('/#/...')`
 * 与在途引导竞争，落到首页/404。可靠的完成信号是侧边栏出现「设备」菜单 —— 它只在
 * 项目作用域菜单响应被处理后渲染，未选项目时不存在。
 *
 * 项目名按单元格精确匹配（`hasText` 是子串匹配，会把历史轮次遗留的
 * `E2E项目-<时间戳>` 一并命中）。
 */
export async function enterProject(page: Page, projectName: string) {
  await page.goto('/#/project/list')
  const row = page
    .locator('tr')
    .filter({ has: page.getByText(projectName, { exact: true }) })
    .first()
  await expect(row).toBeVisible({ timeout: 15_000 })
  await row.getByRole('button', { name: '进入' }).click()
  // switchProject 成功后 URL 先到 '/'，再经 hash 路由回首页（如 /#/dashboard/overview）
  await page.waitForURL((url) => url.pathname === '/' && !url.hash.includes('/project/list'), {
    timeout: 20_000
  })
  await page.waitForLoadState('load')
  // 等项目作用域菜单注册完成（exact 必须：getByRole name 默认子串匹配，「设备」会命中子菜单项）
  await expect(page.getByRole('menuitem', { name: '设备', exact: true })).toBeVisible({
    timeout: 20_000
  })
  await expect(page.getByRole('button', { name: /未进入项目/ })).toHaveCount(0, { timeout: 15_000 })
}

/** 非邀请旅程共用预置账号，按真实成员接口确保目标角色；邀请接受由独立旅程验证。 */
export async function ensureViewerMember(page: Page, projectId: string, email: string) {
  await page.evaluate(
    async ({ projectId, email }) => {
      const projectPath = '/src/api/project.ts'
      const api = await import(projectPath)
      const member = (await api.fetchProjectMembers(projectId)).find(
        (entry: { email?: string; role?: string; accountId?: string }) =>
          entry.email?.toLowerCase() === email.toLowerCase()
      )
      if (!member) {
        await api.fetchInviteMember(projectId, { email, role: 'VIEWER' })
      } else if (member.role !== 'VIEWER') {
        if (!member.accountId) throw new Error('成员目录缺少账号 ID')
        await api.fetchUpdateMemberRole(projectId, member.accountId, 'VIEWER')
      }
    },
    { projectId, email }
  )
}

/** 等待一个异步 UI 结果（Element Plus 消息等）出现。 */
export async function expectToast(page: Page, text: string) {
  await expect(
    page.locator('.el-message, .el-notification').filter({ hasText: text }).first()
  ).toBeVisible()
}

/** 按当前设备列表的交互区等待路由完成，不依赖已经移除的页面标题。 */
export async function openDeviceList(page: Page) {
  await page.goto('/#/device/list')
  await expect(page).toHaveURL(/\/device\/list$/)
  await expect(page.locator('.device-list__content')).toBeVisible()
  await expect(page.getByRole('button', { name: '创建设备', exact: true })).toBeVisible()
}

/** 凭据入口位于设备行的悬停操作菜单，不能再查找行内“凭据”按钮。 */
export async function openDeviceCredentials(page: Page, row: Locator) {
  await row.getByRole('button', { name: '设备操作', exact: true }).hover()
  await page.getByRole('menuitem', { name: '凭据', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '设备凭据' })
  await expect(dialog).toBeVisible()
  return dialog
}

/** 详情入口同样位于设备行菜单，详情呈现在页面内而非弹窗中。 */
export async function openDeviceDetails(page: Page, row: Locator, timeout = 20_000) {
  await row.getByRole('button', { name: '设备操作', exact: true }).hover({ timeout })
  await page.getByRole('menuitem', { name: '详情', exact: true }).click({ timeout })
  await expect(page.locator('.device-detail')).toBeVisible({ timeout })
}

/** 通过真实个人中心接受，既验证头像入口也保持收件人的显式确认。 */
export async function acceptProjectInvitationFromInbox(page: Page, projectName: string) {
  await page.getByAltText('avatar', { exact: true }).first().hover()
  await page.locator('.user-menu-popover:visible').getByText('个人中心', { exact: true }).click()
  await expect(page).toHaveURL(/#\/system\/user-center/)
  const row = page
    .locator('.project-invitations tr')
    .filter({ has: page.getByText(projectName, { exact: true }) })
    .first()
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: '确认接受', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
  await expect(row).toContainText('已接受')
}
