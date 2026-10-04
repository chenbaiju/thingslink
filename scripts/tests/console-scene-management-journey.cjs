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
  await page.getByText('手动场景', { exact: true }).click();
  await page.getByRole('button', { name: '创建场景', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '手动场景版本管理', exact: true });
  await dialog.getByLabel('场景名称', { exact: true }).fill('浏览器场景');
  const editors = dialog.locator('section');
  const conditions = editors.nth(0), actions = editors.nth(1);
  await conditions.locator('.el-select').first().click();
  await page.getByRole('option', { name: 'payload-property-compare', exact: true }).click();
  await conditions.getByRole('button', { name: '添加节点', exact: true }).click();
  await conditions.getByLabel('pointer', { exact: true }).fill('/temperature');
  await conditions.locator('.node-card .el-select').click();
  await page.getByRole('option', { name: 'GT', exact: true }).click();
  await conditions.getByLabel('value', { exact: true }).fill('30');
  await actions.locator('.el-select').first().click();
  await page.getByRole('option', { name: 'notification-action', exact: true }).click();
  for (const recipient of ['first@example.com', 'second@example.com']) {
    await actions.getByRole('button', { name: '添加节点', exact: true }).click();
    const node = actions.locator('.node-card').last();
    await node.getByLabel('channel', { exact: true }).fill('EMAIL');
    await node.getByLabel('recipient', { exact: true }).fill(recipient);
  }
  await actions.locator('.node-card').last().getByRole('button', { name: '上移', exact: true }).click();
  await dialog.getByRole('button', { name: '保存新版本', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '发布此版本' })).toHaveCount(1);
  await dialog.getByRole('button', { name: '载入编辑' }).click();
  await expect(actions.getByLabel('recipient', { exact: true }).first()).toHaveValue('second@example.com');
  await expect(conditions.getByLabel('value', { exact: true })).toHaveValue('30');
  await dialog.getByRole('button', { name: '发布此版本' }).click();
  await page.getByRole('button', { name: '确定', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '暂停场景', exact: true })).toBeEnabled();
  const deviceResponse = page.waitForResponse(r => r.url().includes('/devices/search') && r.status() === 200);
  await dialog.getByRole('button', { name: '搜索设备', exact: true }).click();
  const devicePage = await (await deviceResponse).json();
  assert.ok(devicePage.items.some(item => item.name === '场景设备'));
  await expect(dialog.getByRole('button', { name: '搜索设备', exact: true })).toBeEnabled();
  await dialog.locator('.el-select').last().click();
  await page.getByRole('option', { name: '场景设备', exact: true }).click();
  await dialog.getByLabel('执行载荷', { exact: true }).fill('{"temperature":42}');
  await dialog.getByRole('button', { name: '执行场景', exact: true }).click();
  await expect(dialog.getByTestId('execution-result')).toContainText('DISPATCHED');
  const firstExecution = JSON.parse(await dialog.getByTestId('execution-result').textContent());
  assert.ok(firstExecution.id);
  await dialog.getByRole('button', { name: '暂停场景', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '执行场景', exact: true })).toBeDisabled();
  await conditions.getByLabel('value', { exact: true }).fill('100');
  await dialog.getByRole('button', { name: '保存新版本', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '发布此版本' })).toHaveCount(2);
  await dialog.getByRole('button', { name: '发布此版本' }).first().click();
  await page.getByRole('button', { name: '确定', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '执行场景', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: '执行场景', exact: true }).click();
  await expect(dialog.getByTestId('execution-result')).toContainText('SKIPPED');
  await dialog.getByRole('button', { name: '发布此版本' }).last().click();
  await page.getByRole('button', { name: '确定', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '暂停场景', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: '执行场景', exact: true }).click();
  await expect(dialog.getByTestId('execution-result')).toContainText('DISPATCHED');
  const logsResponse = page.waitForResponse(r => /\/scene-executions(?:\?|$)/.test(r.url()) && r.status() === 200);
  await dialog.getByRole('link', { name: '查看执行记录及投递状态' }).click();
  const logs = await (await logsResponse).json();
  assert.ok(logs.items.some(item => item.id === firstExecution.id));
  await expect(page.getByRole('button', { name: '详情', exact: true })).toHaveCount(3);
  await page.getByRole('button', { name: '详情', exact: true }).last().click();
  await expect(page.getByText(firstExecution.sceneVersionId, { exact: true })).toBeVisible();
  assert.deepEqual(errors, []);
  console.log('PASS real Console scene conditions/ordered actions/publish/execute/pause/revise/rollback/execution logs');
})().catch(async error => {
  console.error(error);
  if (page) { await page.screenshot({ path: path.join(root, 'logs/verify/g3-rule-1b/browser-failure.png') }).catch(() => {}); console.error((await page.locator('body').innerText().catch(() => '')).slice(-5000)); }
  process.exitCode = 1;
}).finally(async () => { await context?.close(); await browser?.close(); if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); } });
