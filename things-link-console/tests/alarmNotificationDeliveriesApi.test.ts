import { expect, it, vi } from 'vitest'
vi.mock('@/utils/http', () => ({ default: { get: vi.fn() } }))
import request from '@/utils/http'
import { fetchAlarmNotificationDeliveries } from '@/api/alarm'
it('只读20项过滤游标，编码项目路径、传取消信号且不回显全局错误', () => {
  const controller = new AbortController()
  fetchAlarmNotificationDeliveries('project/x', 'instance', 'opaque', controller.signal)
  expect(request.get).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/project%2Fx/alarm-notification-deliveries',
    params: { instanceId: 'instance', cursor: 'opaque', limit: 20 },
    signal: controller.signal,
    showErrorMessage: false
  })
})
