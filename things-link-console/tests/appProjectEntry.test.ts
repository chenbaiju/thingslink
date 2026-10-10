import { afterEach, describe, expect, it, vi } from 'vitest'
import { appProjectEntry, defaultAppProjectAddress } from '@/utils/app-project-entry'

const project = { projectKey: 'project-001', name: '测试项目' }
afterEach(() => vi.unstubAllEnvs())

describe('ThingsX 项目入口合同', () => {
  it('仅本机开发页面预填，其他环境不推断平台地址', () => {
    vi.stubEnv('DEV', true)
    for (const host of ['localhost', '127.0.0.1', '[::1]']) {
      expect(defaultAppProjectAddress(host)).toBe('http://127.0.0.1:8080')
    }
    expect(defaultAppProjectAddress('console.example.test')).toBe('')
    expect(defaultAppProjectAddress('192.168.1.2')).toBe('')
    vi.stubEnv('DEV', false)
    expect(defaultAppProjectAddress('localhost')).toBe('')
  })
  it('开发模式接受固定本机HTTP，生产模式拒绝同一载荷', () => {
    vi.stubEnv('DEV', true)
    expect(JSON.parse(appProjectEntry(project, 'http://127.0.0.1:8080/').payload).baseUrl).toBe(
      'http://127.0.0.1:8080'
    )
    for (const address of [
      'http://localhost:8080',
      'http://10.0.2.2:8080',
      'http://127.0.0.1:8081',
      'http://127.0.0.1:8080?',
      'http://127.0.0.1:8080/api',
      'http://u:p@127.0.0.1:8080',
      'http://127.0.0.1.example.com:8080',
      'http://2130706433:8080'
    ]) {
      expect(() => appProjectEntry(project, address)).toThrow()
    }
    vi.stubEnv('DEV', false)
    expect(() => appProjectEntry(project, 'http://127.0.0.1:8080')).toThrow()
    expect(appProjectEntry(project, 'https://example.test').qrAvailable).toBe(true)
  })
  it('生成无凭据的版本化源站配置', () => {
    const result = appProjectEntry(project, ' https://EXAMPLE.test:443/ ')
    expect(JSON.parse(result.payload)).toEqual({
      version: 1,
      baseUrl: 'https://example.test',
      projectKey: 'project-001',
      label: '测试项目'
    })
    expect(result.qrAvailable).toBe(true)
  })
  it.each([
    'http://example.test',
    'https://user:pass@example.test',
    'https://example.test/api',
    'https://example.test?',
    'https://example.test#',
    'https://example.test/?token=secret',
    'https://example.test:0',
    'https://example.test\\evil',
    '//example.test'
  ])('拒绝无效或含敏感信息的地址 %s', (address) => {
    expect(() => appProjectEntry(project, address)).toThrow()
  })
  it('拒绝缺失标识，超长名称省略而不改变项目身份', () => {
    expect(() => appProjectEntry({}, 'https://example.test')).toThrow()
    const result = appProjectEntry({ ...project, name: '名'.repeat(81) }, 'https://example.test')
    expect(result.omitLabel).toBe(true)
    expect(JSON.parse(result.payload)).toEqual({
      version: 1,
      baseUrl: 'https://example.test',
      projectKey: 'project-001'
    })
  })
  it('较长内容保留文本导入，不强行生成不可编码的二维码', () => {
    const result = appProjectEntry(project, `https://${'a'.repeat(1750)}.test`)
    expect(result.qrAvailable).toBe(false)
    expect(JSON.parse(result.payload).projectKey).toBe('project-001')
  })
})
