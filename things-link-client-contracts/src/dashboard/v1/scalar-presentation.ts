/** 宿主无关的精确标量展示，不依赖会话、Vue或数据读取。 */
export interface ScalarNumber { readonly kind: 'NUMBER'; readonly lexical: string }
export interface ScalarProperty {
  readonly dataType: string;
  readonly unit?: string | null;
  readonly onLabel?: string | null;
  readonly offLabel?: string | null;
  readonly enumOptions?: readonly string[] | null;
}
function lexical(value: ScalarNumber | number): string {
  return typeof value === 'number' ? value.toString() : value.lexical;
}
/** NUMBER先保留原词法做十进制四舍五入，不能先转IEEE754抹掉9007199254740993等事实。 */
export function formatDecimal(value: ScalarNumber | number, precision: number): string | null {
  const raw = lexical(value)
  if (!Number.isFinite(Number(raw)) || !/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(raw)
    || !Number.isInteger(precision) || precision < 0 || precision > 6) return null
  const [coefficient = '0', exponent = '0'] = raw.replace(/^-/, '').toLowerCase().split('e')
  const [integer = '0', fraction = ''] = coefficient.split('.')
  const digits = (integer + fraction).replace(/^0+/, '') || '0'
  const cut = digits.length + Number(exponent) - fraction.length + precision
  let rounded: bigint
  // 只取显示精度所需前缀和首个舍入位；巨大系数不能转成巨型BigInt或构造10^巨大指数。
  if (digits === '0' || cut < 0) rounded = 0n
  else {
    // Double有限域最多309位整数，加6位显示精度；这个边界不缩短原始遥测词法。
    if (!Number.isInteger(cut) || cut > 315) return null
    const prefix = cut === 0 ? '0' : digits.slice(0, cut).padEnd(cut, '0')
    const roundUp = cut < digits.length && digits.charCodeAt(cut) >= 53
    rounded = BigInt(prefix) + (roundUp ? 1n : 0n)
  }
  const result = rounded.toString().padStart(precision + 1, '0')
  return `${raw.startsWith('-') && rounded !== 0n ? '-' : ''}${precision ? `${result.slice(0, -precision)}.${result.slice(-precision)}` : result}`
}
function isRuntimeNumber(value: unknown): value is ScalarNumber {
  return typeof value === 'object' && value !== null && 'kind' in value && value.kind === 'NUMBER' && 'lexical' in value && typeof value.lexical === 'string'
}
export function formatScalar(value: unknown, property: ScalarProperty, precision: number, unitMode: 'MODEL' | 'NONE'): string | null {
  let text: string | null = null
  if (property.dataType === 'NUMBER' && isRuntimeNumber(value)) text = formatDecimal(value, precision)
  if (property.dataType === 'TEXT' && typeof value === 'string') text = value
  if (property.dataType === 'SWITCH' && typeof value === 'boolean') text = value ? property.onLabel ?? '开启' : property.offLabel ?? '关闭'
  if (property.dataType === 'ENUM' && typeof value === 'string' && property.enumOptions?.includes(value)) text = value
  return text === null ? null : `${text}${unitMode === 'MODEL' && property.unit ? ` ${property.unit}` : ''}`
}
