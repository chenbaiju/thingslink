#!/usr/bin/env node
// ADR0207～0210：真实生产Console与后端状态；代理只同源转发，不替换业务响应。
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
      const upstream = http.request(new URL(req.url, process.env.OTA_BACKEND), { method: req.method, headers: req.headers }, incoming => {
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
  page.on('response', response => {
    if (response.status() >= 400) console.error('HTTP', response.status(), new URL(response.url()).pathname);
  });
  await page.goto(origin + '/auth/login');
  await page.getByPlaceholder('请输入邮箱').fill(process.env.OTA_EMAIL);
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(process.env.OTA_PASSWORD);
  const track = await page.locator('.drag_verify').boundingBox(); const handle = await page.locator('.dv_handler').boundingBox();
  await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2); await page.mouse.down();
  await page.mouse.move(track.x + track.width - 2, handle.y + handle.height / 2, { steps: 12 }); await page.mouse.up();
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.locator('.project-switcher').click({ timeout: 15000 });
  const menus = page.waitForResponse(response => response.url().includes('/system/menus') && response.status() === 200);
  const switched = page.waitForURL(url => url.pathname === '/', { waitUntil: 'load' });
  await page.locator('.project-switcher-item').filter({ hasText: 'OTA测试项目' }).click();
  await switched;
  await menus;
  await page.getByText('OTA升级', { exact: true }).click();
  await page.getByText('灰度活动', { exact: true }).click();
  const dialog = page.getByRole('dialog');
  async function openAndVerify() {
    const [response] = await Promise.all([
      page.waitForResponse(r => r.url().includes(process.env.OTA_CAMPAIGN + '/execution') && r.status() === 200),
      page.getByRole('button', { name: '详情', exact: true }).first().click()
    ]);
    const execution = await response.json();
    assert.equal(execution.batchProgress.timedOutCount, 1);
    assert.equal(execution.batchProgress.succeededCount, 1);
    assert.equal(execution.batchProgress.rolledBackCount, 0);
    await expect(dialog.getByTestId('ota-campaign-status')).toHaveText(process.env.OTA_STATE === 'COMPLETED' ? '已完成' : '已暂停');
    const label = dialog.locator('td').filter({ hasText: /^已耗尽$/ });
    await expect(label).toHaveCount(1);
    assert.equal((await label.evaluate(cell => cell.nextElementSibling.textContent)).trim(), '1');
    const table = dialog.locator('.el-table').filter({ has: page.getByRole('columnheader', { name: '耗尽', exact: true }) });
    const headers = await table.locator('thead th').allTextContents();
    const index = headers.findIndex(text => text.trim() === '耗尽');
    assert.ok(index >= 0);
    await expect(table.locator('tbody tr').first().locator('td').nth(index)).toHaveText('1');
  }
  await openAndVerify();
  await page.reload();
  await openAndVerify();
  if (process.env.OTA_STATE === 'PAUSED') {
    await dialog.getByRole('button', { name: '恢复', exact: true }).click();
    const box = page.locator('.el-message-box');
    await box.locator('input').fill('浏览器不能绕过失败批次');
    const rejected = page.waitForResponse(r => r.url().includes('/resumptions') && r.request().method() === 'POST');
    await box.locator('.el-message-box__btns .el-button--primary').click();
    const response = await rejected;
    assert.equal(response.status(), 409);
    const body = await response.json(); assert.equal(body.code, 70040);
    await expect(dialog.getByTestId('ota-campaign-status')).toHaveText('已暂停');
  } else {
    await expect(dialog.getByRole('button', { name: '恢复', exact: true })).toHaveCount(0);
  }
  assert.deepEqual(errors, []);
  console.log('PASS real Console exhaustion count / frozen reload / ' + process.env.OTA_STATE + ' recovery boundary');
})().catch(async error => {
  console.error(error);
  if (page) {
    await page.screenshot({ path: path.join(root, 'logs/verify/s14-r8d-3b-2c-3/browser-' + process.env.OTA_STATE + '-failure.png') }).catch(() => {});
    console.error((await page.locator('body').innerText().catch(() => '')).slice(-5000));
  }
  process.exitCode = 1;
}).finally(async () => {
  await context?.close(); await browser?.close();
  if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});
