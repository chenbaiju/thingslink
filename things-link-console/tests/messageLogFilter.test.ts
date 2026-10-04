import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import MessageLogFilterForm from '@/views/device/messages/MessageLogFilterForm.vue'

/** D-056：V1 消息日志筛选不再提供 HTTP/CoAP/TCP 协议选项（运行时协议只有 MQTT）。 */
describe('消息日志筛选（D-056）', () => {
  it('不再出现 HTTP/CoAP/TCP 协议筛选', async () => {
    const wrapper = mount(MessageLogFilterForm, {
      props: {
        devices: [],
        modelValue: {
          deviceId: '',
          direction: '',
          messageType: '',
          timeRange: [new Date('2026-08-20T00:00:00Z'), new Date('2026-08-21T00:00:00Z')],
          traceId: ''
        }
      },
      global: {
        plugins: [
          createI18n({
            legacy: false,
            locale: 'zh',
            messages: {
              zh: {
                table: {
                  searchBar: { reset: '重置', search: '搜索', expand: '展开', collapse: '收起' }
                }
              }
            }
          })
        ],
        // 让 El* 桩渲染默认插槽，标签（方向/Trace ID 等）才会出现在渲染结果里
        renderStubDefaultSlot: true,
        stubs: {
          ElForm: true,
          ElFormItem: { template: '<div><slot name="label" /><slot /></div>' },
          ElSelect: true,
          ElOption: true,
          ElDatePicker: true,
          ElInput: true,
          ElButton: true
        }
      }
    })
    await wrapper.get('.filter-toggle').trigger('click')
    const html = wrapper.html()
    expect(html).not.toContain('HTTP')
    expect(html).not.toContain('CoAP')
    expect(html).not.toContain('TCP')
    expect(html).not.toContain('协议')
    expect(html).toContain('方向')
    expect(html).toContain('Trace ID')
  })
})
