import { afterEach, describe, expect, it, vi } from 'vitest';
import { controlledRelease } from './release-identity';
const digest = 'a'.repeat(64);
const origin = 'https://host.test';
function fixture(scriptURL = `${origin}/app/releases/${digest}/sw.js`) {
  const port1 = { onmessage: null as ((event: { data: unknown }) => void) | null, close: vi.fn() };
  const port2 = { close: vi.fn() };
  vi.stubGlobal('MessageChannel', class { port1 = port1; port2 = port2; });
  const postMessage = vi.fn(); const controller = { scriptURL, postMessage } as unknown as ServiceWorker;
  const answer = (overrides: Record<string, unknown> = {}) => ({ type: 'TC_HOST_RELEASE', requestId: postMessage.mock.calls[0]![0].requestId,
    hostVersion: '1.0.0', artifactDigest: digest, ...overrides });
  return { port1, port2, postMessage, controller, answer };
}
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
describe('controlled host release identity', () => {
  it.each(['1.0.0', '1.1.0', '1.1.1'])('accepts exactly the controller path identity for %s and closes both ports', async hostVersion => {
    const f = fixture(); const pending = controlledRelease(f.controller, origin);
    expect(f.postMessage).toHaveBeenCalledWith(expect.objectContaining({ type: 'TC_HOST_RELEASE_REQUEST' }), [f.port2]);
    f.port1.onmessage!({ data: f.answer({ hostVersion }) }); expect(await pending).toBe(digest);
    expect(f.port1.close).toHaveBeenCalled(); expect(f.port2.close).toHaveBeenCalled();
  });
  it.each([{ requestId: 'old' }, { artifactDigest: 'b'.repeat(64) }, { hostVersion: '2.0.0' }, { extra: 'private' }, { type: 'OTHER' }])('rejects mismatched or extended metadata %j', async changes => {
    const f = fixture(); const pending = controlledRelease(f.controller, origin);
    f.port1.onmessage!({ data: f.answer(changes) }); await expect(pending).rejects.toThrow('宿主控制器版本不可用');
  });
  it.each([`https://else.test/app/releases/${digest}/sw.js`, `${origin}/app/sw.js`, `${origin}/app/releases/${digest}/sw.js?secret=x`])('rejects controller outside canonical origin/release before messaging', async path => {
    const f = fixture(path); await expect(controlledRelease(f.controller, origin)).rejects.toThrow(); expect(f.postMessage).not.toHaveBeenCalled();
  });
  it('times out in two seconds and late valid reply cannot resurrect its promise', async () => {
    vi.useFakeTimers(); const f = fixture(); const pending = controlledRelease(f.controller, origin);
    const rejection = expect(pending).rejects.toThrow('宿主控制器版本不可用');
    await vi.advanceTimersByTimeAsync(2000); await rejection;
    f.port1.onmessage!({ data: f.answer() }); await expect(pending).rejects.toThrow();
  });
  it('rejects transfer failure immediately and cleans the timeout', async () => {
    vi.useFakeTimers(); const f = fixture(); f.postMessage.mockImplementation(() => { throw new Error('cannot transfer'); });
    await expect(controlledRelease(f.controller, origin)).rejects.toThrow('宿主控制器版本不可用'); expect(vi.getTimerCount()).toBe(0);
    expect(f.port1.close).toHaveBeenCalled(); expect(f.port2.close).toHaveBeenCalled();
  });
});
