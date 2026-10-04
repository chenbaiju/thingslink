#!/usr/bin/env node
// 真Console生产包 + 真API/Worker；代理仅提供同源转发，不替换业务响应。
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
process.env.PLAYWRIGHT_BROWSERS_PATH ??= path.join(root, 'things-link-webapp/.playwright-browsers');
const { chromium, expect } = createRequire(path.join(root, 'things-link-webapp/package.json'))('@playwright/test');
let server, browser, context, page;
const errors = [];
const dist = path.join(root, 'things-link-console/dist');
const types = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2' };
(async () => {
  server = http.createServer(async (req, res) => {
    if (req.url.startsWith('/api/') || req.url.startsWith('/actuator/')) {
      const upstream = http.request(new URL(req.url, process.env.RULE_BACKEND), { method: req.method, headers: req.headers }, incoming => {
        res.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(res);
      });
      res.on('close', () => upstream.destroy());
      upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); }); req.pipe(upstream); return;
    }
    let target = path.resolve(dist, '.' + new URL(req.url, 'http://local').pathname);
    if (!target.startsWith(dist + path.sep)) target = path.join(dist, 'index.html');
    try { if (!(await fs.stat(target)).isFile()) target = path.join(dist, 'index.html'); }
    catch { target = path.join(dist, 'index.html'); }
    res.writeHead(200, { 'Content-Type': types[path.extname(target)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(await fs.readFile(target));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ headless: true }); context = await browser.newContext(); page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + '/auth/login');
  await page.getByPlaceholder('请输入邮箱').fill(process.env.RULE_EMAIL);
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(process.env.RULE_PASSWORD);
  const track = await page.locator('.drag_verify').boundingBox(); const handle = await page.locator('.dv_handler').boundingBox();
  await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2); await page.mouse.down();
  await page.mouse.move(track.x + track.width - 2, handle.y + handle.height / 2, { steps: 12 }); await page.mouse.up();
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.locator('.project-switcher').click({ timeout: 15000 });
  await page.locator('.project-switcher-item').filter({ hasText: '规则测试项目' }).click();
  await page.waitForResponse(response => response.url().includes('/system/menus') && response.status() === 200);
  await page.getByText('规则中心', { exact: true }).click();
  await page.locator('#app-sidebar').getByText('自动化', { exact: true }).click();
  await page.getByRole('button', { name: '创建自动化', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '自动化版本管理', exact: true });
  await dialog.getByLabel('自动化名称', { exact: true }).fill('浏览器属性自动化');
  await dialog.getByRole('button', { name: '搜索设备', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '搜索设备', exact: true })).toBeEnabled();
  await dialog.locator('.el-select:has(input[aria-label="目标设备"])').click();
  await page.getByRole('option', { name: '自动化设备', exact: true }).click();
  const conditions = dialog.locator('section').nth(0), actions = dialog.locator('section').nth(1);
  await conditions.locator('.el-select').first().click();
  await page.getByRole('option', { name: 'payload-property-compare', exact: true }).click();
  await conditions.getByRole('button', { name: '添加节点', exact: true }).click();
  await conditions.getByLabel('pointer', { exact: true }).fill('/temperature');
  await conditions.locator('.node-card .el-select').click();
  await page.getByRole('option', { name: 'GT', exact: true }).click();
  await conditions.getByLabel('value', { exact: true }).fill('30');
  await actions.locator('.el-select').first().click();
  await page.getByRole('option', { name: 'notification-action', exact: true }).click();
  await actions.getByRole('button', { name: '添加节点', exact: true }).click();
  await actions.getByLabel('channel', { exact: true }).fill('EMAIL');
  await actions.getByLabel('recipient', { exact: true }).fill('local@example.com');
  await dialog.getByRole('button', { name: '保存新版本', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '发布此版本' })).toHaveCount(1);
  await dialog.getByRole('button', { name: '载入编辑' }).click();
  await expect(conditions.getByLabel('value', { exact: true })).toHaveValue('30');
  await dialog.getByRole('button', { name: '发布此版本' }).click();
  await page.getByRole('button', { name: '确定', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '暂停自动化', exact: true })).toBeEnabled();
  await fs.writeFile(process.env.AUTO_READY, 'published');
  await dialog.getByRole('link', { name: '查看执行记录及投递状态' }).click();
  await page.getByRole('tab', { name: '自动化执行', exact: true }).click();
  await expect(page.getByRole('button', { name: '运行详情', exact: true })).toHaveCount(1);
  await page.getByRole('button', { name: '运行详情', exact: true }).click();
  await expect(page.getByTestId('automation-execution-detail')).toContainText('DISPATCHED');
  assert.ok(!(await page.getByTestId('automation-execution-detail').textContent()).includes('input_snapshot'));
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: '自动化运行详情' })).not.toBeVisible();
  await page.locator('#app-sidebar').getByText('自动化', { exact: true }).click();
  await page.getByRole('button', { name: '管理', exact: true }).click();
  await dialog.getByRole('button', { name: '暂停自动化', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '暂停自动化', exact: true })).toBeDisabled();
  await conditions.getByLabel('value', { exact: true }).fill('100');
  await dialog.getByRole('button', { name: '保存新版本', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '发布此版本' })).toHaveCount(2);
  await dialog.getByRole('button', { name: '删除自动化', exact: true }).click();
  await page.getByRole('button', { name: '确定', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  assert.deepEqual(errors, []);
  console.log('PASS real Console automation device/conditions/actions/publish/logs/pause/revise/delete');
})().catch(async error => {
  console.error(error);
  if (page) { await page.screenshot({ path: path.join(path.dirname(process.env.AUTO_READY), 'browser-failure.png') }).catch(() => {}); console.error((await page.locator('body').innerText().catch(() => '')).slice(-5000)); }
  process.exitCode = 1;
}).finally(async () => { await context?.close(); await browser?.close(); if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); } });
