#!/usr/bin/env node
// S12-3i：实际WebApp源码/Vite/Chromium+真实后端；合法历史夹具不等于生产Host/D-145资格。
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
const backend = process.env.WEBAPP_INTERACTIVE_BACKEND_URL;
assert.ok(backend, '需要专用真实后端地址');
const accounts = [''].map(prefix => ({
  appKey: process.env[`WEBAPP_INTERACTIVE_${prefix}APP_KEY`],
  username: process.env[`WEBAPP_INTERACTIVE_${prefix}USERNAME`],
  password: process.env[`WEBAPP_INTERACTIVE_${prefix}PASSWORD`],
  userId: process.env[`WEBAPP_INTERACTIVE_${prefix}USER_ID`],
  projectId: process.env[`WEBAPP_INTERACTIVE_${prefix}PROJECT_ID`],
}));
for (const account of accounts) for (const key of ['appKey', 'username', 'password']) assert.ok(account[key], '缺少临时静态画布夹具字段');

let vite;
let proxy;
const histories = [];
const alarms = [];

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
    if (request.url.includes('/history/versioned') || request.url === '/api/v1/app/alarms/query') {
      for (const key of ['x-application-key', 'x-application-version', 'x-application-revision', 'x-dashboard-version']) {
        if (!request.headers[key]) failures.push('missing-runtime-context');
      }
    }
    const upstream = http.request(new URL(request.url, backend), {
      method: request.method, headers: { ...request.headers, host: new URL(backend).host },
    }, source => {
      const chunks = [];
      source.on('data', chunk => chunks.push(chunk));
      source.on('end', () => {
        const bytes = Buffer.concat(chunks);
        if (source.statusCode === 200 && request.url.includes('/history/versioned')) histories.push({ url: request.url, body: JSON.parse(bytes.toString('utf8')) });
        if (source.statusCode === 200 && request.url === '/api/v1/app/alarms/query') alarms.push(JSON.parse(bytes.toString('utf8')));
        response.writeHead(source.statusCode, source.headers); response.end(bytes);
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
  for (const name of Object.keys(environment)) if (name.startsWith('WEBAPP_INTERACTIVE_')) delete environment[name];
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

async function currentValue(page, value) {
  await page.waitForFunction(value => document.querySelector('[data-testid="device-value-value"]')?.textContent.includes(value), value);
}
function historyCount() { return requests.filter(row => row.path.includes('/history/versioned')).length; }
async function main() {
  await startVite();
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
  observe(context);
  const a = await context.newPage();
  const first = process.env.WEBAPP_INTERACTIVE_FIRST_ID;
  const second = process.env.WEBAPP_INTERACTIVE_SECOND_ID;
  assert.ok(first && second);
  await a.goto(origin + '/app/' + accounts[0].appKey);
  await login(a, accounts[0]);
  await visible(a, 'device-selector-selector');
  const selector = a.getByTestId('device-selector-selector');
  await selector.locator('option[value="' + first + '"]').waitFor({ state: 'attached' });
  await selector.selectOption(first);
  await currentValue(a, '12.5');
  await visible(a, 'history-granularity-temperature_series');
  assert.match(await a.getByTestId('history-granularity-temperature_series').textContent(), /ONE_MINUTE|分钟/);
  const segments = a.locator('[data-history-segment]');
  await a.waitForFunction(() => document.querySelectorAll('[data-history-segment]').length >= 4);
  assert.ok((await segments.evaluateAll(nodes => [...new Set(nodes.map(node => node.getAttribute('data-version-pair')))])).length >= 3,
    '两个模型版本与LEGACY必须保持独立段，缺失分钟桶另断开');
  const alarmList = a.getByTestId('alarm-list-alarms');
  await a.locator('[data-testid^="alarm-row-"]').waitFor({ state: 'visible' });
  assert.ok((await alarmList.textContent()).includes('交互告警_0'));
  assert.equal(await a.getByTestId('runtime-variable-caption').inputValue(), '', 'fixture刻意省略必需TEXT_ENUM默认值');
  const dynamicText = a.locator('[data-component-id="text"]').getByTestId('dashboard-text');
  assert.equal(await dynamicText.getAttribute('data-state'), 'UNSELECTED');
  assert.match(await dynamicText.textContent(), /请选择/);
  assert.equal(await a.getByTestId('device-value-value').getAttribute('data-state'), 'VALUE',
    'TEXT_ENUM缺选仅影响自己的动态TEXT，不阻断已选设备当前值');
  assert.ok(histories.length > 0 && alarms.length > 0, '局部缺选不能阻断真实历史和告警读取');
  console.log('PASS 必需TEXT_ENUM缺选只提示本组件，当前值/历史/告警保持真实可用');
  const beforeAlarmPage = historyCount();
  await a.getByTestId('alarm-next-alarms').click();
  await a.waitForFunction(() => document.querySelector('[data-testid="alarm-list-alarms"]')?.textContent.includes('交互告警_1'));
  assert.equal(historyCount(), beforeAlarmPage, '告警翻页不得重新请求历史');
  assert.ok(!(await alarmList.textContent()).includes('交互告警_0'), '告警页面替换而非追加');
  assert.ok(!(await alarmList.textContent()).includes('交互告警_2'));
  assert.ok(!(await alarmList.textContent()).includes('交互告警_3'));
  assert.ok(!(await alarmList.textContent()).includes('交互告警_4'));
  assert.equal(alarms.at(-1).hasMore, false, '三项冻结过滤排除PENDING/MAJOR/ACKNOWLEDGED反例');
  console.log('PASS 真实跨模型及LEGACY历史分段/缺桶断开、实际粒度、告警三过滤替换页且不重读历史');

  const beforeRange = histories.length;
  await a.getByTestId('runtime-variable-range').selectOption('LAST_24_HOURS');
  await currentValue(a, '12.5');
  await a.waitForFunction(() => document.querySelector('[data-testid="history-granularity-temperature_series"]') !== null);
  const rangeDeadline = Date.now() + 15000;
  while (histories.length <= beforeRange && Date.now() < rangeDeadline) await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(histories.length > beforeRange, '时间预设必须取得新真实历史响应');
  const query = new URL(histories.at(-1).url, backend);
  assert.equal(Date.parse(query.searchParams.get('to')) - Date.parse(query.searchParams.get('from')), 86400000);
  await a.getByTestId('runtime-variable-caption').selectOption('safe');
  await a.waitForFunction(() => document.querySelector('[data-testid="dashboard-canvas"]')?.textContent.includes('中文<img src=x onerror=alert(1)>'));
  assert.equal(await a.locator('[data-component-id="text"] img, [data-component-id="text"] script').count(), 0);
  assert.ok(requests.every(request => !request.path.includes('onerror') && !request.path.includes('safe')));
  await a.waitForFunction(() => document.querySelector('[data-testid="recovery-status"]')?.getAttribute('data-status') === 'READY');
  const screenshots = await fs.mkdtemp(path.join(os.tmpdir(), 'webapp-interactive-canvas-'));
  await a.screenshot({ path: path.join(screenshots, 'desktop.png'), fullPage: true });
  await a.setViewportSize({ width: 390, height: 844 });
  assert.equal(await a.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await a.screenshot({ path: path.join(screenshots, 'mobile.png'), fullPage: true });
  await a.setViewportSize({ width: 1400, height: 1000 });
  console.log('SCREENSHOTS ' + screenshots);
  await selector.selectOption(second);
  await currentValue(a, '78.25');
  await a.waitForFunction(() => document.querySelector('[data-testid="history-series-temperature_series"]')?.textContent.includes('非数值'));
  assert.equal(await a.locator('[data-history-segment]').count(), 0, '30058不能删去TEXT后伪装完整序列');
  console.log('PASS TIME_RANGE真实窗口和ENUM纯文本；历史30058局部失败仍保留当前值');

  await selector.selectOption(first);
  await currentValue(a, '12.5');
  const revoked = await fetch(process.env.WEBAPP_INTERACTIVE_CONTROL_URL + '/revoke', { method: 'POST' });
  assert.equal(revoked.status, 204);
  await context.setOffline(true);
  await hidden(a, 'dashboard-canvas');
  await context.setOffline(false);
  await visible(a, 'recovery-retry');
  await hidden(a, 'dashboard-canvas');
  console.log('PASS 真实设备撤权60010触发必需复验失败并清全页');
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
  console.error('WebApp交互画布旅程失败：' + error.name + '\n'
    + (error.stack?.split('\n').filter(line => /at .*webapp-interactive-canvas-journey\.cjs:\d+/.test(line)).join('\n') ?? '未知位置'));
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
