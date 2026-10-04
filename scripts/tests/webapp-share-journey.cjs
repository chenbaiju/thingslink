#!/usr/bin/env node
// S12-3k：实际WebApp源码/Vite/Chromium+真实后端；合法历史夹具不等于生产Host/D-145资格。
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
const backend = process.env.WEBAPP_SHARE_BACKEND_URL;
assert.ok(backend, '需要专用真实后端地址');
const shareId = process.env.WEBAPP_SHARE_ID;
const secret = process.env.WEBAPP_SHARE_SECRET;
assert.ok(shareId && secret);
const sensitiveValues = new Set([secret, process.env.WEBAPP_SHARE_CANCEL_SECRET].filter(Boolean));
function safeErrorText(value, maximum = 500) {
  let text = String(value ?? '');
  for (const credential of sensitiveValues) text = text.split(credential).join('[credential]');
  return text.replace(/sh_[A-Za-z0-9_-]{20,}/g, '[share-credential]')
    .replace(/[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}/g, '[jwt]')
    .replace(/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/gi, '[uuid]')
    .replace(/(?:https?|wss?):\/\/[^\s)]+/g, url => url.split(/[?#]/)[0])
    .replace(/[?#][^\s)]*/g, '[url-parameters]').slice(0, maximum);
}
const shareLink = () => origin + '/app/share/' + shareId + '#token=' + secret;

let expiryShareId;
let heldExpiryAlarm;
let heldContext;
let contextGate;
let vite;
let proxy;
const sockets = [];
const socketEvents = [];
let sequence = 0;

let browser;
let viteFailure;
const failures = [];
const requests = [];
const historyAnchors = [];
const contextAnchors = [];
const httpEvidence = [];
function routeLabel(url) { return new URL(url, backend).pathname.replace(/app_[0-9a-f]{32}/g, ':appKey').replace(/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/g, ':uuid'); }
// 只记录低敏感路由/状态及可信公开身份，不保留密码、Cookie、令牌、完整header/body或console正文。
function observe(context) {
  context.on('page', page => {
    page.on('websocket', socket => {
      if (socket.url().includes('/ws/app/') || socket.url().includes(secret)) failures.push('invalid-share-socket-identity');
      socket.on('framereceived', event => {
        try { const value = JSON.parse(String(event.payload)); socketEvents.push({ type: value.type, order: ++sequence }); }
        catch { failures.push('invalid-websocket-json'); }
      });
    });
    page.on('pageerror', error => {
      // 仅输出项目已知类型和源码行号；不记录动态message、URL参数、凭据或业务值。
      const allowed = ['Error', 'TypeError', 'RangeError', 'SyntaxError', 'ReferenceError', 'SecurityError', 'DOMException', 'AbortError', 'RecoveryBudgetError',
        'ShareSessionError', 'ShareDataError', 'DeviceDataError', 'SessionError', 'AbortSignal'];
      const kind = allowed.includes(error.name) ? error.name : 'OtherError';
      const text = String(error.message);
      const fixed = ['cancelled', 'deadline', 'attempts', 'bytes', 'queue', 'aborted', 'identity', 'missing', 'expired', 'revoked'];
      const messageClass = fixed.find(value => text === value || text === 'Recovery budget ' + value
        || text === 'Recovery budget: ' + value || text === 'Share session ' + value) ?? 'unclassified';
      const frames = String(error.stack ?? '').split('\n').flatMap(line => {
        const match = line.match(/(?:\/src\/|\/scripts\/tests\/)([A-Za-z0-9_./-]+)(?:\?[^#\s):]*)?(?:#[^\s):]*)?:(\d+):(\d+)/);
        return match ? [{ source: match[1], line: Number(match[2]), column: Number(match[3]) }] : [];
      }).slice(0, 8);
      failures.push('pageerror:' + kind + ':' + messageClass);
      const detail = safeErrorText(error.message);
      const rawName = safeErrorText(error.name, 100);
      const stackLocations = String(error.stack ?? '').split('\n').filter(line => /(?:\bat\b|\.ts|\.js|\.vue|node_modules)/.test(line))
        .slice(0, 8).map(line => safeErrorText(line, 240));
      console.log('DIAG pageerror=' + JSON.stringify({ kind, rawName, messageClass, detail, frames, stackLocations }));
    });
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
    if (request.url().includes(secret)) failures.push('secret-in-http-url');
    if (url.pathname.startsWith('/api/')) requests.push({ path: url.pathname, method: request.method(), order: ++sequence });
  });

}

// 转发真实后端事实，只控制已生成当前值响应正文的到达时刻；不创建或替换任何业务JSON。
async function startBackendProxy() {
  proxy = http.createServer((request, response) => {
    if (request.url.startsWith('/api/v1/shares/')) {
      const cookie = request.headers.cookie !== undefined;
      const authorization = request.headers.authorization !== undefined;
      if (cookie || authorization) failures.push('ambient-credential-on-share-http');
      console.log('DIAG share-http-credentials=' + JSON.stringify({ cookie, authorization }));
    }
    const upstream = http.request(new URL(request.url, backend), {
      method: request.method, headers: { ...request.headers, host: new URL(backend).host },
    }, source => {
      const chunks = [];
      source.on('data', chunk => chunks.push(chunk));
      source.on('end', () => {
        const bytes = Buffer.concat(chunks);
        if (source.statusCode === 200 && request.url.endsWith('/context')) contextAnchors.push(JSON.parse(bytes.toString('utf8')).historyAnchorAt);
        if (source.statusCode === 200 && new URL(request.url, backend).pathname.endsWith('/properties/temperature/history'))
          historyAnchors.push(new URL(request.url, backend).searchParams.get('anchorAt'));
        let code; try { const value = JSON.parse(bytes.toString('utf8')).code; if (typeof value === 'number') code = value; } catch { /* 不输出响应原文。 */ }
        if (request.url.startsWith('/api/')) console.log('DIAG proxy=' + JSON.stringify({ route: routeLabel(request.url), status: source.statusCode, code, atMs: Math.floor(performance.now()), retryAfter: /^[0-9]+$/.test(String(source.headers['retry-after'] ?? '')) ? Number(source.headers['retry-after']) : undefined }));
        if (expiryShareId && request.url === '/api/v1/shares/' + expiryShareId + '/alarms/query') {
          assert.equal(source.statusCode, 200, '到期反例必须持有到期前真实alarm成功正文');
          heldExpiryAlarm = () => { response.writeHead(source.statusCode, source.headers); response.end(bytes); };
        } else if (contextGate && request.url === '/api/v1/shares/' + process.env.WEBAPP_SHARE_CANCEL_ID + '/context') {
          assert.equal(source.statusCode, 200, '取消反例必须挂起真实有效context成功响应');
          contextGate = false;
          heldContext = () => { response.writeHead(source.statusCode, source.headers); response.end(bytes); };
        } else { response.writeHead(source.statusCode, source.headers); response.end(bytes); }
      });
    });
    upstream.on('error', () => { response.writeHead(502); response.end(); });
    request.pipe(upstream);
  });
  // 真正升级并转发字节，凭据子协议只经网络透传，禁止打印或记录请求头。
  proxy.on('upgrade', (request, client, head) => {
    // 仅临近到期反例故障注入传输层，避免真实WS先到期1008抢先验证另一路径。
    if (expiryShareId && request.url === '/ws/shares/' + expiryShareId + '/properties') {
      client.end('HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
      return;
    }
    const upstream = http.request(new URL(request.url, backend), { headers: { ...request.headers, host: new URL(backend).host } });
    upstream.on('upgrade', (response, server, initial) => {
      client.write('HTTP/1.1 101 Switching Protocols\r\n'
        + Object.entries(response.headers).map(([name, value]) => name + ': ' + value).join('\r\n') + '\r\n\r\n');
      const connection = { client, server }; sockets.push(connection);
      client.on('error', () => {}); server.on('error', () => {});
      client.on('close', () => server.destroy()); server.on('close', () => client.destroy());
      if (head.length) server.write(head);
      if (initial.length) client.write(initial);
      client.pipe(server);
      server.on('data', bytes => client.write(bytes));
    });
    upstream.on('response', response => { response.resume(); client.destroy(); });
    upstream.on('error', () => client.destroy());
    upstream.end();
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
  for (const name of Object.keys(environment)) if (name.startsWith('WEBAPP_SHARE_')) delete environment[name];
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
      if (response.ok && (await response.text()).includes('/@vite/client')) { return; }
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
async function value(page, expected) {
  await page.waitForFunction(number => document.querySelector('[data-testid="device-value-value"]')?.textContent.includes(number), expected);
}
async function realtime(page, status) {
  await page.locator('[data-testid="realtime-status"][data-status="' + status + '"]').waitFor({ state: 'visible', timeout: 15000 });
}
async function change(suffix = '') {
  const response = await fetch(process.env.WEBAPP_SHARE_CONTROL_URL + '/change' + suffix, { method: 'POST' });
  assert.equal(response.status, 204, 'PG更新后必须有真实Redis订阅者接收');
}
async function main() {
  await startVite();
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
  await context.addCookies([{ name: 'tc_app_refresh', value: 'ambient-invalid-app-cookie', domain: 'localhost', path: '/', httpOnly: true, sameSite: 'Strict' }]);
  await context.addInitScript(expectedOrigin => {
    if (location.origin !== expectedOrigin) return;
    localStorage.setItem('tc.app.browser.epoch', 'pending:be_aaaaaaaaaaaaaaaaaaaaaA');
    sessionStorage.setItem('unrelated', 'preserve');
  }, origin);
  observe(context);
  const page = await context.newPage();
  await page.goto(shareLink());
  await page.waitForFunction(() => location.hash === '');
  await visible(page, 'device-selector-selector');
  await page.getByTestId('device-selector-selector').selectOption(process.env.WEBAPP_SHARE_FIRST_ID);
  await value(page, '12.5');
  await realtime(page, 'SUBSCRIBED');
  await visible(page, 'history-granularity-temperature_series');
  await page.locator('[data-testid^="alarm-row-"]').waitFor({ state: 'visible' });
  const endpoints = ['context', 'schema', 'devices/catalog', 'devices/snapshots/query', 'devices/current-values/query', 'alarms/query'];
  for (const endpoint of endpoints) assert.ok(requests.some(row => row.path === '/api/v1/shares/' + shareId + '/' + endpoint), '必须由真实匿名七路读取');
  assert.ok(requests.some(row => /\/properties\/temperature\/history$/.test(row.path)));
  assert.ok(historyAnchors.length > 0 && historyAnchors.every(anchor => contextAnchors.includes(anchor)), '历史锚点必须来自真实context而非客户端自造时间');
  assert.ok(!requests.some(row => row.path.startsWith('/api/v1/app/')), '分享不能恢复或调用环境App身份');
  const ack = socketEvents.find(event => event.type === 'SUBSCRIBED');
  const current = requests.find(row => row.path.endsWith('/current-values/query'));
  assert.ok(ack && current && ack.order < current.order);
  await change();
  await value(page, '88.5');
  const storage = await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage }, hash: location.hash }));
  assert.equal(storage.hash, '');
  assert.deepEqual(storage.local, { 'tc.app.browser.epoch': 'pending:be_aaaaaaaaaaaaaaaaaaaaaA' });
  assert.deepEqual(storage.session, { unrelated: 'preserve' });
  assert.ok(!JSON.stringify(storage).includes(secret));
  assert.equal((await context.cookies()).find(cookie => cookie.name === 'tc_app_refresh')?.value, 'ambient-invalid-app-cookie');
  const screenshots = await fs.mkdtemp(path.join(os.tmpdir(), 'webapp-share-journey-'));
  await page.screenshot({ path: path.join(screenshots, 'desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await page.screenshot({ path: path.join(screenshots, 'mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1400, height: 1000 });
  console.log('SCREENSHOTS ' + screenshots);
  console.log('PASS 真实匿名七路/ACK和Redis提示，fragment清除且完全不借App Cookie/epoch');

  const beforeReload = requests.length;
  await page.reload();
  await page.getByTestId('share-status').waitFor({ state: 'visible' });
  await hidden(page, 'dashboard-canvas');
  assert.equal(requests.length, beforeReload, '无fragment重载不得从存储或App身份找回capability');
  await page.goto(shareLink());
  await visible(page, 'device-selector-selector');
  await page.getByTestId('device-selector-selector').selectOption(process.env.WEBAPP_SHARE_FIRST_ID);
  await value(page, '88.5');
  const revoked = await fetch(process.env.WEBAPP_SHARE_CONTROL_URL + '/revoke', { method: 'POST' });
  assert.equal(revoked.status, 204);
  // 实际服务器静默身份复验关闭分享WS，客户端必须清除页面capability事实。
  await hidden(page, 'dashboard-canvas');
  assert.ok(!requests.some(row => row.path.startsWith('/api/v1/app/')));
  assert.equal(failures.length, 0);
  console.log('PASS 重载需原分享链接，真实撤销后清页且不回退App身份');

  // 独立未撤销capability提供真实200上下文；取消后旧响应不得改变新入口状态。
  const cancelled = await context.newPage();
  contextGate = true;
  await cancelled.goto(origin + '/app/share/' + process.env.WEBAPP_SHARE_CANCEL_ID + '#token=' + process.env.WEBAPP_SHARE_CANCEL_SECRET);
  const contextDeadline = performance.now() + 15000;
  while (!heldContext && performance.now() < contextDeadline) await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(heldContext);
  const beforeCancel = requests.length;
  await cancelled.evaluate(() => { location.hash = '#invalid'; });
  await cancelled.locator('[data-testid="share-status"][data-status="INVALID_LINK"], [data-testid="share-status"][data-status="MISSING_LINK"]').waitFor({ state: 'visible' });
  heldContext();
  await cancelled.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.ok(['INVALID_LINK', 'MISSING_LINK'].includes(await cancelled.getByTestId('share-status').getAttribute('data-status')));
  await hidden(cancelled, 'dashboard-canvas');
  assert.equal(requests.length, beforeCancel, '入口取消后不能继续旧schema/data请求');
  console.log('PASS 入口取消时真实context在途，旧成功响应不恢复画布或覆盖缺凭据状态');

  const expiring = await (await fetch(process.env.WEBAPP_SHARE_CONTROL_URL + '/expiry', { method: 'POST' })).json();
  sensitiveValues.add(expiring.secret);
  expiryShareId = expiring.shareId;
  const expiryPage = await context.newPage();
  await expiryPage.addInitScript(() => {
    window.__shareValueEverVisible = false;
    new MutationObserver(() => {
      if (document.querySelector('[data-testid="device-value-value"][data-state="VALUE"]')) window.__shareValueEverVisible = true;
    }).observe(document, { childList: true, subtree: true, attributes: true });
  });
  await expiryPage.goto(origin + '/app/share/' + expiring.shareId + '#token=' + expiring.secret);
  await visible(expiryPage, 'device-selector-selector');
  await expiryPage.getByTestId('device-selector-selector').selectOption(process.env.WEBAPP_SHARE_FIRST_ID);
  const alarmDeadline = performance.now() + 10000;
  while (!heldExpiryAlarm && performance.now() < alarmDeadline) await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(heldExpiryAlarm);
  await realtime(expiryPage, 'REST_READY');
  assert.equal(await expiryPage.evaluate(() => window.__shareValueEverVisible), false);
  const remaining = Date.parse(expiring.expiresAt) - Date.now() + 300;
  if (remaining > 0) await new Promise(resolve => setTimeout(resolve, remaining));
  const beforeExpiredRelease = requests.length;
  heldExpiryAlarm();
  await expiryPage.locator('[data-testid="share-status"][data-status="RETRY_REQUIRED"]').waitFor({ state: 'visible' });
  assert.equal(await expiryPage.evaluate(() => window.__shareValueEverVisible), false,
    '到期后迟到整轮正文不能短暂安装任何旧当前值');
  await hidden(expiryPage, 'dashboard-canvas');
  await new Promise(resolve => setTimeout(resolve, 1000));
  assert.equal(requests.length, beforeExpiredRelease, '到期必须锁存而非自动无限恢复');
  console.log('PASS 合法临近到期share的真实alarm正文迟到，MutationObserver证明从未安装过期值且恢复锁存');
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
  console.error('WebApp匿名分享画布旅程失败：' + error.name + '\n'
    + (error.stack?.split('\n').filter(line => /at .*webapp-share-journey\.cjs:\d+/.test(line)).join('\n') ?? '未知位置'));
  console.log('DIAG failure kinds=' + JSON.stringify(failures));
  console.log('DIAG HTTP=' + JSON.stringify(httpEvidence));
  const page = browser?.contexts()[0]?.pages()[0];
  if (page) console.log('DIAG page=' + JSON.stringify(await page.evaluate(() => ({
    share: document.querySelector('[data-testid="share-status"]')?.getAttribute('data-status'),
    realtime: document.querySelector('[data-testid="realtime-status"]')?.getAttribute('data-status'),
    recovery: document.querySelector('[data-testid="recovery-status"]')?.getAttribute('data-status'),
    canvas: document.querySelector('[data-testid="static-dashboard-error"]')?.textContent,
    error: document.querySelector('[data-testid="error-message"]')?.textContent,
    kinds: [...document.querySelectorAll('[data-kind]')].map(element => element.getAttribute('data-kind')),
  }))));
  process.exitCode = 1;
}).finally(cleanup);
