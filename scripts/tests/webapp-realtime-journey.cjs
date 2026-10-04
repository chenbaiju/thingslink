#!/usr/bin/env node
// S12-3j：实际WebApp源码/Vite/Chromium+真实后端；合法历史夹具不等于生产Host/D-145资格。
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
const backend = process.env.WEBAPP_REALTIME_BACKEND_URL;
assert.ok(backend, '需要专用真实后端地址');
const accounts = [''].map(prefix => ({
  appKey: process.env[`WEBAPP_REALTIME_${prefix}APP_KEY`],
  username: process.env[`WEBAPP_REALTIME_${prefix}USERNAME`],
  password: process.env[`WEBAPP_REALTIME_${prefix}PASSWORD`],
  userId: process.env[`WEBAPP_REALTIME_${prefix}USER_ID`],
  projectId: process.env[`WEBAPP_REALTIME_${prefix}PROJECT_ID`],
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
        try { const value = JSON.parse(String(event.payload)); socketEvents.push({ type: value.type, order: ++sequence }); }
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
  for (const name of Object.keys(environment)) if (name.startsWith('WEBAPP_REALTIME_')) delete environment[name];
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
async function change(suffix = '') {
  const response = await fetch(process.env.WEBAPP_REALTIME_CONTROL_URL + '/change' + suffix, { method: 'POST' });
  assert.equal(response.status, 204, 'PG更新后必须有真实Redis订阅者接收');
}
async function main() {
  await startVite();
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
  observe(context);
  const a = await context.newPage();
  const first = process.env.WEBAPP_REALTIME_FIRST_ID;
  const second = process.env.WEBAPP_REALTIME_SECOND_ID;
  assert.ok(first && second);
  await a.goto(origin + '/app/' + accounts[0].appKey);
  await login(a, accounts[0]);
  await visible(a, 'device-selector-selector');
  const selector = a.getByTestId('device-selector-selector');
  await selector.locator('option[value="' + first + '"]').waitFor({ state: 'attached' });
  await selector.selectOption(first);
  await realtime(a, 'SUBSCRIBED');
  await value(a, '12.5');
  const fullReadyAt = performance.now();
  const fullCurrentCount = () => requests.filter(request => /\/applications\/[^/]+\/current$/.test(request.path)).length;
  const fullCurrentBaseline = fullCurrentCount();
  const ack = socketEvents.find(event => event.type === 'SUBSCRIBED');
  const current = requests.find(request => request.path.endsWith('/current-values/query'));
  assert.ok(ack && current && ack.order < current.order, '真实ACK必须先于首次当前值HTTP');
  await change();
  await value(a, '88.5');
  assert.ok(socketEvents.some(event => event.type === 'INVALIDATE'));
  console.log('PASS 实际WebSocket ACK先于current读取，真实PG修改和Redis失效提示触发刷新');

  // 捕获真实旧当前值正文；第二条真实Redis提示在HTTP在途时到达，必须保留为下一次读取。
  const oldCurrent = delayedResponse('/api/v1/app/devices/current-values/query');
  await change('/stale');
  await bounded(oldCurrent.received, '等待99.5旧PG当前值正文');
  const hintedBefore = socketEvents.filter(event => event.type === 'INVALIDATE').length;
  const queriesBeforeRelease = requests.filter(request => request.path.endsWith('/current-values/query')).length;
  await change();
  const hintDeadline = Date.now() + 10000;
  while (socketEvents.filter(event => event.type === 'INVALIDATE').length <= hintedBefore && Date.now() < hintDeadline)
    await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(socketEvents.filter(event => event.type === 'INVALIDATE').length > hintedBefore, '第二条提示必须在旧HTTP正文仍挂起时实际到达');
  oldCurrent.release();
  const rereadDeadline = Date.now() + 15000;
  while (requests.filter(request => request.path.endsWith('/current-values/query')).length <= queriesBeforeRelease && Date.now() < rereadDeadline)
    await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(requests.filter(request => request.path.endsWith('/current-values/query')).length > queriesBeforeRelease,
    '在途新提示不得随旧请求完成被清掉，必须产生下一次权威读取');
  await value(a, '88.5');
  console.log('PASS 当前值HTTP在途新提示保留，释放旧99.5后下一次PG读取显示最新88.5');

  // 实际可见页持续热点dirty不能重置完整恢复的绝对60秒due；不修改浏览器性能时钟。
  for (const offset of [10000, 20000, 30000, 40000, 45000]) {
    const remaining = fullReadyAt + offset - performance.now();
    if (remaining > 0) await new Promise(resolve => setTimeout(resolve, remaining));
    const beforeHint = requests.filter(request => request.path.endsWith('/current-values/query')).length;
    const completedBeforeHint = completedCurrentReads;
    await change('/notify');
    const queryDeadline = performance.now() + 10000;
    while (completedCurrentReads <= completedBeforeHint && performance.now() < queryDeadline)
      await new Promise(resolve => setTimeout(resolve, 20));
    assert.ok(requests.filter(request => request.path.endsWith('/current-values/query')).length > beforeHint && completedCurrentReads > completedBeforeHint);
    await value(a, '88.5');
    assert.equal(fullCurrentCount(), fullCurrentBaseline, '热点dirty只读取当前值，不提前或重复完整应用确权');
  }
  await change('/silent');
  assert.equal(await a.evaluate(() => document.visibilityState), 'visible');
  const beforeCalibration = fullReadyAt + 55000 - performance.now();
  if (beforeCalibration > 0) await new Promise(resolve => setTimeout(resolve, beforeCalibration));
  assert.equal(fullCurrentCount(), fullCurrentBaseline);
  await value(a, '88.5');
  const calibrationDeadline = fullReadyAt + 75000;
  while (fullCurrentCount() === fullCurrentBaseline && performance.now() < calibrationDeadline)
    await new Promise(resolve => setTimeout(resolve, 50));
  assert.ok(fullCurrentCount() > fullCurrentBaseline, '距首次完整恢复60秒的独立校准不能被45秒处热点dirty推迟');
  assert.ok(performance.now() - fullReadyAt >= 55000, '不能以提前非周期重读冒充60秒校准');
  await value(a, '66.5');
  console.log('PASS 热点dirty不推迟绝对60秒完整确权，未发Redis提示的真实PG66.5由独立校准发现');

  holdNextSocketFrames = true;
  await change('/stale');
  const holdDeadline = Date.now() + 10000;
  while (!heldSocketBytes && Date.now() < holdDeadline) await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(heldSocketBytes, '代理必须实际截住旧socket真实失效提示');
  await selector.selectOption(second);
  await realtime(a, 'SUBSCRIBED');
  await value(a, '78.25');
  // 旧传输若已正确关闭则字节不能再到达；仍存活也只能进入已失效的socket回调。
  if (!heldSocketBytes.client.destroyed) heldSocketBytes.client.write(heldSocketBytes.bytes);
  await a.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await selector.inputValue(), second);
  assert.ok(!(await a.getByTestId('device-value-value').textContent()).includes('99.5'));
  console.log('PASS 切设备后旧socket真实迟到字节不能回流新选择');

  const beforeDisconnect = sockets.length;
  sockets.at(-1).server.destroy();
  await realtime(a, 'REST_READY');
  await value(a, '78.25');
  assert.equal(sockets.length, beforeDisconnect, '普通断线不立即无限重连');
  dropNextSocketFrames = true;
  const acksBefore = socketEvents.filter(event => event.type === 'SUBSCRIBED').length;
  await selector.selectOption(first);
  await realtime(a, 'REST_READY');
  await value(a, '99.5');
  assert.equal(socketEvents.filter(event => event.type === 'SUBSCRIBED').length, acksBefore,
    '该次真实101后的ACK已由代理丢弃，不能伪称实时可用');
  console.log('PASS 实际断线及无ACK在有界等待后REST降级，剩余预算仍取得真实当前值');
  await storageIsNonCredential(a);
  await a.getByTestId('logout-button').click();
  await hidden(a, 'dashboard-canvas');
  await a.waitForFunction(() => document.querySelector('[data-testid="session-status"]')?.getAttribute('data-status') === 'anonymous');
  assert.equal((await context.cookies(origin + '/api/v1/app/browser-auth')).filter(row => row.name === 'tc_app_refresh').length, 0);
  const closeDeadline = Date.now() + 5000;
  while (sockets.some(connection => !connection.client.destroyed) && Date.now() < closeDeadline)
    await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(sockets.every(connection => connection.client.destroyed), '退出前后已结束所有真实升级传输');
  assert.equal(failures.length, 0);
  assert.equal(viteFailure, undefined);
  console.log('PASS 实际退出清Cookie和画布并关闭全部真实升级连接');
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
    + (error.stack?.split('\n').filter(line => /at .*webapp-realtime-journey\.cjs:\d+/.test(line)).join('\n') ?? '未知位置'));
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
