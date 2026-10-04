import { describe, expect, it, vi } from 'vitest'
import {
  createApplicationEditor,
  emptyContent,
  parseContent,
  revision,
  title
} from '@/features/application/editor-model'
const id = '00000000-0000-0000-0000-000000000001'
const other = '00000000-0000-0000-0000-000000000002'
const version = '00000000-0000-0000-0000-000000000003'
function fixture() {
  let context = 'owner',
    writable = true
  const content = emptyContent('厂区应用')
  const draft = vi.fn().mockResolvedValue({ applicationId: id, revision: '0', content })
  const create = vi.fn().mockResolvedValue({ id })
  const save = vi.fn().mockImplementation(async (_, rev, value) => ({
    applicationId: id,
    revision: (BigInt(rev) + 1n).toString(),
    content: value
  }))
  const editor = createApplicationEditor({
    context: () => context,
    readable: () => true,
    writable: () => writable,
    draft,
    create,
    save,
    key: () => crypto.randomUUID(),
    changed: vi.fn()
  })
  return {
    editor,
    draft,
    create,
    save,
    content,
    identity: () => {
      context = 'viewer'
      writable = false
      editor.reset()
    },
    readonly: () => {
      writable = false
    }
  }
}
describe('应用草稿封闭合同', () => {
  it('保留精确版本、有序引用、指定入口及Long字符串', () => {
    const value = emptyContent('测试')
    value.dashboardRefs = [
      { dashboardId: other, dashboardVersionId: version, title: '二' },
      { dashboardId: id, dashboardVersionId: version, title: '一' }
    ]
    value.entryDashboardId = id
    expect(parseContent(value)).toEqual(value)
    expect(revision('9223372036854775807')).toBe(true)
    expect(revision('9223372036854775808')).toBe(false)
    expect(revision('01')).toBe(false)
  })
  it('拒绝未知字段、重复/超量引用、不存在入口和错误宿主范围', () => {
    expect(() => parseContent({ ...emptyContent('应用'), extra: 'secret' })).toThrow()
    for (const host of [
      { minInclusive: '2.0.0', maxExclusive: '1.0.0' },
      { minInclusive: '01.0.0', maxExclusive: '2.0.0' },
      { minInclusive: '1.0.0', maxExclusive: '65536.0.0' }
    ])
      expect(() => parseContent({ ...emptyContent('应用'), hostCompatibility: host })).toThrow()
    const ref = { dashboardId: id, dashboardVersionId: version, title: '页' }
    expect(() =>
      parseContent({ ...emptyContent('应用'), dashboardRefs: [ref, ref], entryDashboardId: id })
    ).toThrow()
    expect(() =>
      parseContent({ ...emptyContent('应用'), dashboardRefs: [ref], entryDashboardId: other })
    ).toThrow()
    expect(() =>
      parseContent({
        ...emptyContent('应用'),
        dashboardRefs: Array(6).fill(ref),
        entryDashboardId: id
      })
    ).toThrow()
    expect(title('\ud800')).toBe(false)
    expect(title('😀'.repeat(80))).toBe(true)
    expect(title('😀'.repeat(81))).toBe(false)
  })
})
describe('应用编辑异步与恢复边界', () => {
  it('创建、修改、保存后重开；revision不经Number', async () => {
    const f = fixture()
    await f.editor.create('管理名')
    f.draft.mockResolvedValue({
      applicationId: id,
      revision: '9007199254740993',
      content: f.content
    })
    await f.editor.open(id)
    f.editor.change((c) => {
      c.displayName = '新公开名'
    })
    await f.editor.save()
    expect(f.save.mock.calls[0][1]).toBe('9007199254740993')
    expect(f.editor.snapshot()).toMatchObject({
      revision: '9007199254740994',
      dirty: false,
      blocked: false
    })
  })
  it('未知创建只以原正文/同key显式重试；新名称不能改写在途意图', async () => {
    const f = fixture()
    f.create.mockRejectedValueOnce(Error('timeout'))
    await f.editor.create('原应用')
    expect(f.editor.snapshot().creatingUnknown).toBe(true)
    await f.editor.create('不能另建')
    expect(f.create.mock.calls[1]).toEqual(f.create.mock.calls[0])
    expect(f.editor.snapshot().id).toBe(id)
  })
  it('创建成功后读取失败只恢复已知ID，不自动重建', async () => {
    const f = fixture()
    f.draft.mockRejectedValueOnce(Error('offline'))
    await f.editor.create('应用')
    expect(f.editor.snapshot()).toMatchObject({ id, blocked: true, creatingUnknown: false })
    await f.editor.open(id)
    expect(f.create).toHaveBeenCalledTimes(1)
    expect(f.editor.snapshot().content).toEqual(f.content)
  })
  it('冲突/未知保存保留本地并锁写；只有显式远端重载解除', async () => {
    for (const code of [60032, 10014, undefined]) {
      const f = fixture()
      await f.editor.open(id)
      f.editor.change((c) => {
        c.displayName = '本地保留'
      })
      f.save.mockRejectedValueOnce({ code })
      await f.editor.save()
      await f.editor.save()
      expect(f.editor.snapshot()).toMatchObject({
        dirty: true,
        blocked: true,
        content: { displayName: '本地保留' }
      })
      expect(f.save).toHaveBeenCalledTimes(1)
      await f.editor.open(id)
      expect(f.editor.snapshot()).toMatchObject({
        dirty: false,
        blocked: false,
        content: { displayName: '厂区应用' }
      })
    }
  })
  it('单在途保护且旧身份迟到成功不能复活草稿', async () => {
    const f = fixture()
    await f.editor.open(id)
    f.editor.change((c) => {
      c.displayName = '新名字'
    })
    let resolve!: (v: unknown) => void
    f.save.mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r
        })
    )
    const writing = f.editor.save()
    await f.editor.save()
    expect(f.save).toHaveBeenCalledTimes(1)
    f.identity()
    resolve({ applicationId: id, revision: '1', content: f.content })
    await writing
    expect(f.editor.snapshot()).toMatchObject({ id: null, content: null, busy: false })
  })
  it('只读不创建/修改/保存；错误资源响应不进入编辑器', async () => {
    const f = fixture()
    await f.editor.open(id)
    f.readonly()
    f.editor.change((c) => {
      c.displayName = '不能修改'
    })
    await f.editor.save()
    await f.editor.create('禁止')
    expect(f.save).not.toHaveBeenCalled()
    expect(f.create).not.toHaveBeenCalled()
    expect(f.editor.snapshot().content?.displayName).toBe('厂区应用')
    f.draft.mockResolvedValue({ applicationId: other, revision: '1', content: f.content })
    await f.editor.open(id)
    expect(f.editor.snapshot().blocked).toBe(true)
  })
  it('非法本地输入不发送HTTP，失败重载不丢本地', async () => {
    const f = fixture()
    await f.editor.open(id)
    f.editor.change((c) => {
      c.displayName = ''
    })
    await f.editor.save()
    expect(f.save).not.toHaveBeenCalled()
    f.draft.mockRejectedValueOnce(Error('timeout'))
    await f.editor.open(id)
    expect(f.editor.snapshot().content?.displayName).toBe('')
    expect(f.editor.snapshot().blocked).toBe(true)
  })
  it.each([403, 30001, 50001, 50017, 60030, 60031, 20010])(
    '失权或资源失效 %s 清除私有内容',
    async (code) => {
      const f = fixture()
      await f.editor.open(id)
      f.editor.change((c) => {
        c.displayName = '本地'
      })
      f.save.mockRejectedValueOnce({ code })
      await f.editor.save()
      expect(f.editor.snapshot().content).toBeNull()
    }
  )
})
