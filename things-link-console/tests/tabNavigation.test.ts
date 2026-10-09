import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { ElTabs, ElTabPane, type TabsInstance, type TabsPaneContext } from 'element-plus'
import { revealAdjacentTab } from '@/utils/tab-navigation'

let wrapper: VueWrapper
let viewportWidth = 240
const labels = ['概览', '连接', '任务调度', '自动化', '场景', '接入配置']
const offset = (element: HTMLElement) =>
  Number(element.style.transform.match(/translateX\((-?[\d.]+)px\)/)?.[1] ?? 0) || 0

beforeEach(() => {
  viewportWidth = 240
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(private callback: (entries: Array<{ contentRect: DOMRect }>) => void) {}
      observe(element: HTMLElement) {
        queueMicrotask(() => this.callback([{ contentRect: element.getBoundingClientRect() }]))
      }
      unobserve() {}
      disconnect() {}
    }
  )
  vi.stubGlobal(
    'DOMMatrixReadOnly',
    class {
      m41: number
      constructor(transform = '') {
        this.m41 = Number(transform.match(/translateX\((-?[\d.]+)px\)/)?.[1] ?? 0)
      }
    }
  )
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockImplementation(function (
    this: HTMLElement
  ) {
    if (this.classList.contains('el-tabs__nav-scroll')) return viewportWidth
    if (this.classList.contains('el-tabs__nav')) return labels.length * 80
    return this.getAttribute('role') === 'tab' ? 80 : 0
  })
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (
    this: HTMLElement
  ) {
    return this.offsetWidth
  })
  vi.spyOn(HTMLElement.prototype, 'offsetLeft', 'get').mockImplementation(function (
    this: HTMLElement
  ) {
    return this.getAttribute('role') === 'tab' ? Number(this.id.slice(4)) * 80 : 0
  })
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
    this: HTMLElement
  ) {
    const left =
      this.offsetLeft + (this.getAttribute('role') === 'tab' ? offset(this.parentElement!) : 0)
    return {
      left,
      right: left + this.offsetWidth,
      width: this.offsetWidth,
      top: 0,
      bottom: 40,
      height: 40,
      x: left,
      y: 0,
      toJSON() {}
    }
  })
})
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
async function settle() {
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 40))
  await nextTick()
}
async function setup() {
  wrapper = mount(
    defineComponent({
      setup() {
        const active = ref('0')
        const tabs = ref<TabsInstance>()
        return () =>
          h(
            ElTabs,
            {
              ref: tabs,
              modelValue: active.value,
              'onUpdate:modelValue': (value) => {
                active.value = String(value)
              },
              onTabClick: (pane: TabsPaneContext) => revealAdjacentTab(tabs.value, pane)
            },
            () => labels.map((label, index) => h(ElTabPane, { name: String(index), label }))
          )
      }
    }),
    { attachTo: document.body }
  )
  await settle()
  // 标签注册结束后触发组件的布局更新。
  await wrapper.get('#tab-1').trigger('click')
  await settle()
}
const navOffset = () => offset(wrapper.get('.el-tabs__nav').element as HTMLElement)

it('点击右侧最后一个可见标签只露出下一项，向左点击边缘标签同样露出前一项', async () => {
  await setup()
  expect(navOffset()).toBe(0)
  await wrapper.get('#tab-2').trigger('click')
  await settle()
  expect(navOffset()).toBe(-80)
  expect(wrapper.get('#tab-2').attributes('aria-selected')).toBe('true')
  await wrapper.get('#tab-1').trigger('click')
  await settle()
  expect(navOffset()).toBe(0)
  expect(wrapper.get('#tab-1').attributes('aria-selected')).toBe('true')
})

it('点击中间标签不移动，末项不越界，原来的左右箭头仍可使用', async () => {
  await setup()
  await wrapper.get('#tab-1').trigger('click')
  await settle()
  expect(navOffset()).toBe(0)
  await wrapper.get('.el-tabs__nav-next').trigger('click')
  await settle()
  expect(navOffset()).toBe(-240)
  await wrapper.get('#tab-5').trigger('click')
  await settle()
  expect(navOffset()).toBe(-240)
  await wrapper.get('.el-tabs__nav-prev').trigger('click')
  await settle()
  expect(navOffset()).toBe(0)
})

it('部分可见的边缘标签先完整定位再露出下一项；没有溢出时不移动', async () => {
  viewportWidth = 210
  await setup()
  await wrapper.get('#tab-2').trigger('click')
  await settle()
  expect(navOffset()).toBe(-110)
  wrapper.unmount()
  viewportWidth = 600
  await setup()
  await wrapper.get('#tab-5').trigger('click')
  await settle()
  expect(navOffset()).toBe(0)
})
