#!/usr/bin/env node
// G3-HOST-1f：只转发真实后端，使用不可变A/B实际构建与真实CLI；不启动Vite或替换业务响应。
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs/promises');
const http = require('node:http');
const { createRequire } = require('node:module');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { randomUUID } = require('node:crypto');
const root = path.resolve(__dirname, '../..');
const webapp = path.join(root, 'things-link-webapp');
process.env.PLAYWRIGHT_BROWSERS_PATH ??= path.join(webapp, '.playwright-browsers');
const { chromium, expect } = createRequire(path.join(webapp, 'package.json'))('@playwright/test');
const origin = `http://localhost:${process.env.HOST_JOURNEY_PORT}`;
const backend = process.env.HOST_JOURNEY_BACKEND;
const entry = `${origin}/app/${process.env.HOST_JOURNEY_APP_KEY}`;
let proxy, browser, context, observer, page, step = 'setup';
const receipt = { checks: {}, releases: [], applicationVersionId: process.env.HOST_JOURNEY_APP_VERSION,
  dashboardVersionId: process.env.HOST_JOURNEY_BOARD_VERSION };
const currents = [];
const pageErrors = [];
const milestone = value => { step = value; console.log(`HOST_JOURNEY ${value}`); };
async function switchHost(version, expectedRevision) {
  const { stdout } = await promisify(execFile)(process.env.HOST_JOURNEY_JAVA,
    ['-jar', process.env.HOST_JOURNEY_JAR, 'activate', version, String(expectedRevision), randomUUID()],
    { env: process.env, timeout: 20000, maxBuffer: 65536 });
  const result = JSON.parse(stdout);
  assert.equal(result.result, 'COMMITTED'); assert.equal(result.current.hostVersion, version);
  assert.equal(result.current.revision, expectedRevision + 1);
  return result.current.artifactDigest;
}
async function openApplication() {
  page = await context.newPage();
  await page.goto(entry);
  await expect(page.getByTestId('login-form').or(page.locator('text=HOST_SWITCH_SAME_PUBLICATION'))).toBeVisible({ timeout: 20000 });
  if (await page.getByTestId('login-form').isVisible()) {
    await page.getByTestId('username').fill(process.env.HOST_JOURNEY_USERNAME);
    await page.getByTestId('password').fill(process.env.HOST_JOURNEY_PASSWORD);
    await page.getByTestId('login-submit').click();
  }
  await expect(page.getByText('HOST_SWITCH_SAME_PUBLICATION', { exact: true })).toBeVisible({ timeout: 20000 });
  await expect(page.getByAltText('固定资源')).toBeVisible();
  await page.waitForFunction(() => document.querySelector('[data-testid="pwa-status"]')?.getAttribute('data-status') === 'READY', null, { timeout: 30000 });
}
async function activeController(digest) {
  await page.waitForFunction(expected => navigator.serviceWorker.controller?.scriptURL === expected,
    `${origin}/app/releases/${digest}/sw.js`, { timeout: 30000 });
}
async function waitForUpdate(digest) {
  // 正常online恢复触发产品内置发现逻辑，不从测试调用register/skipWaiting/claim。
  await context.setOffline(true); await context.setOffline(false);
  await page.waitForFunction(expected => navigator.serviceWorker.getRegistration('/app/').then(value => value?.waiting?.scriptURL === expected),
    `${origin}/app/releases/${digest}/sw.js`, { timeout: 40000 });
  await expect(page.getByTestId('pwa-status')).toHaveAttribute('data-status', 'UPDATE_WAITING');
}
async function closeOldAndActivate(digest) {
  await page.close();
  await observer.waitForFunction(expected => navigator.serviceWorker.getRegistration('/app/').then(value => value?.active?.scriptURL === expected),
    `${origin}/app/releases/${digest}/sw.js`, { timeout: 30000 });
  await openApplication(); await activeController(digest);
}
(async () => {
  proxy = http.createServer((request, response) => {
    // 保持真实HTTP状态/正文，仅将同源浏览器请求转发到专用测试后端。
    const upstream = http.request(new URL(request.url, backend), { method: request.method, headers: request.headers }, incoming => {
      response.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(response);
    });
    upstream.on('error', () => { if (!response.headersSent) response.writeHead(502); response.end(); });
    request.pipe(upstream);
  });
  await new Promise((resolve, reject) => { proxy.once('error', reject); proxy.listen(Number(process.env.HOST_JOURNEY_PORT), resolve); });
  browser = await chromium.launch({ headless: true });
  context = await browser.newContext();
  context.on('page', item => {
    item.on('pageerror', () => pageErrors.push('pageerror'));
    item.on('response', async response => {
      if (/\/api\/v1\/app\/applications\/[^/]+\/current$/.test(new URL(response.url()).pathname) && response.status() === 200) {
        try { const value = await response.json(); currents.push({ app: value.applicationVersionId, board: value.dashboards[0]?.dashboardVersionId }); }
        catch { /* 导航取消的响应不认领为已观测current；最终必须取得完整样本。 */ }
      }
    });
  });
  // 观察页在/app scope之外，不保活旧Service Worker客户端。
  observer = await context.newPage(); await observer.goto(origin + '/');
  milestone('A_OPEN'); await openApplication();
  const a = await page.evaluate(async () => (await fetch('/app/host-candidate.json', { credentials: 'omit', cache: 'no-store' })).json());
  assert.equal(a.hostVersion, '1.1.0'); receipt.releases.push(a.artifactDigest);
  await page.reload(); await activeController(a.artifactDigest);
  await expect(page.getByText('HOST_SWITCH_SAME_PUBLICATION', { exact: true })).toBeVisible({ timeout: 20000 });
  receipt.checks.initialA = true;
  milestone('B_ACTIVATE'); const b = await switchHost('1.1.1', 1); receipt.releases.push(b);
  assert.notEqual(a.artifactDigest, b);
  await waitForUpdate(b); await activeController(a.artifactDigest);
  const oldResource = await context.request.get(`${origin}/app/releases/${a.artifactDigest}/index.html`);
  assert.equal(oldResource.status(), 200); assert.match(oldResource.headers()['cache-control'], /immutable/);
  receipt.checks.oldAResourceAndWaitingB = true;
  milestone('B_OPEN'); await closeOldAndActivate(b); receipt.checks.newB = true;
  milestone('OFFLINE_PUBLIC_ONLY');
  await context.setOffline(true);
  await expect(page.getByText('HOST_SWITCH_SAME_PUBLICATION', { exact: true })).not.toBeVisible();
  const offline = await page.reload(); assert.equal(offline.status(), 200); assert.equal(offline.fromServiceWorker(), true);
  await expect(page.getByText('HOST_SWITCH_SAME_PUBLICATION', { exact: true })).not.toBeVisible();
  const cachePaths = await page.evaluate(async () => {
    const urls = [];
    for (const key of await caches.keys()) for (const request of await (await caches.open(key)).keys()) urls.push(new URL(request.url).pathname);
    return urls;
  });
  assert.ok(cachePaths.length > 0); assert.ok(cachePaths.every(value => value.startsWith('/app/') && !value.includes('/api/')));
  receipt.checks.offlineNoPrivateCanvas = true;
  await context.setOffline(false); await page.reload(); await activeController(b);
  await expect(page.getByText('HOST_SWITCH_SAME_PUBLICATION', { exact: true })).toBeVisible({ timeout: 20000 });
  milestone('A_ROLLBACK'); const again = await switchHost('1.1.0', 2); assert.equal(again, a.artifactDigest);
  await waitForUpdate(again); await activeController(b);
  milestone('A_REOPEN'); await closeOldAndActivate(again); receipt.checks.rollbackA = true;
  await expect.poll(() => currents.length).toBeGreaterThanOrEqual(3);
  assert.ok(currents.every(value => value.app === receipt.applicationVersionId && value.board === receipt.dashboardVersionId));
  receipt.checks.sameApplicationAndDashboardVersions = true;
  // 身份围栏会先清UI；必须等真实logout 204，不能见登录框就关闭页面中断撤族请求。
  const logout = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/app/browser-auth/logout');
  await page.getByTestId('logout-button').click(); assert.equal((await logout).status(), 204);
  await expect(page.getByTestId('login-form')).toBeVisible();
  receipt.checks.logout = true; assert.deepEqual(pageErrors, []);
  await fs.writeFile(process.env.HOST_JOURNEY_RECEIPT, JSON.stringify(receipt, null, 2) + '\n');
  milestone('PASS');
})().catch(async error => {
  console.error(`HOST_JOURNEY_FAILURE stage=${step} type=${error.name}`);
  await fs.writeFile(process.env.HOST_JOURNEY_RECEIPT, JSON.stringify({ ...receipt, failedStage: step, failureType: error.name }, null, 2) + '\n');
  process.exitCode = 1;
}).finally(async () => {
  await context?.close(); await browser?.close();
  if (proxy) await new Promise(resolve => proxy.close(resolve));
});
