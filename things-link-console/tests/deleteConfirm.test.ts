import { describe, expect, it } from 'vitest'
import zh from '@/locales/langs/zh.json'

/**
 * D-044/011-A：删除确认说明有条件恢复、服务端期限与旧能力失效；
 * 业务数据不立即物理清除，不能写成永久物理删除。
 */
describe('危险操作确认文案（D-044）', () => {
  it('删除项目说明有条件恢复、能力失效与不立即物理清除', () => {
    const copy = zh.project.deleteConfirm as string
    expect(copy).toContain('项目回收站')
    expect(copy).toContain('服务端恢复期限')
    expect(copy).toContain('符合资格时可恢复')
    expect(copy).toContain('恢复不会使旧凭据或分享重新生效')
    expect(copy).toContain('业务数据不会立即物理清除')
    expect(copy).toContain('不会删除任何账号')
  })

  it('删除项目不得宣称永久物理删除', () => {
    expect(zh.project.deleteConfirm).not.toContain('永久删除')
    expect(zh.project.deleteConfirm).not.toContain('彻底删除')
  })
})
