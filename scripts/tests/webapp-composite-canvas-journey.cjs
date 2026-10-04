#!/usr/bin/env node
// S12-3h：实际WebApp源码/Vite/Chromium+真实后端；合法历史夹具不等于生产Host/D-145资格。
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
const backend = process.env.WEBAPP_COMPOSITE_BACKEND_URL;
assert.ok(backend, '需要专用真实后端地址');
const accounts = [''].map(prefix => ({
  appKey: process.env[`WEBAPP_COMPOSITE_${prefix}APP_KEY`],
  username: process.env[`WEBAPP_COMPOSITE_${prefix}USERNAME`],
  password: process.env[`WEBAPP_COMPOSITE_${prefix}PASSWORD`],
  userId: process.env[`WEBAPP_COMPOSITE_${prefix}USER_ID`],
  projectId: process.env[`WEBAPP_COMPOSITE_${prefix}PROJECT_ID`],
}));
for (const account of accounts) for (const key of ['appKey', 'username', 'password']) assert.ok(account[key], '缺少临时静态画布夹具字段');

let vite;
let proxy;
let corruptNextCurrent = false;
let capturedBody;

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



// 成功路径转发真实后端原文；唯一反例明确注入257元素超限值，验证客户端拒绝而非伪造成功。
async function startBackendProxy() {
  proxy = http.createServer((request, response) => {
    const upstream = http.request(new URL(request.url, backend), {
      method: request.method, headers: { ...request.headers, host: new URL(backend).host },
    }, source => {
      const chunks = [];
      source.on('data', chunk => chunks.push(chunk));
      source.on('end', () => {
        const bytes = Buffer.concat(chunks);
        if (request.url === '/api/v1/app/devices/current-values/query' && source.statusCode === 200 && !capturedBody) capturedBody = bytes;
        if (corruptNextCurrent && request.url === '/api/v1/app/devices/current-values/query' && source.statusCode === 200) {
          corruptNextCurrent = false;
          const invalid = JSON.parse(bytes.toString('utf8'));
          invalid.devices[0].values.find(value => value.propertyKey === 'samples').value = Array(257).fill('非法超限');
          const altered = Buffer.from(JSON.stringify(invalid));
          const headers = { ...source.headers, 'content-length': altered.length };
          delete headers['transfer-encoding'];
          response.writeHead(200, headers); response.end(altered);
        } else { response.writeHead(source.statusCode, source.headers); response.end(bytes); }
      });
    });
    upstream.on('error', () => { response.writeHead(502); response.end(); });
    request.pipe(upstream);
  });
  await new Promise((resolve, reject) => { proxy.once('error', reject); proxy.listen(0, '127.0.0.1', resolve); });
  return 'http://127.0.0.1:' + proxy.address().port;
}
async function startVite() {
  await reservePortCheck();
  const bin = path.join(path.dirname(fromWebapp.resolve('vite/package.json')), 'bin/vite.js');
  const target = await startBackendProxy();
  const environment = { ...process.env, VITE_DEV_API_TARGET: target };
  // 账户凭据仅留在Node旅程进程；不传播给Vite，更不创建VITE_密码字段。
  for (const name of Object.keys(environment)) if (name.startsWith('WEBAPP_COMPOSITE_')) delete environment[name];
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

async function main() {
  await startVite();
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
  observe(context);
  const a = await context.newPage();
  const first = process.env.WEBAPP_COMPOSITE_FIRST_ID;
  const second = process.env.WEBAPP_COMPOSITE_SECOND_ID;
  assert.ok(first && second);
  await a.goto(origin + '/app/' + accounts[0].appKey);
  await login(a, accounts[0]);
  await visible(a, 'device-selector-selector');
  const selector = a.getByTestId('device-selector-selector');
  await selector.locator('option[value="' + first + '"]').waitFor({ state: 'attached' });
  await selector.selectOption(first);
  await visible(a, 'composite-list-list');
  await a.locator('[data-list-index="0"]').waitFor({ state: 'visible' });
  const json = a.getByTestId('composite-json-json');
  await json.waitFor({ state: 'visible' });
  assert.ok(capturedBody, '必须取得真实后端current原始字节');
  const receipt = await fetch(process.env.WEBAPP_COMPOSITE_CONTROL_URL + '/canonical', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: capturedBody,
  });
  assert.equal(receipt.status, 204);
  const beforeLocal = requests.length;
  await json.locator('details').first().locator('summary').first().click();
  await a.waitForFunction(() => document.querySelector('[data-testid="composite-json-json"]')?.textContent.includes('9007199254740993'));
  assert.ok((await json.textContent()).includes('中文😀'));
  assert.ok((await json.textContent()).includes('<script>alert(2)</script>'));
  assert.equal(await json.locator('script,img').count(), 0, '遥测字符串只能作为文本显示');
  await a.getByTestId('list-next-list').click();
  await a.locator('[data-list-index="255"]').waitFor({ state: 'visible' });
  assert.ok((await a.locator('[data-list-index="255"]').textContent()).includes('完整元素-255-中文😀'));
  await a.getByTestId('list-previous-list').click();
  await a.locator('[data-list-index="0"]').waitFor({ state: 'visible' });
  assert.equal(requests.length, beforeLocal, '完整列表分页和JSON展开不能新增任何API');
  const screenshots = await fs.mkdtemp(path.join(os.tmpdir(), 'webapp-composite-canvas-'));
  await a.screenshot({ path: path.join(screenshots, 'desktop.png'), fullPage: true });
  await a.setViewportSize({ width: 390, height: 844 });
  assert.equal(await a.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await a.screenshot({ path: path.join(screenshots, 'mobile.png'), fullPage: true });
  await a.setViewportSize({ width: 1400, height: 1000 });
  console.log('SCREENSHOTS ' + screenshots);
  console.log('PASS 真实PG OBJECT/LIST完整读取、大整数/Unicode安全文本、256项末项可达及本地分页展开零HTTP');

  // 唯一故意损坏响应：真实服务端正常返回后注入非法LIST，明确证明前端Profile拒绝。
  corruptNextCurrent = true;
  await context.setOffline(true);
  await hidden(a, 'dashboard-canvas');
  await context.setOffline(false);
  await a.waitForFunction(() => document.querySelector('[data-testid="recovery-status"]')?.getAttribute('data-status') === 'RETRY_REQUIRED');
  assert.ok((await a.getByTestId('static-dashboard-error').textContent()).includes('设备数据合同不匹配'));
  await hidden(a, 'dashboard-canvas');
  await a.getByTestId('recovery-retry').click();
  await a.locator('[data-list-index="0"]').waitFor({ state: 'visible' });
  console.log('PASS 注入超限LIST明确契约错误、清整画布并显式重试恢复真实事实');

  const revoked = await fetch(process.env.WEBAPP_COMPOSITE_CONTROL_URL + '/revoke', { method: 'POST' });
  assert.equal(revoked.status, 204);
  await context.setOffline(true);
  await hidden(a, 'dashboard-canvas');
  await context.setOffline(false);
  await visible(a, 'dashboard-canvas');
  await a.waitForFunction(() => document.querySelector('[data-testid="recovery-status"]')?.getAttribute('data-status') === 'READY');
  await a.waitForFunction(() => {
    const canvas = document.querySelector('[data-testid="dashboard-canvas"]');
    return canvas && document.querySelector('[data-testid="composite-json-json"]')?.getAttribute('data-state') === 'NOT_AVAILABLE'
      && document.querySelector('[data-testid="composite-list-list"]')?.getAttribute('data-state') === 'NOT_AVAILABLE'
      && !canvas.textContent.includes('9007199254740993')
      && !canvas.textContent.includes('完整元素-0-中文😀') && !canvas.querySelector('[data-list-index]');
  }, null, { timeout: 15000 });
  console.log('PASS 真实PG撤权复验清除旧复合值和列表节点');
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
  console.error('WebApp复合画布旅程失败：' + error.name + '\n'
    + (error.stack?.split('\n').filter(line => /at .*webapp-composite-canvas-journey\.cjs:\d+/.test(line)).join('\n') ?? '未知位置'));
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
