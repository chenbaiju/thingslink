#!/usr/bin/env node
// S12-3c：实际WebApp源码/Vite/Chromium+真实后端；合法历史夹具不等于生产Host/D-145资格。
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
const backend = process.env.WEBAPP_JOURNEY_BACKEND_URL;
assert.ok(backend, '需要专用真实后端地址');
const accounts = ['', 'OTHER_'].map(prefix => ({
  appKey: process.env[`WEBAPP_JOURNEY_${prefix}APP_KEY`],
  username: process.env[`WEBAPP_JOURNEY_${prefix}USERNAME`],
  password: process.env[`WEBAPP_JOURNEY_${prefix}PASSWORD`],
  userId: process.env[`WEBAPP_JOURNEY_${prefix}USER_ID`],
  projectId: process.env[`WEBAPP_JOURNEY_${prefix}PROJECT_ID`],
}));
for (const account of accounts) for (const value of Object.values(account)) assert.ok(value, '缺少临时WebApp夹具字段');

let vite;
let proxy;
let bodyGate;
let artifactDirectory;
let browser;
let viteFailure;
const failures = [];
const requests = [];
const currents = [];

// 只记录低敏感路由/状态及可信公开身份，不保留密码、Cookie、令牌、完整header/body或console正文。
function observe(context) {
  context.on('page', page => {
    page.on('pageerror', () => failures.push('pageerror'));
  });
  context.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/')) requests.push({ path: url.pathname, method: request.method() });
  });

}



// 转发真实后端事实，只控制当前响应最后字节的到达时刻；不创建或替换任何业务JSON。
async function startBackendProxy() {
  proxy = http.createServer((request, response) => {
    const upstream = http.request(new URL(request.url, backend), {
      method: request.method, headers: { ...request.headers, host: new URL(backend).host },
    }, source => {
      const chunks = [];
      source.on('data', chunk => chunks.push(chunk));
      source.on('end', () => {
        const bytes = Buffer.concat(chunks);
        // 从代理取得真实后端完整响应事实；UI仍独立确认入口，避免CDP导航回收误报JSON失败。
        if (/\/api\/v1\/app\/applications\/[^/]+\/current$/.test(request.url) && source.statusCode === 200) {
          try {
            const value = JSON.parse(bytes.toString('utf8'));
            currents.push({ appKey: value.application?.appKey, userId: value.identity?.appUserId,
              projectId: value.identity?.projectId, entry: value.entryDashboardId });
          } catch { failures.push('invalid-current-json'); }
        }
        const gate = bodyGate;
        if (gate && request.url === gate.path && request.headers['x-webapp-journey-page'] === 'first') {
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
  for (const name of Object.keys(environment)) if (name.startsWith('WEBAPP_JOURNEY_')) delete environment[name];
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
  await visible(page, 'username');
  await page.getByTestId('username').fill(account.username);
  await page.getByTestId('password').fill(account.password);
  await page.getByTestId('login-submit').click();
  await ready(page, account);
}
async function ready(page, account) {
  await visible(page, 'session-ready');
  await visible(page, 'application-entry');
  assert.equal(await page.getByTestId('session-user').getAttribute('data-user-id'), account.userId);
  assert.equal(await page.getByTestId('session-project').getAttribute('data-project-id'), account.projectId);
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline && !currents.some(value => value.appKey === account.appKey
    && value.userId === account.userId && value.projectId === account.projectId && value.entry)) {
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert.ok(currents.some(value => value.appKey === account.appKey && value.userId === account.userId
    && value.projectId === account.projectId && value.entry), '真实current必须对应本轮应用/用户/项目及已授权入口');
}
function authRequests(operation) { return requests.filter(value => value.path === '/api/v1/app/browser-auth/' + operation).length; }
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
  const context = await browser.newContext();
  observe(context);
  const first = accounts[0];
  const second = accounts[1];
  const a = await context.newPage();
  // 只识别延迟闸门所属页面；不改变认证或业务正文，防止另一标签同路径恢复抢占闸门。
  await a.setExtraHTTPHeaders({ 'X-WebApp-Journey-Page': 'first' });
  await a.goto(origin + '/app/' + first.appKey);
  await login(a, first);
  console.log('PASS 初次真实登录与current');
  const cookie = (await context.cookies(origin + '/api/v1/app/browser-auth')).find(value => value.name === 'tc_app_refresh');
  assert.ok(cookie, '真实浏览器应保存refresh Cookie');
  assert.equal(cookie.httpOnly, true);
  assert.equal(cookie.sameSite, 'Strict');
  assert.equal(cookie.path, '/api/v1/app/browser-auth');
  assert.equal(cookie.secure, false); // 本片只使用显式loopback开发例外。
  assert.equal(await a.evaluate(() => document.cookie.includes('tc_app_refresh')), false);
  assert.equal(await a.getByTestId('password').count(), 0, '认证后口令输入应卸载');
  await storageIsNonCredential(a);

  const beforeRestore = authRequests('refresh');
  await a.reload();
  await ready(a, first);
  assert.ok(authRequests('refresh') > beforeRestore, '页面内存丢失后必须真实Cookie刷新恢复');
  console.log('PASS 单标签reload恢复');
  const b = await context.newPage();
  await b.goto(origin + '/app/' + first.appKey);
  await ready(b, first);
  console.log('PASS 第二标签恢复');
  const beforeConcurrent = authRequests('refresh');
  await Promise.all([a.reload(), b.reload()]);
  await Promise.all([ready(a, first), ready(b, first)]);
  assert.ok(authRequests('refresh') >= beforeConcurrent + 2, '双标签必须各自获得真实刷新身份');
  console.log('PASS 实际WebApp登录、发布current入口、reload恢复及双标签刷新');

  // 故障注入仅屏蔽浏览器通知，不修改生产App/session或mock认证/current事实。
  await a.addInitScript(() => {
    const add = window.addEventListener.bind(window);
    window.addEventListener = (type, listener, options) => {
      if (type !== 'storage') add(type, listener, options);
    };
    const NativeChannel = window.BroadcastChannel;
    window.BroadcastChannel = class extends NativeChannel {
      addEventListener(type, listener, options) { if (type !== 'message') super.addEventListener(type, listener, options); }
      set onmessage(_handler) { /* 模拟漏通知，持久epoch仍由真实浏览器更新。 */ }
      get onmessage() { return null; }
    };
  });
  const delayed = delayedResponse('/api/v1/app/applications/' + first.appKey + '/current');
  const headers = a.waitForResponse(response => response.url().endsWith(delayed.path) && response.status() === 200);
  await a.reload();
  await bounded(delayed.received, '真实current正文闸门未触发');
  await bounded(headers, '浏览器未收到真实current响应头');
  const previousEpoch = await a.evaluate(() => localStorage.getItem('tc.app.browser.epoch'));
  try {
    await b.goto(origin + '/app/' + second.appKey);
    await visible(b, 'switch-account-button');
    const logoutsBeforeSwitch = authRequests('logout');
    await b.getByTestId('switch-account-button').click();
    await login(b, second);
    assert.equal(authRequests('logout'), logoutsBeforeSwitch, '明确替换不可先退出破坏原子性');
    assert.notEqual(await a.evaluate(() => localStorage.getItem('tc.app.browser.epoch')), previousEpoch);
    assert.equal(await a.getByTestId('session-status').getAttribute('data-status'), 'authenticated',
      '通知确实被屏蔽：正文完成之前旧页面仍需依赖最后一道同步围栏');
  } finally { delayed.release?.(); }
  try {
    await a.locator('[data-testid="session-status"][data-status="invalidated"]').waitFor({ timeout: 15000 });
  } catch (failure) {
    console.log('DIAG delayed-current ' + JSON.stringify({
      status: await a.getByTestId('session-status').getAttribute('data-status'),
      entryVisible: await a.locator('.application-facts').isVisible(),
      errorVisible: await a.getByTestId('error-message').isVisible(), failures,
    }));
    throw failure;
  }
  console.log('PASS 真实current响应头先到/正文延迟、漏storage与Broadcast通知后同步epoch围栏拒绝旧入口');
  await hidden(a, 'session-ready');
  await hidden(a, 'application-entry');
  await storageIsNonCredential(b);
  const beforeOld = currents.filter(value => value.appKey === first.appKey).length;
  await a.bringToFront();
  await hidden(a, 'application-entry');
  assert.equal(currents.filter(value => value.appKey === first.appKey).length, beforeOld,
    '旧标签可见时不得借新账号继续读取旧应用current');
  await b.bringToFront();
  await ready(b, second);
  await b.getByTestId('logout-button').click();
  await b.locator('[data-testid="session-status"][data-status="anonymous"]').waitFor({ timeout: 15000 });
  await hidden(b, 'session-ready');
  await hidden(b, 'application-entry');
  assert.equal((await context.cookies(origin + '/api/v1/app/browser-auth')).filter(value => value.name === 'tc_app_refresh').length, 0);
  await a.reload();
  await visible(a, 'username');
  await hidden(a, 'application-entry');
  assert.equal(failures.length, 0, '真实页面不得有未处理异常或current解析失败');
  assert.equal(viteFailure, undefined);
  // 真实refresh已经在PG提交且Set-Cookie头已到，但正文尚未被页面确认；销毁页面不得自动恢复。
  await login(a, first);
  const pending = delayedResponse('/api/v1/app/browser-auth/refresh');
  const pendingHeaders = a.waitForResponse(response => response.url().endsWith(pending.path) && response.status() === 200);
  await a.reload();
  await bounded(pending.received, '真实refresh正文闸门未触发');
  await bounded(pendingHeaders, '浏览器未收到真实refresh响应头');
  const attemptsBeforeUnknownReload = authRequests('refresh');
  try {
    await a.reload();
    await a.locator('[data-testid="session-status"][data-status="unknown"]').waitFor({ timeout: 15000 });
    await visible(a, 'username');
    await hidden(a, 'application-entry');
    assert.equal(authRequests('refresh'), attemptsBeforeUnknownReload, 'pending身份请求后reload必须拒绝自动刷新');
  } finally { pending.release?.(); }
  await login(a, first);
  await a.getByTestId('logout-button').click();
  await a.locator('[data-testid="session-status"][data-status="anonymous"]').waitFor({ timeout: 15000 });
  await hidden(a, 'session-ready');
  assert.equal((await context.cookies(origin + '/api/v1/app/browser-auth')).filter(value => value.name === 'tc_app_refresh').length, 0);
  console.log('PASS 真实refresh提交/响应正文未知后reload不自动恢复，明确登录恢复并最终退出');
  // /app/是真正通用壳；移动截图不含账号、口令、Cookie或业务数据。
  const mobile = await context.newPage();
  await mobile.setViewportSize({ width: 390, height: 844 });
  await mobile.goto(origin + '/app/');
  await mobile.getByTestId('application-title').waitFor({ state: 'visible' });
  assert.equal(await mobile.getByTestId('login-form').count(), 0);
  assert.equal(await mobile.locator('a.brand').getAttribute('href'), '/app/');
  assert.equal(await mobile.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
  artifactDirectory = await fs.mkdtemp(path.join(os.tmpdir(), 'webapp-session-journey-'));
  const screenshot = path.join(artifactDirectory, 'mobile-entry.png');
  await mobile.screenshot({ path: screenshot, fullPage: true });
  console.log('WEBAPP_JOURNEY_MOBILE_SCREENSHOT=' + screenshot);
  await mobile.goto(origin + '/login');
  // Vite base=/app/可直接拒绝旧根路径，不要求未挂载App必须出现组件testid。
  assert.equal(await mobile.getByTestId('login-form').count(), 0);
  assert.equal(await mobile.locator('input[type="password"]').count(), 0);
  console.log('PASS /app/通用壳无登录、旧/login拒绝与移动viewport无横向溢出');
  console.log('PASS 实际WebApp跨租户替换、旧标签清入口、退出及无凭据持久化');
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
main().catch(error => {
  // 不打印assert actual/expected或环境，避免身份响应/Cookie进入诊断。
  console.error('WebApp会话旅程失败：' + error.name + '\n'
    + (error.stack?.split('\n').filter(line => /at .*webapp-session-journey\.cjs:\d+/.test(line)).join('\n') ?? '未知位置'));
  console.log('DIAG failure kinds=' + JSON.stringify(failures));
  process.exitCode = 1;
}).finally(cleanup);
