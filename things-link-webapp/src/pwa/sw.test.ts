import { readFileSync } from 'node:fs';
import { createHash, webcrypto } from 'node:crypto';
import { runInNewContext } from 'node:vm';
import { describe, expect, it, vi } from 'vitest';
const source = readFileSync(new URL('./sw.js', import.meta.url), 'utf8')
  .replace("const HOST_VERSION = '1.0.0';", `const HOST_VERSION = '${__HOST_VERSION__}';`);
const digest = 'a'.repeat(64); const previous = 'b'.repeat(64); const older = 'c'.repeat(64);
const origin = 'https://host.test'; const prefix = `tc-webapp-host-${__HOST_VERSION__}-`;
const html = '<!doctype html><title>公开壳</title>';
const sha = (value: string) => createHash('sha256').update(value).digest('hex');
function fixture(options: { entries?: unknown[]; manifestText?: string; wrongBody?: boolean; networkFailure?: boolean; cacheFailure?: boolean } = {}) {
  const listeners = new Map<string, (event: any) => void>();
  const tables = new Map<string, Map<string, Response>>();
  const key = (input: string | Request) => typeof input === 'string' ? input : input.url;
  const caches = {
    keys: async () => [...tables.keys()], delete: async (name: string) => tables.delete(name),
    open: async (name: string) => {
      if (!tables.has(name)) tables.set(name, new Map()); const table = tables.get(name)!;
      return { put: async (request: string | Request, response: Response) => {
        if (options.cacheFailure && name === prefix + digest) throw new Error('quota'); table.set(key(request), response.clone());
      }, match: async (request: string | Request) => table.get(key(request))?.clone(), keys: async () => [...table.keys()].map(url => new Request(url)) };
    },
  };
  const entries = options.entries ?? [{ path: '/app/index.html', sha256: sha(html), byteLength: Buffer.byteLength(html) }];
  const manifest = options.manifestText ?? JSON.stringify({ formatVersion: 'tc.webapp-precache/v1', hostVersion: __HOST_VERSION__, artifactDigest: digest, entries });
  const fetch = vi.fn(async (input: string | Request) => {
    const url = key(input); if (options.networkFailure) throw new Error('offline');
    if (url.endsWith('/precache.json')) return new Response(manifest);
    if (url.includes(`/releases/${digest}/`)) return new Response(options.wrongBody ? 'altered' : html, { headers: { 'Content-Type': 'text/html', 'Referrer-Policy': 'same-origin' } });
    return new Response('network only');
  });
  const client = { id: 'page', type: 'window', url: origin + '/app/share/' + previous };
  const self = { location: { href: origin + `/app/releases/${digest}/sw.js` }, addEventListener: (name: string, handler: (e: any) => void) => listeners.set(name, handler),
    clients: { get: vi.fn(async () => client), matchAll: vi.fn(async () => [client]), claim: vi.fn() }, skipWaiting: vi.fn() };
  runInNewContext(source, { self, caches, fetch, AbortController, setTimeout, clearTimeout, crypto: webcrypto, URL, Request, Response, Headers, TextDecoder, Uint8Array, console: undefined });
  const lifecycle = async (name: string) => { let result: Promise<void> | undefined; listeners.get(name)!({ waitUntil: (promise: Promise<void>) => { result = promise; } }); await result; };
  const request = async (url: string, mode?: string) => {
    const input = new Request(url); if (mode) Object.defineProperty(input, 'mode', { value: mode });
    let result!: Promise<Response>; listeners.get('fetch')!({ request: input, respondWith: (promise: Promise<Response>) => { result = promise; } }); return result;
  };
  return { tables, caches, fetch, self, listeners, lifecycle, request };
}
describe('version bound public service worker', () => {
  it('installs verified public bytes under public keys and serves this release navigation offline', async () => {
    const f = fixture(); await f.lifecycle('install'); await f.lifecycle('activate');
    const cache = f.tables.get(prefix + digest)!; expect([...cache.keys()]).toEqual([origin + '/app/index.html']);
    expect(await (await f.request(origin + '/app/share/' + previous, 'navigate')).text()).toBe(html);
    expect(f.fetch.mock.calls.every(([input]) => String(input).includes(`/releases/${digest}/`))).toBe(true);
    expect(f.self.skipWaiting).not.toHaveBeenCalled(); expect(f.self.clients.claim).not.toHaveBeenCalled();
  });
  it.each(['/api/v1/app/current', '/app/assets/../private.js', '/app/assets/%61.js', '/app/assets/app.js?token=x', 'https://else.test/app/index.html', '/app/assets/x.json'])('rejects unsafe precache entry %s', async path => {
    const f = fixture({ entries: [{ path, sha256: sha(html), byteLength: Buffer.byteLength(html) }] });
    await expect(f.lifecycle('install')).rejects.toThrow(); expect([...f.tables.keys()]).toEqual([]);
  });
  it('rejects duplicate paths, duplicate fields, digest mismatch, oversized and missing shell', async () => {
    const good = { path: '/app/index.html', sha256: sha(html), byteLength: Buffer.byteLength(html) };
    const variants = [fixture({ entries: [good, good] }), fixture({ entries: [{ ...good, byteLength: 4194305 }] }),
      fixture({ entries: [{ ...good, sha256: previous }] }), fixture({ entries: [{ ...good, path: '/app/assets/app.js' }] }),
      fixture({ manifestText: `{"formatVersion":"tc.webapp-precache/v1","formatVersion":"tc.webapp-precache/v1"}` }), fixture({ manifestText: ' '.repeat(65537) })];
    for (const f of variants) await expect(f.lifecycle('install')).rejects.toThrow();
  });
  it.each([{ wrongBody: true }, { networkFailure: true }, { cacheFailure: true }])('failed install preserves previous active and unrelated caches %j', async options => {
    const f = fixture(options); f.tables.set(prefix + previous, new Map([[origin + '/app/index.html', new Response('old')]])); f.tables.set('other-app', new Map());
    await expect(f.lifecycle('install')).rejects.toThrow(); expect([...f.tables.keys()].sort()).toEqual(['other-app', prefix + previous].sort());
  });
  it('retains only this and previous activated versions without touching external namespaces', async () => {
    const f = fixture(); f.tables.set(prefix + previous, new Map()); f.tables.set(prefix + older, new Map()); f.tables.set('outside-cache', new Map());
    f.tables.set('tc-webapp-host-metadata-v1', new Map([[origin + '/app/__public_host_release__', Response.json({ active: previous, previous: older })]]));
    await f.lifecycle('install'); await f.lifecycle('activate');
    expect([...f.tables.keys()].sort()).toEqual([prefix + digest, prefix + previous, 'outside-cache', 'tc-webapp-host-metadata-v1'].sort());
    await f.lifecycle('activate'); expect(f.tables.has(prefix + previous)).toBe(true);
  });
  it('does not intercept API, identity, query URLs, private responses or host metadata', async () => {
    const f = fixture(); await f.lifecycle('install');
    const previousCalls = f.fetch.mock.calls.length;
    for (const url of [origin + '/api/v1/shares/' + previous + '/context', origin + '/api/v1/app/auth/browser/refresh',
      origin + '/api/v1/app/applications/app_a/current', origin + '/app/assets/app.js?token=secret',
      origin + '/app/host-candidate.json', origin + '/app/releases/' + digest + '/source-receipt.json',
      origin + '/app/releases/' + digest + '/host-candidate.json', 'https://else.test/private']) {
      const respondWith = vi.fn(); f.listeners.get('fetch')!({ request: new Request(url, { cache: 'no-store' }), respondWith });
      expect(respondWith).not.toHaveBeenCalled();
    }
    expect(f.fetch).toHaveBeenCalledTimes(previousCalls);
    expect([...f.tables.get(prefix + digest)!.keys()]).toEqual([origin + '/app/index.html']);
  });
  it('returns explicit public failure rather than borrowing another version shell', async () => {
    const f = fixture(); f.tables.set(prefix + previous, new Map([[origin + '/app/index.html', new Response('old')]]));
    expect((await f.request(origin + '/app/project', 'navigate')).status).toBe(503);
  });
  it('answers metadata only to controlled same-origin app windows and exact messages', async () => {
    const f = fixture(); const port = { postMessage: vi.fn() }; const message = { type: 'TC_HOST_RELEASE_REQUEST', requestId: '11111111-1111-4111-8111-111111111111' };
    const send = async (data: unknown) => { let done!: Promise<void>; f.listeners.get('message')!({ data, source: { id: 'page' }, ports: [port], waitUntil: (promise: Promise<void>) => { done = promise; } }); await done; };
    await send(message); expect(port.postMessage).toHaveBeenCalledWith({ type: 'TC_HOST_RELEASE', requestId: message.requestId, hostVersion: __HOST_VERSION__, artifactDigest: digest });
    port.postMessage.mockClear(); await send({ ...message, secret: 'private' }); expect(port.postMessage).not.toHaveBeenCalled();
    f.self.clients.matchAll.mockResolvedValue([]); await send(message); expect(port.postMessage).not.toHaveBeenCalled();
  });
});


it('aborts an installation stalled in the public body at the total 60 second deadline and preserves old cache', async () => {
  vi.useFakeTimers();
  try {
    const f = fixture(); f.tables.set(prefix + previous, new Map([[origin + '/app/index.html', new Response('old')]]));
    const cancel = vi.fn();
    f.fetch.mockImplementationOnce(async () => new Response(new ReadableStream({ cancel })));
    const install = f.lifecycle('install'); const rejection = expect(install).rejects.toThrow();
    await vi.advanceTimersByTimeAsync(59999); expect(cancel).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1); await rejection;
    expect(cancel).toHaveBeenCalledOnce(); expect([...f.tables.keys()]).toEqual([prefix + previous]);
    expect(vi.getTimerCount()).toBe(0);
  } finally { vi.useRealTimers(); }
});


it('leaves HTTP failures and network rejection to the original caller without SW response promises', async () => {
  const f = fixture(); await f.lifecycle('install');
  const keys = [...f.tables.get(prefix + digest)!.keys()];
  const request = new Request(origin + '/api/v1/shares/' + previous + '/context', { cache: 'no-store' });
  const respondWith = vi.fn(); f.listeners.get('fetch')!({ request, respondWith });
  expect(respondWith).not.toHaveBeenCalled();
  // 未接管时浏览器按原请求执行；测试分别证明原调用仍能观察网络拒绝和HTTP状态。
  f.fetch.mockRejectedValueOnce(new TypeError('Load failed'));
  await expect(f.fetch(request)).rejects.toThrow('Load failed');
  f.fetch.mockResolvedValueOnce(new Response('upstream unavailable', { status: 503, headers: { 'Retry-After': '10' } }));
  const unavailable = await f.fetch(request);
  expect(unavailable.status).toBe(503); expect(unavailable.headers.get('Retry-After')).toBe('10');
  expect(await unavailable.text()).toBe('upstream unavailable');
  expect([...f.tables.get(prefix + digest)!.keys()]).toEqual(keys);
});
