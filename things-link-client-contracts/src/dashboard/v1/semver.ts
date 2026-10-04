/** 三段数字语义版本的数值投影。 */
export interface NumericSemVer {
  /** 主版本号。 */
  readonly major: number;
  /** 次版本号。 */
  readonly minor: number;
  /** 修订版本号。 */
  readonly patch: number;
}

/** 一项包含下界、一项排除上界的宿主版本范围。 */
export interface HostVersionRange {
  /** 可兼容的最低宿主版本。 */
  readonly minInclusive: string;
  /** 首个不兼容宿主版本。 */
  readonly maxExclusive: string;
}

/**
 * 严格解析三段纯数字语义版本，不接受前导零、预发布或构建元数据。
 *
 * @param value 待解析版本文本。
 * @returns 合法时返回数值投影，否则返回null。
 */
export function parseNumericSemVer(value: string): NumericSemVer | null {
  const match = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)/.exec(value);
  if (match === null || match[0].length !== value.length) {
    return null;
  }
  const parts = match.slice(1).map(Number);
  if (parts.some((part) => !Number.isSafeInteger(part) || part > 65_535)) {
    return null;
  }
  return { major: parts[0]!, minor: parts[1]!, patch: parts[2]! };
}

/**
 * 以数值段比较两个已解析语义版本，避免字符串字典序误判10与2。
 *
 * @param left 左侧版本。
 * @param right 右侧版本。
 * @returns 左侧较小时为负数，相等为0，较大时为正数。
 */
export function compareNumericSemVer(left: NumericSemVer, right: NumericSemVer): number {
  return left.major - right.major || left.minor - right.minor || left.patch - right.patch;
}

/**
 * 判断宿主版本是否落入一项包含下界、一项排除上界的有效数字版本范围。
 *
 * @param hostVersion 宿主三段数字版本。
 * @param range 合同声明的版本范围。
 * @returns 版本和范围均合法且满足下界小于等于宿主并小于上界时返回true。
 */
export function isHostVersionCompatible(hostVersion: string, range: HostVersionRange): boolean {
  const host = parseNumericSemVer(hostVersion);
  const minimum = parseNumericSemVer(range.minInclusive);
  const maximum = parseNumericSemVer(range.maxExclusive);
  if (host === null || minimum === null || maximum === null || compareNumericSemVer(minimum, maximum) >= 0) {
    return false;
  }
  return compareNumericSemVer(host, minimum) >= 0 && compareNumericSemVer(host, maximum) < 0;
}
