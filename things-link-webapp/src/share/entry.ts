import { createShareSession, type ShareSession, type ShareSessionPorts } from './session';
export type ShareEntry = { readonly status: 'invalid' | 'missing' } | {
  readonly status: 'ready'; readonly shareId: string;
  createSession(ports: ShareSessionPorts): ShareSession;
};
/** 最早启动同步清fragment；只有清理成功后，闭包才能一次性交付无凭据getter的会话。 */
export function captureShareEntry(ports: { href: string; replaceState(url: string): void }): ShareEntry {
  let url: URL; try { url = new URL(ports.href); } catch { return Object.freeze({ status: 'invalid' }); }
  const fragment = url.hash; url.hash = '';
  try { ports.replaceState(`${url.pathname}${url.search}`); } catch { return Object.freeze({ status: 'invalid' }); }
  const match = /^\/app\/share\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/.exec(url.pathname);
  if (!match || url.search || url.username || url.password || !['https:', 'http:'].includes(url.protocol)) return Object.freeze({ status: 'invalid' });
  if (!fragment) return Object.freeze({ status: 'missing' });
  const token = /^#token=(sh_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048])$/.exec(fragment);
  if (!token) return Object.freeze({ status: 'invalid' });
  let secret: string | undefined = token[1]; const shareId = match[1]!;
  return Object.freeze({ status: 'ready', shareId, createSession: (sessionPorts: ShareSessionPorts): ShareSession => {
    if (!secret || sessionPorts.origin !== url.origin) throw new Error('Share entry unavailable');
    const value = secret; secret = undefined; return createShareSession(shareId, value, sessionPorts);
  } });
}
