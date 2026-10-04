import { parseNumericSemVer } from "./semver.js";
import { isStrictJsonNumber, type StrictJsonNumber, type StrictJsonValue } from "./strict-json.js";
import { DashboardContractViolation } from "./violation.js";

/**
 * 要求值为LocalKey。
 *
 * @param value 待校验JSON值。
 * @param path 稳定JSON路径。
 * @returns 原始字符串。
 */
export function requireLocalKey(value: StrictJsonValue, path: string): string {
  return requirePatternedString(value, path, /^[a-z][a-z0-9_]{0,63}$/, "LocalKey语法不合法");
}

/**
 * 要求值为顶层PropertyKey。
 *
 * @param value 待校验JSON值。
 * @param path 稳定JSON路径。
 * @returns 原始字符串。
 */
export function requirePropertyKey(value: StrictJsonValue, path: string): string {
  return requirePatternedString(value, path, /^[A-Za-z0-9_-]{1,64}$/, "PropertyKey语法不合法");
}

/**
 * 要求值为规范小写UUID。
 *
 * @param value 待校验JSON值。
 * @param path 稳定JSON路径。
 * @returns 原始UUID文本。
 */
export function requireCanonicalUuid(value: StrictJsonValue, path: string): string {
  return requirePatternedString(
    value,
    path,
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    "必须是规范小写UUID",
  );
}

/**
 * 要求值为64位小写SHA-256摘要。
 *
 * @param value 待校验JSON值。
 * @param path 稳定JSON路径。
 * @returns 原始摘要文本。
 */
export function requireSha256(value: StrictJsonValue, path: string): string {
  return requirePatternedString(value, path, /^[0-9a-f]{64}$/, "必须是64位小写SHA-256");
}

/**
 * 要求值为三段无前导零且每段不超过65535的SemVer。
 *
 * @param value 待校验JSON值。
 * @param path 稳定JSON路径。
 * @returns 原始版本文本。
 */
export function requireSemVer(value: StrictJsonValue, path: string): string {
  const text = requireString(value, path);
  if (parseNumericSemVer(text) === null) reject("INVALID_VALUE", path, "SemVer语法不合法或数值段超过65535");
  return text;
}

/**
 * 要求值为1至80码点、非空白且不含C0/C1控制字符的Title。
 *
 * @param value 待校验JSON值。
 * @param path 稳定JSON路径。
 * @returns 原始标题。
 */
export function requireTitle(value: StrictJsonValue, path: string): string {
  const text = requireString(value, path);
  const length = codePointLength(text);
  if (length < 1 || length > 80 || isJavaBlank(text) || containsControl(text, false)) {
    reject("INVALID_VALUE", path, "Title必须为1至80码点的非空白无控制字符文本");
  }
  return text;
}

/**
 * 要求值为最多256码点且不含C0/C1控制字符的ShortText。
 *
 * @param value 待校验JSON值。
 * @param path 稳定JSON路径。
 * @returns 原始短文本。
 */
export function requireShortText(value: StrictJsonValue, path: string): string {
  const text = requireString(value, path);
  if (codePointLength(text) > 256 || containsControl(text, false)) {
    reject("INVALID_VALUE", path, "ShortText最多256码点且不得含控制字符");
  }
  return text;
}

/**
 * 要求值为最多4096码点且控制字符只允许TAB/LF的TextContent。
 *
 * @param value 待校验JSON值。
 * @param path 稳定JSON路径。
 * @returns 原始正文。
 */
export function requireTextContent(value: StrictJsonValue, path: string): string {
  const text = requireString(value, path);
  if (codePointLength(text) > 4096 || containsControl(text, true)) {
    reject("INVALID_VALUE", path, "TextContent最多4096码点且控制字符只允许TAB/LF");
  }
  return text;
}

/**
 * 要求数字词法满足ConfigNumber范围和十五位有效数字约束。
 *
 * @param value 待校验JSON值。
 * @param path 稳定JSON路径。
 * @returns 保留原词法的数字token。
 */
export function requireConfigNumber(value: StrictJsonValue, path: string): StrictJsonNumber {
  const number = requireNumber(value, path);
  const decimal = decomposeDecimal(number.lexical);
  if (decimal.significantDigits.length > 15) {
    reject("INVALID_NUMBER", path, "去十进制尾零后不得超过15位有效数字");
  }
  if (!decimal.zero && (decimal.magnitudeExponent < -12 || decimal.magnitudeExponent > 12
      || decimal.magnitudeExponent === 12 && !/^10*$/.test(decimal.digitsWithoutLeadingZeros))) {
    reject("INVALID_NUMBER", path, "绝对值必须为0或位于10^-12至10^12");
  }
  return number;
}

/**
 * 精确比较两个已经通过ConfigNumber规则的十进制token。
 *
 * @param left 左侧ConfigNumber。
 * @param right 右侧ConfigNumber。
 * @returns 左侧较小时为负、相等为0、较大时为正。
 */
export function compareConfigNumbers(left: StrictJsonNumber, right: StrictJsonNumber): number {
  const leftNumber = decomposeDecimal(requireConfigNumber(left, "$internal.left").lexical);
  const rightNumber = decomposeDecimal(requireConfigNumber(right, "$internal.right").lexical);
  if (leftNumber.zero && rightNumber.zero) return 0;
  if (leftNumber.negative !== rightNumber.negative) return leftNumber.negative ? -1 : 1;
  const minimumScale = Math.min(leftNumber.scale, rightNumber.scale);
  const leftInteger = BigInt(leftNumber.digitsWithoutLeadingZeros || "0") * 10n ** BigInt(leftNumber.scale - minimumScale);
  const rightInteger = BigInt(rightNumber.digitsWithoutLeadingZeros || "0") * 10n ** BigInt(rightNumber.scale - minimumScale);
  const absolute = leftInteger < rightInteger ? -1 : leftInteger > rightInteger ? 1 : 0;
  return leftNumber.negative ? -absolute : absolute;
}

/**
 * 要求数字token保持JSON整数词法，并可选执行闭区间判定。
 *
 * @param value 待校验JSON值。
 * @param path 稳定JSON路径。
 * @param minimum 可选最小整数。
 * @param maximum 可选最大整数。
 * @returns 精确BigInt整数。
 */
export function requireJsonInteger(
  value: StrictJsonValue,
  path: string,
  minimum?: bigint,
  maximum?: bigint,
): bigint {
  const number = requireNumber(value, path);
  if (!/^-?(?:0|[1-9]\d*)$/.test(number.lexical)) {
    reject("TYPE_MISMATCH", path, "必须是JSON整数token");
  }
  const integer = BigInt(number.lexical);
  if (minimum !== undefined && integer < minimum || maximum !== undefined && integer > maximum) {
    reject("INVALID_VALUE", path, "整数超出允许范围");
  }
  return integer;
}

/** 要求未知JSON值为字符串。 */
function requireString(value: StrictJsonValue, path: string): string {
  if (typeof value !== "string") reject("TYPE_MISMATCH", path, "必须是字符串");
  return value;
}

/** 要求未知JSON值为保留词法的数字token。 */
function requireNumber(value: StrictJsonValue, path: string): StrictJsonNumber {
  if (!isStrictJsonNumber(value)) reject("TYPE_MISMATCH", path, "必须是JSON number");
  return value;
}

/** 要求字符串满足指定闭集正则。 */
function requirePatternedString(value: StrictJsonValue, path: string, pattern: RegExp, detail: string): string {
  const text = requireString(value, path);
  const match = pattern.exec(text);
  if (match === null || match[0].length !== text.length) reject("INVALID_VALUE", path, detail);
  return text;
}

/** 按Unicode码点而非UTF-16代码单元计数。 */
function codePointLength(value: string): number {
  return Array.from(value).length;
}

/** 复刻Java String.isBlank使用的Character.isWhitespace集合。 */
function isJavaBlank(value: string): boolean {
  return Array.from(value).every((character) => {
    const codePoint = character.codePointAt(0)!;
    return codePoint >= 0x09 && codePoint <= 0x0d
      || codePoint >= 0x1c && codePoint <= 0x20
      || codePoint === 0x1680
      || codePoint >= 0x2000 && codePoint <= 0x2006
      || codePoint >= 0x2008 && codePoint <= 0x200a
      || codePoint === 0x2028
      || codePoint === 0x2029
      || codePoint === 0x205f
      || codePoint === 0x3000;
  });
}

/** 判断是否含禁止的C0/C1控制字符。 */
function containsControl(value: string, allowTabAndLf: boolean): boolean {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0)!;
    const control = codePoint <= 0x1f || codePoint >= 0x7f && codePoint <= 0x9f;
    return control && !(allowTabAndLf && (codePoint === 0x09 || codePoint === 0x0a));
  });
}

/** 将合法JSON数字拆成范围和有效位判定所需的精确十进制投影。 */
function decomposeDecimal(lexical: string): DecimalProjection {
  const match = /^(-)?(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?/.exec(lexical);
  if (match === null || match[0].length !== lexical.length) throw new Error("严格JSON解析器产生了非法数字词法");
  const integer = match[2]!;
  const fraction = match[3] ?? "";
  const explicitExponent = Number(match[4] ?? "0");
  const joined = `${integer}${fraction}`;
  const digitsWithoutLeadingZeros = joined.replace(/^0+/, "");
  if (digitsWithoutLeadingZeros.length === 0) {
    return { zero: true, negative: false, digitsWithoutLeadingZeros: "", significantDigits: "", magnitudeExponent: 0, scale: 0 };
  }
  const significantDigits = digitsWithoutLeadingZeros.replace(/0+$/, "");
  return {
    zero: false,
    negative: match[1] !== undefined,
    digitsWithoutLeadingZeros,
    significantDigits,
    magnitudeExponent: explicitExponent - fraction.length + digitsWithoutLeadingZeros.length - 1,
    scale: explicitExponent - fraction.length,
  };
}

/** 精确十进制数的内部投影。 */
interface DecimalProjection {
  /** 是否为任意词法形式的零。 */
  readonly zero: boolean;
  /** 非零数是否带负号。 */
  readonly negative: boolean;
  /** 删除前导零后的全部数字。 */
  readonly digitsWithoutLeadingZeros: string;
  /** 继续删除十进制尾零后的有效数字。 */
  readonly significantDigits: string;
  /** 首位有效数字所在的十进制指数。 */
  readonly magnitudeExponent: number;
  /** 整数系数对应的十进制幂。 */
  readonly scale: number;
}

/** 抛出稳定合同拒绝结果。 */
function reject(reason: ConstructorParameters<typeof DashboardContractViolation>[0], path: string, detail: string): never {
  throw new DashboardContractViolation(reason, path, detail);
}
