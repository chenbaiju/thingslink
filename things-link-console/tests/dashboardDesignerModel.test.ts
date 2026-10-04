import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createDashboardEditor,
  emptyDashboard,
  parseGaugeBounds,
  deviceVariableReferences,
  type HistoryComponentInput,
  type AlarmComponentInput,
  type TextComponentInput,
  type DeviceComponentInput,
  type DraftSnapshot
} from '../src/features/dashboard/designer-model'
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function fixture() {
  vi.useFakeTimers()
  let available = true
  const save = vi.fn(
    async (id: string, revision: string, content: unknown): Promise<DraftSnapshot> => ({
      dashboardId: id,
      revision: String(BigInt(revision) + 1n),
      content
    })
  )
  const editor = createDashboardEditor({
    save,
    canEdit: () => true,
    available: () => available,
    now: () => Date.now(),
    setTimer: (callback, delay) => setTimeout(callback, delay),
    clearTimer: (timer) => clearTimeout(timer as ReturnType<typeof setTimeout>),
    changed: () => undefined
  })
  editor.open({ dashboardId: 'first', revision: '0', content: emptyDashboard() })
  return {
    editor,
    save,
    offline: () => {
      available = false
    }
  }
}
afterEach(() => vi.useRealTimers())
describe('完整草稿编辑与串行保存', () => {
  it('初始0修订在1500ms空闲后保存并接受1回执', async () => {
    const { editor, save } = fixture()
    editor.add('TEXT')
    await vi.advanceTimersByTimeAsync(1499)
    expect(save).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(save.mock.calls[0]?.[1]).toBe('0')
    expect(editor.snapshot().revision).toBe('1')
    expect(editor.snapshot().dirty).toBe(false)
  })
  it('接受JSONB对象键重排，但拒绝相同修订的实值更改', async () => {
    const { editor, save } = fixture()
    function reverse(value: unknown): unknown {
      if (Array.isArray(value)) return value.map(reverse)
      if (value && typeof value === 'object')
        return Object.fromEntries(
          Object.entries(value)
            .reverse()
            .map(([key, item]) => [key, reverse(item)])
        )
      return value
    }
    save.mockImplementationOnce(async (dashboardId, revision, content) => ({
      dashboardId,
      revision: String(BigInt(revision) + 1n),
      content: reverse(content)
    }))
    editor.add('TEXT')
    await vi.advanceTimersByTimeAsync(1500)
    expect(editor.snapshot().error).toBe('')
    expect(editor.snapshot().dirty).toBe(false)
    save.mockImplementationOnce(async (dashboardId, revision, content) => {
      const changed = JSON.parse(JSON.stringify(content))
      changed.pages[0].components[0].props.content = '非发送内容'
      return { dashboardId, revision: String(BigInt(revision) + 1n), content: changed }
    })
    editor.updateSelected({ props: { content: '发送内容' } })
    await vi.advanceTimersByTimeAsync(1500)
    expect(editor.snapshot().dirty).toBe(true)
    expect(editor.snapshot().revision).toBe('1')
    expect(editor.snapshot().error).not.toBe('')
  })
  it('持续编辑最迟10秒送出，不因每秒编辑无限推迟', async () => {
    const { editor, save } = fixture()
    editor.add('TEXT')
    for (let i = 0; i < 9; i++) {
      await vi.advanceTimersByTimeAsync(1000)
      editor.updateSelected({ props: { content: `值${i}` } })
    }
    expect(save).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1000)
    expect(save).toHaveBeenCalledTimes(1)
  })
  it('在途旧快照只确认自身，新编辑串行使用新revision继续保存', async () => {
    const { editor, save } = fixture()
    const first = deferred<DraftSnapshot>()
    save.mockImplementationOnce(() => first.promise)
    editor.add('TEXT')
    await vi.advanceTimersByTimeAsync(1500)
    const sent = save.mock.calls[0]![2]
    editor.updateSelected({ props: { content: '新内容' } })
    await vi.advanceTimersByTimeAsync(1600)
    expect(save).toHaveBeenCalledTimes(1)
    first.resolve({ dashboardId: 'first', revision: '1', content: sent })
    await vi.advanceTimersByTimeAsync(0)
    expect(save).toHaveBeenCalledTimes(2)
    expect(save.mock.calls[1]?.[1]).toBe('1')
    expect(editor.snapshot().schema?.pages[0]?.components[0]?.props).toMatchObject({
      content: '新内容'
    })
  })
  it('切换文档后旧flight不能覆盖新稿，旧flight结束前保持单并发', async () => {
    const { editor, save } = fixture()
    const old = deferred<DraftSnapshot>()
    const next = deferred<DraftSnapshot>()
    save.mockImplementationOnce(() => old.promise).mockImplementationOnce(() => next.promise)
    editor.add('TEXT')
    await vi.advanceTimersByTimeAsync(1500)
    const oldContent = save.mock.calls[0]![2]
    editor.open({ dashboardId: 'second', revision: '0', content: emptyDashboard() })
    editor.add('IMAGE')
    await vi.advanceTimersByTimeAsync(1500)
    expect(save).toHaveBeenCalledTimes(1)
    old.resolve({ dashboardId: 'first', revision: '1', content: oldContent })
    await vi.advanceTimersByTimeAsync(0)
    expect(save).toHaveBeenCalledTimes(2)
    expect(editor.snapshot().dashboardId).toBe('second')
    expect(editor.snapshot().saving).toBe(true)
    next.resolve({ dashboardId: 'second', revision: '1', content: save.mock.calls[1]![2] })
    await vi.advanceTimersByTimeAsync(0)
    expect(editor.snapshot().saving).toBe(false)
  })
  it('409保留本地且不能显式盲覆盖，普通失败只能明确重试', async () => {
    const { editor, save } = fixture()
    save.mockRejectedValueOnce({ code: 60037 })
    editor.add('TEXT')
    await vi.advanceTimersByTimeAsync(1500)
    expect(editor.snapshot().conflict).toBe(true)
    expect(editor.snapshot().dirty).toBe(true)
    editor.retrySave()
    await vi.advanceTimersByTimeAsync(60000)
    expect(save).toHaveBeenCalledTimes(1)
    editor.open({ dashboardId: 'first', revision: '0', content: emptyDashboard() })
    save.mockRejectedValueOnce(new Error('offline'))
    editor.add('TEXT')
    await vi.advanceTimersByTimeAsync(1500)
    await vi.advanceTimersByTimeAsync(60000)
    expect(save).toHaveBeenCalledTimes(2)
    editor.retrySave()
    await vi.advanceTimersByTimeAsync(0)
    expect(save).toHaveBeenCalledTimes(3)
  })
  it('主题/两种布局可撤销，非法重叠完整拒绝且最多50条历史', () => {
    const { editor } = fixture()
    editor.add('TEXT')
    editor.setTheme('DARK')
    editor.setPresentation('FIXED_SCREEN')
    expect(editor.snapshot().schema?.presentation.mode).toBe('FIXED_SCREEN')
    editor.undo()
    expect(editor.snapshot().schema?.presentation.mode).toBe('RESPONSIVE_GRID')
    editor.redo()
    expect(editor.snapshot().schema?.presentation.theme).toBe('DARK')
    editor.add('IMAGE')
    const before = JSON.stringify(editor.snapshot().schema)
    editor.updateSelected({ layout: { x: 0, y: 0, w: 480, h: 160 } })
    expect(JSON.stringify(editor.snapshot().schema)).toBe(before)
    editor.select('component_1')
    for (let i = 0; i < 60; i++) editor.updateSelected({ props: { content: `更新${i}` } })
    let count = 0
    while (editor.snapshot().canUndo) {
      editor.undo()
      count++
    }
    expect(count).toBe(50)
  })
  it('大草稿历史按UTF8总字节8MiB先淘汰；双页切固定不能丢页', () => {
    const { editor } = fixture()
    const schema = {
      ...emptyDashboard(),
      pages: [
        {
          id: 'main',
          title: '首页',
          components: Array.from({ length: 40 }, (_, index) => ({
            id: `text_${index}`,
            kind: 'TEXT',
            componentVersion: '1.0.0',
            layout: { x: (index % 2) * 12, y: Math.floor(index / 2), w: 12, h: 1 },
            props: { content: '中'.repeat(4000) },
            bindings: {}
          }))
        }
      ]
    }
    editor.open({ dashboardId: 'large', revision: '0', content: schema })
    expect(editor.snapshot().schema).not.toBeNull()
    editor.select('text_0')
    const bytes = new TextEncoder().encode(JSON.stringify(editor.snapshot().schema)).length
    for (let index = 0; index < 50; index++)
      editor.updateSelected({
        props: { content: '中'.repeat(3998) + String(index).padStart(2, '0') }
      })
    let count = 0
    while (editor.snapshot().canUndo) {
      editor.undo()
      count++
    }
    expect(count).toBeLessThan(50)
    expect(count * (bytes - 10)).toBeLessThanOrEqual(8 * 1024 * 1024)
    editor.open({ dashboardId: 'pages', revision: '0', content: emptyDashboard() })
    editor.addPage('第二页')
    editor.setPresentation('FIXED_SCREEN')
    expect(editor.snapshot().schema?.pages).toHaveLength(2)
    expect(editor.snapshot().schema?.presentation.mode).toBe('RESPONSIVE_GRID')
    expect(editor.snapshot().error).not.toBe('')
  }, 20000)
  it('网络不可用禁止undo；重置丢弃schema和全部历史并围栏旧响应', async () => {
    const { editor, save, offline } = fixture()
    editor.add('TEXT')
    offline()
    editor.undo()
    expect(editor.snapshot().schema?.pages[0]?.components).toHaveLength(1)
    editor.reset()
    await vi.advanceTimersByTimeAsync(60000)
    expect(save).not.toHaveBeenCalled()
    expect(editor.snapshot().schema).toBeNull()
    expect(editor.snapshot().canUndo).toBe(false)
  })
  it('已交付文本枚举草稿可编辑，完整保留未绑定变量', async () => {
    const { editor, save } = fixture()
    const schema = {
      ...emptyDashboard(),
      variables: [
        { key: 'caption', type: 'TEXT_ENUM', title: '标签', options: [{ value: 'a', label: 'A' }] }
      ]
    }
    editor.open({ dashboardId: 'data', revision: '0', content: schema })
    expect(editor.snapshot().readonly).toBe(false)
    expect(editor.snapshot().schema?.variables).toHaveLength(1)
    editor.add('TEXT')
    await vi.advanceTimersByTimeAsync(2000)
    expect(save).toHaveBeenCalledTimes(1)
    expect(editor.snapshot().schema?.variables).toHaveLength(1)
  })
})

const boundInput: DeviceComponentInput = {
  kind: 'VALUE_CARD',
  title: '温度',
  deviceId: '11111111-1111-4111-8111-111111111111',
  model: {
    versionId: '22222222-2222-4222-8222-222222222222',
    digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
    digest: 'a'.repeat(64),
    profile: 'TC_PROPERTY_COMPOSITE_V1'
  },
  propertyKey: 'temperature'
}
describe('单设备组件原子编辑', () => {
  it('模型、变量与组件一次提交和撤销；重做及保存重开保留完整绑定', async () => {
    const { editor, save } = fixture()
    editor.addDeviceComponent(boundInput)
    const snapshot = editor.snapshot().schema!
    expect(snapshot.models).toHaveLength(1)
    expect(snapshot.variables).toHaveLength(1)
    expect(snapshot.models[0]).toMatchObject(boundInput.model)
    expect(snapshot.pages[0]!.components[0]!.bindings).toMatchObject({
      value: {
        source: 'CURRENT_VALUE',
        device: { variableKey: snapshot.variables[0]!.key },
        propertyKey: 'temperature'
      }
    })
    editor.undo()
    expect(editor.snapshot().schema).toEqual(emptyDashboard())
    expect(editor.snapshot().canUndo).toBe(false)
    editor.redo()
    expect(editor.snapshot().schema).toEqual(snapshot)
    await vi.advanceTimersByTimeAsync(1500)
    expect(save.mock.calls[0]?.[1]).toBe('0')
    expect(save.mock.calls[0]?.[2]).toEqual(snapshot)
    editor.open({ dashboardId: 'first', revision: '1', content: snapshot })
    expect(editor.snapshot().readonly).toBe(false)
    editor.add('TEXT')
    editor.addDeviceComponent({ ...boundInput, kind: 'STATUS', title: '状态' })
    expect(editor.snapshot().schema?.models).toHaveLength(1)
    expect(editor.snapshot().schema?.variables).toHaveLength(1)
    expect(editor.snapshot().schema?.pages[0]?.components).toHaveLength(3)
    await vi.advanceTimersByTimeAsync(1500)
    expect(save.mock.calls[1]?.[1]).toBe('1')
    expect(editor.snapshot().dirty).toBe(false)
  })
  it('同模型不同设备独立变量；摘要冲突及缺失属性不留下部分绑定或历史', () => {
    const { editor } = fixture()
    editor.addDeviceComponent(boundInput)
    editor.addDeviceComponent({
      ...boundInput,
      kind: 'STATUS',
      deviceId: '33333333-3333-4333-8333-333333333333'
    })
    expect(editor.snapshot().schema?.models).toHaveLength(1)
    expect(editor.snapshot().schema?.variables).toHaveLength(2)
    const before = editor.snapshot().schema
    editor.addDeviceComponent({
      ...boundInput,
      model: { ...boundInput.model, digest: 'b'.repeat(64) }
    })
    expect(editor.snapshot().schema).toEqual(before)
    expect(editor.snapshot().error).not.toBe('')
    editor.addDeviceComponent({ ...boundInput, propertyKey: undefined })
    expect(editor.snapshot().schema).toEqual(before)
    editor.undo()
    expect(editor.snapshot().schema?.variables).toHaveLength(1)
  })
  it('离线禁止添加，保存冲突保留完整绑定而不重写远端', async () => {
    const { editor, save, offline } = fixture()
    save.mockRejectedValueOnce({ code: 60037 })
    editor.addDeviceComponent(boundInput)
    await vi.advanceTimersByTimeAsync(1500)
    expect(editor.snapshot().conflict).toBe(true)
    expect(editor.snapshot().schema?.models).toHaveLength(1)
    offline()
    editor.addDeviceComponent({ ...boundInput, kind: 'STATUS' })
    expect(editor.snapshot().schema?.pages[0]?.components).toHaveLength(1)
    editor.retrySave()
    await vi.advanceTimersByTimeAsync(60000)
    expect(save).toHaveBeenCalledTimes(1)
  })
})

it('替换选中绑定只占一条历史，保持id和布局并不改其他组件引用', () => {
  const { editor } = fixture()
  editor.addDeviceComponent(boundInput)
  editor.addDeviceComponent({ ...boundInput, kind: 'STATUS' })
  const before = editor.snapshot().schema!
  const target = before.pages[0]!.components[1]!
  const rebind = editor.rebindSelectedDevice
  rebind({
    ...boundInput,
    kind: 'STATUS',
    title: '新设备',
    deviceId: '33333333-3333-4333-8333-333333333333'
  })
  const after = editor.snapshot().schema!
  expect(after.pages[0]!.components).toHaveLength(2)
  expect(after.pages[0]!.components[1]!.id).toBe(target.id)
  expect(after.pages[0]!.components[1]!.layout).toEqual(target.layout)
  expect(after.pages[0]!.components[0]).toEqual(before.pages[0]!.components[0])
  expect(after.variables).toHaveLength(2)
  editor.undo()
  expect(editor.snapshot().schema).toEqual(before)
})

it('三种当前值组件原子添加、保存重开与撤销，并复用同设备模型引用', async () => {
  const { editor, save } = fixture()
  for (const [kind, dataType, componentProps] of [
    ['GAUGE', 'NUMBER', { scaleMode: 'EXPLICIT', min: 0, max: 10 }],
    ['JSON_VIEW', 'OBJECT', { initialExpandDepth: 2 }],
    ['TABLE', 'LIST', { mode: 'LIST_VALUE', rowLimit: 2 }]
  ] as const) {
    editor.addDeviceComponent({
      ...boundInput,
      kind,
      propertyMetadata: { key: 'temperature', name: '属性', dataType },
      componentProps
    })
    expect(editor.snapshot().error).toBe('')
  }
  const schema = editor.snapshot().schema!
  expect(schema.models).toHaveLength(1)
  expect(schema.variables).toHaveLength(1)
  expect(schema.pages[0]!.components.map((c) => c.kind)).toEqual(['GAUGE', 'JSON_VIEW', 'TABLE'])
  editor.undo()
  expect(editor.snapshot().schema!.pages[0]!.components).toHaveLength(2)
  editor.redo()
  await vi.advanceTimersByTimeAsync(1500)
  expect(save.mock.calls[0]?.[2]).toEqual(schema)
  editor.open({ dashboardId: 'first', revision: '1', content: schema })
  expect(editor.snapshot().readonly).toBe(false)
  editor.select(schema.pages[0]!.components[0]!.id)
  editor.rebindSelectedDevice({
    ...boundInput,
    kind: 'JSON_VIEW',
    propertyMetadata: { key: 'temperature', name: '属性', dataType: 'LIST' }
  })
  const rebound = editor.snapshot().schema!.pages[0]!.components[0]!
  expect(rebound.id).toBe(schema.pages[0]!.components[0]!.id)
  expect(rebound.layout).toEqual(schema.pages[0]!.components[0]!.layout)
  expect(rebound.props).not.toHaveProperty('min')
  editor.undo()
  expect(editor.snapshot().schema).toEqual(schema)
})
it('模型量程与属性类型拒绝不合法输入而不产生悬挂引用或撤销历史', () => {
  const { editor } = fixture()
  const property = {
    key: 'temperature',
    name: '温度',
    dataType: 'NUMBER',
    minimumValue: { kind: 'NUMBER' as const, lexical: '0' },
    maximumValue: { kind: 'NUMBER' as const, lexical: '10' }
  }
  for (const patch of [
    { propertyMetadata: { ...property, maximumValue: null } },
    { propertyMetadata: { ...property, maximumValue: { kind: 'NUMBER' as const, lexical: '0' } } },
    {
      propertyMetadata: {
        ...property,
        maximumValue: { kind: 'NUMBER' as const, lexical: '1.234567890123456' }
      }
    },
    { componentProps: { scaleMode: 'MODEL', min: 0 } },
    { propertyMetadata: { ...property, dataType: 'TEXT' } }
  ]) {
    editor.addDeviceComponent({
      ...boundInput,
      kind: 'GAUGE',
      propertyMetadata: property,
      ...patch
    })
    expect(editor.snapshot().schema).toEqual(emptyDashboard())
    expect(editor.snapshot().canUndo).toBe(false)
  }
  editor.addDeviceComponent({ ...boundInput, kind: 'GAUGE', propertyMetadata: property })
  expect(editor.snapshot().schema!.pages[0]!.components[0]!.props).toMatchObject({
    scaleMode: 'MODEL'
  })
  expect(editor.snapshot().error).toBe('')
})
it('重绑既有标量参数保持不变，列表与复合配置不允许越界', () => {
  const { editor } = fixture()
  editor.addDeviceComponent({ ...boundInput, componentProps: { precision: 5, unitMode: 'NONE' } })
  editor.rebindSelectedDevice({ ...boundInput, title: '新标题' })
  expect(editor.snapshot().schema!.pages[0]!.components[0]!.props).toMatchObject({
    precision: 5,
    unitMode: 'NONE'
  })
  const before = editor.snapshot().schema
  for (const [kind, dataType, componentProps] of [
    ['TABLE', 'OBJECT', { mode: 'LIST_VALUE', rowLimit: 2 }],
    ['TABLE', 'LIST', { mode: 'LIST_VALUE', rowLimit: 257 }],
    ['JSON_VIEW', 'OBJECT', { initialExpandDepth: 3 }]
  ] as const) {
    editor.rebindSelectedDevice({
      ...boundInput,
      kind,
      propertyMetadata: { key: 'temperature', name: '属性', dataType },
      componentProps
    })
    expect(editor.snapshot().schema).toEqual(before)
  }
})

it('显式边界先验证原始十进制精度，拒绝舍入、反向和字段注入', () => {
  expect(parseGaugeBounds('0', '10')).toEqual({ min: 0, max: 10 })
  for (const [min, max] of [
    ['0', '1.234567890123456'],
    ['10', '0'],
    ['0', '1e13'],
    ['0', '1e-13'],
    ['0, "extra":1', '10']
  ])
    expect(parseGaugeBounds(min!, max!)).toBeNull()
})

const variableInput = {
  type: 'DEVICE_MULTI' as const,
  title: '设备组',
  required: true,
  model: boundInput.model,
  defaultDeviceIds: [boundInput.deviceId],
  maxItems: 3
}
it('设备变量与双表跨页引用完整保存，拒绝被引用变量删除及改型换模', async () => {
  const { editor, save } = fixture()
  editor.upsertDeviceVariable(variableInput)
  const variable = editor.snapshot().schema!.variables[0]!
  const input = {
    kind: 'TABLE' as const,
    model: boundInput.model,
    variableKey: variable.key,
    title: '多设备',
    columns: [{ label: '温度', propertyKey: 'temperature' }],
    properties: [{ key: 'temperature', name: '温度', dataType: 'NUMBER' }],
    rowLimit: 2
  }
  editor.addVariableComponent(input)
  editor.addPage('第二页')
  const second = editor.snapshot().schema!.pages[1]!.id
  editor.setPage(second)
  editor.addVariableComponent(input)
  editor.addVariableComponent({
    kind: 'DEVICE_SELECTOR',
    variableKey: variable.key,
    title: '选择器'
  })
  const schema = editor.snapshot().schema!
  expect(deviceVariableReferences(schema, variable.key)).toHaveLength(3)
  editor.removeDeviceVariable(variable.key)
  expect(editor.snapshot().schema).toEqual(schema)
  editor.upsertDeviceVariable({ ...variableInput, key: variable.key, type: 'DEVICE_SINGLE' })
  expect(editor.snapshot().schema).toEqual(schema)
  editor.upsertDeviceVariable({
    ...variableInput,
    key: variable.key,
    model: { ...boundInput.model, versionId: '44444444-4444-4444-8444-444444444444' }
  })
  expect(editor.snapshot().schema).toEqual(schema)
  editor.upsertDeviceVariable({
    ...variableInput,
    key: variable.key,
    title: '新标题',
    defaultDeviceIds: []
  })
  expect(editor.snapshot().schema!.variables[0]).toMatchObject({
    title: '新标题',
    defaultDeviceIds: []
  })
  editor.undo()
  expect(editor.snapshot().schema).toEqual(schema)
  editor.redo()
  await vi.advanceTimersByTimeAsync(1500)
  const saved = save.mock.calls[0]![2]
  editor.open({ dashboardId: 'first', revision: '1', content: saved })
  expect(editor.snapshot().readonly).toBe(false)
  expect(editor.snapshot().schema!.pages).toHaveLength(2)
})
it('单设备组件复用无默认变量不偷偷写默认，拒绝多变量及模型错配', () => {
  const { editor } = fixture()
  editor.upsertDeviceVariable({ ...variableInput, type: 'DEVICE_SINGLE', defaultDeviceIds: [] })
  const key = editor.snapshot().schema!.variables[0]!.key
  editor.addDeviceComponent({ ...boundInput, variableKey: key })
  expect(editor.snapshot().schema!.variables).toHaveLength(1)
  expect(editor.snapshot().schema!.variables[0]).not.toHaveProperty('defaultDeviceId')
  const before = editor.snapshot().schema
  editor.addDeviceComponent({ ...boundInput, variableKey: 'missing' })
  expect(editor.snapshot().schema).toEqual(before)
  editor.upsertDeviceVariable(variableInput)
  const multi = editor.snapshot().schema!.variables[1]!.key
  const second = editor.snapshot().schema
  editor.addDeviceComponent({ ...boundInput, variableKey: multi })
  expect(editor.snapshot().schema).toEqual(second)
})
it('多设备表列完整原子校验、重绑保持位置，引用解除后变量可删除', () => {
  const { editor } = fixture()
  editor.upsertDeviceVariable(variableInput)
  const key = editor.snapshot().schema!.variables[0]!.key
  const input = {
    kind: 'TABLE' as const,
    model: boundInput.model,
    variableKey: key,
    title: '表格',
    columns: [{ label: '数值', propertyKey: 'temperature' }],
    properties: [{ key: 'temperature', name: '数值', dataType: 'NUMBER' }]
  }
  for (const columns of [
    [],
    Array.from({ length: 11 }, () => ({ label: '列', propertyKey: 'temperature' })),
    [{ label: '非法', propertyKey: 'object' }]
  ]) {
    const before = editor.snapshot().schema
    editor.addVariableComponent({ ...input, columns })
    expect(editor.snapshot().schema).toEqual(before)
  }
  editor.addVariableComponent(input)
  const previous = editor.snapshot().schema!.pages[0]!.components[0]!
  editor.rebindVariableComponent({
    ...input,
    columns: [{ id: 'custom', label: '重命名', propertyKey: 'temperature' }],
    rowLimit: 1
  })
  const updated = editor.snapshot().schema!.pages[0]!.components[0]!
  expect(updated.id).toBe(previous.id)
  expect(updated.layout).toEqual(previous.layout)
  expect(updated.props).toMatchObject({ columns: [{ id: 'custom', label: '重命名' }], rowLimit: 1 })
  editor.removeSelected()
  editor.removeDeviceVariable(key)
  expect(editor.snapshot().schema!.variables).toHaveLength(0)
  editor.undo()
  expect(editor.snapshot().schema!.variables).toHaveLength(1)
})

it('选择器通用标题保存与替换保留组件身份', () => {
  const { editor } = fixture()
  editor.upsertDeviceVariable(variableInput)
  const key = editor.snapshot().schema!.variables[0]!.key
  editor.addVariableComponent({ kind: 'DEVICE_SELECTOR', variableKey: key, title: '选择A' })
  const before = editor.snapshot().schema!.pages[0]!.components[0]!
  expect(before.props).toMatchObject({ title: '选择A' })
  editor.rebindVariableComponent({ kind: 'DEVICE_SELECTOR', variableKey: key, title: '选择B' })
  expect(editor.snapshot().schema!.pages[0]!.components[0]).toMatchObject({
    id: before.id,
    props: { title: '选择B' }
  })
})

const timeInput = {
  title: '观察时间',
  required: true,
  defaultPreset: 'LAST_1_HOUR' as const,
  allowedPresets: ['LAST_1_HOUR', 'LAST_24_HOURS'] as ('LAST_1_HOUR' | 'LAST_24_HOURS')[]
}
it('多模型四系列原子编辑、时间引用跨页保护与完整保存重开', async () => {
  const { editor, save } = fixture()
  editor.upsertTimeRange(timeInput)
  const timeKey = editor.snapshot().schema!.variables[0]!.key
  const series: HistoryComponentInput['series'] = []
  for (let i = 1; i <= 4; i++) {
    const model = {
      ...boundInput.model,
      versionId: `${i}${'2'.repeat(7)}-2222-4222-8222-222222222222`
    }
    editor.upsertDeviceVariable({ ...variableInput, type: 'DEVICE_SINGLE', model })
    const key = editor.snapshot().schema!.variables.at(-1)!.key
    series.push({
      label: `系列${i}`,
      deviceVariableKey: key,
      timeRangeVariableKey: timeKey,
      propertyKey: 'temperature',
      granularity: 'ONE_HOUR',
      aggregation: 'AVG',
      model,
      propertyMetadata: { key: 'temperature', name: '温度', dataType: 'NUMBER' }
    })
  }
  editor.addHistoryComponent({ title: '四模型曲线', showLegend: false, series })
  const chart = editor.snapshot().schema!.pages[0]!.components[0]!
  expect(chart).toMatchObject({ kind: 'LINE_CHART', props: { showLegend: false } })
  expect(chart.bindings).toMatchObject({
    series: series.map((s) => ({
      value: { device: { variableKey: s.deviceVariableKey }, timeRangeVariableKey: timeKey }
    }))
  })
  editor.addPage('其他页')
  editor.setPage(editor.snapshot().schema!.pages[1]!.id)
  expect(deviceVariableReferences(editor.snapshot().schema!, timeKey)).toHaveLength(1)
  const before = editor.snapshot().schema
  editor.removeTimeRange(timeKey)
  expect(editor.snapshot().schema).toEqual(before)
  editor.upsertTimeRange({ ...timeInput, key: timeKey, defaultPreset: 'LAST_24_HOURS' })
  expect(editor.snapshot().schema!.variables[0]).toMatchObject({ defaultPreset: 'LAST_24_HOURS' })
  editor.undo()
  expect(editor.snapshot().schema).toEqual(before)
  editor.redo()
  await vi.advanceTimersByTimeAsync(1500)
  editor.open({ dashboardId: 'first', revision: '1', content: save.mock.calls[0]![2] })
  expect(editor.snapshot().readonly).toBe(false)
  expect(editor.snapshot().schema!.models).toHaveLength(4)
})
it('时间预设拒绝空集和缺省越界，历史系列拒绝错模型/非NUMBER/多设备', () => {
  const { editor } = fixture()
  editor.upsertTimeRange({ ...timeInput, allowedPresets: [] })
  expect(editor.snapshot().schema).toEqual(emptyDashboard())
  editor.upsertTimeRange({ ...timeInput, allowedPresets: ['LAST_24_HOURS'] })
  expect(editor.snapshot().schema).toEqual(emptyDashboard())
  editor.upsertTimeRange(timeInput)
  const timeKey = editor.snapshot().schema!.variables[0]!.key
  editor.upsertDeviceVariable({ ...variableInput, type: 'DEVICE_SINGLE' })
  const deviceKey = editor.snapshot().schema!.variables[1]!.key
  const row: HistoryComponentInput['series'][number] = {
    label: '温度',
    deviceVariableKey: deviceKey,
    timeRangeVariableKey: timeKey,
    propertyKey: 'temperature',
    granularity: 'RAW',
    aggregation: 'AVG',
    model: boundInput.model,
    propertyMetadata: { key: 'temperature', name: '温度', dataType: 'NUMBER' }
  }
  editor.upsertDeviceVariable(variableInput)
  const multiKey = editor.snapshot().schema!.variables.at(-1)!.key
  const before = editor.snapshot().schema
  for (const series of [
    [],
    [row, row],
    [{ ...row, deviceVariableKey: multiKey }],
    Array.from({ length: 5 }, () => row),
    [{ ...row, model: { ...row.model, digest: 'b'.repeat(64) } }],
    [{ ...row, propertyMetadata: { ...row.propertyMetadata, dataType: 'TEXT' } }],
    [{ ...row, timeRangeVariableKey: deviceKey }]
  ]) {
    editor.addHistoryComponent({ title: '图', showLegend: true, series })
    expect(editor.snapshot().schema).toEqual(before)
  }
  editor.addHistoryComponent({
    title: '图',
    showLegend: true,
    series: [row, { ...row, aggregation: 'MAX' }]
  })
  const original = editor.snapshot().schema!.pages[0]!.components[0]!
  editor.rebindHistoryComponent({
    title: '单系列',
    showLegend: false,
    series: [
      { ...row, id: 'series_2' },
      { ...row, aggregation: 'MAX' }
    ]
  })
  const changed = editor.snapshot().schema!.pages[0]!.components[0]!
  expect(changed.id).toBe(original.id)
  expect(changed.layout).toEqual(original.layout)
  expect(changed.props).toMatchObject({ series: [{ id: 'series_2' }, { id: 'series_1' }] })
  editor.removeSelected()
  editor.removeTimeRange(timeKey)
  expect(editor.snapshot().schema!.variables.some((v) => v.key === timeKey)).toBe(false)
})

const alarmInput: Omit<AlarmComponentInput, 'deviceVariableKey'> = {
  title: '设备告警',
  conditionStates: ['ACTIVE', 'PENDING'],
  ackStates: ['UNACKNOWLEDGED'],
  severities: ['MAJOR', 'WARNING'],
  pageSize: 2,
  showClearedAt: true
}
it('告警单双设备变量完整过滤保存，跨页引用保护和重绑撤销保留身份', async () => {
  const { editor, save } = fixture()
  editor.upsertDeviceVariable({ ...variableInput, type: 'DEVICE_SINGLE' })
  const single = editor.snapshot().schema!.variables[0]!.key
  editor.upsertDeviceVariable(variableInput)
  const multi = editor.snapshot().schema!.variables[1]!.key
  editor.addAlarmComponent({ ...alarmInput, deviceVariableKey: single })
  const original = editor.snapshot().schema!.pages[0]!.components[0]!
  const rebind = editor.rebindAlarmComponent
  rebind({
    ...alarmInput,
    deviceVariableKey: multi,
    conditionStates: ['CLEARED'],
    ackStates: ['ACKNOWLEDGED'],
    pageSize: 50,
    showClearedAt: false
  })
  const rebound = editor.snapshot().schema!.pages[0]!.components[0]!
  expect(rebound.id).toBe(original.id)
  expect(rebound.layout).toEqual(original.layout)
  expect(rebound).toMatchObject({
    props: { pageSize: 50, showClearedAt: false },
    bindings: {
      alarms: {
        devices: { variableKey: multi },
        conditionStates: ['CLEARED'],
        ackStates: ['ACKNOWLEDGED']
      }
    }
  })
  editor.undo()
  expect(editor.snapshot().schema!.pages[0]!.components[0]).toEqual(original)
  editor.redo()
  editor.addPage('第二页')
  editor.setPage(editor.snapshot().schema!.pages[1]!.id)
  const before = editor.snapshot().schema
  editor.removeDeviceVariable(multi)
  expect(editor.snapshot().schema).toEqual(before)
  expect(deviceVariableReferences(before!, multi)).toHaveLength(1)
  await vi.advanceTimersByTimeAsync(1500)
  editor.open({ dashboardId: 'first', revision: '1', content: save.mock.calls[0]![2] })
  expect(editor.snapshot().readonly).toBe(false)
  expect(editor.snapshot().schema!.pages[0]!.components[0]).toEqual(rebound)
})
it('告警空集、重复枚举、非法页数与时间变量绑定均不产生部分组件或历史', () => {
  const { editor } = fixture()
  editor.upsertDeviceVariable(variableInput)
  const deviceVariableKey = editor.snapshot().schema!.variables[0]!.key
  editor.upsertTimeRange(timeInput)
  const timeKey = editor.snapshot().schema!.variables[1]!.key
  const before = editor.snapshot().schema
  for (const patch of [
    { conditionStates: [] },
    { conditionStates: ['ACTIVE', 'ACTIVE'] },
    { ackStates: [] },
    { ackStates: ['ACKNOWLEDGED', 'ACKNOWLEDGED'] },
    { severities: [] },
    { severities: ['MAJOR', 'MAJOR'] },
    { pageSize: 0 },
    { pageSize: 51 },
    { deviceVariableKey: timeKey }
  ] as Partial<AlarmComponentInput>[]) {
    editor.addAlarmComponent({ ...alarmInput, deviceVariableKey, ...patch })
    expect(editor.snapshot().schema).toEqual(before)
  }
  editor.undo()
  expect(editor.snapshot().schema!.variables).toHaveLength(1)
})

const textVariable = {
  title: '工作模式',
  required: true,
  options: [
    { value: 'a', label: ' 甲 ' },
    { value: 'b', label: '<script>danger()</script>' }
  ]
}
const textStyle = { align: 'CENTER' as const, size: 'LARGE' as const, tone: 'PRIMARY' as const }
it('枚举动态文本多组件跨页共享，默认与标签保存保持原文且引用保护', async () => {
  const { editor, save } = fixture()
  editor.upsertTextEnum(textVariable)
  const key = editor.snapshot().schema!.variables[0]!.key
  editor.addTextComponent({ mode: 'DYNAMIC', variableKey: key, ...textStyle })
  editor.addPage('另一页')
  editor.setPage(editor.snapshot().schema!.pages[1]!.id)
  editor.addTextComponent({ mode: 'DYNAMIC', variableKey: key, ...textStyle })
  const before = editor.snapshot().schema
  editor.removeTextEnum(key)
  expect(editor.snapshot().schema).toEqual(before)
  expect(deviceVariableReferences(before!, key)).toHaveLength(2)
  editor.upsertTextEnum({ ...textVariable, key, defaultValue: 'a' })
  const updated = editor.snapshot().schema!
  expect(updated.variables[0]).toMatchObject({
    defaultValue: 'a',
    options: [
      { value: 'a', label: ' 甲 ' },
      { value: 'b', label: '<script>danger()</script>' }
    ]
  })
  expect(updated.pages[0]!.components[0]!.props).not.toHaveProperty('content')
  editor.undo()
  expect(editor.snapshot().schema).toEqual(before)
  editor.redo()
  await vi.advanceTimersByTimeAsync(1500)
  editor.open({ dashboardId: 'first', revision: '1', content: save.mock.calls[0]![2] })
  expect(editor.snapshot().readonly).toBe(false)
  expect(editor.snapshot().schema).toEqual(updated)
})
it('静态动态双向替换完整清内容源，样式更新不注入content且身份布局不变', () => {
  const { editor } = fixture()
  editor.upsertTextEnum(textVariable)
  const key = editor.snapshot().schema!.variables[0]!.key
  editor.addTextComponent({ mode: 'STATIC', content: '旧文字', ...textStyle })
  const original = editor.snapshot().schema!.pages[0]!.components[0]!
  const rebind = editor.rebindTextComponent
  rebind({ mode: 'DYNAMIC', variableKey: key, ...textStyle })
  editor.updateSelected({ props: { tone: 'SECONDARY' } })
  const dynamic = editor.snapshot().schema!.pages[0]!.components[0]!
  expect(dynamic.id).toBe(original.id)
  expect(dynamic.layout).toEqual(original.layout)
  expect(dynamic.props).not.toHaveProperty('content')
  expect(dynamic.bindings).toEqual({ text: { source: 'ENUM_TEXT', variableKey: key } })
  rebind({ mode: 'STATIC', content: '新文字', ...textStyle })
  const restored = editor.snapshot().schema!.pages[0]!.components[0]!
  expect(restored.bindings).toEqual({})
  expect(restored.props).toMatchObject({ content: '新文字', ...textStyle })
  editor.removeTextEnum(key)
  expect(editor.snapshot().schema!.variables).toHaveLength(0)
})
it('枚举数量重复默认与Unicode限制完整拒绝；TEXT拒绝两个内容源', () => {
  const { editor } = fixture()
  for (const options of [
    [],
    Array.from({ length: 21 }, (_, i) => ({ value: `a${i}`, label: '项' })),
    [
      { value: 'a', label: '甲' },
      { value: 'a', label: '乙' }
    ],
    [{ value: 'Bad', label: '甲' }],
    [{ value: 'a', label: '😀'.repeat(81) }],
    [{ value: 'a', label: '含\n换行' }]
  ]) {
    editor.upsertTextEnum({ ...textVariable, options })
    expect(editor.snapshot().schema).toEqual(emptyDashboard())
  }
  editor.upsertTextEnum({ ...textVariable, defaultValue: 'missing' })
  expect(editor.snapshot().schema).toEqual(emptyDashboard())
  editor.upsertTextEnum({ ...textVariable, options: [{ value: 'a', label: '😀'.repeat(80) }] })
  expect(editor.snapshot().schema!.variables).toHaveLength(1)
  const key = editor.snapshot().schema!.variables[0]!.key,
    before = editor.snapshot().schema
  for (const input of [
    { mode: 'DYNAMIC', variableKey: key, content: '双内容' },
    { mode: 'STATIC', variableKey: key, content: '双内容' },
    { mode: 'DYNAMIC', variableKey: 'missing' }
  ] as Partial<TextComponentInput>[]) {
    editor.addTextComponent({ ...textStyle, ...input } as TextComponentInput)
    expect(editor.snapshot().schema).toEqual(before)
  }
})
