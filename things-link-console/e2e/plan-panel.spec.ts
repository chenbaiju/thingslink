import { expect, test } from '@playwright/test'
import { enterProject, login } from './helpers'

/**
 * 套餐面板的真实浏览器验收（S14-4c 展示面）。
 *
 * 与其余旅程不同，本旅程用的是**默认供给**账号（`run-e2e-tests.sh` 不传夹具开关，脚本走
 * `provision-console-tenant.sql`，因此该租户有真实 FREE 订阅与 `PLAN_R1_FREE` 绑定）：
 * 只有这样的租户才能读到套餐摘要——legacy 夹具租户在面板上只会看到「不提供套餐事实」。
 *
 * 断言四件事：① 摘要确实存在且订阅状态为生效中（证明供给路径在真实栈上生效）；
 * ② 冻结额度按目录单位展示（存储 100 MB），运行时有效额度按运行时单位展示（同一维度是字节数）；
 * ③ 没有扩容/调整时给空态；④ 默认供给下运行时绑定与订阅修订版一致，因此**不**出现不一致告警。
 */
const PLAN_EMAIL = process.env.E2E_PLAN_EMAIL ?? 'e2e-plan@example.com'
const PLAN_PASSWORD = process.env.E2E_PLAN_PASSWORD ?? 'contract-pass-123'
const PLAN_PROJECT = process.env.E2E_PLAN_PROJECT ?? '套餐面板项目'

test('套餐面板：默认供给的租户在真实浏览器里看到冻结额度与运行时有效额度', async ({ page }) => {
  await login(page, PLAN_EMAIL, PLAN_PASSWORD)
  await enterProject(page, PLAN_PROJECT)
  await page.locator('#app-sidebar').getByText('项目', { exact: true }).click()
  await page.locator('#app-sidebar').getByText('项目设置', { exact: true }).click()

  const panel = page.locator('.plan-summary')
  await expect(panel).toBeVisible({ timeout: 20_000 })

  // ① 摘要存在：默认供给写了 FREE 订阅，面板不能落到「本读取面不提供套餐事实」。
  await expect(panel).not.toContainText('当前账号不是该项目归属租户的成员')
  await expect(panel).toContainText('我的套餐')
  await expect(panel).toContainText('免费版（FREE）')
  await expect(panel).toContainText('product-revision-1')
  await expect(panel).toContainText('生效中')

  // ② 冻结额度（目录单位）与运行时有效额度（运行时单位）并列，且都带单位。
  const frozen = panel.locator('[data-testid="plan-frozen-limits"]')
  const effective = panel.locator('[data-testid="plan-effective-limits"]')
  await expect(frozen).toContainText('设备数上限')
  await expect(frozen).toContainText('3 COUNT')
  await expect(frozen).toContainText('100 MB')
  await expect(effective).toContainText('3 COUNT')
  // 免费档没有资源包/人工调整，运行时有效值就等于冻结值；存储按字节而不是 MB。
  await expect(effective).toContainText('104,857,600 BYTE')

  // ③ 没有扩容与调整时给空态，而不是伪造行。
  //    注意：空表在 Element Plus 下高度为 0，`toBeVisible()` 会判失败；对内容用 toContainText。
  await expect(panel).toContainText('扩容与调整')
  await expect(panel.locator('[data-testid="plan-additions"]')).toContainText('—')

  // ④ 默认供给下运行时绑定就是订阅修订版：不出现不一致告警。
  await expect(panel).not.toContainText('运行时实际绑定的档位与订阅锁定的修订版不一致')
})
