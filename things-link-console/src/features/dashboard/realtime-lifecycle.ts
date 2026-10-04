/** 保留跨设计器卸载的物理关闭义务；重新挂载不能绕过仍未关闭的旧连接。 */
interface OwnedRealtime {
  close(deadline?: number): Promise<void>
}
const connections = new Set<OwnedRealtime>()
export function ownDesignerRealtime(connection: OwnedRealtime): void {
  connections.add(connection)
}
/** 失败的关闭仍留在注册表，显式恢复必须重新确认它已经关闭。 */
export async function closeDesignerRealtime(deadline?: number): Promise<void> {
  const results = await Promise.allSettled(
    [...connections].map(async (connection) => {
      await connection.close(deadline)
      connections.delete(connection)
    })
  )
  const failure = results.find((result) => result.status === 'rejected')
  if (failure?.status === 'rejected') throw failure.reason
}
/** 取消、页面切换或身份变化不能退还两次自动dirty轮之间的最小间隔。 */
let lastDirtyStart = -Infinity
export function nextDesignerDirtyAt(): number {
  return Math.max(performance.now(), lastDirtyStart + 1000)
}
export function markDesignerDirtyStart(): void {
  lastDirtyStart = performance.now()
}
