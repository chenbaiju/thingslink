import { expect, it, vi } from 'vitest'
const get = vi.hoisted(() => vi.fn())
vi.mock('@/utils/http', () => ({ default: { get } }))
import { readDeviceEvidence } from '@/api/assistant-evidence'

it('GET 独立重复属性参数、取消信号及固定路径，不使用逗号/数组括号', async () => {
  const controller = new AbortController()
  await readDeviceEvidence('project', 'device', 'model', ['zero', 'flag'], controller.signal)
  expect(get).toHaveBeenCalledOnce()
  const call = get.mock.calls[0][0]
  expect(call.url).toBe('/api/v1/projects/project/assistant/devices/device/snapshot')
  expect(call.params.toString()).toBe(
    'expectedModelVersionId=model&propertyKey=zero&propertyKey=flag'
  )
  expect(call.signal).toBe(controller.signal)
  expect(call.showErrorMessage).toBe(false)
})
