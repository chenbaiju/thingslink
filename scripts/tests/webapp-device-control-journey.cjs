#!/usr/bin/env node
// ADR0110：只控制真实HTTP响应到达，不伪造202、目录、命令状态或最终成功。
const assert = require('node:assert/strict');
const path = require('node:path');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
const webapp = path.join(root, 'things-link-webapp');
const fromWebapp = createRequire(path.join(webapp, 'package.json'));
process.env.PLAYWRIGHT_BROWSERS_PATH ??= path.join(webapp, '.playwright-browsers');
const { chromium } = fromWebapp('@playwright/test');
const { port, origin, reservePortCheck } = require('./webapp-journey-origin.cjs');
const backend = process.env.WEBAPP_CONTROL_BACKEND_URL;
const control = process.env.WEBAPP_CONTROL_CONTROL_URL;
const appKey = process.env.WEBAPP_CONTROL_APP_KEY;
const device = process.env.WEBAPP_CONTROL_FIRST_ID;
for (const value of [backend, control, appKey, device, process.env.WEBAPP_CONTROL_USERNAME, process.env.WEBAPP_CONTROL_PASSWORD]) assert.ok(value, '缺少独立控制旅程夹具');
let browser; let proxy; let vite; let page;
let dropAccepted = false; let holdStatus = false; let releaseStatus; let statusReady;
const accepted = []; const failures = []; const sockets = new Set();
const commandPath = new RegExp('^/api/v1/app/devices/' + device + '/commands$');
const statusPath = /^\/api\/v1\/app\/devices\/[0-9a-f-]+\/commands\/[0-9a-f-]+$/;
async function action(route) {
  const response = await fetch(control + route, { method: 'POST', signal: AbortSignal.timeout(5000) });
  assert.equal(response.status, 204, '真实测试控制端必须完成权威动作');
}
async function start() {
  await reservePortCheck();
  proxy = http.createServer((request, response) => {
    const body = [];
    request.on('data', chunk => body.push(chunk));
    request.on('end', () => {
      const bytes = Buffer.concat(body);
      const upstream = http.request(new URL(request.url, backend), { method: request.method, headers: { ...request.headers, host: new URL(backend).host } }, source => {
        const chunks = [];
        source.on('data', chunk => chunks.push(chunk));
        source.on('end', () => {
          const result = Buffer.concat(chunks);
          if (request.url.includes('/commands') || request.url.includes('/command-definitions')) {
            let code; try { const parsed = JSON.parse(result.toString('utf8')); if (typeof parsed.code === 'number') code = parsed.code; } catch { /* 不输出原文。 */ }
            console.log('HTTP control ' + JSON.stringify({ method: request.method, status: source.statusCode, code }));
          }
          if (request.method === 'POST' && commandPath.test(request.url) && source.statusCode === 202) {
            const value = JSON.parse(result.toString('utf8'));
            // 仅内存比较同一意图，不输出命令正文/幂等键到日志。
            accepted.push({ key: request.headers['idempotency-key'], body: bytes.toString('utf8'), id: value.commandId ?? value.id });
            if (dropAccepted) { dropAccepted = false; response.destroy(); return; }
          }
          if (request.method === 'GET' && statusPath.test(request.url) && holdStatus) {
            holdStatus = false;
            releaseStatus = () => { if (!response.destroyed) { response.writeHead(source.statusCode, source.headers); response.end(result); } };
            statusReady(); return;
          }
          response.writeHead(source.statusCode, source.headers); response.end(result);
        });
      });
      upstream.on('error', () => { if (!response.headersSent) response.writeHead(502); response.end(); });
      upstream.end(bytes);
    });
  });
  proxy.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
  await new Promise(resolve => proxy.listen(0, '127.0.0.1', resolve));
  const target = 'http://127.0.0.1:' + proxy.address().port;
  const environment = { ...process.env, VITE_DEV_API_TARGET: target };
  for (const key of Object.keys(environment)) if (key.startsWith('WEBAPP_CONTROL_')) delete environment[key];
  const bin = path.join(path.dirname(fromWebapp.resolve('vite/package.json')), 'bin/vite.js');
  vite = spawn(process.execPath, [bin, '--host', 'localhost', '--port', String(port), '--strictPort'], { cwd: webapp, env: environment, stdio: ['ignore', 'pipe', 'pipe'] });
  vite.stdout.on('data', () => {}); vite.stderr.on('data', () => {});
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    assert.equal(vite.exitCode, null, '独立Vite应持续运行');
    try {
      const response = await fetch(origin + '/app/', { signal: AbortSignal.timeout(1000) });
      if (response.ok && (await response.text()).includes('/@vite/client')) {
        const probe = await fetch(origin + '/api/v1/app/applications/' + appKey + '/resolve', { signal: AbortSignal.timeout(3000) });
        assert.equal(probe.status, 200); assert.equal((await probe.json()).appKey, appKey); return;
      }
    } catch { /* 仅有界公开就绪探测，不自动重试业务写入。 */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('控制旅程Vite启动超时');
}
async function state(value) {
  await page.locator('[data-testid="control-status"][data-status="' + value + '"]').waitFor({ timeout: 15000 });
}
async function selectCommand() {
  await page.getByTestId('control-device-first').click();
  await page.locator('[data-testid="control-device"] option[value="' + device + '"]').waitFor({ state: 'attached', timeout: 15000 });
  await page.getByTestId('control-device').selectOption(device);
  await page.locator('[data-testid="control-command"] option[value="adjust"]').waitFor({ state: 'attached', timeout: 15000 });
  await page.getByTestId('control-command').selectOption('adjust');
}
async function run() {
  await start(); browser = await chromium.launch({ headless: true });
  const context = await browser.newContext(); page = await context.newPage();
  page.on('pageerror', () => failures.push('pageerror'));
  page.on('dialog', async dialog => { failures.push('executed-content'); await dialog.dismiss(); });
  await page.goto(origin + '/app/' + appKey);
  await page.getByTestId('username').fill(process.env.WEBAPP_CONTROL_USERNAME);
  await page.getByTestId('password').fill(process.env.WEBAPP_CONTROL_PASSWORD);
  await page.getByTestId('login-submit').click();
  await page.locator('[data-testid="session-status"][data-status="authenticated"]').waitFor({ timeout: 20000 });
  await page.getByTestId('device-control-open').click(); await selectCommand();
  assert.ok((await page.getByTestId('control-input-schema').textContent()).includes('integer'), '真实目录应保留输入Schema原文');
  await page.getByTestId('control-input').fill('{"level":"bad-type"}');
  await page.getByTestId('control-submit').click(); await state('REJECTED'); await action('/facts?empty');
  console.log('PASS actual-catalog-and-schema-rejection');
  await page.getByTestId('control-input').fill('{"level":3}'); dropAccepted = true;
  await page.getByTestId('control-submit').click(); await state('UNKNOWN'); await action('/facts?once');
  assert.equal(accepted.length, 1); assert.equal(await page.getByTestId('control-input').isDisabled(), true);
  await new Promise(resolve => setTimeout(resolve, 350)); assert.equal(accepted.length, 1, '未知结果不可自动重发');
  await page.getByTestId('control-retry').click(); await state('ACCEPTED');
  assert.equal(accepted.length, 2); assert.ok(accepted[0].key); assert.equal(accepted[1].key, accepted[0].key);
  assert.equal(accepted[1].body, accepted[0].body); assert.ok(accepted[0].id); assert.equal(accepted[1].id, accepted[0].id);
  await action('/facts?once'); console.log('PASS unknown-explicit-same-intent-retry-single-command');
  await action('/transition?dispatch'); await page.getByTestId('control-refresh').click(); await state('DISPATCHED');
  await action('/transition?ack'); await page.getByTestId('control-refresh').click(); await state('ACKNOWLEDGED');
  assert.equal(await page.locator('[data-testid="control-status"][data-status="SUCCEEDED"]').count(), 0);
  await action('/transition?success'); await page.getByTestId('control-refresh').click(); await state('SUCCEEDED');
  assert.ok((await page.getByTestId('control-result').textContent()).includes('true'));
  console.log('PASS actual-dispatch-ack-not-success-and-device-success');
  const received = new Promise(resolve => { statusReady = resolve; }); holdStatus = true;
  await page.getByTestId('control-refresh').click();
  let holdTimer;
  try { await Promise.race([received, new Promise((_, reject) => { holdTimer = setTimeout(() => reject(new Error('状态围栏未到达')), 8000); })]); }
  finally { clearTimeout(holdTimer); }
  await page.getByTestId('device-control-back').click(); releaseStatus();
  await page.getByTestId('control-status').waitFor({ state: 'hidden', timeout: 5000 });
  await page.getByTestId('device-control-open').click();
  assert.equal(await page.getByTestId('control-result').count(), 0); assert.equal(await page.getByTestId('control-input').count(), 0);
  await page.getByTestId('device-control-back').click(); await page.getByTestId('logout-button').click();
  await page.locator('[data-testid="session-status"][data-status="anonymous"]').waitFor({ timeout: 15000 });
  const persisted = await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) }));
  assert.deepEqual(persisted.local, ['tc.app.browser.epoch']); assert.deepEqual(persisted.session, []);
  assert.deepEqual(failures, []); console.log('PASS late-result-fence-and-logout-clears-private-intent');
}
async function cleanup() {
  releaseStatus?.(); await browser?.close();
  for (const socket of sockets) socket.destroy(); if (proxy) await new Promise(resolve => proxy.close(resolve));
  if (vite && vite.exitCode === null) { vite.kill('SIGTERM'); await Promise.race([once(vite, 'exit'), new Promise(resolve => setTimeout(resolve, 3000))]); if (vite.exitCode === null) vite.kill('SIGKILL'); }
}
run().catch(async error => {
  console.error('FAIL device-control-journey ' + (error?.name ?? 'Error'));
  if (page) console.error('STATE ' + JSON.stringify(await page.evaluate(() => ({ session: document.querySelector('[data-testid="session-status"]')?.getAttribute('data-status'), control: document.querySelector('[data-testid="control-status"]')?.getAttribute('data-status') })).catch(() => ({}))));
  process.exitCode = 1;
}).finally(cleanup);
