import { describe, expect, it, vi } from 'vitest';
import { captureShareEntry } from './entry';
const id = '11111111-1111-4111-8111-111111111111';
const token = `sh_${'A'.repeat(43)}`;
describe('earliest share fragment capture', () => {
  it('clears fragment synchronously and hands off a one-use secret-free capability', () => {
    const replaceState = vi.fn(); const entry = captureShareEntry({ href: `https://app.test/app/share/${id}#token=${token}`, replaceState });
    expect(replaceState).toHaveBeenCalledWith(`/app/share/${id}`); expect(JSON.stringify(entry)).not.toContain(token);
    expect(entry.status).toBe('ready'); if (entry.status !== 'ready') throw new Error();
    const session = entry.createSession({ origin: 'https://app.test', fetch: vi.fn() });
    expect(session.checkCurrent()).toEqual({ shareId: id }); expect(JSON.stringify(session)).not.toContain(token);
    expect(() => entry.createSession({ origin: 'https://app.test', fetch: vi.fn() })).toThrow();
  });
  it.each([`#token=${token}&token=${token}`, `#token=${token}=`, '#token=sh_' + 'B'.repeat(43), `#token=%73${token.slice(1)}`])('rejects noncanonical fragment %s after clearing', fragment => {
    const replaceState = vi.fn(); expect(captureShareEntry({ href: `https://app.test/app/share/${id}${fragment}`, replaceState }).status).toBe('invalid'); expect(replaceState).toHaveBeenCalledOnce();
  });
  it('fails closed if history cleanup fails and never restores on reload', () => {
    expect(captureShareEntry({ href: `https://app.test/app/share/${id}#token=${token}`, replaceState: () => { throw new Error('denied'); } }).status).toBe('invalid');
    expect(captureShareEntry({ href: `https://app.test/app/share/${id}`, replaceState: vi.fn() }).status).toBe('missing');
  });
});
