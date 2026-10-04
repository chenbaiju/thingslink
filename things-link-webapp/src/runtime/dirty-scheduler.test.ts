import { afterEach, expect, it, vi } from 'vitest';
import { createDirtyScheduler } from './dirty-scheduler';
afterEach(() => vi.useRealTimers());
it('merges arrivals, retains in-flight dirt and waits at least one second between starts', async () => {
  vi.useFakeTimers(); vi.setSystemTime(0);
  let finish!: () => void;
  const run = vi.fn(() => new Promise<void>(resolve => { finish = resolve; }));
  const s = createDirtyScheduler({ now: () => Date.now(), readyAt: () => Date.now(), run,
    setTimer: (f, ms) => setTimeout(f, ms), clearTimer: t => clearTimeout(t as ReturnType<typeof setTimeout>) });
  s.mark(); s.mark(); await vi.advanceTimersByTimeAsync(0);
  expect(run).toHaveBeenCalledTimes(1);
  s.mark(); await vi.advanceTimersByTimeAsync(500); finish(); await Promise.resolve();
  await vi.advanceTimersByTimeAsync(499); expect(run).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1); expect(run).toHaveBeenCalledTimes(2);
  finish(); s.clear();
});
it('waits for full recovery or budget eligibility without opening a round and clears old intent', async () => {
  vi.useFakeTimers(); vi.setSystemTime(0);
  let ready: number | null = null;
  const run = vi.fn(async () => {});
  const s = createDirtyScheduler({ now: () => Date.now(), readyAt: () => ready, run,
    setTimer: (f, ms) => setTimeout(f, ms), clearTimer: t => clearTimeout(t as ReturnType<typeof setTimeout>) });
  s.mark(); await vi.advanceTimersByTimeAsync(10000); expect(run).not.toHaveBeenCalled();
  ready = 12000; s.wake(); await vi.advanceTimersByTimeAsync(1999); expect(run).not.toHaveBeenCalled();
  s.clear(); await vi.advanceTimersByTimeAsync(1); expect(run).not.toHaveBeenCalled();
  s.mark(); await vi.advanceTimersByTimeAsync(0); expect(run).toHaveBeenCalledTimes(1);
});
