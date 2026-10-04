#!/usr/bin/env node
// S12-3l：生产静态release、真实服务和三浏览器；只缓存公开壳，不认领生产Host资格。
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { createRequire } = require('node:module');
const { port, origin, reservePortCheck } = require('./webapp-journey-origin.cjs');
const root = path.resolve(__dirname, '../..');
const webapp = path.join(root, 'things-link-webapp');
const requireWebapp = createRequire(path.join(webapp, 'package.json'));
process.env.PLAYWRIGHT_BROWSERS_PATH ??= path.join(webapp, '.playwright-browsers');
const engines = requireWebapp('@playwright/test');
const backend = process.env.WEBAPP_PWA_BACKEND_URL;
const shareId = process.env.WEBAPP_PWA_ID;
const secret = process.env.WEBAPP_PWA_SECRET;
assert.ok(backend && shareId && secret);
let server;
let browser;
let temporary;
let latest;
let corrupt = false;
let corruptServed = 0;
const releases = new Map();
const transports = [];
const failures = [];
const publicType = name => name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css'
  : name.endsWith('.html') ? 'text/html' : name.endsWith('.png') ? 'image/png'
    : name.endsWith('.webmanifest') ? 'application/manifest+json' : 'application/json';
function safe(value) {
  return String(value).split(secret).join('[credential]').replace(/sh_[A-Za-z0-9_-]{20,}/g, '[credential]')
    .replace(/[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}/g, '[jwt]')
    .replace(/(?:https?|wss?):\/\/[^\s)]+/g, url => url.split(/[?#]/)[0])
    .replace(/[?#][^\s)]*/g, '[parameters]').slice(0, 400);
}
async function candidate(directory) {
  const descriptor = JSON.parse(await fs.readFile(path.join(directory, 'artifacts/host-candidate.json'), 'utf8'));
  const digest = descriptor.artifactDigest;
  assert.match(digest, /^[0-9a-f]{64}$/);
  const release = path.join(directory, 'artifacts/releases', digest);
  const precache = JSON.parse(await fs.readFile(path.join(release, 'precache.json'), 'utf8'));
  return { digest, release, precache };
}
async function secondCandidate() {
  temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'webapp-pwa-candidate-'));
  const excluded = new Set(['.git', '.idea', 'node_modules', 'target', 'dist', 'artifacts', '.playwright-browsers', 'logs', '__pycache__']);
  for (const module of ['things-link-webapp', 'things-link-client-contracts'])
    await fs.cp(path.join(root, module), path.join(temporary, module), {
      recursive: true, filter: name => !excluded.has(path.basename(name)) && !path.basename(name).startsWith('.env'),
    });
  const copy = path.join(temporary, 'things-link-webapp');
  const index = path.join(copy, 'index.html');
  await fs.writeFile(index, (await fs.readFile(index, 'utf8')).replace('</title>', ' · 公开升级候选</title>'));
  const log = path.join(temporary, 'candidate-build.log');
  const handle = await fs.open(log, 'w');
  async function run(args, cwd) {
    const child = spawn('pnpm', args, { cwd, stdio: ['ignore', handle.fd, handle.fd] });
    const exit = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('临时候选命令超时')); }, 180000);
      child.on('error', error => { clearTimeout(timer); reject(error); });
      child.on('exit', code => { clearTimeout(timer); resolve(code); });
    });
    assert.equal(exit, 0, '真实隔离依赖安装及候选构建必须成功');
  }
  try {
    // 每个临时工程独立node_modules；冻结离线安装只复用包存储，不链接/删除根工程依赖。
    for (const module of ['things-link-client-contracts', 'things-link-webapp'])
      await run(['install', '--offline', '--frozen-lockfile'], path.join(temporary, module));
    await run(['build'], copy);
  } finally { await handle.close(); }
  return candidate(copy);
}
async function startServer() {
  await reservePortCheck();
  server = http.createServer(async (request, response) => {
    const url = new URL(request.url, origin);
    if (url.pathname === '/__pwa_probe__') { response.setHeader('Content-Type', 'text/html'); response.end('<!doctype html><title>公开测试探针</title>'); return; }
    if (url.pathname.startsWith('/api/')) {
      const upstream = http.request(new URL(request.url, backend), { method: request.method, headers: { ...request.headers, host: new URL(backend).host } }, result => {
        response.writeHead(result.statusCode, result.headers); result.pipe(response);
      });
      upstream.on('error', () => { response.writeHead(502); response.end(); }); request.pipe(upstream); return;
    }
    try {
      let release = releases.get(latest);
      let file;
      const versioned = url.pathname.match(/^\/app\/releases\/([0-9a-f]{64})\/(.+)$/);
      if (versioned) { release = releases.get(versioned[1]); file = versioned[2]; }
      else if (url.pathname === '/app/host-candidate.json') file = 'host-candidate.json';
      else if (url.pathname === '/app/manifest.webmanifest') file = 'manifest.webmanifest';
      else if (url.pathname.startsWith('/app/assets/')) file = url.pathname.slice('/app/'.length);
      else if (url.pathname.startsWith('/app/')) file = 'index.html';
      if (!release || !file || file.includes('..') || path.isAbsolute(file)) { response.writeHead(404); response.end(); return; }
      let bytes = await fs.readFile(path.join(release.release, file));
      if (corrupt && versioned && versioned[1] === latest && /^assets\/.*\.js$/.test(file)) {
        corruptServed++; bytes = Buffer.concat([bytes, Buffer.from('\n/* damaged transport */')]);
      }
      response.setHeader('Content-Type', publicType(file));
      response.setHeader('Cache-Control', versioned ? 'public,max-age=31536000,immutable' : 'no-store');
      if (file === 'sw.js') response.setHeader('Service-Worker-Allowed', '/app/');
      response.end(bytes);
    } catch { response.writeHead(404); response.end(); }
  });
  server.on('upgrade', (request, client, head) => {
    const upstream = http.request(new URL(request.url, backend), { headers: { ...request.headers, host: new URL(backend).host } });
    upstream.on('upgrade', (response, socket, initial) => {
      transports.push(client, socket);
      client.on('error', () => {}); socket.on('error', () => {});
      client.write('HTTP/1.1 101 Switching Protocols\r\n' + Object.entries(response.headers).map(([key, value]) => `${key}: ${value}`).join('\r\n') + '\r\n\r\n');
      if (head.length) socket.write(head); if (initial.length) client.write(initial);
      client.pipe(socket); socket.pipe(client);
      client.on('close', () => socket.destroy()); socket.on('close', () => client.destroy());
    });
    upstream.on('response', result => { result.resume(); client.destroy(); }); upstream.on('error', () => client.destroy()); upstream.end();
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, 'localhost', resolve); });
}
async function stopServer() {
  // 关闭实际监听及升级连接；WebKit的模拟offline导航会在SW缓存响应前报内部错误。
  transports.splice(0).forEach(socket => socket.destroy());
  if (!server) return;
  const closing = server;
  server = undefined;
  closing.closeAllConnections();
  await new Promise(resolve => closing.close(resolve));
}
async function waitRegistration(page, slot, digest, expectedState) {
  const deadline = performance.now() + 70000;
  while (performance.now() < deadline) {
    // waitForFunction轮询函数不用于异步注册查找；显式等待evaluate的Promise再判断事实。
    const value = await page.evaluate(async slot => {
      const registration = await navigator.serviceWorker.getRegistration('/app/');
      const worker = registration?.[slot];
      return worker ? { state: worker.state, scriptURL: worker.scriptURL } : null;
    }, slot);
    if (digest === null ? value === null : value?.scriptURL.includes(digest) && (!expectedState || value.state === expectedState)) return;
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  throw new Error('真实ServiceWorker注册状态等待超时');
}
async function controlled(page, digest) {
  await page.waitForFunction(digest => navigator.serviceWorker.controller?.scriptURL.includes(digest), digest, { timeout: 20000 });
}
async function cacheSnapshot(page, candidates) {
  const stored = await page.evaluate(async () => {
    const result = [];
    for (const name of await caches.keys()) {
      const cache = await caches.open(name);
      for (const request of await cache.keys()) {
        const bytes = await (await cache.match(request)).arrayBuffer();
        const sha256 = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))]
          .map(value => value.toString(16).padStart(2, '0')).join('');
        result.push({ name, url: request.url, byteLength: bytes.byteLength, sha256 });
      }
    }
    return result;
  });
  assert.ok(stored.length > 0);
  const byCache = new Map(candidates.map(release => ['tc-webapp-host-1.0.0-' + release.digest, new Set(release.precache.entries.map(entry => entry.path))]));
  byCache.set('tc-webapp-host-metadata-v1', new Set(['/app/__public_host_release__']));
  for (const entry of stored) {
    const url = new URL(entry.url);
    assert.equal(url.origin, origin); assert.equal(url.search, ''); assert.equal(url.hash, '');
    assert.ok(byCache.get(entry.name)?.has(url.pathname), '只能缓存精确precache公开path与唯一SW版本metadata，禁止旁置文件入缓存');
    assert.ok(!entry.url.includes(secret) && !url.pathname.startsWith('/api/'));
  }
  for (const release of candidates) {
    const actual = stored.filter(entry => entry.name === 'tc-webapp-host-1.0.0-' + release.digest);
    assert.deepEqual(actual.map(entry => new URL(entry.url).pathname).sort(),
      release.precache.entries.map(entry => entry.path).sort(), '本版公开资源必须完整，不能仅验证已有键');
    for (const expected of release.precache.entries) {
      const entry = actual.find(entry => new URL(entry.url).pathname === expected.path);
      assert.equal(entry.byteLength, expected.byteLength);
      assert.equal(entry.sha256, expected.sha256, '实际缓存字节须匹配本版真实清单');
    }
  }
}
async function hostIdentity(page, digest) {
  const identity = await page.evaluate(async () => {
    const controller = navigator.serviceWorker.controller;
    if (!controller) throw new Error('公开Host控制者缺失');
    const channel = new MessageChannel();
    const requestId = crypto.randomUUID();
    let timer;
    try {
      return await new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(new Error('公开Host身份消息超时')), 5000);
        channel.port1.onmessage = async event => {
          try {
            const message = event.data;
            if (message.type !== 'TC_HOST_RELEASE' || message.requestId !== requestId
              || !/^[0-9a-f]{64}$/.test(message.artifactDigest)) throw new Error('公开Host身份消息无效');
            const base = '/app/releases/' + message.artifactDigest + '/';
            const descriptor = await (await fetch(base + 'host-candidate.json', { cache: 'no-store' })).json();
            const receipt = await (await fetch(base + 'source-receipt.json', { cache: 'no-store' })).json();
            resolve({ message: message.artifactDigest, descriptor: descriptor.artifactDigest,
              receipt: receipt.artifactDigest, hostVersion: message.hostVersion });
          } catch (error) { reject(error); }
        };
        controller.postMessage({ type: 'TC_HOST_RELEASE_REQUEST', requestId }, [channel.port2]);
      });
    } finally { clearTimeout(timer); channel.port1.close(); channel.port2.close(); }
  });
  assert.deepEqual(identity, { message: digest, descriptor: digest, receipt: digest, hostVersion: '1.0.0' });
}
async function register(page, digest) {
  await page.evaluate(async digest => {
    await navigator.serviceWorker.register('/app/releases/' + digest + '/sw.js', { scope: '/app/', updateViaCache: 'none' });
  }, digest);
}
async function journey(engine, a, b) {
  browser = await engines[engine].launch({ headless: true });
  const context = await browser.newContext({ serviceWorkers: 'allow', viewport: { width: 1200, height: 900 } });
  context.on('page', page => {
    page.on('pageerror', error => {
      failures.push(engine + ':pageerror');
      console.error('PWA_PAGE_ERROR ' + safe(error.message));
    });
    page.on('console', message => {
      if (message.type() === 'error' || message.type() === 'warning')
        console.error('PWA_CONSOLE ' + JSON.stringify({ engine, type: message.type(), message: safe(message.text()) }));
    });
  });
  latest = a.digest; corrupt = false;
  let page = await context.newPage();
  await page.goto(origin + '/app/');
  await waitRegistration(page, 'active', a.digest, 'activated');
  await page.reload(); // 首次安装不clients.claim，必须新导航才取得控制。
  await controlled(page, a.digest);
  const manifest = await (await fetch(origin + '/app/manifest.webmanifest')).json();
  assert.equal(manifest.start_url, '/app/'); assert.equal(manifest.scope, '/app/');
  await page.goto(origin + '/app/share/' + shareId + '#token=' + secret);
  await page.getByTestId('device-selector-selector').waitFor({ state: 'visible' });
  await page.getByTestId('device-selector-selector').selectOption(process.env.WEBAPP_PWA_FIRST_ID);
  await page.waitForFunction(() => document.querySelector('[data-testid="device-value-value"]')?.textContent.includes('12.5'));
  assert.equal(await page.evaluate(() => location.hash), '');
  await cacheSnapshot(page, [a]);
  await context.setOffline(true);
  await page.getByTestId('dashboard-canvas').waitFor({ state: 'hidden' });
  console.log('PASS ' + engine + ' 模拟offline事件清除真实分享私有画布');
  if (engine === 'webkit') {
    // 模拟offline已验证业务清理；缓存导航改以真实服务不可达验证，不宣称模拟导航通过。
    await stopServer();
    await context.setOffline(false);
    await assert.rejects(fetch(origin + '/__pwa_probe__'), '实际静态服务必须不可达');
  }
  const offlineNavigation = await page.reload();
  assert.equal(offlineNavigation.status(), 200);
  assert.equal(offlineNavigation.fromServiceWorker(), true, '公开壳必须来自真实SW而非HTTP缓存');
  await controlled(page, a.digest);
  await page.getByTestId('share-status').waitFor({ state: 'visible' });
  assert.equal(await page.getByTestId('device-value-value').count(), 0);
  await cacheSnapshot(page, [a]);
  if (engine === 'webkit') {
    console.log('PASS webkit 真实测试HTTP服务不可达时SW缓存reload200且无私有事实');
    await startServer();
  } else await context.setOffline(false);
  await page.goto(origin + '/app/share/' + shareId + '#token=' + secret);
  await page.getByTestId('device-selector-selector').waitFor({ state: 'visible' });
  await page.getByTestId('device-selector-selector').selectOption(process.env.WEBAPP_PWA_FIRST_ID);
  await page.waitForFunction(() => document.querySelector('[data-testid="device-value-value"]')?.textContent.includes('12.5'));
  console.log('PASS ' + engine + ' 生产公开壳缓存与真实分享离线清理/重载/线上确权');

  latest = b.digest; corrupt = true; const damagedBefore = corruptServed;
  await register(page, b.digest).catch(() => {});
  await waitRegistration(page, 'installing', null);
  assert.ok(corruptServed > damagedBefore, '坏候选必须实际下载损坏资源');
  assert.equal(await page.evaluate(async () => !!(await navigator.serviceWorker.getRegistration('/app/'))?.waiting), false);
  await controlled(page, a.digest);
  await cacheSnapshot(page, [a]);
  corrupt = false;
  await register(page, b.digest);
  await waitRegistration(page, 'waiting', b.digest, 'installed');
  await controlled(page, a.digest);
  await hostIdentity(page, a.digest);
  await cacheSnapshot(page, [a, b]);
  await page.close();
  page = await context.newPage();
  // 同源但位于/app/作用域之外，不成为旧Worker客户端；适用于三引擎而非Chromium专属CDP。
  await page.goto(origin + '/__pwa_probe__');
  await waitRegistration(page, 'active', b.digest, 'activated');
  await page.goto(origin + '/app/');
  await controlled(page, b.digest);
  await hostIdentity(page, b.digest);
  await cacheSnapshot(page, [a, b]);
  assert.deepEqual((await page.evaluate(() => caches.keys())).sort(),
    ['tc-webapp-host-1.0.0-' + a.digest, 'tc-webapp-host-1.0.0-' + b.digest, 'tc-webapp-host-metadata-v1'].sort());
  await page.screenshot({ path: path.join(temporary, engine + '-activated.png'), fullPage: true });
  console.log('PASS ' + engine + ' 损坏安装保旧版/默认waiting/关闭旧窗口后升级');
  await context.close(); await browser.close(); browser = undefined;
}
async function main() {
  const a = await candidate(webapp);
  const b = await secondCandidate();
  assert.notEqual(a.digest, b.digest);
  releases.set(a.digest, a); releases.set(b.digest, b);
  await startServer();
  for (const engine of ['chromium', 'firefox', 'webkit']) await journey(engine, a, b);
  assert.deepEqual(failures, []);
  console.log('PWA_ARTIFACTS ' + temporary);
}
main().catch(async error => {
  // 不输出Playwright包含完整凭据URL的异常message，只输出本脚本固定源码位置。
  if (temporary) console.error('PWA_BUILD_LOG ' + path.join(temporary, 'candidate-build.log'));
  const page = browser?.contexts()[0]?.pages().find(page => !page.isClosed());
  if (page) {
    try {
      console.error('PWA_STATE ' + JSON.stringify(await page.evaluate(async () => {
        const registration = await navigator.serviceWorker.getRegistration('/app/');
        const worker = value => value ? { state: value.state, path: new URL(value.scriptURL).pathname } : null;
        return { controller: worker(navigator.serviceWorker.controller), active: worker(registration?.active),
          installing: worker(registration?.installing), waiting: worker(registration?.waiting),
          pwa: document.querySelector('[data-testid="pwa-status"]')?.textContent,
          share: document.querySelector('[data-testid="share-status"]')?.getAttribute('data-status') };
      })));
    } catch { console.error('PWA_STATE unavailable'); }
  }
  console.error('PWA journey failed: ' + (['Error', 'AssertionError', 'TimeoutError'].includes(error.name) ? error.name : 'OtherError'));
  console.error(String(error.stack ?? '').split('\n').filter(line => /at .*webapp-pwa-journey\.cjs:\d+/.test(line)).join('\n'));
  process.exitCode = 1;
}).finally(async () => {
  if (browser) await browser.close();
  await stopServer();
});
