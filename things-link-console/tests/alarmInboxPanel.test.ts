import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ArtNotification from '@/components/core/layouts/art-notification/index.vue'

function state() {
  return {
    items: [
      {
        eventId: 'event-one',
        instanceId: 'instance-one',
        receivedAt: '2026-09-05T12:00:00Z',
        severity: 'MAJOR' as const,
        alarmType: '温度过高',
        read: false
      }
    ],
    unreadCount: 100 as number | null,
    loading: false,
    marking: false,
    error: '',
    countError: '',
    hasMore: true,
    canGoBack: false,
    pageNumber: 1,
    refresh: vi.fn(),
    refreshCount: vi.fn(),
    nextPage: vi.fn(),
    previousPage: vi.fn(),
    markRead: vi.fn(),
    markCurrentPageRead: vi.fn(),
    dispose: vi.fn()
  }
}

function panel(inbox = state(), available = true, writable = true) {
  // 组件只消费公开状态；请求/并发语义由alarmInbox.test单独验证。
  return mount(ArtNotification, {
    props: {
      value: true,
      available,
      writable,
      inbox,
      projectStatus: writable ? 'ACTIVE' : 'ARCHIVED'
    }
  })
}

function button(wrapper: ReturnType<typeof panel>, text: string) {
  return wrapper.findAll('button').find((entry) => entry.text() === text)!
}

describe('Console告警通知面板', () => {
  it('展示真实范围与99+，逐项标记只提交明确事件，不出现模板消息或全部已读', async () => {
    const inbox = state()
    const wrapper = panel(inbox)
    expect(wrapper.text()).toContain('最近30天')
    expect(wrapper.text()).toContain('99+')
    expect(wrapper.text()).toContain('不确认或清除告警')
    expect(wrapper.get('[data-testid="alarm-inbox-item"]').attributes('data-event-id')).toBe(
      'event-one'
    )
    expect(wrapper.text()).toContain('温度过高')
    expect(wrapper.text()).toContain('2026-09-05 20:00:00')
    expect(wrapper.text()).not.toContain('全部已读')
    expect(wrapper.text()).not.toContain('待办')
    await button(wrapper, '标记已读').trigger('click')
    expect(inbox.markRead).toHaveBeenCalledExactlyOnceWith(['event-one'])
    await button(wrapper, '标记当前页已读').trigger('click')
    expect(inbox.markCurrentPageRead).toHaveBeenCalledTimes(1)
  })

  it('错误显示未知与重试，不把加载失败作为空列表', async () => {
    const inbox = state()
    inbox.items = []
    inbox.unreadCount = null
    inbox.error = '告警通知列表已过期，请刷新'
    inbox.countError = '未读数暂不可用'
    const wrapper = panel(inbox)
    expect(wrapper.text()).toContain('未读：未知')
    expect(wrapper.text()).toContain(inbox.error)
    expect(wrapper.text()).not.toContain('最近30天暂无')
    await button(wrapper, '重试通知').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    await button(wrapper, '重试未读数').trigger('click')
    expect(inbox.refreshCount).toHaveBeenCalledTimes(1)
  })

  it('无认证项目时不展示旧列表及写入动作', () => {
    const wrapper = panel(state(), false)
    expect(wrapper.text()).toContain('请登录并选择项目')
    expect(wrapper.find('[data-testid="alarm-inbox-item"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('标记已读')
  })

  it('只读与进行中状态禁止重复标记，分页由真实边界决定', async () => {
    const inbox = state()
    const wrapper = panel(inbox, true, false)
    expect(wrapper.text()).toContain('项目已归档，通知只读')
    expect(button(wrapper, '标记已读').attributes('disabled')).toBeDefined()
    expect(button(wrapper, '标记当前页已读').attributes('disabled')).toBeDefined()
    expect(button(wrapper, '上一页').attributes('disabled')).toBeDefined()
    await button(wrapper, '下一页').trigger('click')
    expect(inbox.nextPage).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ inbox: { ...inbox, marking: true } })
    expect(button(wrapper, '下一页').attributes('disabled')).toBeDefined()
  })

  it('已读项保留展示且不再提供逐项已读动作，关闭可用键盘', async () => {
    const inbox = state()
    inbox.items[0].read = true
    const wrapper = panel(inbox)
    expect(wrapper.text()).toContain('已读')
    expect(button(wrapper, '标记已读')).toBeUndefined()
    expect(button(wrapper, '标记当前页已读').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="alarm-inbox-panel"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('update:value')).toEqual([[false]])
  })
})
