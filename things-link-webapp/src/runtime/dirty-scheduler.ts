/** 数据合同§5：脏提示只唤醒唯一顶层调度器，保留独立的完整校准优先级。 */
export function createDirtyScheduler(ports: {
  now(): number;
  setTimer(callback: () => void, delay: number): unknown;
  clearTimer(timer: unknown): void;
  /** 无法开始时返回null，等待在途结束显式wake，避免轮询或创建失败轮。 */
  readyAt(): number | null;
  run(): Promise<void>;
}) {
  let pending = false;
  let running = false;
  let timer: unknown;
  let lastStart = -Infinity;
  let generation = 0;
  const wake = (): void => {
    if (!pending || running || timer !== undefined) return;
    const ready = ports.readyAt();
    if (ready === null) return;
    timer = ports.setTimer(() => {
      timer = undefined;
      const next = ports.readyAt();
      if (next === null) return;
      if (ports.now() < Math.max(next, lastStart + 1000)) { wake(); return; }
      pending = false; running = true; lastStart = ports.now();
      const original = generation;
      void ports.run().finally(() => {
        running = false;
        if (original === generation) wake();
        else if (pending) wake();
      });
    }, Math.max(0, ready - ports.now(), lastStart + 1000 - ports.now()));
  };
  return {
    mark() { pending = true; wake(); },
    wake,
    clear() {
      generation++; pending = false;
      if (timer !== undefined) ports.clearTimer(timer);
      timer = undefined;
      // 已发请求的启动时间不能因换页退款，run仍由顶层取消和围栏收束。
    },
  };
}
