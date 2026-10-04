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
  await page.getByText('消息规则', { exact: true }).click();
  await page.getByRole('button', { name: '创建规则', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '消息规则版本管理', exact: true });
  await dialog.getByLabel('规则名称', { exact: true }).fill('浏览器规则');
  await dialog.getByLabel('规则源码', { exact: true }).fill('input => input');
  await dialog.locator('.el-select').first().click();
  await page.getByRole('option', { name: 'notification-action', exact: true }).click();
  await dialog.getByRole('button', { name: '添加节点', exact: true }).click();
  await dialog.getByLabel('channel', { exact: true }).fill('EMAIL');
  await dialog.getByLabel('recipient', { exact: true }).fill('local@example.com');
  await dialog.getByRole('button', { name: '保存新版本', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '发布此版本' })).toHaveCount(1);
  await dialog.getByLabel('规则源码', { exact: true }).fill('input => ({value: input.value + 1})');
  await dialog.getByRole('button', { name: '保存新版本', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '发布此版本' })).toHaveCount(2);
  await dialog.getByRole('button', { name: '载入编辑' }).last().click();
  await expect(dialog.getByLabel('recipient', { exact: true })).toHaveValue('local@example.com');
  await expect(dialog.getByLabel('规则源码', { exact: true })).toHaveValue('input => input');
  await dialog.getByRole('button', { name: '发布此版本' }).first().click();
  await page.getByRole('button', { name: '确定', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '暂停规则', exact: true })).toBeEnabled();
  await dialog.getByLabel('调试样例', { exact: true }).fill('{"value":4}');
  await dialog.getByRole('button', { name: '调试此版本' }).first().click();
  await expect(dialog.getByTestId('debug-result')).toContainText('SUCCESS', { timeout: 20000 });
  assert.equal(JSON.parse(JSON.parse(await dialog.getByTestId('debug-result').textContent()).outputJson).value, 5);
  await dialog.getByRole('button', { name: '暂停规则', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '暂停规则', exact: true })).toBeDisabled();
  await dialog.getByRole('button', { name: '发布此版本' }).last().click();
  await page.getByRole('button', { name: '确定', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '暂停规则', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: '删除规则', exact: true }).click();
  await page.getByRole('button', { name: '确定', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  assert.deepEqual(errors, []);
  console.log('PASS real Console login/project/immutable rule versions/actions/debug/publish/pause/rollback/delete');
})().catch(async error => {
  console.error(error);
  if (page) { await page.screenshot({ path: path.join(root, 'logs/verify/g3-rule-1/browser-failure.png') }).catch(() => {}); console.error((await page.locator('body').innerText().catch(() => '')).slice(-5000)); }
  process.exitCode = 1;
}).finally(async () => { await context?.close(); await browser?.close(); if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); } });
