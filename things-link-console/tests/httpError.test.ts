import { describe, expect, it } from 'vitest'
import { buildErrorMessage, HttpError, preferApiError } from '@/utils/http/error'

/** A6-04/§11.5：HttpError 正式携带 traceId/details，未归类错误给出恢复建议。 */
describe('HttpError traceId/details 与恢复建议（A6-04）', () => {
  it('HttpError 携带 traceId 与 details，toLogData 完整输出', () => {
    const error = new HttpError('服务内部错误', 90000, { traceId: 't-123', details: ['boom'] })
    expect(error.traceId).toBe('t-123')
    expect(error.details).toEqual(['boom'])
    expect(error.outcomeUnknown).toBe(false)
    expect(error.toLogData()).toMatchObject({
      code: 90000,
      traceId: 't-123',
      details: ['boom'],
      outcomeUnknown: false
    })
  })

  it('未归类错误（9xxxx）带 traceId 时附加恢复建议', () => {
    const message = buildErrorMessage('服务内部错误', 90000, 't-1')
    expect(message).toContain('traceId：t-1')
    expect(message).toContain('请联系支持')
  })

  it('裸 5xx 带 traceId 时附加恢复建议', () => {
    expect(buildErrorMessage('服务不可用', 503, 't-2')).toContain('traceId：t-2')
  })

  it('已知业务码只显示映射消息，不附加 traceId 噪音', () => {
    expect(buildErrorMessage('设备标识符已存在', 30021, 't-3')).toBe('设备标识符已存在')
  })

  it('无 traceId 时不附加恢复建议', () => {
    expect(buildErrorMessage('服务内部错误', 90000)).toBe('服务内部错误')
  })

  it('结果未知标记进入日志，供写操作保留幂等键', () => {
    const error = new HttpError('网络连接异常', 400, { outcomeUnknown: true })
    expect(error.outcomeUnknown).toBe(true)
    expect(error.toLogData().outcomeUnknown).toBe(true)
  })
})

/** A6-04 全路径要求：刷新请求自身的失败必须保留其 traceId/details，而非丢失。 */
describe('刷新失败诊断字段提取（A6-04）', () => {
  const fallback = { message: '原请求 401', traceId: 'orig-t', details: ['orig'] }

  it('刷新响应是标准 ApiError 时优先用刷新自身的诊断字段', () => {
    expect(
      preferApiError(
        { response: { data: { code: 20020, message: '登录已失效', traceId: 'refresh-t' } } },
        fallback
      )
    ).toEqual({ message: '登录已失效', traceId: 'refresh-t', details: undefined })
  })

  it('刷新失败无响应体（网络错误）时回退到原 401 的 ApiError', () => {
    expect(preferApiError(new Error('network'), fallback)).toEqual(fallback)
  })

  it('刷新失败体不是标准 ApiError 时回退到原 401 的 ApiError', () => {
    expect(preferApiError({ response: { data: 'boom' } }, fallback)).toEqual(fallback)
  })

  it('无回退且无标准体时返回 undefined', () => {
    expect(preferApiError(new Error('network'))).toBeUndefined()
  })
})
