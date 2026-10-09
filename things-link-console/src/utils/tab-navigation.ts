import { nextTick } from 'vue'
import type { TabsInstance, TabsPaneContext } from 'element-plus'

/** 点击可见范围边缘的标签时，沿原方向露出一个相邻标签。 */
export async function revealAdjacentTab(tabs: TabsInstance | undefined, pane: TabsPaneContext) {
  const nav = tabs?.tabNavRef?.tabListRef
  const viewport = nav?.parentElement
  if (!nav || !viewport || pane.props.disabled) return
  const items = Array.from(nav.children).filter(
    (item): item is HTMLElement =>
      item instanceof HTMLElement && item.getAttribute('role') === 'tab'
  )
  const index = items.findIndex((item) => item.id === `tab-${pane.paneName}`)
  if (index < 0) return
  const bounds = viewport.getBoundingClientRect()
  const before = items[index - 1]
  const after = items[index + 1]
  const tolerance = 1
  const target =
    after && after.getBoundingClientRect().right > bounds.right + tolerance
      ? after
      : before && before.getBoundingClientRect().left < bounds.left - tolerance
        ? before
        : undefined
  if (!target) return

  // 先让组件完成选中标签的定位，再通过已有滚轮入口移动，保留箭头与滚轮的边界状态。
  await nextTick()
  await tabs?.tabNavRef?.scrollToActiveTab()
  await nextTick()
  if (!nav.isConnected || tabs?.currentName !== pane.paneName) return
  const offset = -new DOMMatrixReadOnly(nav.style.transform || undefined).m41
  const left = target.offsetLeft - offset
  const right = left + target.offsetWidth
  const delta = left < 0 ? left : right > viewport.clientWidth ? right - viewport.clientWidth : 0
  if (Math.abs(delta) > tolerance) {
    nav.dispatchEvent(new WheelEvent('wheel', { deltaX: delta, bubbles: true, cancelable: true }))
  }
}
