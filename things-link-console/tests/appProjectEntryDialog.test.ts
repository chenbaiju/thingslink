import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput } from 'element-plus'
import AppProjectEntryDialog from '@/views/project/list/AppProjectEntryDialog.vue'

const components = { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput }
afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  document.body.innerHTML = ''
})
describe('App 接入弹窗', () => {
  it('本机开发打开即显示真实二维码，复制和切换保持目标项目一致', async () => {
    vi.stubEnv('DEV', true)
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const wrapper = mount(AppProjectEntryDialog, {
      props: { project: { id: 'a', projectKey: 'key-a', name: '项目A' } },
      global: { components },
      attachTo: document.body
    })
    await flushPromises()
    expect(document.querySelector('input')!.value).toBe('http://127.0.0.1:8080')
    expect(document.querySelector('[aria-label="项目接入二维码"] svg path')).not.toBeNull()
    const copy = Array.from(document.querySelectorAll('button')).find((e) =>
      e.textContent?.includes('复制接入信息')
    )!
    copy.click()
    await flushPromises()
    expect(JSON.parse(writeText.mock.calls[0][0])).toEqual({
      version: 1,
      baseUrl: 'http://127.0.0.1:8080',
      projectKey: 'key-a',
      label: '项目A'
    })
    await wrapper.setProps({ project: { id: 'b', projectKey: 'key-b', name: '项目B' } })
    await flushPromises()
    copy.click()
    await flushPromises()
    expect(JSON.parse(writeText.mock.calls[1][0]).projectKey).toBe('key-b')
    const input = document.querySelector('input')!
    for (const address of ['', 'http://example.test']) {
      input.value = address
      input.dispatchEvent(new Event('input', { bubbles: true }))
      await flushPromises()
      expect(document.querySelector('[aria-label="项目接入二维码"]')).toBeNull()
      expect(copy.disabled).toBe(true)
      expect(document.body.textContent).not.toContain('已复制接入信息')
    }
    wrapper.unmount()
  })
  it('明确地址才生成，可复制，项目变化清空旧地址', async () => {
    vi.stubEnv('DEV', false)
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const wrapper = mount(AppProjectEntryDialog, {
      props: { project: { id: 'a', projectKey: 'key-a', name: '项目A' } },
      global: { components },
      attachTo: document.body
    })
    await flushPromises()
    expect(document.body.textContent).not.toContain('"version":1')
    const input = document.querySelector('input')!
    input.value = 'https://example.test'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const copy = Array.from(document.querySelectorAll('button')).find((e) =>
      e.textContent?.includes('复制接入信息')
    )!
    copy.click()
    await flushPromises()
    expect(JSON.parse(writeText.mock.calls[0][0])).toEqual({
      version: 1,
      baseUrl: 'https://example.test',
      projectKey: 'key-a',
      label: '项目A'
    })
    expect(document.body.textContent).toContain('已复制接入信息')
    writeText.mockRejectedValueOnce(new Error('denied'))
    copy.click()
    await flushPromises()
    expect(document.body.textContent).toContain('自动复制不可用')
    await wrapper.setProps({ project: { id: 'b', projectKey: 'key-b' } })
    await flushPromises()
    expect(input.value).toBe('')
    expect(document.body.textContent).not.toContain('已复制接入信息')
    expect(copy.disabled).toBe(true)
    wrapper.unmount()
  })
})
