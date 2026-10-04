#!/usr/bin/env node
// S12-3e：实际WebApp源码/Vite/Chromium+真实后端；合法历史夹具不等于生产Host/D-145资格。
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
const backend = process.env.WEBAPP_RECOVERY_BACKEND_URL;
assert.ok(backend, '需要专用真实后端地址');
const accounts = [''].map(prefix => ({
  appKey: process.env[`WEBAPP_RECOVERY_${prefix}APP_KEY`],
  username: process.env[`WEBAPP_RECOVERY_${prefix}USERNAME`],
  password: process.env[`WEBAPP_RECOVERY_${prefix}PASSWORD`],
  userId: process.env[`WEBAPP_RECOVERY_${prefix}USER_ID`],
  projectId: process.env[`WEBAPP_RECOVERY_${prefix}PROJECT_ID`],
}));
for (const account of accounts) for (const key of ['appKey', 'username', 'password']) assert.ok(account[key], '缺少临时静态画布夹具字段');

let vite;
let proxy;
let bodyGate;
let failCurrentOnce = true;
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
    page.on('dialog', async dialog => { failures.push('executed-markup'); await dialog.dismiss(); });
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
      // 仅注入一次显式故障状态，认证和后续成功current/schema正文完全来自真实后端。
      if (failCurrentOnce && /^\/api\/v1\/app\/applications\/[^/]+\/current$/.test(request.url)) {
        failCurrentOnce = false;
        source.resume();
        response.writeHead(503, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
        response.end('{"code":90000,"message":"测试注入临时依赖故障"}');
        return;
      }
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
        if (gate && (typeof gate.path === 'string' ? request.url === gate.path : gate.path.test(request.url)) && request.headers['x-webapp-journey-page'] === 'first') {
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
  for (const name of Object.keys(environment)) if (name.startsWith('WEBAPP_RECOVERY_')) delete environment[name];
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
async function canvas(page, mode) {
  await visible(page, 'dashboard-canvas');
  assert.equal(await page.getByTestId('static-dashboard').getAttribute('data-layout'), mode);
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

function countCurrent() { return requests.filter(value => /\/applications\/[^/]+\/current$/.test(value.path)).length; }
function countSchema() { return requests.filter(value => value.path.endsWith('/schema')).length; }
async function ready(page) { await visible(page, 'dashboard-canvas'); }
async function retry(page) { await visible(page, 'recovery-retry'); await page.getByTestId('recovery-retry').click(); }
// 自动化Chromium保持页面可见；明确注入生命周期事件测试宿主处理，不冒充原生后台实机资格。
async function visibility(page, state) {
  await page.evaluate(value => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => value });
    document.dispatchEvent(new Event('visibilitychange'));
  }, state);
}
async function main() {
  await startVite();
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
  observe(context);
  const account = accounts[0];
  const a = await context.newPage();
  await a.goto(origin + '/app/' + account.appKey);
  await login(a, account);
  await visible(a, 'recovery-retry');
  await hidden(a, 'dashboard-canvas');
  const latchedCurrent = countCurrent();
  const latchedSchema = countSchema();
  assert.equal(latchedCurrent, 1, '单次失败后不得自动反复请求current');
  await context.setOffline(true);
  await context.setOffline(false);
  await visibility(a, 'hidden');
  await visibility(a, 'visible');
  await visible(a, 'recovery-retry');
  await hidden(a, 'dashboard-canvas');
  assert.equal(countCurrent(), latchedCurrent, 'online/visible不能解除失败锁存');
  assert.equal(countSchema(), latchedSchema);
  await retry(a);
  await ready(a);
  assert.ok(countCurrent() > latchedCurrent, '明确重试必须重新取得真实current');
  assert.ok(countSchema() > latchedSchema, '明确重试必须重新取得精确schema');
  console.log('PASS 一次真实current故障后失败锁存，online/visible不自动解除，明确重试完整恢复');

  const beforeOfflineCurrent = countCurrent();
  const beforeOfflineSchema = countSchema();
  await context.setOffline(true);
  await hidden(a, 'dashboard-canvas');
  await context.setOffline(false);
  await ready(a);
  assert.ok(countCurrent() > beforeOfflineCurrent && countSchema() > beforeOfflineSchema,
    '正常离线后上线必须完整重取current及schema，不能恢复旧缓存画布');
  const beforeHiddenCurrent = countCurrent();
  const beforeHiddenSchema = countSchema();
  await visibility(a, 'hidden');
  await hidden(a, 'dashboard-canvas');
  await visibility(a, 'visible');
  await ready(a);
  assert.ok(countCurrent() > beforeHiddenCurrent && countSchema() > beforeHiddenSchema,
    '重新可见必须完整重取current及schema');
  await storageIsNonCredential(a);
  await a.getByTestId('logout-button').click();
  await hidden(a, 'dashboard-canvas');
  await a.waitForFunction(() => document.querySelector('[data-testid="session-status"]')?.getAttribute('data-status') === 'anonymous');
  assert.equal((await context.cookies(origin + '/api/v1/app/browser-auth')).filter(value => value.name === 'tc_app_refresh').length, 0);
  assert.equal(failures.length, 0);
  assert.equal(viteFailure, undefined);
  console.log('PASS 真实offline与注入visibility事件清画布、返回完整恢复且不持久缓存业务，最终真实退出');
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
  console.error('WebApp静态恢复旅程失败：' + error.name + '\n'
    + (error.stack?.split('\n').filter(line => /at .*webapp-static-recovery-journey\.cjs:\d+/.test(line)).join('\n') ?? '未知位置'));
  console.log('DIAG failure kinds=' + JSON.stringify(failures));
  process.exitCode = 1;
}).finally(cleanup);
