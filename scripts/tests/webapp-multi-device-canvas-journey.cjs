#!/usr/bin/env node
// S12-3g：实际WebApp源码/Vite/Chromium+真实后端；合法历史夹具不等于生产Host/D-145资格。
const assert = require('node:assert/strict');
const path = require('node:path');
const http = require('node:http');
const fs = require('node:fs/promises');
const os = require('node:os');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { createRequire } = require('node:module');

const root = path.resolve(__dirname, '../..');
const webapp = path.join(root, 'things-link-webapp');
const fromWebapp = createRequire(path.join(webapp, 'package.json'));
process.env.PLAYWRIGHT_BROWSERS_PATH ??= path.join(webapp, '.playwright-browsers');
const { chromium } = fromWebapp('@playwright/test');
const { port: journeyPort, origin, reservePortCheck } = require('./webapp-journey-origin.cjs');
const backend = process.env.WEBAPP_MULTI_DEVICE_BACKEND_URL;
assert.ok(backend, '需要专用真实后端地址');
const accounts = [''].map(prefix => ({
  appKey: process.env[`WEBAPP_MULTI_DEVICE_${prefix}APP_KEY`],
  username: process.env[`WEBAPP_MULTI_DEVICE_${prefix}USERNAME`],
  password: process.env[`WEBAPP_MULTI_DEVICE_${prefix}PASSWORD`],
  userId: process.env[`WEBAPP_MULTI_DEVICE_${prefix}USER_ID`],
  projectId: process.env[`WEBAPP_MULTI_DEVICE_${prefix}PROJECT_ID`],
}));
for (const account of accounts) for (const key of ['appKey', 'username', 'password']) assert.ok(account[key], '缺少临时静态画布夹具字段');

let vite;
let proxy;
let bodyGate;

let browser;
let viteFailure;
const failures = [];
const requests = [];

// 只记录低敏感路由/状态及可信公开身份，不保留密码、Cookie、令牌、完整header/body或console正文。
function observe(context) {
  context.on('page', page => {
    page.on('pageerror', () => failures.push('pageerror'));
    page.on('dialog', async dialog => { failures.push('executed-markup'); await dialog.dismiss(); });
  });
  context.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/')) requests.push({ path: url.pathname, method: request.method() });
  });

}



// 转发真实后端事实，只控制已生成当前值响应正文的到达时刻；不创建或替换任何业务JSON。
async function startBackendProxy() {
  proxy = http.createServer((request, response) => {
    const upstream = http.request(new URL(request.url, backend), {
      method: request.method, headers: { ...request.headers, host: new URL(backend).host },
    }, source => {
      const chunks = [];
      source.on('data', chunk => chunks.push(chunk));
      source.on('end', () => {
        const bytes = Buffer.concat(chunks);
        const gate = bodyGate;
        if (gate && (typeof gate.path === 'string' ? request.url === gate.path : gate.path.test(request.url))) {
          bodyGate = undefined;
          if (source.statusCode !== 200 || bytes.length <= 1) {
            failures.push('invalid-real-current-gate');
            response.writeHead(source.statusCode, source.headers); response.end(bytes); gate.ready(); return;
          }
          response.writeHead(source.statusCode, source.headers);
          response.write(bytes.subarray(0, 1));
          response.flushHeaders();
          gate.release = () => response.end(bytes.subarray(1));
          gate.ready();
        } else { response.writeHead(source.statusCode, source.headers); response.end(bytes); }
      });
    });
    upstream.on('error', () => { response.writeHead(502); response.end(); });
    request.pipe(upstream);
  });
  await new Promise((resolve, reject) => { proxy.once('error', reject); proxy.listen(0, '127.0.0.1', resolve); });
  return 'http://127.0.0.1:' + proxy.address().port;
}
function delayedResponse(requestPath) {
  let ready;
  const received = new Promise(resolve => { ready = resolve; });
  const gate = { path: requestPath, ready, received, release: undefined };
  bodyGate = gate;
  return gate;
}
async function bounded(promise, description) {
  let timer;
  try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(description)), 15000); })]); }
  finally { clearTimeout(timer); }
}

async function startVite() {
  await reservePortCheck();
  const bin = path.join(path.dirname(fromWebapp.resolve('vite/package.json')), 'bin/vite.js');
  const target = await startBackendProxy();
  const environment = { ...process.env, VITE_DEV_API_TARGET: target };
  // 账户凭据仅留在Node旅程进程；不传播给Vite，更不创建VITE_密码字段。
  for (const name of Object.keys(environment)) if (name.startsWith('WEBAPP_MULTI_DEVICE_')) delete environment[name];
  vite = spawn(process.execPath, [bin, '--host', 'localhost', '--port', String(journeyPort), '--strictPort'],
    { cwd: webapp, env: environment, stdio: ['ignore', 'pipe', 'pipe'] });
  vite.once('error', () => { viteFailure = 'vite-launch'; });
  vite.once('exit', () => { viteFailure = 'vite-exit'; });
  // 消费输出避免子进程管道阻塞；不打印可能含环境或路径的服务器原始诊断。
  vite.stdout.on('data', () => {});
  vite.stderr.on('data', () => {});
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    assert.equal(viteFailure, undefined, '实际Vite必须保持运行');
    try {
      const response = await fetch(origin + '/app/', { signal: AbortSignal.timeout(1000) });
      if (response.ok && (await response.text()).includes('/@vite/client')) return;
    } catch { /* Vite启动期间只做有限就绪探测，不伪造业务响应。 */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('实际WebApp Vite启动超时');
}

function element(page, id) {
  if (id === 'session-ready') return page.locator('[data-testid="session-status"][data-status="authenticated"]');
  if (id === 'application-entry') return page.locator('.application-facts');
  return page.getByTestId(id);
}
async function visible(page, id) { await element(page, id).waitFor({ state: 'visible', timeout: 15000 }); }
async function hidden(page, id) { await element(page, id).waitFor({ state: 'hidden', timeout: 15000 }); }
async function login(page, account) {
  if (await page.getByTestId('switch-account-button').isVisible()) await page.getByTestId('switch-account-button').click();
  await visible(page, 'username');
  await page.getByTestId('username').fill(account.username);
  await page.getByTestId('password').fill(account.password);
  await page.getByTestId('login-submit').click();
  await page.locator('[data-testid="session-status"][data-status="authenticated"]').waitFor({ timeout: 15000 });
}
async function storageIsNonCredential(page) {
  const state = await page.evaluate(async () => ({
    keys: Object.keys(localStorage),
    epoch: localStorage.getItem('tc.app.browser.epoch'),
    sessionKeys: Object.keys(sessionStorage),
    cacheNames: await caches.keys(),
    registrations: (await navigator.serviceWorker.getRegistrations()).length,
  }));
  assert.deepEqual(state.keys, ['tc.app.browser.epoch']);
  assert.match(state.epoch, /^be_[A-Za-z0-9_-]{21}[AQgw]$/);
  assert.deepEqual(state.sessionKeys, []);
  assert.deepEqual(state.cacheNames, []);
  assert.equal(state.registrations, 0);
}

async function selection(selector) {
  return selector.evaluate(element => [...element.selectedOptions].map(option => option.value).sort());
}
async function row(page, device, temperature) {
  const row = page.locator('[data-testid="device-table-row-table"][data-device-id="' + device + '"]');
  await row.waitFor({ state: 'visible', timeout: 15000 });
  if (temperature) await page.waitForFunction(({ device, temperature }) =>
    document.querySelector('[data-testid="device-table-row-table"][data-device-id="' + device + '"]')?.textContent.includes(temperature),
    { device, temperature }, { timeout: 15000 });
  return row;
}
function apiCount() { return requests.length; }
async function main() {
  await startVite();
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
  observe(context);
  const a = await context.newPage();
  const first = process.env.WEBAPP_MULTI_DEVICE_FIRST_ID;
  const second = process.env.WEBAPP_MULTI_DEVICE_SECOND_ID;
  assert.ok(first && second);
  await a.goto(origin + '/app/' + accounts[0].appKey);
  await login(a, accounts[0]);
  await visible(a, 'device-selector-selector');
  const selector = a.getByTestId('device-selector-selector');
  const copy = a.getByTestId('device-selector-selector_copy');
  await selector.locator('option[value="' + first + '"]').waitFor({ state: 'attached' });
  assert.deepEqual(await selection(selector), [], '无默认值不得自动选设备');
  await selector.selectOption([first, second]);
  await row(a, first, '12.5');
  assert.deepEqual(await selection(copy), [first, second].sort(), '同变量选择器共享真实内存选择');
  assert.equal(await (await row(a, first)).locator('[data-column-id="secret"]').getAttribute('data-state'), 'NO_VALUE');
  const beforeLocalPage = apiCount();
  await a.getByTestId('device-table-next-table').click();
  await row(a, second, '78.25');
  await a.getByTestId('device-table-previous-table').click();
  await row(a, first, '12.5');
  assert.equal(apiCount(), beforeLocalPage, 'rowLimit本地翻页不得新增任何API请求');
  await a.getByTestId('device-catalog-next-selector').click();
  await selector.locator('option').filter({ hasText: '目录温度设备' }).waitFor({ state: 'attached' });
  assert.deepEqual(await selection(selector), [first, second].sort(), '目录新页不能撤销页外选择');
  assert.equal(await selector.locator('option').count(), 3, '仅当页C和已选A/B，不累计目录');
  await a.getByTestId('device-catalog-first-selector').click();
  // A作为页外已选项本来就存在，必须等待目录请求实际收束，不能把它当首页到达证据。
  await a.waitForFunction(() => document.querySelector('[data-testid="recovery-status"]')?.getAttribute('data-status') === 'READY');
  await selector.locator('option[value="' + first + '"]').waitFor({ state: 'attached' });
  assert.equal(await selector.locator('option').filter({ hasText: '目录温度设备' }).count(), 0, '回首页不缓存旧C页');
  const screenshots = await fs.mkdtemp(path.join(os.tmpdir(), 'webapp-multi-device-canvas-'));
  await a.screenshot({ path: path.join(screenshots, 'desktop.png'), fullPage: true });
  await a.setViewportSize({ width: 390, height: 844 });
  assert.equal(await a.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await a.screenshot({ path: path.join(screenshots, 'mobile.png'), fullPage: true });
  await a.setViewportSize({ width: 1400, height: 1000 });
  console.log('SCREENSHOTS ' + screenshots);
  console.log('PASS 两个共享多选器、真实双设备表格、NO_VALUE、本地翻页零HTTP及目录替换页保留选择');

  // 单选B作为对照，再挂起真实旧集合[A,B]，撤选A必须清掉旧行。
  await selector.selectOption([second]);
  await row(a, second, '78.25');
  const gate = delayedResponse('/api/v1/app/devices/current-values/query');
  await selector.selectOption([first, second]);
  await bounded(gate.received, '等待真实旧集合当前值响应');
  await copy.selectOption([second]);
  assert.equal(await a.locator('[data-testid="device-table-row-table"][data-device-id="' + first + '"]').count(), 0,
    '撤选时立即清掉旧集合行');
  gate.release();
  await row(a, second, '78.25');
  assert.equal(await a.locator('[data-testid="device-table-row-table"][data-device-id="' + first + '"]').count(), 0);
  assert.deepEqual(await selection(selector), [second]);
  console.log('PASS 旧集合真实当前值迟到不能复活已撤选行，另一共享选择器同步');

  const revoked = await fetch(process.env.WEBAPP_MULTI_DEVICE_CONTROL_URL + '/revoke', { method: 'POST' });
  assert.equal(revoked.status, 204);
  await context.setOffline(true);
  await hidden(a, 'dashboard-canvas');
  await context.setOffline(false);
  await a.waitForFunction(() => document.querySelector('[data-testid="device-table-row-table"]')?.getAttribute('data-state') === 'NOT_AVAILABLE',
    null, { timeout: 15000 });
  const table = a.getByTestId('device-table-table');
  assert.ok(!(await table.textContent()).includes('78.25'));
  assert.ok(!(await table.textContent()).includes('温度乙'));
  console.log('PASS 真实PG撤权后复验表格不暴露旧设备名与值');
  await storageIsNonCredential(a);
  await a.getByTestId('logout-button').click();
  await hidden(a, 'dashboard-canvas');
  await a.waitForFunction(() => document.querySelector('[data-testid="session-status"]')?.getAttribute('data-status') === 'anonymous');
  assert.equal((await context.cookies(origin + '/api/v1/app/browser-auth')).filter(row => row.name === 'tc_app_refresh').length, 0);
  assert.equal(failures.length, 0);
  assert.equal(viteFailure, undefined);
  console.log('PASS 真实PG撤销设备绑定清旧值，真实退出清Cookie及画布');
  await context.close();
}

async function cleanup() {
  if (browser) await browser.close();
  if (proxy) { proxy.closeAllConnections(); proxy.close(); }
  if (vite && vite.exitCode === null) {
    vite.kill('SIGTERM');
    await Promise.race([once(vite, 'exit'), new Promise(resolve => setTimeout(resolve, 3000))]);
    if (vite.exitCode === null) vite.kill('SIGKILL');
  }
}
main().catch(async error => {
  // 不打印assert actual/expected或环境，避免身份响应/Cookie进入诊断。
  console.error('WebApp多设备画布旅程失败：' + error.name + '\n'
    + (error.stack?.split('\n').filter(line => /at .*webapp-multi-device-canvas-journey\.cjs:\d+/.test(line)).join('\n') ?? '未知位置'));
  console.log('DIAG failure kinds=' + JSON.stringify(failures));
  const page = browser?.contexts()[0]?.pages()[0];
  if (page) console.log('DIAG page=' + JSON.stringify(await page.evaluate(() => ({
    session: document.querySelector('[data-testid="session-status"]')?.getAttribute('data-status'),
    recovery: document.querySelector('[data-testid="recovery-status"]')?.getAttribute('data-status'),
    canvas: document.querySelector('[data-testid="static-dashboard-error"]')?.textContent,
    error: document.querySelector('[data-testid="error-message"]')?.textContent,
    kinds: [...document.querySelectorAll('[data-kind]')].map(element => element.getAttribute('data-kind')),
  }))));
  process.exitCode = 1;
}).finally(cleanup);
