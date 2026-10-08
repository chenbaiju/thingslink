import { expect, test } from '@playwright/test'
import { expandWorkspace, login, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

/**
 * 套餐与权益目录的真实浏览器验收（S14-6e，关闭 D-167 遗留入口）。
 *
 * 这是**平台级只读页**：无需先选项目、任何已登录账号可读（与「系统状态」同一口径），
 * 展示平台全局售卖事实——四个档位的身份、冻结额度维度与能力权益。
 *
 * 断言四件事：① 四个档位都渲染（目录来自真实后端 `GET /api/v1/plans`，不是前端硬编码）；
 * ② 付费档保持未开卖、且把参考价明确标注为「不是成交价」；③ 冻结额度带单位（免费档 3 台 / 100 MB）；
 * ④ 不依赖项目上下文（本旅程全程没有进入任何项目）。
 */
test('套餐与权益目录：平台级只读页展示四档与参考价口径', async ({ page }) => {
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await expandWorkspace(page, '账号与平台工具')
  // 注意：不调用 enterProject —— 平台级页面在未选择项目时就必须可读。
  await page.locator('#app-sidebar').getByText('套餐与权益', { exact: true }).click()

  const tiers = page.locator('[data-testid="plan-catalog-tiers"]')
  await expect(tiers).toBeVisible({ timeout: 20_000 })

  for (const code of ['FREE', 'STANDARD', 'ENTERPRISE', 'PROFESSIONAL']) {
    await expect(page.locator(`[data-testid="plan-catalog-tier-${code}"]`)).toBeVisible()
  }
  await expect(page.locator('.plan-catalog__notice')).toContainText('product-revision-1')

  const free = page.locator('[data-testid="plan-catalog-tier-FREE"]')
  await expect(free).toContainText('免费版')
  await expect(free).toHaveAttribute('title', /product-revision-1.*FREE/)
  await expect(page.getByTestId('quota:DEVICES_MAX-FREE')).toHaveText('3')
  await expect(page.getByTestId('quota:STORAGE_LIMIT-FREE')).toHaveText('100 MB')

  const standard = page.locator('[data-testid="plan-catalog-tier-STANDARD"]')
  await expect(standard).toContainText('未开售')
  await expect(page.getByTestId('plan-catalog-price').nth(1)).toContainText('参考价（不是成交价）')
  await expect(page.locator('[data-testid="plan-catalog-error"]')).toHaveCount(0)
})
