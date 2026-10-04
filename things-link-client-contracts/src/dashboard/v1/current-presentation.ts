import type { RuntimeNumber } from "./composite-value.js";

function lexical(value: RuntimeNumber | number): string {
  return typeof value === 'number' ? value.toString() : value.lexical
}
/** 量程仅接受ConfigNumber；数值转换只用于有界指针几何，不替换屏幕上的词法事实。 */
function validBoundary(value: RuntimeNumber | number): boolean {
  const raw = lexical(value)
  const numeric = Number(raw)
  if (!Number.isFinite(numeric) || numeric !== 0 && (Math.abs(numeric) < 1e-12 || Math.abs(numeric) > 1e12)) return false
  const digits = raw.replace(/^-/, '').toLowerCase().split('e')[0]!.replace('.', '').replace(/^0+|0+$/g, '')
  return digits.length <= 15 && !(numeric === 0 && digits.length > 0)
}
/** 比较原十进制事实，避免高精度值刚越界时被Number舍回量程内。 */
function compareDecimal(left: string, right: string): number {
  const parts = (raw: string) => {
    const [coefficient, exponent = '0'] = raw.replace(/^-/, '').toLowerCase().split('e')
    const [integer, fraction = ''] = coefficient!.split('.')
    const digits = (integer + fraction).replace(/^0+/, '')
    return { sign: digits ? raw.startsWith('-') ? -1 : 1 : 0, digits, order: digits.length + Number(exponent) - fraction.length }
  }
  const a = parts(left); const b = parts(right)
  if (a.sign !== b.sign) return a.sign < b.sign ? -1 : 1
  if (a.sign === 0) return 0
  if (a.order !== b.order) return (a.order < b.order ? -1 : 1) * a.sign
  const length = Math.max(a.digits.length, b.digits.length)
  const aa = a.digits.padEnd(length, '0'); const bb = b.digits.padEnd(length, '0')
  return aa === bb ? 0 : (aa < bb ? -1 : 1) * a.sign
}
export function gaugePosition(value: RuntimeNumber, minimum: RuntimeNumber | number | null, maximum: RuntimeNumber | number | null): { minimum: string; maximum: string; percent: number; outOfRange: boolean } | null {
  const numeric = Number(value.lexical)
  if (!Number.isFinite(numeric) || minimum === null || maximum === null || !validBoundary(minimum) || !validBoundary(maximum)) return null
  const low = Number(lexical(minimum)); const high = Number(lexical(maximum))
  if (low >= high) return null
  return { minimum: lexical(minimum), maximum: lexical(maximum), percent: Math.max(0, Math.min(100, (numeric - low) / (high - low) * 100)), outOfRange: compareDecimal(value.lexical, lexical(minimum)) < 0 || compareDecimal(value.lexical, lexical(maximum)) > 0 }
}

export function localTablePage<T>(rows: readonly T[], requested: number, rowLimit: number): { rows: readonly T[]; index: number; count: number; total: number } {
  const count = Math.max(1, Math.ceil(rows.length / rowLimit))
  const index = Math.max(0, Math.min(count - 1, requested))
  return { rows: rows.slice(index * rowLimit, (index + 1) * rowLimit), index, count, total: rows.length }
}
