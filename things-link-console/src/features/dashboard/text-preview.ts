import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
export type TextSelections = Readonly<Record<string, string | null>>
export interface TextPreviewEntry {
  id: string
  text: string
  state: 'SELECTED' | 'UNSELECTED' | 'INVALID'
  align: 'LEFT' | 'CENTER' | 'RIGHT'
  size: 'SMALL' | 'MEDIUM' | 'LARGE'
  tone: 'REGULAR' | 'SECONDARY' | 'PRIMARY'
}
/** 纯文本本地投影；不通过数据读取轮次，不把选项value拼入查询或HTML。 */
export function textPreview(
  schema: DashboardSchemaV1,
  pageId: string,
  selections: TextSelections = {}
) {
  const page = schema.pages.find((page) => page.id === pageId)
  const entries: TextPreviewEntry[] = []
  const variables = new Map<
    string,
    Extract<DashboardSchemaV1['variables'][number], { type: 'TEXT_ENUM' }>
  >()
  for (const component of page?.components ?? []) {
    if (component.kind !== 'TEXT' || !('text' in component.bindings)) continue
    const key = component.bindings.text.variableKey
    const variable = schema.variables.find((variable) => variable.key === key)
    if (variable?.type !== 'TEXT_ENUM') throw new Error('文本预览变量合同不匹配')
    variables.set(key, variable)
    const selected = Object.hasOwn(selections, key)
      ? selections[key]
      : (variable.defaultValue ?? null)
    const option = variable.options.find((option) => option.value === selected)
    const state = selected === null ? 'UNSELECTED' : option ? 'SELECTED' : 'INVALID'
    entries.push({
      id: component.id,
      ...component.props,
      state,
      text: option
        ? option.label
        : state === 'INVALID'
          ? '文本选项已失效，请重新选择'
          : variable.required
            ? '请选择文本选项'
            : '未选择文本选项'
    })
  }
  return { entries, variables: [...variables.values()] }
}
