/** 表单内部JSON值；不是HTTP业务对象的第二套定义。 */
export type JsonValue =
  | null
  | boolean
  | number
  | string
  | JsonValue[]
  | { [key: string]: JsonValue }
export type ConfigObject = { [key: string]: JsonValue }
export interface FieldSchema {
  type?: string
  enum?: string[]
  maxLength?: number
}
export interface ConfigSchema {
  properties?: Record<string, FieldSchema>
  required?: string[]
}

/** JSON类型未知时明确采用JSON编辑，不能把数字和对象隐式字符串化。 */
export function fieldValue(text: string, schema: FieldSchema): JsonValue | undefined {
  if (text === '') return undefined
  return schema.type === 'string' || schema.enum ? text : JSON.parse(text)
}
/** 未知字段禁止有损编辑；历史配置仍可完整显示。 */
export function canEditConfig(config: unknown, schema: ConfigSchema): config is ConfigObject {
  return (
    !!config &&
    typeof config === 'object' &&
    !Array.isArray(config) &&
    Object.keys(config).every((key) => Object.hasOwn(schema.properties ?? {}, key))
  )
}

/** 调序或删除节点时，未通过 JSON 校验的输入与错误必须跟随原节点。 */
export function remapNodeFields(fields: Record<string, string>, order: number[]) {
  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(fields)) {
    const colon = key.indexOf(':')
    const target = order.indexOf(Number(key.slice(0, colon)))
    if (target >= 0) result[`${target}${key.slice(colon)}`] = value
  }
  return result
}
