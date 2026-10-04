#!/usr/bin/env node
// S12-3m：实际WebApp源码/Vite/Chromium+真实后端；合法历史夹具不等于生产Host/D-145资格。
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
const { port: journeyPort, origin, reservePortCheck } = require('./webapp-journey-origin.cjs');
const backend = process.env.WEBAPP_FOREGROUND_BACKEND_URL;
assert.ok(backend, '需要专用真实后端地址');
const accounts = [''].map(prefix => ({
  appKey: process.env[`WEBAPP_FOREGROUND_${prefix}APP_KEY`],
  username: process.env[`WEBAPP_FOREGROUND_${prefix}USERNAME`],
  password: process.env[`WEBAPP_FOREGROUND_${prefix}PASSWORD`],
  userId: process.env[`WEBAPP_FOREGROUND_${prefix}USER_ID`],
  projectId: process.env[`WEBAPP_FOREGROUND_${prefix}PROJECT_ID`],
}));
for (const account of accounts) for (const key of ['appKey', 'username', 'password']) assert.ok(account[key], '缺少临时实时画布夹具字段');

let vite;
let proxy;
let bodyGate;
let dropNextSocketFrames = false;
let holdNextSocketFrames = false;
let heldSocketBytes;
const sockets = [];
const socketEvents = [];
let sequence = 0;
let completedCurrentReads = 0;

let browser;
let viteFailure;
const failures = [];
const requests = [];
const httpEvidence = [];
function routeLabel(url) { return new URL(url, backend).pathname.replace(/app_[0-9a-f]{32}/g, ':appKey').replace(/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/g, ':uuid'); }
async function probeResolution(target, label) {
  const response = await fetch(target + '/api/v1/app/applications/' + accounts[0].appKey + '/resolve', { signal: AbortSignal.timeout(5000) });
  const body = await response.text();
  let code; let resolvedKey; try { const parsed = JSON.parse(body); resolvedKey = parsed.appKey; const value = parsed.code; if (typeof value === 'number') code = value; } catch { /* 只记录JSON数值code，不输出原响应。 */ }
  console.log('DIAG probe=' + JSON.stringify({ label, port: new URL(target).port, status: response.status, code }));
  assert.equal(response.status, 200, '探测必须命中本测试真实应用');
  assert.equal(resolvedKey, accounts[0].appKey, '不能将外部Vite或其他应用当成本测试服务');
}

// 只记录低敏感路由/状态及可信公开身份，不保留密码、Cookie、令牌、完整header/body或console正文。
function observe(context) {
  context.on('page', page => {
    page.on('websocket', socket => {
      socket.on('framereceived', event => {
        try { const value = JSON.parse(String(event.payload)); socketEvents.push({ type: value.type, order: ++sequence, alarms: value.alarmQueryKeys?.length ?? 0, alarmCount: value.alarmCount }); }
        catch { failures.push('invalid-websocket-json'); }
      });
    });
    page.on('pageerror', () => failures.push('pageerror'));
    page.on('dialog', async dialog => { failures.push('executed-markup'); await dialog.dismiss(); });
  });
  context.on('response', response => {
    const route = new URL(response.url()).pathname;
    if (route.startsWith('/api/')) httpEvidence.push({ route: route.replace(/app_[0-9a-f]{32}/g, ':appKey').replace(/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/g, ':uuid'), status: response.status() });
  });
  context.on('requestfailed', request => {
    const route = new URL(request.url()).pathname;
    if (route.startsWith('/api/')) httpEvidence.push({ route: route.replace(/app_[0-9a-f]{32}/g, ':appKey').replace(/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/g, ':uuid'), status: 'transport-failed' });
  });
  context.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/')) requests.push({ path: url.pathname, method: request.method(), order: ++sequence });
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
        if (request.url === '/api/v1/app/devices/current-values/query') completedCurrentReads++;
        let code; try { const value = JSON.parse(bytes.toString('utf8')).code; if (typeof value === 'number') code = value; } catch { /* 不输出响应原文。 */ }
        if (request.url.startsWith('/api/')) console.log('DIAG proxy=' + JSON.stringify({ route: routeLabel(request.url), status: source.statusCode, code }));
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
  // 真正升级并转发字节，凭据子协议只经网络透传，禁止打印或记录请求头。
  proxy.on('upgrade', (request, client, head) => {
    const upstream = http.request(new URL(request.url, backend), { headers: { ...request.headers, host: new URL(backend).host } });
    const drop = dropNextSocketFrames; dropNextSocketFrames = false;
    upstream.on('upgrade', (response, server, initial) => {
      client.write('HTTP/1.1 101 Switching Protocols\r\n'
        + Object.entries(response.headers).map(([name, value]) => name + ': ' + value).join('\r\n') + '\r\n\r\n');
      const connection = { client, server }; sockets.push(connection);
      client.on('error', () => {}); server.on('error', () => {});
      client.on('close', () => server.destroy()); server.on('close', () => client.destroy());
      if (head.length) server.write(head);
      if (initial.length && !drop) client.write(initial);
      client.pipe(server);
      server.on('data', bytes => {
        if (drop) return;
        if (holdNextSocketFrames) { holdNextSocketFrames = false; heldSocketBytes = { client, bytes: Buffer.from(bytes) }; return; }
        client.write(bytes);
      });
    });
    upstream.on('response', response => { response.resume(); client.destroy(); });
    upstream.on('error', () => client.destroy());
    upstream.end();
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
  await probeResolution(backend, 'backend');
  await probeResolution(target, 'proxy');
  const environment = { ...process.env, VITE_DEV_API_TARGET: target };
  // 账户凭据仅留在Node旅程进程；不传播给Vite，更不创建VITE_密码字段。
  for (const name of Object.keys(environment)) if (name.startsWith('WEBAPP_FOREGROUND_')) delete environment[name];
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
      if (response.ok && (await response.text()).includes('/@vite/client')) { await probeResolution(origin, 'vite'); return; }
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

async function value(page, expected) {
  await page.waitForFunction(number => document.querySelector('[data-testid="device-value-value"]')?.textContent.includes(number), expected);
}
async function realtime(page, status) {
  await page.locator('[data-testid="realtime-status"][data-status="' + status + '"]').waitFor({ state: 'visible', timeout: 15000 });
}
async function transition(action) {
  const response = await fetch(process.env.WEBAPP_FOREGROUND_CONTROL_URL + '/transition?' + action, { method: 'POST' });
  assert.equal(response.status, 204, '真实状态机事务必须成功');
}
const alarmReads = () => requests.filter(row => row.path === '/api/v1/app/alarms/query').length;
const historyReads = () => requests.filter(row => row.path.includes('/history/versioned')).length;
const alarmHints = () => socketEvents.filter(row => row.type === 'INVALIDATE' && row.alarms > 0).length;
async function waitUntil(predicate, message, timeout = 15000) {
  const deadline = Date.now() + timeout;
  while (!predicate() && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 25));
  assert.ok(predicate(), message);
}
async function alarmText(page, present, timeout = 15000) {
  await page.waitForFunction(present => document.querySelector('[data-testid="alarm-list-alarms"], [data-testid="alarm-list-pure_alarms"]')?.textContent.includes('前台状态告警') === present, present, { timeout });
}
async function main() {
  await startVite();
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
  observe(context);
  const a = await context.newPage();
  const first = process.env.WEBAPP_FOREGROUND_FIRST_ID;
  await a.goto(origin + '/app/' + accounts[0].appKey);
  await login(a, accounts[0]);
  await visible(a, 'device-selector-selector');
  await a.locator('select[data-testid^="device-selector-"]').selectOption(first);
  await a.locator('[data-testid="alarm-realtime-status"][data-status="SUBSCRIBED"]').waitFor();
  await value(a, '12.5');
  await alarmText(a, false);
  assert.ok(socketEvents.some(row => row.type === 'SUBSCRIBED' && row.alarmCount === 1));
  const firstAck = socketEvents.find(row => row.type === 'SUBSCRIBED' && row.alarmCount === 1);
  const firstAlarmRead = requests.find(row => row.path === '/api/v1/app/alarms/query');
  assert.ok(firstAck.order < firstAlarmRead.order, '纯事实首次读取必须发生在实际v2 ACK后');
  const baselineHistory = historyReads();
  const beforeRollback = alarmHints();
  await transition('rollback');
  await new Promise(resolve => setTimeout(resolve, 1300));
  assert.equal(alarmHints(), beforeRollback, '回滚状态不能发Redis/WS提示');
  let previous = alarmHints(); let reads = alarmReads();
  await transition('pending');
  await waitUntil(() => alarmHints() > previous && alarmReads() > reads, '真实PENDING提示必须触发REST重读');
  await alarmText(a, false);
  await new Promise(resolve => setTimeout(resolve, 1100));
  previous = alarmHints();
  await transition('activate');
  await waitUntil(() => alarmHints() > previous, '真实ACTIVATED必须产生提示');
  await alarmText(a, true);
  previous = alarmHints();
  await transition('ack');
  await waitUntil(() => alarmHints() > previous, 'ACK必须发状态提示而非仅ACTIVATED通知');
  await alarmText(a, false);
  previous = alarmHints(); reads = alarmReads();
  await transition('clear');
  await waitUntil(() => alarmHints() > previous && alarmReads() > reads, 'CLEARED即使不匹配当前过滤也必须重读');
  assert.equal(historyReads(), baselineHistory, '告警dirty不得重跑历史');
  console.log('PASS 实际PENDING/ACTIVATED/ACK/CLEARED经提交后Redis与v2 WS权威替换，回滚不提示');

  const beforePureCurrent = requests.filter(row => row.path.includes('/current-values/')).length;
  await a.locator('[data-testid="dashboard-page-tab"][data-page-id="alarms_only"]').click();
  await a.locator('[data-testid="dashboard-canvas"][data-page-id="alarms_only"]').waitFor();
  if (await a.locator('select[data-testid^="device-selector-"]').inputValue() !== first)
    await a.locator('select[data-testid^="device-selector-"]').selectOption(first);
  await a.locator('[data-testid="alarm-realtime-status"][data-status="SUBSCRIBED"]').waitFor();
  await alarmText(a, false);
  assert.equal(await a.getByTestId('device-value-value').count(), 0);
  assert.equal(requests.filter(row => row.path.includes('/current-values/')).length, beforePureCurrent);
  await transition('pending');
  await new Promise(resolve => setTimeout(resolve, 1300));
  await transition('activate');
  await alarmText(a, true);
  console.log('PASS 纯告警页面无属性订阅也完成实际v2 ACK及实时事实替换');

  // 丢弃清除提示的真实传输后关闭连接，客户端只能依靠REST恢复发现已清除事实。
  holdNextSocketFrames = true;
  await transition('clear');
  await waitUntil(() => !!heldSocketBytes, '须实际截获清除提示');
  const connected = sockets.length;
  for (const connection of sockets) connection.server.destroy();
  await a.locator('[data-testid="alarm-realtime-status"][data-status="REST_READY"]').waitFor();
  await alarmText(a, false, 70000);
  assert.ok(sockets.length <= connected + 1, '仅绝对60秒校准允许一次恢复建连');
  console.log('PASS 实际清除提示丢失后断线REST补拉替换旧告警集合');

  // 新选择重新建连后，真实200告警正文迟到不能越过当前设备撤权。
  await a.locator('select[data-testid^="device-selector-"]').selectOption(process.env.WEBAPP_FOREGROUND_SECOND_ID);
  await a.locator('select[data-testid^="device-selector-"]').selectOption(first);
  await a.locator('[data-testid="alarm-realtime-status"][data-status="SUBSCRIBED"]').waitFor();
  await transition('pending');
  await new Promise(resolve => setTimeout(resolve, 1300));
  const gate = delayedResponse('/api/v1/app/alarms/query');
  await transition('activate');
  await bounded(gate.received, '必须截获真实新告警200正文');
  const revoked = await fetch(process.env.WEBAPP_FOREGROUND_CONTROL_URL + '/revoke', { method: 'POST' });
  assert.equal(revoked.status, 204);
  await transition('ack');
  await hidden(a, 'dashboard-canvas');
  gate.release();
  await a.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await a.locator('[data-testid^="alarm-list-"]').count(), 0);
  await storageIsNonCredential(a);
  await a.getByTestId('logout-button').click();
  await a.waitForFunction(() => document.querySelector('[data-testid="session-status"]')?.getAttribute('data-status') === 'anonymous');
  assert.equal((await context.cookies(origin + '/api/v1/app/browser-auth')).filter(row => row.name === 'tc_app_refresh').length, 0);
  assert.deepEqual(failures, []);
  assert.equal(viteFailure, undefined);
  console.log('PASS 设备撤权清页且真实迟到告警正文不回流，退出清理身份与实时连接');
  await context.close();
}

async function cleanup() {
  if (browser) await browser.close();
  for (const connection of sockets) { connection.client.destroy(); connection.server.destroy(); }
  if (proxy) { proxy.closeAllConnections(); proxy.close(); }
  if (vite && vite.exitCode === null) {
    vite.kill('SIGTERM');
    await Promise.race([once(vite, 'exit'), new Promise(resolve => setTimeout(resolve, 3000))]);
    if (vite.exitCode === null) vite.kill('SIGKILL');
  }
}
main().catch(async error => {
  // 不打印assert actual/expected或环境，避免身份响应/Cookie进入诊断。
  console.error('WebApp实时画布旅程失败：' + error.name + '\n'
    + (error.stack?.split('\n').filter(line => /at .*webapp-foreground-alarm-journey\.cjs:\d+/.test(line)).join('\n') ?? '未知位置'));
  console.log('DIAG failure kinds=' + JSON.stringify(failures));
  console.log('DIAG HTTP=' + JSON.stringify(httpEvidence));
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
