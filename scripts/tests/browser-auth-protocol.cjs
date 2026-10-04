#!/usr/bin/env node
// S12-3b：用真实浏览器、HTTP后端与PG验证协议；此页面只属夹具，不是WebApp宿主交付。
const assert = require('node:assert/strict');
const http = require('node:http');
const path = require('node:path');
const { createRequire } = require('node:module');

const root = path.resolve(__dirname, '../..');
process.env.PLAYWRIGHT_BROWSERS_PATH ??= path.join(root, 'things-link-console/.playwright-browsers');
const { chromium } = createRequire(path.join(root, 'things-link-console/package.json'))('@playwright/test');
const origin = 'http://localhost:18766';
const endpoint = '/api/v1/app/browser-auth/';
const backend = new URL(process.env.BROWSER_AUTH_BACKEND_URL);
const credentials = (prefix = '') => ({
  projectKey: process.env[`BROWSER_AUTH_${prefix}PROJECT_KEY`],
  username: process.env[`BROWSER_AUTH_${prefix}USERNAME`],
  password: process.env[`BROWSER_AUTH_${prefix}PASSWORD`]
});
for (const value of [...Object.values(credentials()), ...Object.values(credentials('OTHER_'))]) {
  assert.ok(value, '需要真实后端临时测试账号');
}

// 只有测试代理持有响应闸门；认证处理及Set-Cookie仍全部由真实后端完成。
const held = new Map();
const arrivals = new Map();
let outsideCookie = false;
let requests = 0;
const fixture = `<!doctype html><meta charset="utf-8"><title>认证协议夹具</title><script>
(() => {
  let epoch = localStorage.getItem('browserEpoch');
  let access = null;
  let flight = null;
  const fresh = () => 'be_' + btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(16))))
    .replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
  const invoke = async (operation, body, hold) => {
    const response = await fetch('/api/v1/app/browser-auth/' + operation, {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...(hold ? { 'X-Test-Hold': hold } : {}) },
      body: JSON.stringify(body)
    });
    const text = await response.text();
    return { status: response.status, data: text ? JSON.parse(text) : null,
      cache: response.headers.get('cache-control'), cookieHeader: response.headers.get('set-cookie') };
  };
  const accept = (result, expected) => {
    if (localStorage.getItem('browserEpoch') !== expected) { access = null; return { stale: true }; }
    access = result.status === 200 ? result.data.accessToken : null;
    return result;
  };
  window.protocol = {
    fresh,
    raw: invoke,
    // rawLateLogin刻意不走互斥，以注入已发响应交错；正常身份操作全程持有同一个WebLock。
    rawLateLogin: async (body, hold) => {
      epoch = fresh(); localStorage.setItem('browserEpoch', epoch); access = null;
      const expected = epoch;
      return accept(await invoke('login', { ...body, browserEpoch: expected }, hold), expected);
    },
    login: body => navigator.locks.request('app-browser-session', async () => {
      epoch = fresh(); localStorage.setItem('browserEpoch', epoch); access = null;
      const expected = epoch;
      return accept(await invoke('login', { ...body, browserEpoch: expected }), expected);
    }),
    adopt: () => { epoch = localStorage.getItem('browserEpoch'); access = null; },
    refresh: () => {
      if (flight) return flight;
      flight = navigator.locks.request('app-browser-session', async () => {
        if (epoch !== localStorage.getItem('browserEpoch')) { access = null; return { stale: true }; }
        const expected = epoch;
        return accept(await invoke('refresh', { browserEpoch: expected }), expected);
      }).finally(() => { flight = null; });
      return flight;
    },
    epoch: () => localStorage.getItem('browserEpoch'),
    hasAccess: () => access !== null,
    logout: () => navigator.locks.request('app-browser-session', async () => {
      if (epoch !== localStorage.getItem('browserEpoch')) { access = null; return { stale: true }; }
      const previous = epoch; epoch = fresh(); localStorage.setItem('browserEpoch', epoch); access = null;
      return invoke('logout', { browserEpoch: previous });
    })
  };
})();
</script>`;

const server = http.createServer((req, res) => {
  if (req.url === '/favicon.ico') { res.writeHead(204); res.end(); return; }
  if (req.url === '/' || req.url === '/outside' || req.url === endpoint + 'fixture') {
    if (req.url === '/outside') outsideCookie = Boolean(req.headers.cookie);
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(fixture);
    return;
  }
  requests++;
  const gate = req.headers['x-test-hold'];
  const headers = { ...req.headers, host: backend.host };
  delete headers['x-test-hold'];
  const upstream = http.request(new URL(req.url, backend), { method: req.method, headers }, result => {
    const chunks = [];
    result.on('data', chunk => chunks.push(chunk));
    result.on('end', () => {
      const deliver = () => { res.writeHead(result.statusCode, result.headers); res.end(Buffer.concat(chunks)); };
      if (gate) { held.set(gate, deliver); arrivals.get(gate)?.(); }
      else deliver();
    });
  });
  upstream.on('error', () => { res.writeHead(502); res.end(); });
  req.pipe(upstream);
});

async function waitHeld(key) {
  if (held.has(key)) return;
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('真实后端响应闸门未到达')), 15000);
    arrivals.set(key, () => { clearTimeout(timer); resolve(); });
  });
}
function release(key) { assert.ok(held.has(key)); held.get(key)(); held.delete(key); }
function session(result) {
  assert.equal(result.status, 200);
  assert.deepEqual(Object.keys(result.data).sort(), ['accessExpiresAt', 'accessToken', 'appUserId', 'projectId']);
  assert.equal(result.cache, 'no-store');
  assert.equal(result.cookieHeader, null);
  return result.data;
}

async function main() {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(18766, resolve); });
  const browser = await chromium.launch({ headless: true });
  try {
    const context = await browser.newContext();
    const a = await context.newPage();
    const b = await context.newPage();
    await Promise.all([a.goto(origin), b.goto(origin)]);
    const login = session(await a.evaluate(c => protocol.login(c), credentials()));
    const cookies = await context.cookies(origin + endpoint);
    assert.equal(cookies.length, 1);
    assert.equal(cookies[0].name, 'tc_app_refresh');
    assert.equal(cookies[0].httpOnly, true);
    assert.equal(cookies[0].sameSite, 'Strict');
    assert.equal(cookies[0].path, endpoint.slice(0, -1));
    assert.equal(cookies[0].secure, false); // 只验证显式loopback例外，HTTPS Secure另由HTTP配置测试证明。
    assert.ok(cookies[0].expires > Date.now() / 1000);
    await a.goto(origin + endpoint + 'fixture');
    assert.equal(await a.evaluate(() => document.cookie), '');
    await a.goto(origin + '/outside');
    assert.equal(outsideCookie, false);

    const before = requests;
    const same = await a.evaluate(() => Promise.all([protocol.refresh(), protocol.refresh()]));
    assert.equal(requests - before, 1, '同标签刷新必须单飞');
    assert.equal(session(same[0]).appUserId, login.appUserId);
    await b.evaluate(() => protocol.adopt());
    const both = await Promise.all([a.evaluate(() => protocol.refresh()), b.evaluate(() => protocol.refresh())]);
    both.forEach(result => assert.equal(session(result).appUserId, login.appUserId));
    console.log('PASS Chromium HttpOnly/Path/Strict、无refresh正文、同标签单飞和双标签互斥轮换');

    const next = session(await b.evaluate(c => protocol.login(c), credentials('OTHER_')));
    assert.notEqual(next.appUserId, login.appUserId);
    const staleRequests = requests;
    assert.equal((await a.evaluate(() => protocol.refresh())).stale, true);
    assert.equal(requests, staleRequests, '旧标签不能借用新身份发刷新');
    const originalEpoch = await a.evaluate(() => protocol.epoch());
    const oldLogin = a.evaluate(c => protocol.rawLateLogin(c, 'old-login'), credentials());
    await waitHeld('old-login');
    const newest = session(await b.evaluate(c => protocol.login(c), credentials('OTHER_')));
    release('old-login');
    assert.equal((await oldLogin).stale, true, '真实旧响应不得确认旧身份');
    const currentEpoch = await b.evaluate(() => protocol.epoch());
    assert.notEqual(currentEpoch, originalEpoch);
    const overwritten = (await context.cookies(origin + endpoint))[0].value;
    const mismatch = await b.evaluate(() => protocol.refresh());
    assert.equal(mismatch.status, 409);
    assert.equal(mismatch.data.code, 60029);
    assert.equal(await b.evaluate(() => protocol.hasAccess()), false);
    assert.equal((await context.cookies(origin + endpoint))[0].value, overwritten, '错代不能清Cookie');
    assert.equal(await b.evaluate(() => protocol.epoch()), currentEpoch);
    console.log('PASS Chromium跨账号替换、旧标签停发、真实迟login覆写后拒绝身份回退');

    session(await b.evaluate(c => protocol.login(c), credentials('OTHER_')));
    const logoutEpoch = await b.evaluate(() => protocol.epoch());
    const lateLogout = b.evaluate(epoch => protocol.raw('logout', { browserEpoch: epoch }, 'old-logout'), logoutEpoch);
    await waitHeld('old-logout');
    session(await a.evaluate(c => protocol.login(c), credentials()));
    release('old-logout');
    assert.equal((await lateLogout).status, 204);
    assert.equal((await context.cookies(origin + endpoint)).length, 0, '真实迟删除可能删除新Cookie');
    assert.equal((await a.evaluate(() => protocol.refresh())).status, 401);
    assert.equal(await a.evaluate(() => protocol.hasAccess()), false);
    session(await a.evaluate(c => protocol.login(c), credentials()));
    assert.equal((await a.evaluate(() => protocol.logout())).status, 204);
    assert.equal((await context.cookies(origin + endpoint)).length, 0);
    console.log('PASS Chromium迟logout删除的MISSING边界与真实退出清Cookie');

    const denied = await context.newPage();
    await denied.goto('http://127.0.0.1:18766');
    const wrongOrigin = await denied.evaluate(() => protocol.raw('refresh', { browserEpoch: protocol.fresh() }));
    assert.equal(wrongOrigin.status, 403);
    assert.equal(wrongOrigin.data.code, 60028);
    for (const page of [a, b]) {
      assert.deepEqual(await page.evaluate(() => Object.keys(localStorage)), ['browserEpoch']);
      assert.equal(await page.evaluate(() => sessionStorage.length), 0);
      assert.deepEqual(await page.evaluate(() => caches.keys()), []);
    }
    assert.equal(newest.projectId, next.projectId);
    console.log('PASS Chromium实际Origin拒绝与浏览器存储不留凭据');
    await context.close();
  } finally {
    await browser.close();
  }
}
main().catch(error => {
  // 断言上下文可能含Cookie/access；只输出错误位置，不打印actual/expected或环境。
  console.error('浏览器认证协议验证失败：' + (error.stack?.split('\n').slice(1, 4).join('\n') ?? '未知位置'));
  process.exitCode = 1;
}).finally(() => {
  server.closeAllConnections();
  server.close();
});
