vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ accessToken: 'test-token' }) }))
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { emptyDashboard } from '../src/features/dashboard/designer-model'
const mocks = vi.hoisted(() => ({ load: vi.fn() }))
vi.mock('../src/features/dashboard/device-preview', () => ({
  loadDesignerDevicePreview: mocks.load
}))
vi.mock('../src/api/dashboard-binding', () => ({
  createDesignerReadScope: () => ({ close: vi.fn() }),
  fetchPreviewSnapshots: vi.fn(),
  fetchPreviewCurrent: vi.fn()
}))
import DesignerDevicePreview from '../src/views/dashboard/designer/components/DesignerDevicePreview.vue'
beforeEach(() => {
  mocks.load.mockReset().mockResolvedValue([])
})
it('请求在途换项目丢弃迟到值，finally释放后允许新项目刷新', async () => {
  let resolve!: (rows: unknown[]) => void
  mocks.load
    .mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        })
    )
    .mockResolvedValueOnce([{ componentId: 'new', title: '新项目', text: '在线' }])
  const wrapper = mount(DesignerDevicePreview, {
    props: { schema: emptyDashboard(), pageId: 'main', projectId: 'first', available: true },
    global: {
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElAlert: true
      }
    }
  })
  await wrapper.find('button').trigger('click')
  expect(wrapper.find('button').attributes('disabled')).toBeDefined()
  await wrapper.setProps({ projectId: 'second' })
  resolve([{ componentId: 'old', title: '旧项目机密', text: '123' }])
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧项目机密')
  expect(wrapper.find('button').attributes('disabled')).toBeUndefined()
  expect(mocks.load).toHaveBeenCalledTimes(2)
  expect(wrapper.text()).toContain('新项目')
  await wrapper.setProps({ available: false })
  expect(wrapper.text()).not.toContain('新项目')
  expect(wrapper.find('button').attributes('disabled')).toBeDefined()
  mocks.load.mockResolvedValueOnce([
    { componentId: 'restored', title: '重新可用', text: '新授权值' }
  ])
  await wrapper.setProps({ available: true })
  await flushPromises()
  expect(wrapper.text()).toContain('新授权值')
  expect(mocks.load).toHaveBeenCalledTimes(3)
  wrapper.unmount()
})
describe('快照失败', () => {
  it('异常不伪造数据，明确重试入口可恢复', async () => {
    mocks.load.mockRejectedValueOnce(new Error('403'))
    const wrapper = mount(DesignerDevicePreview, {
      props: { schema: emptyDashboard(), pageId: 'main', projectId: 'first', available: true },
      global: {
        stubs: {
          ElButton: {
            props: ['disabled'],
            template: '<button :disabled="disabled"><slot /></button>'
          },
          ElAlert: true
        }
      }
    })
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('el-alert-stub').attributes('title')).toContain('读取失败')
    expect(wrapper.find('button').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-testid=preview-rest-state]').attributes('data-state')).toBe(
      'RETRY_REQUIRED'
    )
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(mocks.load).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid=preview-rest-state]').attributes('data-state')).toBe(
      'RETRY_REQUIRED'
    )
    await wrapper.setProps({ available: false })
    await wrapper.setProps({ available: true })
    await flushPromises()
    expect(mocks.load).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid=preview-rest-state]').attributes('data-state')).toBe(
      'RETRY_REQUIRED'
    )
    mocks.load.mockResolvedValueOnce([{ componentId: 'recovered', title: '恢复', text: '权威值' }])
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('权威值')
    wrapper.unmount()
    Reflect.deleteProperty(document, 'hidden')
  })
})

describe('复合快照安全呈现', () => {
  it('完整树保持词法数字品牌；纯文本显示HTML且本地翻页不重新读取', async () => {
    const { parseCompositeValue, parseDashboardRuntimeResponse } = await import(
      '@things-link/client-contracts/dashboard/v1'
    )
    const raw = parseDashboardRuntimeResponse(
      new TextEncoder().encode('{"large":9007199254740993,"text":"<img src=x onerror=alert(1)>"}')
    )
    const object = parseCompositeValue(raw, 'OBJECT')
    const list = parseCompositeValue(
      parseDashboardRuntimeResponse(new TextEncoder().encode('{"value":[9007199254740993,2,3]}'))
        .value,
      'LIST'
    )
    mocks.load.mockResolvedValueOnce([
      {
        componentId: 'json',
        title: '对象',
        text: '完整JSON快照',
        composite: { value: object, mode: 'JSON', initialExpandDepth: 1, rowLimit: 20 }
      },
      {
        componentId: 'list',
        title: '列表',
        text: '完整列表快照',
        composite: { value: list, mode: 'LIST', initialExpandDepth: 1, rowLimit: 2 }
      },
      {
        componentId: 'gauge',
        title: '仪表',
        text: '12.50',
        gauge: { minimum: '0', maximum: '10', percent: 100, outOfRange: true }
      }
    ])
    const wrapper = mount(DesignerDevicePreview, {
      props: { schema: emptyDashboard(), pageId: 'main', projectId: 'project', available: true },
      global: {
        stubs: {
          ElButton: {
            props: ['disabled'],
            template: '<button :disabled="disabled"><slot /></button>'
          },
          ElAlert: true
        }
      }
    })
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('9007199254740993')
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('超出量程')
    expect(wrapper.find('[data-testid="preview-list-total"]').text()).toContain('完整元素数：3')
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    const calls = mocks.load.mock.calls.length
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '下一页列表')!
      .trigger('click')
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
    expect(wrapper.find('tbody').text()).toContain('3')
    expect(mocks.load.mock.calls.length).toBe(calls)
    await wrapper.setProps({ pageId: 'other' })
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('9007199254740993')
    wrapper.unmount()
  })
})

it('隐藏清复合视图，恢复可见且无错误时自动完整读取', async () => {
  mocks.load.mockResolvedValueOnce([{ componentId: 'value', title: '可见页快照', text: '42' }])
  const wrapper = mount(DesignerDevicePreview, {
    props: { schema: emptyDashboard(), pageId: 'main', projectId: 'project', available: true },
    global: {
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElAlert: true
      }
    }
  })
  const descriptor = Object.getOwnPropertyDescriptor(document, 'hidden')
  try {
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('可见页快照')
    const calls = mocks.load.mock.calls.length
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('可见页快照')
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(wrapper.find('button').attributes('disabled')).toBeUndefined()
    expect(mocks.load.mock.calls.length).toBe(calls + 1)
    expect(wrapper.get('[data-testid=preview-rest-state]').attributes('data-state')).toBe(
      'REST_READY'
    )
  } finally {
    wrapper.unmount()
    if (descriptor) Object.defineProperty(document, 'hidden', descriptor)
    else Reflect.deleteProperty(document, 'hidden')
  }
})
