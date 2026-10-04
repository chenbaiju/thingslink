/* 独立经典Worker；仅持有已验证公开制品和版本元数据，禁止导入业务代码。 */
'use strict';
const HOST_VERSION = '1.0.0';
const CACHE_PREFIX = 'tc-webapp-host-' + HOST_VERSION + '-';
const STAGING_PREFIX = 'tc-webapp-host-staging-' + HOST_VERSION + '-';
const META_CACHE = 'tc-webapp-host-metadata-v1';
const META_PATH = '/app/__public_host_release__';
const HEX = /^[0-9a-f]{64}$/;
const script = new URL(self.location.href);
const releaseMatch = /^\/app\/releases\/([0-9a-f]{64})\/sw\.js$/.exec(script.pathname);
if (!releaseMatch || script.search || script.hash || !['https:', 'http:'].includes(script.protocol)) throw new Error('Invalid public host release');
const DIGEST = releaseMatch[1];
const CACHE = CACHE_PREFIX + DIGEST;
const RELEASE = '/app/releases/' + DIGEST;
const origin = script.origin;
const fail = () => { throw new Error('Invalid public host artifact'); };
const exact = (value, fields) => {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).sort().join(',') !== fields.slice().sort().join(',')) fail();
};
const publicPath = path => typeof path === 'string' && (['/app/index.html', '/app/manifest.webmanifest', '/app/sw.js'].includes(path)
  || /^\/app\/assets\/(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_-]+\.(?:js|css|png|svg|woff2)$/.test(path));

/* 清单同样拒重复字段、尾随数据和过深结构，不能让JSON最后值覆盖改变文件身份。 */
function parseManifest(bytes) {
  if (bytes.length >= 3 && bytes[0] === 239 && bytes[1] === 187 && bytes[2] === 191) fail();
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  let offset = 0;
  const white = () => { while (/[\t\n\r ]/.test(text[offset] || '\0')) offset++; };
  const string = () => {
    const start = offset++;
    while (offset < text.length) {
      const character = text[offset++];
      if (character === '\\') { offset++; continue; }
      if (character === '"') return JSON.parse(text.slice(start, offset));
    }
    return fail();
  };
  const value = depth => {
    if (depth > 6) fail(); white();
    if (text[offset] === '"') return string();
    if (text[offset] === '{') {
      offset++; white(); const result = Object.create(null); const seen = new Set();
      if (text[offset] === '}') { offset++; return result; }
      for (;;) {
        white(); if (text[offset] !== '"') fail(); const key = string();
        if (seen.has(key)) fail(); seen.add(key); white(); if (text[offset++] !== ':') fail();
        result[key] = value(depth + 1); white(); const separator = text[offset++];
        if (separator === '}') return result; if (separator !== ',') fail();
      }
    }
    if (text[offset] === '[') {
      offset++; white(); const result = []; if (text[offset] === ']') { offset++; return result; }
      for (;;) { if (result.length >= 128) fail(); result.push(value(depth + 1)); white(); const separator = text[offset++]; if (separator === ']') return result; if (separator !== ',') fail(); }
    }
    const token = /^(?:-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null)/.exec(text.slice(offset));
    if (!token) fail(); offset += token[0].length; return JSON.parse(token[0]);
  };
  const result = value(0); white(); if (offset !== text.length) fail(); return result;
}
async function bounded(response, limit, signal) {
  if (signal.aborted) fail();
  if (!response.ok || response.type === 'opaque' || !response.body) fail();
  const length = response.headers.get('Content-Length');
  if (length !== null && (!/^[0-9]+$/.test(length) || Number(length) > limit)) fail();
  const reader = response.body.getReader();
  const abort = () => { void reader.cancel().catch(() => {}); };
  signal.addEventListener('abort', abort, { once: true });
  const chunks = []; let count = 0;
  try {
    for (;;) { const part = await reader.read(); if (signal.aborted) fail(); if (part.done) break; count += part.value.byteLength; if (count > limit) fail(); chunks.push(part.value); }
  } catch (error) { try { await reader.cancel(); } catch { /* 不覆盖最初失败。 */ } throw error; }
  finally { signal.removeEventListener('abort', abort); reader.releaseLock(); }
  const result = new Uint8Array(count); let position = 0;
  for (const chunk of chunks) { result.set(chunk, position); position += chunk.length; }
  return result;
}
const network = (path, signal) => fetch(origin + RELEASE + path, { credentials: 'omit', mode: 'same-origin', redirect: 'error', cache: 'no-store', signal });
const hash = async bytes => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), byte => byte.toString(16).padStart(2, '0')).join('');
async function installRelease() {
  // 公开安装独立60秒总上限，不延长任何业务恢复轮，也不自动重试坏候选。
  const controller = new AbortController(); const signal = controller.signal;
  const deadline = setTimeout(() => controller.abort(), 60000);
  const temporaryName = STAGING_PREFIX + DIGEST;
  let prior = false;
  let publishing = false;
  try {
    prior = (await caches.keys()).includes(CACHE);
    const manifest = parseManifest(await bounded(await network('/precache.json', signal), 65536, signal));
    exact(manifest, ['formatVersion', 'hostVersion', 'artifactDigest', 'entries']);
    if (manifest.formatVersion !== 'tc.webapp-precache/v1' || manifest.hostVersion !== HOST_VERSION || manifest.artifactDigest !== DIGEST
      || !Array.isArray(manifest.entries) || manifest.entries.length < 1 || manifest.entries.length > 128) fail();
    const paths = new Set(); let total = 0;
    for (const entry of manifest.entries) {
      exact(entry, ['path', 'sha256', 'byteLength']);
      if (!publicPath(entry.path) || paths.has(entry.path) || !HEX.test(entry.sha256) || !Number.isSafeInteger(entry.byteLength)
        || entry.byteLength < 1 || entry.byteLength > 4194304) fail();
      paths.add(entry.path); total += entry.byteLength; if (total > 16777216) fail();
    }
    if (!paths.has('/app/index.html')) fail();
    await caches.delete(temporaryName); const temporary = await caches.open(temporaryName);
    for (const entry of manifest.entries) {
      const response = await network(entry.path.slice('/app'.length), signal);
      const bytes = await bounded(response, entry.byteLength, signal);
      if (signal.aborted || bytes.length !== entry.byteLength || await hash(bytes) !== entry.sha256) fail();
      const headers = new Headers();
      for (const key of ['content-type', 'content-security-policy', 'referrer-policy', 'x-content-type-options', 'x-frame-options']) {
        const value = response.headers.get(key); if (value !== null) headers.set(key, value);
      }
      headers.set('Cache-Control', 'no-store');
      await temporary.put(origin + entry.path, new Response(bytes, { status: 200, headers }));
    }
    // 全部摘要验证完成后才发布；同D已存在时保持原版，不能破坏正在受控的同版页面。
    if (signal.aborted) fail();
    if (!prior) {
      publishing = true; const destination = await caches.open(CACHE);
      for (const request of await temporary.keys()) { if (signal.aborted) fail(); await destination.put(request, await temporary.match(request)); }
      if (signal.aborted) fail();
    }
  } catch (error) {
    if (publishing && !prior) await caches.delete(CACHE);
    throw error;
  } finally { clearTimeout(deadline); await caches.delete(temporaryName); }
}
async function activateRelease() {
  const metadata = await caches.open(META_CACHE);
  let previous;
  try {
    const response = await metadata.match(origin + META_PATH);
    if (response) {
      const saved = JSON.parse(await response.text());
      if (saved.active === DIGEST && HEX.test(saved.previous)) previous = saved.previous;
      else if (HEX.test(saved.active) && saved.active !== DIGEST) previous = saved.active;
    }
  } catch { /* 元数据仅影响保留旧版，不允许改变当前制品身份。 */ }
  const names = await caches.keys();
  if (!names.includes(CACHE)) fail();
  if (previous && !names.includes(CACHE_PREFIX + previous)) previous = undefined;
  await metadata.put(origin + META_PATH, Response.json({ active: DIGEST, previous: previous || null }));
  for (const name of names) {
    if (name.startsWith(CACHE_PREFIX) && HEX.test(name.slice(CACHE_PREFIX.length)) && name !== CACHE && name !== CACHE_PREFIX + previous) await caches.delete(name);
    if (name.startsWith(STAGING_PREFIX) && HEX.test(name.slice(STAGING_PREFIX.length))) await caches.delete(name);
  }
  // 不skipWaiting、不claim：旧页继续由旧Worker和旧版资源服务。
}
const offline = () => new Response('公开宿主离线资源不可用，请联网重新打开。', { status: 503, headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' } });
async function publicResponse(path) { return await (await caches.open(CACHE)).match(origin + path) || offline(); }
self.addEventListener('install', event => { event.waitUntil(installRelease()); });
self.addEventListener('activate', event => { event.waitUntil(activateRelease()); });
self.addEventListener('fetch', event => {
  const request = event.request; const url = new URL(request.url);
  if (request.method === 'GET' && url.origin === origin && (url.pathname === '/app/' || url.pathname.startsWith('/app/')) && request.mode === 'navigate') {
    event.respondWith(publicResponse('/app/index.html')); return;
  }
  if (request.method === 'GET' && url.origin === origin && !url.search && !url.hash && publicPath(url.pathname)) {
    // 仅命中的本版条目可离线读取；未列举静态亦不借网络混入新版。
    event.respondWith(publicResponse(url.pathname)); return;
  }
  // API/身份/私有数据/旁置清单完全不接管：浏览器原调用处理网络错误。
  // no-store由会话、公开resolve及host元数据入口落实；此处不fetch、match或put。
  return;
});
self.addEventListener('message', event => {
  event.waitUntil((async () => {
    const message = event.data;
    try { exact(message, ['type', 'requestId']); } catch { return; }
    if (message.type !== 'TC_HOST_RELEASE_REQUEST' || typeof message.requestId !== 'string'
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(message.requestId)
      || !event.source || !event.source.id || !event.ports || event.ports.length !== 1) return;
    const client = await self.clients.get(event.source.id);
    if (!client || client.type !== 'window') return;
    const url = new URL(client.url);
    if (url.origin !== origin || !url.pathname.startsWith('/app/')) return;
    const controlled = await self.clients.matchAll({ type: 'window', includeUncontrolled: false });
    if (!controlled.some(item => item.id === client.id)) return;
    event.ports[0].postMessage({ type: 'TC_HOST_RELEASE', requestId: message.requestId, hostVersion: HOST_VERSION, artifactDigest: DIGEST });
  })());
});
