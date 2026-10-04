import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AlarmInboxPage } from '@/api/alarmInbox'
import type { AlarmInboxIdentity } from '@/composables/useAlarmInbox'

const { fetchAlarmInbox, fetchAlarmInboxUnreadCount, fetchMarkAlarmInboxRead } = vi.hoisted(() => ({
  fetchAlarmInbox: vi.fn(),
  fetchAlarmInboxUnreadCount: vi.fn(),
  fetchMarkAlarmInboxRead: vi.fn()
}))

vi.mock('@/api/alarmInbox', () => ({
  fetchAlarmInbox,
  fetchAlarmInboxUnreadCount,
  fetchMarkAlarmInboxRead
}))

import { useAlarmInbox } from '@/composables/useAlarmInbox'

/** 手动控制完成顺序，不用计时猜测网络竞态。 */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (cause: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

function page(id: string, nextCursor?: string): AlarmInboxPage {
  return {
    items: [{ eventId: id, instanceId: `instance-${id}`, read: false }],
    hasMore: Boolean(nextCursor),
    nextCursor: nextCursor ?? null,
    windowStart: '2026-08-06T12:00:00Z',
    windowEnd: '2026-09-05T12:00:00Z'
  }
}

describe('ADR0093个人告警通知状态', () => {
  const disposers: Array<() => void> = []

  function create(initial: AlarmInboxIdentity | null = { accountId: 'a', projectId: 'p' }) {
    const identity = ref<AlarmInboxIdentity | null>(initial)
    const writable = ref(true)
    const inbox = useAlarmInbox(identity, writable)
    disposers.push(inbox.dispose)
    return { identity, writable, inbox }
  }

  beforeEach(() => {
    vi.resetAllMocks()
    fetchAlarmInbox.mockResolvedValue({ items: [], hasMore: false })
    fetchAlarmInboxUnreadCount.mockResolvedValue({ unreadCount: 0 })
    fetchMarkAlarmInboxRead.mockResolvedValue({ markedCount: 0 })
  })

  afterEach(() => {
    disposers.splice(0).forEach((dispose) => dispose())
  })

  it('初始不轮询且无身份不发请求，摘要保持未知', async () => {
    const { inbox } = create(null)
    await Promise.all([inbox.refresh(), inbox.refreshCount(), inbox.markRead(['e'])])
    expect(fetchAlarmInbox).not.toHaveBeenCalled()
    expect(fetchAlarmInboxUnreadCount).not.toHaveBeenCalled()
    expect(fetchMarkAlarmInboxRead).not.toHaveBeenCalled()
    expect(inbox.unreadCount.value).toBeNull()
  })

  it('首屏固定20条，列表刷新不重复触发计数', async () => {
    const pending = deferred<AlarmInboxPage>()
    fetchAlarmInbox.mockReturnValueOnce(pending.promise)
    const { inbox } = create()
    const operation = inbox.refresh()
    expect(inbox.loading.value).toBe(true)
    expect(fetchAlarmInbox).toHaveBeenCalledWith('p', undefined, 20, expect.any(AbortSignal))
    pending.resolve(page('e'))
    await operation
    expect(inbox.items.value[0].eventId).toBe('e')
    expect(inbox.loading.value).toBe(false)
    expect(fetchAlarmInboxUnreadCount).not.toHaveBeenCalled()
  })

  it('替换当前页并保留前页游标，后退再前进不会拼接或跳页', async () => {
    fetchAlarmInbox
      .mockResolvedValueOnce(page('one', 'cursor-two'))
      .mockResolvedValueOnce(page('two', 'cursor-three'))
      .mockResolvedValueOnce(page('three'))
      .mockResolvedValueOnce(page('two', 'cursor-three'))
      .mockResolvedValueOnce(page('one', 'cursor-two'))
    const { inbox } = create()
    await inbox.refresh()
    await inbox.nextPage()
    expect(inbox.pageNumber.value).toBe(2)
    expect(inbox.items.value.map((item) => item.eventId)).toEqual(['two'])
    expect(fetchAlarmInbox).toHaveBeenLastCalledWith('p', 'cursor-two', 20, expect.any(AbortSignal))
    await inbox.nextPage()
    expect(inbox.pageNumber.value).toBe(3)
    expect(inbox.hasMore.value).toBe(false)
    await inbox.previousPage()
    expect(fetchAlarmInbox).toHaveBeenLastCalledWith('p', 'cursor-two', 20, expect.any(AbortSignal))
    await inbox.previousPage()
    expect(fetchAlarmInbox).toHaveBeenLastCalledWith('p', undefined, 20, expect.any(AbortSignal))
    expect(inbox.canGoBack.value).toBe(false)
  })

  it('翻页失败保留已显示页且不会推进栈，首页刷新可恢复', async () => {
    fetchAlarmInbox
      .mockResolvedValueOnce(page('one', 'two'))
      .mockRejectedValueOnce(new Error('游标过期，请刷新'))
    const { inbox } = create()
    await inbox.refresh()
    await inbox.nextPage()
    expect(inbox.pageNumber.value).toBe(1)
    expect(inbox.items.value[0].eventId).toBe('one')
    expect(inbox.error.value).toBe('游标过期，请刷新')
    fetchAlarmInbox.mockResolvedValueOnce(page('fresh'))
    await inbox.refresh()
    expect(inbox.error.value).toBe('')
    expect(inbox.items.value[0].eventId).toBe('fresh')
  })

  it('列表重复刷新取消旧请求且迟到响应不能覆盖新页', async () => {
    const old = deferred<AlarmInboxPage>()
    const recent = deferred<AlarmInboxPage>()
    fetchAlarmInbox.mockReturnValueOnce(old.promise).mockReturnValueOnce(recent.promise)
    const { inbox } = create()
    const first = inbox.refresh()
    const oldSignal = fetchAlarmInbox.mock.calls[0][3] as AbortSignal
    const second = inbox.refresh()
    expect(oldSignal.aborted).toBe(true)
    recent.resolve(page('new'))
    await second
    old.resolve(page('old'))
    await first
    expect(inbox.items.value[0].eventId).toBe('new')
    expect(inbox.loading.value).toBe(false)
  })

  it('旧列表失败不能清除新请求loading或污染error', async () => {
    const old = deferred<AlarmInboxPage>()
    const recent = deferred<AlarmInboxPage>()
    fetchAlarmInbox.mockReturnValueOnce(old.promise).mockReturnValueOnce(recent.promise)
    const { inbox } = create()
    const first = inbox.refresh()
    const second = inbox.refresh()
    old.reject(new Error('旧失败'))
    await first
    expect(inbox.loading.value).toBe(true)
    expect(inbox.error.value).toBe('')
    recent.resolve(page('new'))
    await second
  })

  it('项目切换同步清空已显示状态并取消列表/计数，迟到结果都丢弃', async () => {
    const oldPage = deferred<AlarmInboxPage>()
    const oldCount = deferred<{ unreadCount: number }>()
    const { identity, inbox } = create()
    fetchAlarmInbox.mockResolvedValueOnce(page('shown', 'next'))
    await inbox.refresh()
    fetchAlarmInbox.mockReturnValueOnce(oldPage.promise)
    fetchAlarmInboxUnreadCount.mockReturnValueOnce(oldCount.promise)
    const first = inbox.refresh()
    const count = inbox.refreshCount()
    const pageSignal = fetchAlarmInbox.mock.calls[1][3] as AbortSignal
    const countSignal = fetchAlarmInboxUnreadCount.mock.calls[0][1] as AbortSignal
    identity.value = { accountId: 'a', projectId: 'other' }
    expect(inbox.items.value).toEqual([])
    expect(inbox.unreadCount.value).toBeNull()
    expect(inbox.hasMore.value).toBe(false)
    expect(pageSignal.aborted).toBe(true)
    expect(countSignal.aborted).toBe(true)
    oldPage.resolve(page('old'))
    oldCount.resolve({ unreadCount: 8 })
    await Promise.all([first, count])
    expect(inbox.items.value).toEqual([])
    expect(inbox.unreadCount.value).toBeNull()
  })

  it('同项目更换账号也隔离，A到B再到A不能复活旧A响应', async () => {
    const pending = deferred<AlarmInboxPage>()
    fetchAlarmInbox.mockReturnValueOnce(pending.promise)
    const { identity, inbox } = create()
    const operation = inbox.refresh()
    identity.value = { accountId: 'b', projectId: 'p' }
    identity.value = { accountId: 'a', projectId: 'p' }
    pending.resolve(page('old-a'))
    await operation
    expect(inbox.items.value).toEqual([])
  })

  it('摘要失败变未知而非零，下一次成功能恢复包括真实零', async () => {
    fetchAlarmInboxUnreadCount
      .mockResolvedValueOnce({ unreadCount: 100 })
      .mockRejectedValueOnce(new Error('网络中断'))
    const { inbox } = create()
    await inbox.refreshCount()
    expect(inbox.unreadCount.value).toBe(100)
    await inbox.refreshCount()
    expect(inbox.unreadCount.value).toBeNull()
    expect(inbox.countError.value).toBe('网络中断')
    await inbox.refreshCount()
    expect(inbox.unreadCount.value).toBe(0)
    expect(inbox.countError.value).toBe('')
  })

  it('缺字段或越界摘要也不伪报零', async () => {
    const { inbox } = create()
    for (const value of [{}, { unreadCount: -1 }, { unreadCount: 101 }, { unreadCount: 1.5 }]) {
      fetchAlarmInboxUnreadCount.mockResolvedValueOnce(value)
      await inbox.refreshCount()
      expect(inbox.unreadCount.value).toBeNull()
      expect(inbox.countError.value).not.toBe('')
    }
  })

  it('计数乱序取消旧请求且旧失败不覆盖成功', async () => {
    const pending = deferred<{ unreadCount: number }>()
    fetchAlarmInboxUnreadCount
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce({ unreadCount: 3 })
    const { inbox } = create()
    const first = inbox.refreshCount()
    const signal = fetchAlarmInboxUnreadCount.mock.calls[0][1] as AbortSignal
    await inbox.refreshCount()
    expect(signal.aborted).toBe(true)
    pending.reject(new Error('过期失败'))
    await first
    expect(inbox.unreadCount.value).toBe(3)
    expect(inbox.countError.value).toBe('')
  })

  it('仅提交当前页明确未读ID，去重且不提交已读项', async () => {
    fetchAlarmInbox.mockResolvedValueOnce({
      items: [
        { eventId: 'e', read: false },
        { eventId: 'seen', read: true }
      ]
    })
    const { inbox } = create()
    await inbox.refresh()
    await inbox.markRead(['e', 'e', 'seen'])
    expect(fetchMarkAlarmInboxRead).toHaveBeenCalledWith('p', ['e'], expect.any(AbortSignal))
    expect(fetchAlarmInbox).toHaveBeenCalledTimes(2)
    expect(fetchAlarmInboxUnreadCount).toHaveBeenCalledOnce()
  })

  it('零新增是幂等成功并返回首页，清除已读角标依赖重新读取事实', async () => {
    fetchAlarmInbox
      .mockResolvedValueOnce(page('one', 'two'))
      .mockResolvedValueOnce(page('two'))
      .mockResolvedValueOnce({ items: [{ eventId: 'one', read: true }] })
    const { inbox } = create()
    await inbox.refresh()
    await inbox.nextPage()
    await inbox.markCurrentPageRead()
    expect(fetchMarkAlarmInboxRead).toHaveBeenCalledWith('p', ['two'], expect.any(AbortSignal))
    expect(inbox.pageNumber.value).toBe(1)
    expect(inbox.canGoBack.value).toBe(false)
    expect(inbox.items.value[0].read).toBe(true)
    expect(inbox.unreadCount.value).toBe(0)
  })

  it('陌生ID、过量数组和只读身份均不会发送写请求', async () => {
    fetchAlarmInbox.mockResolvedValueOnce(page('shown'))
    const { inbox, writable } = create()
    await inbox.refresh()
    await inbox.markRead(['shown', 'foreign'])
    await inbox.markRead(Array(101).fill('shown'))
    await inbox.markRead([])
    writable.value = false
    await inbox.markRead(['shown'])
    expect(fetchMarkAlarmInboxRead).not.toHaveBeenCalled()
    expect(inbox.error.value).toContain('只读')
  })

  it('标记失败保留未读事实和可重试状态，不伪造成功', async () => {
    fetchAlarmInbox.mockResolvedValueOnce(page('e'))
    fetchMarkAlarmInboxRead.mockRejectedValueOnce(new Error('项目只读'))
    const { inbox } = create()
    await inbox.refresh()
    await inbox.markRead(['e'])
    expect(inbox.items.value[0].read).toBe(false)
    expect(inbox.error.value).toBe('项目只读')
    expect(inbox.marking.value).toBe(false)
    expect(fetchAlarmInboxUnreadCount).not.toHaveBeenCalled()
  })

  it('标记中重复点击不发第二请求，切换后旧成功也不得刷新新身份', async () => {
    const pending = deferred<{ markedCount: number }>()
    fetchAlarmInbox.mockResolvedValueOnce(page('e'))
    fetchMarkAlarmInboxRead.mockReturnValueOnce(pending.promise)
    const { identity, inbox } = create()
    await inbox.refresh()
    const first = inbox.markRead(['e'])
    expect(inbox.marking.value).toBe(true)
    await inbox.markRead(['e'])
    expect(fetchMarkAlarmInboxRead).toHaveBeenCalledOnce()
    const signal = fetchMarkAlarmInboxRead.mock.calls[0][2] as AbortSignal
    identity.value = { accountId: 'a', projectId: 'new' }
    expect(signal.aborted).toBe(true)
    pending.resolve({ markedCount: 1 })
    await first
    expect(fetchAlarmInbox).toHaveBeenCalledOnce()
    expect(fetchAlarmInboxUnreadCount).not.toHaveBeenCalled()
    expect(inbox.items.value).toEqual([])
    expect(inbox.marking.value).toBe(false)
  })

  it('登出取消写请求并丢弃迟到错误', async () => {
    const pending = deferred<{ markedCount: number }>()
    fetchAlarmInbox.mockResolvedValueOnce(page('e'))
    fetchMarkAlarmInboxRead.mockReturnValueOnce(pending.promise)
    const { identity, inbox } = create()
    await inbox.refresh()
    const operation = inbox.markRead(['e'])
    identity.value = null
    pending.reject(new Error('旧项目错误'))
    await operation
    expect(inbox.error.value).toBe('')
    expect(inbox.unreadCount.value).toBeNull()
  })

  it('dispose取消在途请求、清空状态且阻止后续刷新', async () => {
    const pending = deferred<AlarmInboxPage>()
    fetchAlarmInbox.mockReturnValueOnce(pending.promise)
    const { inbox } = create()
    const operation = inbox.refresh()
    const signal = fetchAlarmInbox.mock.calls[0][3] as AbortSignal
    inbox.dispose()
    expect(signal.aborted).toBe(true)
    pending.resolve(page('old'))
    await operation
    await inbox.refresh()
    expect(inbox.items.value).toEqual([])
    expect(fetchAlarmInbox).toHaveBeenCalledOnce()
  })
})
