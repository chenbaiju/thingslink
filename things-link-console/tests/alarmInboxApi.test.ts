import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/utils/http', () => ({ default: { get, post } }))

import {
  fetchAlarmInbox,
  fetchAlarmInboxUnreadCount,
  fetchMarkAlarmInboxRead
} from '@/api/alarmInbox'

describe('个人告警通知API合同', () => {
  beforeEach(() => vi.clearAllMocks())

  it('三个入口都传取消信号并禁用全局错误提示，标记只提交eventIds', () => {
    const controller = new AbortController()
    fetchAlarmInbox('project', 'opaque', 20, controller.signal)
    fetchAlarmInboxUnreadCount('project', controller.signal)
    fetchMarkAlarmInboxRead('project', ['event'], controller.signal)
    expect(get).toHaveBeenNthCalledWith(1, {
      url: '/api/v1/projects/project/alarm-notifications',
      params: { cursor: 'opaque', limit: 20 },
      signal: controller.signal,
      showErrorMessage: false
    })
    expect(get).toHaveBeenNthCalledWith(2, {
      url: '/api/v1/projects/project/alarm-notifications/unread-count',
      signal: controller.signal,
      showErrorMessage: false
    })
    expect(post).toHaveBeenCalledWith({
      url: '/api/v1/projects/project/alarm-notifications/read',
      params: { eventIds: ['event'] },
      signal: controller.signal,
      showErrorMessage: false,
      showSuccessMessage: false
    })
  })
})
