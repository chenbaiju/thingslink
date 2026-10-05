import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Panel from '@/components/agent/AnalysisResultPanel.vue'
const call = {
  id: '019c1234-5678-7890-8123-456789abcdef',
  status: 'SUCCEEDED',
  createdAt: '2026-10-05T00:00:00Z',
  deadline: '2026-10-05T00:01:00Z',
  expiresAt: '2026-10-06T00:00:00Z',
  dispatchedAt: '2026-10-05T00:00:01Z',
  finishedAt: '2026-10-05T00:00:02Z'
}
function run() {
  return {
    call: { ...call },
    category: 'SUCCEEDED',
    result: {
      model: 'deepseek-flash',
      promptVersion: 'thingslink-agent-single-analysis-v1',
      summary: '<script>secret</script> **plain** [link](https://example.test)',
      findings: ['FACT', 'HYPOTHESIS', 'RECOMMENDATION'].map((kind) => ({
        kind,
        statement: '<img src=x onerror=secret> 原文',
        evidenceIds: ['e-device', 'e-alarm']
      })),
      limitations: ['<a href="javascript:secret">限制</a>'],
      usage: {
        promptTokens: 10,
        completionTokens: 0,
        totalTokens: 10,
        cacheHitTokens: 0,
        cacheMissTokens: 10
      }
    }
  }
}
it('纯文本展示类别、引用与零用量，不执行HTML/Markdown且不声称内容已验真', () => {
  const panel = mount(Panel, { props: { run: run() } })
  expect(panel.findAll('script,img,a,strong')).toHaveLength(0)
  for (const text of [
    '<script>secret</script>',
    '模型陈述的事实',
    '待核验的假设',
    '需人工判断的建议',
    'e-device、e-alarm',
    '输出 0',
    '缓存命中 0',
    '不证明结论正确',
    '不等于费用结算'
  ])
    expect(panel.text()).toContain(text)
  panel.unmount()
})
it.each(['REPLAY', 'UNQUALIFIED', 'TRANSPORT_UNKNOWN'])(
  '%s不能从成功元数据恢复正文或编造零用量',
  (category) => {
    const panel = mount(Panel, { props: { run: { call, category, result: null } } })
    expect(panel.text()).toContain('没有可展示正文')
    expect(panel.text()).not.toContain('Token 用量')
    expect(panel.text()).not.toContain('secret')
    panel.unmount()
  }
)
it('关闭无调用、空条目与未知正文分别显示', async () => {
  const value = run()
  value.result.findings = []
  value.result.limitations = []
  const panel = mount(Panel, { props: { run: value } })
  expect(panel.text()).toContain('未返回此类条目')
  expect(panel.text()).toContain('不代表不存在限制')
  await panel.setProps({ run: { call: null, category: 'UNAVAILABLE', result: null } })
  expect(panel.text()).not.toContain(call.id)
  expect(panel.text()).toContain('当前分析不可用')
  panel.unmount()
})
it('非法回执整条不展示，清空输入立即清除正文', async () => {
  const panel = mount(Panel, { props: { run: run() } })
  await panel.setProps({ run: { ...run(), category: 'REPLAY' } })
  expect(panel.text()).toContain('未通过校验')
  expect(panel.text()).not.toContain('secret')
  expect(panel.text()).not.toContain(call.id)
  await panel.setProps({ run: undefined })
  expect(panel.text()).toBe('')
  panel.unmount()
})
