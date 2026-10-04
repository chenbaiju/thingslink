#!/usr/bin/env node
// S12-3d：实际WebApp源码/Vite/Chromium+真实后端；合法历史夹具不等于生产Host/D-145资格。
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
const backend = process.env.WEBAPP_STATIC_BACKEND_URL;
assert.ok(backend, '需要专用真实后端地址');
const accounts = ['', 'OTHER_', 'UNSUPPORTED_'].map(prefix => ({
  appKey: process.env[`WEBAPP_STATIC_${prefix}APP_KEY`],
  username: process.env[`WEBAPP_STATIC_${prefix}USERNAME`],
  password: process.env[`WEBAPP_STATIC_${prefix}PASSWORD`],
  userId: process.env[`WEBAPP_STATIC_${prefix}USER_ID`],
  projectId: process.env[`WEBAPP_STATIC_${prefix}PROJECT_ID`],
}));
for (const account of accounts) for (const key of ['appKey', 'username', 'password']) assert.ok(account[key], '缺少临时静态画布夹具字段');

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
  for (const name of Object.keys(environment)) if (name.startsWith('WEBAPP_STATIC_')) delete environment[name];
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

async function main() {
  await startVite();
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1800, height: 1100 } });
  observe(context);
  const [grid, fixed, unsupported] = accounts;
  const a = await context.newPage();
  await a.setExtraHTTPHeaders({ 'X-WebApp-Journey-Page': 'first' });
  await a.goto(origin + '/app/' + grid.appKey);
  await login(a, grid);
  await canvas(a, 'RESPONSIVE_GRID');
  const viewportWidth = await a.locator('.static-viewport').evaluate(element => element.clientWidth);
  assert.ok(viewportWidth >= 768, '真实桌面内容viewport必须达到24列断点，不能只看window宽度');
  const text = a.locator('[data-component-id="safe_text"]');
  const image = a.locator('[data-component-id="mark"]');
  assert.ok((await text.boundingBox()).x > (await image.boundingBox()).x, '桌面按逻辑x定位，不按数组顺序伪造布局');
  assert.ok((await a.getByTestId('dashboard-text').textContent()).includes('<script>alert(2)</script>'));
  assert.equal(await a.getByTestId('dashboard-text').locator('script,img').count(), 0, '恶意字面量只能是文本节点');
  const img = a.getByTestId('dashboard-image');
  await img.evaluate(element => element.decode());
  assert.ok(await img.evaluate(element => element.naturalWidth > 0));
  const url = new URL(await img.getAttribute('src'), origin);
  assert.equal(url.origin, origin);
  assert.ok(url.pathname.endsWith('5977f591d5eeb691ee85c7656468a8b5dd1378b70b02a55af574331a0f0f0eaa.png'));
  const resource = await fetch(url);
  assert.equal(resource.status, 200);
  assert.equal(require('node:crypto').createHash('sha256').update(Buffer.from(await resource.arrayBuffer())).digest('hex'),
    '5977f591d5eeb691ee85c7656468a8b5dd1378b70b02a55af574331a0f0f0eaa');
  await a.locator('[data-testid="dashboard-page-tab"][data-page-id="next"]').click();
  assert.equal(await a.getByTestId('dashboard-canvas').getAttribute('data-page-id'), 'next');
  assert.equal(await a.getByTestId('dashboard-text').textContent(), '第二页静态内容');
  await a.locator('[data-testid="dashboard-page-tab"][data-page-id="main"]').click();
  await a.setViewportSize({ width: 390, height: 844 });
  await a.getByTestId('dashboard-canvas').evaluate(element => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.ok((await text.boundingBox()).y < (await image.boundingBox()).y, '窄屏按components数组顺序重排，不按x/y重新排序');
  assert.ok(Math.abs((await text.boundingBox()).width - (await image.boundingBox()).width) < 1);
  assert.equal(await a.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  artifactDirectory = await fs.mkdtemp(path.join(os.tmpdir(), 'webapp-static-canvas-'));
  const gridScreenshot = path.join(artifactDirectory, 'responsive-mobile.png');
  await a.screenshot({ path: gridScreenshot, fullPage: true });
  console.log('WEBAPP_STATIC_GRID_SCREENSHOT=' + gridScreenshot);
  console.log('PASS 真实静态Schema、纯文本不执行、受控PNG、24列/窄屏数组顺序与双页切换');

  const b = await context.newPage();
  await b.goto(origin + '/app/' + grid.appKey);
  await canvas(b, 'RESPONSIVE_GRID');
  // 仅模拟通知漏收；Schema body仍完全来自真实已发布版本及真实授权后端。
  await a.addInitScript(() => {
    const add = window.addEventListener.bind(window);
    window.addEventListener = (type, listener, options) => { if (type !== 'storage') add(type, listener, options); };
    const NativeChannel = window.BroadcastChannel;
    window.BroadcastChannel = class extends NativeChannel {
      addEventListener(type, listener, options) { if (type !== 'message') super.addEventListener(type, listener, options); }
      set onmessage(_handler) {}
      get onmessage() { return null; }
    };
  });
  const delayed = delayedResponse(new RegExp('^/api/v1/app/applications/' + grid.appKey + '/versions/[^/]+/dashboards/[^/]+/schema\\?'));
  const headers = a.waitForResponse(response => response.url().includes('/' + grid.appKey + '/versions/') && response.url().includes('/schema?') && response.status() === 200);
  await a.reload();
  await bounded(delayed.received, '真实Schema正文闸门未触发');
  await bounded(headers, '浏览器未收到真实Schema响应头');
  try {
    await b.goto(origin + '/app/' + fixed.appKey);
    await visible(b, 'switch-account-button');
    await login(b, fixed);
    await canvas(b, 'FIXED_SCREEN');
  } finally { delayed.release?.(); }
  await a.locator('[data-testid="session-status"][data-status="invalidated"]').waitFor({ timeout: 15000 });
  await hidden(a, 'dashboard-canvas');
  console.log('PASS 真实迟Schema正文在跨账号代次切换后不得挂载旧画布');

  await b.setViewportSize({ width: 390, height: 844 });
  await b.getByTestId('dashboard-canvas').evaluate(element => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  const geometry = await b.getByTestId('dashboard-canvas').boundingBox();
  const outer = await b.locator('.static-viewport').boundingBox();
  assert.ok(Math.abs(geometry.width / 1920 - geometry.height / 1080) < 0.001, '固定画布必须等比FIT');
  assert.ok(geometry.x >= outer.x - 1 && geometry.y >= outer.y - 1);
  assert.ok(geometry.x + geometry.width <= outer.x + outer.width + 1 && geometry.y + geometry.height <= outer.y + outer.height + 1);
  assert.equal(await b.getByTestId('dashboard-page-tab').count(), 0);
  const fixedScreenshot = path.join(artifactDirectory, 'fixed-mobile.png');
  await b.screenshot({ path: fixedScreenshot, fullPage: true });
  console.log('WEBAPP_STATIC_FIXED_SCREENSHOT=' + fixedScreenshot);
  await b.getByTestId('logout-button').click();
  await b.locator('[data-testid="session-status"][data-status="anonymous"]').waitFor({ timeout: 15000 });
  await hidden(b, 'dashboard-canvas');

  const c = await context.newPage();
  await c.goto(origin + '/app/' + unsupported.appKey);
  await login(c, unsupported);
  await c.getByTestId('static-dashboard-error').waitFor({ state: 'visible', timeout: 15000 });
  assert.equal(await c.getByTestId('dashboard-canvas').count(), 0, '合法但当前宿主不兼容的版本范围必须整版拒绝');
  assert.equal(await c.getByTestId('dashboard-image').count(), 0, '不得部分渲染该版中仍支持的IMAGE');
  await c.getByTestId('logout-button').click();
  await c.locator('[data-testid="session-status"][data-status="anonymous"]').waitFor({ timeout: 15000 });
  await hidden(c, 'session-ready');
  assert.equal((await context.cookies(origin + '/api/v1/app/browser-auth')).filter(value => value.name === 'tc_app_refresh').length, 0);
  assert.equal(failures.length, 0);
  assert.equal(viteFailure, undefined);
  console.log('PASS 固定1920×1080等比FIT与不兼容宿主范围整版拒绝，最终真实退出');
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
  console.error('WebApp静态画布旅程失败：' + error.name + '\n'
    + (error.stack?.split('\n').filter(line => /at .*webapp-static-canvas-journey\.cjs:\d+/.test(line)).join('\n') ?? '未知位置'));
  console.log('DIAG failure kinds=' + JSON.stringify(failures));
  process.exitCode = 1;
}).finally(cleanup);
