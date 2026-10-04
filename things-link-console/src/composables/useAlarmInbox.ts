import { computed, getCurrentScope, onScopeDispose, ref, toValue, watch } from 'vue'
import type { MaybeRefOrGetter } from 'vue'
import {
  fetchAlarmInbox,
  fetchAlarmInboxUnreadCount,
  fetchMarkAlarmInboxRead,
  type AlarmInboxItem
} from '@/api/alarmInbox'

/** 账号与项目共同组成个人阅读身份；不接收或猜测owner tenant。 */
export interface AlarmInboxIdentity {
  accountId: string
  projectId: string
}

/** 单页固定20条，用户显式翻页，不循环抽干项目历史。 */
const PAGE_SIZE = 20

/** 保留服务端面向用户的错误；未知异常使用可重试文案，不展示对象或堆栈。 */
function messageOf(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message ? cause.message : fallback
}

/**
 * ADR0093：显式已读、当前页游标栈和身份隔离。
 * refresh只刷新列表，计数由refreshCount独立管理；标记成功同时刷新两者。
 * 这里不启动计时器，宿主负责仅在前台可见时发起刷新。
 */
export function useAlarmInbox(
  identity: MaybeRefOrGetter<AlarmInboxIdentity | null>,
  writable: MaybeRefOrGetter<boolean>
) {
  const items = ref<AlarmInboxItem[]>([])
  const unreadCount = ref<number | null>(null)
  const loading = ref(false)
  const marking = ref(false)
  const error = ref('')
  const countError = ref('')
  const hasMore = ref(false)
  const pageNumber = ref(1)
  const canGoBack = computed(() => pageNumber.value > 1)

  let cursors: Array<string | undefined> = [undefined]
  let nextCursor: string | undefined
  let generation = 0
  let pageRequest = 0
  let countRequest = 0
  let markRequest = 0
  let disposed = false
  let pageController: AbortController | undefined
  let countController: AbortController | undefined
  let markController: AbortController | undefined

  const currentIdentity = () => {
    const value = toValue(identity)
    return value?.accountId && value.projectId
      ? { accountId: value.accountId, projectId: value.projectId }
      : null
  }

  const reset = () => {
    generation++
    pageRequest++
    countRequest++
    markRequest++
    pageController?.abort()
    countController?.abort()
    markController?.abort()
    pageController = undefined
    countController = undefined
    markController = undefined
    items.value = []
    unreadCount.value = null
    loading.value = false
    marking.value = false
    error.value = ''
    countError.value = ''
    hasMore.value = false
    pageNumber.value = 1
    cursors = [undefined]
    nextCursor = undefined
  }

  // 同步清空使同一tick里的新身份也看不到旧数据；代次同时防止A→B→A后的旧A请求复活。
  const stopIdentity = watch(() => JSON.stringify(currentIdentity()), reset, {
    immediate: true,
    flush: 'sync'
  })

  const isCurrent = (startedGeneration: number) => !disposed && generation === startedGeneration

  const loadPage = async (target: number, cursor: string | undefined) => {
    const who = currentIdentity()
    if (disposed || !who) return
    const startedGeneration = generation
    const requestId = ++pageRequest
    pageController?.abort()
    const controller = new AbortController()
    pageController = controller
    loading.value = true
    error.value = ''
    try {
      const result = await fetchAlarmInbox(who.projectId, cursor, PAGE_SIZE, controller.signal)
      if (!isCurrent(startedGeneration) || requestId !== pageRequest) return
      items.value = result.items ?? []
      nextCursor = result.nextCursor ?? undefined
      hasMore.value = Boolean(result.hasMore && result.nextCursor)
      pageNumber.value = target + 1
      cursors = [...cursors.slice(0, target), cursor]
    } catch (cause) {
      if (isCurrent(startedGeneration) && requestId === pageRequest) {
        error.value = messageOf(cause, '告警通知加载失败，请重试')
      }
    } finally {
      if (isCurrent(startedGeneration) && requestId === pageRequest) {
        loading.value = false
        pageController = undefined
      }
    }
  }

  const refresh = () => loadPage(0, undefined)

  const refreshCount = async () => {
    const who = currentIdentity()
    if (disposed || !who) return
    const startedGeneration = generation
    const requestId = ++countRequest
    countController?.abort()
    const controller = new AbortController()
    countController = controller
    countError.value = ''
    try {
      const result = await fetchAlarmInboxUnreadCount(who.projectId, controller.signal)
      if (!isCurrent(startedGeneration) || requestId !== countRequest) return
      const value = result.unreadCount
      if (typeof value !== 'number' || !Number.isInteger(value) || value < 0 || value > 100) {
        throw new Error('未读数量暂不可用，请重试')
      }
      unreadCount.value = value
    } catch (cause) {
      if (isCurrent(startedGeneration) && requestId === countRequest) {
        unreadCount.value = null
        countError.value = messageOf(cause, '未读数量加载失败，请重试')
      }
    } finally {
      if (isCurrent(startedGeneration) && requestId === countRequest) countController = undefined
    }
  }

  const nextPage = () => {
    if (loading.value || marking.value || !hasMore.value || !nextCursor) return Promise.resolve()
    return loadPage(pageNumber.value, nextCursor)
  }

  const previousPage = () => {
    if (loading.value || marking.value || !canGoBack.value) return Promise.resolve()
    const target = pageNumber.value - 2
    return loadPage(target, cursors[target])
  }

  const markRead = async (eventIds: string[]) => {
    const who = currentIdentity()
    if (disposed || !who || marking.value || loading.value) return
    if (!toValue(writable)) {
      error.value = '当前项目只读，不能标记已读'
      return
    }
    if (!Array.isArray(eventIds) || eventIds.length < 1 || eventIds.length > 100) {
      error.value = '请选择当前页的告警通知'
      return
    }
    const displayed = new Map(
      items.value.filter((item) => item.eventId).map((item) => [item.eventId, item])
    )
    if (eventIds.some((id) => typeof id !== 'string' || !displayed.has(id))) {
      error.value = '只能标记当前页已展示的告警通知，请刷新后重试'
      return
    }
    const ids = [...new Set(eventIds)].filter((id) => !displayed.get(id)?.read)
    if (!ids.length) return
    const startedGeneration = generation
    const requestId = ++markRequest
    const controller = new AbortController()
    markController = controller
    marking.value = true
    error.value = ''
    try {
      await fetchMarkAlarmInboxRead(who.projectId, ids, controller.signal)
      if (!isCurrent(startedGeneration) || requestId !== markRequest) return
      // markedCount为0也是合法幂等成功；旧身份的成功不能触发新身份的两次刷新。
      await Promise.all([refresh(), refreshCount()])
    } catch (cause) {
      if (isCurrent(startedGeneration) && requestId === markRequest) {
        error.value = messageOf(cause, '标记已读失败，请重试')
      }
    } finally {
      if (isCurrent(startedGeneration) && requestId === markRequest) {
        marking.value = false
        markController = undefined
      }
    }
  }

  const markCurrentPageRead = () => {
    const ids = items.value
      .filter((item) => !item.read && item.eventId)
      .map((item) => item.eventId as string)
    return ids.length ? markRead(ids) : Promise.resolve()
  }

  const dispose = () => {
    if (disposed) return
    disposed = true
    stopIdentity()
    reset()
  }
  if (getCurrentScope()) onScopeDispose(dispose)

  return {
    items,
    unreadCount,
    loading,
    marking,
    error,
    countError,
    hasMore,
    canGoBack,
    pageNumber,
    refresh,
    refreshCount,
    nextPage,
    previousPage,
    markRead,
    markCurrentPageRead,
    dispose
  }
}
