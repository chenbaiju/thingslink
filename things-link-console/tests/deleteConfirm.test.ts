import { describe, expect, it } from 'vitest'
import zh from '@/locales/langs/zh.json'

/**
 * D-044：删除项目确认文案必须说明「不可自助撤销/恢复」与「业务数据不立即物理清除」，
 * 不得写成「永久物理删除」，也不能宣称 ADR 0040 的完整删除状态机已实现。
 */
describe('危险操作确认文案（D-044）', () => {
  it('删除项目说明不可自助撤销与不立即物理清除', () => {
    const copy = zh.project.deleteConfirm as string
    expect(copy).toContain('无法自助撤销或恢复')
    expect(copy).toContain('不会在此操作中立即物理清除')
    expect(copy).toContain('不会删除任何账号')
  })

  it('删除项目不得宣称永久物理删除', () => {
    expect(zh.project.deleteConfirm).not.toContain('永久删除')
    expect(zh.project.deleteConfirm).not.toContain('彻底删除')
  })
})
