import { mount, flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ElementPlus from 'element-plus'
import QuotaUsageTable from '@/views/project/settings/QuotaUsageTable.vue'

/** 实际只读配额组件：零不是无限，自动化不提供租户提额控件。 */
describe('自动化额度只读展示', () => {
  it('零额度明确显示未开通且没有修改入口', async () => {
    const wrapper = mount(QuotaUsageTable, {
      global: { plugins: [ElementPlus] },
      props: {
        scope: {
          dailyMetrics: [
            {
              metric: 'AUTOMATION_EXECUTION',
              limit: 0,
              used: 0,
              remaining: 0,
              status: 'HARD_LIMIT'
            }
          ]
        }
      }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('自动化执行次数')
    expect(wrapper.text()).toContain('未开通')
    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
    wrapper.unmount()
  })

  it('已开通正常额度继续沿共享池水位展示', async () => {
    const wrapper = mount(QuotaUsageTable, {
      global: { plugins: [ElementPlus] },
      props: {
        scope: {
          dailyMetrics: [
            { metric: 'AUTOMATION_EXECUTION', limit: 10, used: 1, remaining: 9, status: 'NORMAL' }
          ]
        }
      }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('正常')
    expect(wrapper.text()).not.toContain('未开通')
    wrapper.unmount()
  })
})
