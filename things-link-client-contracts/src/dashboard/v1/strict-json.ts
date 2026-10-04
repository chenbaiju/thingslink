import { DashboardContractViolation } from "./violation.js";

/** 严格JSON数字token，保留原始十进制词法而不经过IEEE-754舍入。 */
export interface StrictJsonNumber {
  /** 内部数字节点判别标记。 */
  readonly type: "JSON_NUMBER";
  /** 原始合法JSON数字词法。 */
  readonly lexical: string;
}

/** 严格JSON数组。 */
export interface StrictJsonArray extends ReadonlyArray<StrictJsonValue> {}
/** 严格JSON对象。 */
export interface StrictJsonObject {
  /** 解码后属性名对应的严格JSON值。 */
  readonly [key: string]: StrictJsonValue;
}
/** 保留数字词法的严格JSON值。 */
export type StrictJsonValue = null | boolean | string | StrictJsonNumber | StrictJsonArray | StrictJsonObject;
/** 只登记当前模块解析器实际创建的数字节点，阻止JSON对象伪造内部判别字段。 */
const STRICT_JSON_NUMBERS = new WeakSet<object>();

/** 判断值是否是严格解析器创建并登记的数字token。 */
export function isStrictJsonNumber(value: StrictJsonValue): value is StrictJsonNumber {
  return value !== null && typeof value === "object" && STRICT_JSON_NUMBERS.has(value);
}

/**
 * 为Schema默认注入创建受控整数token，并登记与冻结其身份。
 *
 * <p>此函数仅供包内语义管线使用，不从版本化公共入口导出。</p>
 *
 * @param lexical 受信任的严格JSON整数词法。
 * @returns 已登记且冻结的数字token。
 */
export function createStrictJsonInteger(lexical: string): StrictJsonNumber {
  if (!/^-?(?:0|[1-9]\d*)$/.test(lexical) || lexical.length > 64) {
    throw new Error("内部默认整数必须使用不超过64字节的严格JSON词法");
  }
  return registerNumber(lexical);
}

/**
 * 为完整语义管线创建受控的规范JSON数字token。
 *
 * <p>此函数只接收由包内十进制规范器产生的词法，不从版本化公共入口导出；调用方不能借此
 * 伪造解析器数字身份。</p>
 *
 * @param lexical 已规范化的严格JSON数字词法。
 * @returns 已登记且冻结的数字token。
 */
export function createStrictJsonNumber(lexical: string): StrictJsonNumber {
  if (!/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(lexical)) {
    throw new Error("内部规范数字必须使用严格JSON词法");
  }
  return registerNumber(lexical);
}

/**
 * 解析Dashboard v1原始UTF-8 JSON，只证明原文、JSON结构和合同版本。
 *
 * <p>返回值不是已通过业务语义校验的DashboardSchema，不应直接用于保存或发布。</p>
 *
 * @param source 未经解码的原始字节。
 * @returns 递归冻结且保留数字词法的JSON根对象。
 * @throws DashboardContractViolation 原文、JSON结构或版本不符合合同时抛出。
 */
export function parseDashboardV1Json(source: Uint8Array): StrictJsonObject {
  if (source.byteLength > 512_000) {
    reject("RAW_TOO_LARGE", "$", "原文超过512000字节上限");
  }
  const snapshot = source.slice();
  if (snapshot.length >= 3 && snapshot[0] === 0xef && snapshot[1] === 0xbb && snapshot[2] === 0xbf) {
    reject("BOM_NOT_ALLOWED", "$", "不得包含UTF-8 BOM");
  }
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(snapshot);
  } catch {
    reject("INVALID_UTF8", "$", "原文不是严格合法的UTF-8");
  }
  return new StrictJsonReader(text!).parseDashboardRoot();
}

/** 精确Schema包的严格根树及未经重序列化的Schema原文字节；尚未证明业务语义。 */
export interface DashboardSchemaEnvelope {
  /** 保留数字词法、已拒绝重复字段的完整信封。 */
  readonly envelope: StrictJsonObject;
  /** 精确保留根schema值的词法、空白和转义，交由既有500KiB入口验证。 */
  readonly schemaSource: Uint8Array;
}

/** 元数据合同§5.3：有界读取768KiB信封，不扩大Schema既有500KiB及16层上限。 */
export function parseDashboardSchemaEnvelope(source: Uint8Array): DashboardSchemaEnvelope {
  if (source.byteLength > 768 * 1024) reject("RAW_TOO_LARGE", "$", "Schema包超过768KiB上限");
  const snapshot = source.slice();
  if (snapshot[0] === 0xef && snapshot[1] === 0xbb && snapshot[2] === 0xbf) {
    reject("BOM_NOT_ALLOWED", "$", "不得包含UTF-8 BOM");
  }
  let text: string;
  try { text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(snapshot); }
  catch { reject("INVALID_UTF8", "$", "原文不是严格合法的UTF-8"); }
  const reader = new StrictJsonReader(text!, true);
  const envelope = reader.parseObjectRoot();
  const rawSchema = reader.schemaText;
  if (rawSchema === undefined || rawSchema[0] !== "{") reject("ROOT_MUST_BE_OBJECT", "$.schema", "Schema必须为对象");
  const schemaSource = new TextEncoder().encode(rawSchema);
  if (schemaSource.length > 512_000) reject("RAW_TOO_LARGE", "$.schema", "Schema原文超过512000字节上限");
  return Object.freeze({ envelope, schemaSource });
}

/** 数据运行合同§3：运行响应数字保留词法，不沿用Schema配置数的64字节或指数12限制。 */
export function parseDashboardRuntimeResponse(source: Uint8Array): StrictJsonObject {
  if (source.byteLength > 4 * 1024 * 1024) reject("RAW_TOO_LARGE", "$", "运行响应超过4MiB上限");
  const snapshot = source.slice();
  if (snapshot[0] === 0xef && snapshot[1] === 0xbb && snapshot[2] === 0xbf) reject("BOM_NOT_ALLOWED", "$", "不得包含UTF-8 BOM");
  let text: string;
  try { text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(snapshot); }
  catch { reject("INVALID_UTF8", "$", "原文不是严格合法的UTF-8"); }
  return new StrictJsonReader(text!, false, true).parseObjectRoot();
}

/** 不依赖Node运行时的递归下降JSON读取器。 */
class StrictJsonReader {
  /** 已严格解码的JSON文本。 */
  private readonly source: string;
  /** 当前UTF-16代码单元下标。 */
  private index = 0;
  /** 仅精确包入口启用根schema词法捕捉，不影响旧Schema解析。 */
  private readonly captureSchema: boolean;
  /** 遥测采用服务端Double有限域，保留BigDecimal原词法而不套配置数边界。 */
  private readonly runtimeNumbers: boolean;
  /** 捕捉的完整schema值，供上层保真转回UTF-8。 */
  public schemaText: string | undefined;

  /**
   * 创建读取器。
   *
   * @param source 已严格解码的JSON文本。
   * @param captureSchema 是否保留信封根schema的原始词法。
   * @param runtimeNumbers 是否使用运行响应的独立有限数值域。
   */
  public constructor(source: string, captureSchema = false, runtimeNumbers = false) {
    this.source = source;
    this.captureSchema = captureSchema;
    this.runtimeNumbers = runtimeNumbers;
  }

  /**
   * 读取完整对象根并检查尾随值；不在信封层假定Schema版本。
   *
   * @returns 递归冻结的对象根。
   */
  public parseObjectRoot(): StrictJsonObject {
    this.skipWhitespace();
    if (this.peek() !== "{") {
      this.rejectInvalidOrNonObjectRoot();
    }
    const root = this.parseObject("$", 1);
    this.skipWhitespace();
    if (!this.atEnd()) {
      if (this.hasTrailingJsonToken()) {
        reject("TRAILING_VALUE", "$", "根对象后不得包含额外JSON值");
      }
      reject("INVALID_JSON", "$", "根对象后包含非法JSON词法");
    }
    return root;
  }

  /** 旧Schema入口继续独立识别版本，不接受任意信封根对象。 */
  public parseDashboardRoot(): StrictJsonObject {
    const root = this.parseObjectRoot();
    const version = root.schemaVersion;
    if (typeof version !== "string") {
      reject("VERSION_REQUIRED", "$.schemaVersion", "必须声明字符串类型的schemaVersion");
    }
    if (version !== "tc.dashboard/v1") {
      reject("VERSION_UNSUPPORTED", "$.schemaVersion", "Schema版本未登记");
    }
    return root;
  }

  /** 区分合法非对象首token与首token自身的JSON语法错误。 */
  private rejectInvalidOrNonObjectRoot(): never {
    const current = this.peek();
    // Jackson在空输入时得到null首token，并将其归入“根必须是对象”；客户端保持同一稳定原因。
    if (current === undefined) reject("ROOT_MUST_BE_OBJECT", "$", "根值必须是JSON对象");
    if (current === "[") reject("ROOT_MUST_BE_OBJECT", "$", "根值必须是JSON对象");
    // Jackson取得VALUE_STRING token后不会在根类型拒绝前解码内容；保持同一首错优先级。
    if (current === '"') reject("ROOT_MUST_BE_OBJECT", "$", "根值必须是JSON对象");
    if (current === "t" && this.hasLiteralToken("true")
        || current === "f" && this.hasLiteralToken("false")
        || current === "n" && this.hasLiteralToken("null")) {
      reject("ROOT_MUST_BE_OBJECT", "$", "根值必须是JSON对象");
    }
    if ((current === "-" || isDigit(current)) && this.hasValidNumberToken()) {
      reject("ROOT_MUST_BE_OBJECT", "$", "根值必须是JSON对象");
    }
    reject("INVALID_JSON", "$", "首个JSON token语法不合法");
  }

  /** 判断当前位置是否是完整固定值token。 */
  private hasLiteralToken(token: string): boolean {
    if (this.source.slice(this.index, this.index + token.length) !== token) return false;
    return isTokenBoundary(this.source[this.index + token.length]);
  }

  /** 判断当前位置是否以严格JSON数字token开头且随后是token边界。 */
  private hasValidNumberToken(): boolean {
    const remaining = this.source.slice(this.index);
    const match = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(remaining);
    return match !== null && isTokenBoundary(remaining[match[0].length]);
  }

  /** 判断根对象之后能否读取第二个合法JSON token，而不把任意垃圾误报为尾随值。 */
  private hasTrailingJsonToken(): boolean {
    const current = this.peek();
    // Jackson看到容器起始符即可形成START token，无需等整个第二值闭合。
    if (current === "{" || current === "[") return true;
    // Jackson在第二个VALUE_STRING token出现时先报尾随，不继续解码字符串内容。
    if (current === '"') return true;
    if (current === "t") return this.hasLiteralToken("true");
    if (current === "f") return this.hasLiteralToken("false");
    if (current === "n") return this.hasLiteralToken("null");
    return (current === "-" || isDigit(current)) && this.hasValidNumberToken();
  }

  /** 读取指定路径和值深度的JSON值。 */
  private parseValue(path: string, depth: number): StrictJsonValue {
    const current = this.peek();
    if (current === "{") return this.parseObject(path, depth);
    if (current === "[") return this.parseArray(path, depth);
    if (current === '"') return this.parseString(path);
    if (current === "t") return this.parseLiteral("true", true, path);
    if (current === "f") return this.parseLiteral("false", false, path);
    if (current === "n") return this.parseLiteral("null", null, path);
    if (current === "-" || isDigit(current)) return this.parseNumber(path);
    reject("INVALID_JSON", path, "包含非法JSON值");
  }

  /** 读取对象并按解码后的属性名判重。 */
  private parseObject(path: string, depth: number): StrictJsonObject {
    this.requireDepth(depth, path);
    this.index++;
    this.skipWhitespace();
    const result: Record<string, StrictJsonValue> = Object.create(null);
    if (this.consume("}")) return Object.freeze(result);
    while (true) {
      if (this.peek() !== '"') reject("INVALID_JSON", path, "对象属性名必须是字符串");
      const key = this.parseString(path);
      const valuePath = appendProperty(path, key);
      if (Object.hasOwn(result, key)) reject("DUPLICATE_KEY", valuePath, "同一对象包含解码后重复属性名");
      this.skipWhitespace();
      if (!this.consume(":")) reject("INVALID_JSON", valuePath, "属性名后缺少冒号");
      this.skipWhitespace();
      const schemaValue = this.captureSchema && path === "$" && key === "schema";
      const start = this.index;
      // 信封不占用Schema的16层预算，其他信封值仍遵循普通深度上限。
      result[key] = this.parseValue(valuePath, schemaValue ? 1 : depth + 1);
      if (schemaValue) this.schemaText = this.source.slice(start, this.index);
      this.skipWhitespace();
      if (this.consume("}")) return Object.freeze(result);
      if (!this.consume(",")) reject("INVALID_JSON", path, "对象成员之间缺少逗号");
      this.skipWhitespace();
    }
  }

  /** 读取数组并冻结所有成员。 */
  private parseArray(path: string, depth: number): StrictJsonArray {
    this.requireDepth(depth, path);
    this.index++;
    this.skipWhitespace();
    const result: StrictJsonValue[] = [];
    if (this.consume("]")) return Object.freeze(result);
    while (true) {
      const itemPath = `${path}[${result.length}]`;
      result.push(this.parseValue(itemPath, depth + 1));
      this.skipWhitespace();
      if (this.consume("]")) return Object.freeze(result);
      if (!this.consume(",")) reject("INVALID_JSON", path, "数组成员之间缺少逗号");
      this.skipWhitespace();
    }
  }

  /** 解码JSON字符串并拒绝U+0000和未配对代理项。 */
  private parseString(path: string): string {
    this.index++;
    let result = "";
    while (!this.atEnd()) {
      const current = this.source.charCodeAt(this.index++);
      if (current === 0x22) return result;
      if (current <= 0x1f) reject("INVALID_JSON", path, "字符串包含未转义控制字符");
      if (current === 0x5c) {
        result += this.parseEscape(path);
        continue;
      }
      if (isHighSurrogate(current)) {
        const low = this.source.charCodeAt(this.index);
        if (!isLowSurrogate(low)) reject("INVALID_UNICODE", path, "字符串包含未配对代理项");
        result += String.fromCharCode(current, low);
        this.index++;
      } else if (isLowSurrogate(current)) {
        reject("INVALID_UNICODE", path, "字符串包含未配对代理项");
      } else {
        result += String.fromCharCode(current);
      }
    }
    reject("INVALID_JSON", path, "字符串未闭合");
  }

  /** 读取反斜杠后的JSON转义。 */
  private parseEscape(path: string): string {
    if (this.atEnd()) reject("INVALID_JSON", path, "字符串转义未完成");
    const escaped = this.source[this.index++];
    const simple: Readonly<Record<string, string>> = {
      '"': '"', "\\": "\\", "/": "/", b: "\b", f: "\f", n: "\n", r: "\r", t: "\t",
    };
    if (escaped !== undefined && Object.hasOwn(simple, escaped)) return simple[escaped]!;
    if (escaped !== "u") reject("INVALID_JSON", path, "包含非法字符串转义");
    const first = this.parseHexCodeUnit(path);
    if (first === 0) reject("INVALID_UNICODE", path, "字符串不得包含U+0000");
    if (isHighSurrogate(first)) {
      if (this.source.slice(this.index, this.index + 2) !== "\\u") {
        reject("INVALID_UNICODE", path, "字符串包含未配对代理项");
      }
      this.index += 2;
      const second = this.parseHexCodeUnit(path);
      if (!isLowSurrogate(second)) reject("INVALID_UNICODE", path, "字符串包含未配对代理项");
      return String.fromCharCode(first, second);
    }
    if (isLowSurrogate(first)) reject("INVALID_UNICODE", path, "字符串包含未配对代理项");
    return String.fromCharCode(first);
  }

  /** 读取四位十六进制UTF-16代码单元。 */
  private parseHexCodeUnit(path: string): number {
    const token = this.source.slice(this.index, this.index + 4);
    if (!/^[0-9A-Fa-f]{4}$/.test(token)) reject("INVALID_JSON", path, "Unicode转义必须包含四位十六进制数");
    this.index += 4;
    return Number.parseInt(token, 16);
  }

  /** 读取严格JSON数字并保留词法。 */
  private parseNumber(path: string): StrictJsonNumber {
    const start = this.index;
    this.consume("-");
    if (this.consume("0")) {
      if (isDigit(this.peek())) reject("INVALID_JSON", path, "JSON数字整数部分不得包含前导零");
    } else {
      if (!isNonZeroDigit(this.peek())) reject("INVALID_JSON", path, "JSON数字整数部分不合法");
      while (isDigit(this.peek())) this.index++;
    }
    if (this.consume(".")) {
      if (!isDigit(this.peek())) reject("INVALID_JSON", path, "JSON数字小数点后必须有数字");
      while (isDigit(this.peek())) this.index++;
    }
    if (this.peek() === "e" || this.peek() === "E") {
      this.index++;
      if (this.peek() === "+" || this.peek() === "-") this.index++;
      if (!isDigit(this.peek())) reject("INVALID_JSON", path, "JSON数字指数缺少数字");
      while (isDigit(this.peek())) this.index++;
    }
    const lexical = this.source.slice(start, this.index);
    if (this.runtimeNumbers) {
      if (!Number.isFinite(Number(lexical))) reject("INVALID_JSON", path, "运行数字超出服务端有限解释域");
      return registerNumber(lexical);
    }
    if (lexical.length > 64) reject("NUMBER_TOO_LONG", path, "数字词法超过64个ASCII字节");
    const exponent = /[eE][+-]?(\d+)$/.exec(lexical)?.[1];
    if (exponent !== undefined && (exponent.length > 2 || exponent.length > 1 && exponent.startsWith("0") || Number(exponent) > 12)) {
      reject("INVALID_EXPONENT", path, "数字指数必须无多余前导零且绝对值不超过12");
    }
    return registerNumber(lexical);
  }

  /** 读取true、false或null固定词法。 */
  private parseLiteral<T extends boolean | null>(token: string, value: T, path: string): T {
    if (this.source.slice(this.index, this.index + token.length) !== token) {
      reject("INVALID_JSON", path, "JSON固定值词法不合法");
    }
    this.index += token.length;
    return value;
  }

  /** 根对象计为第一层，拒绝超过16层的对象或数组。 */
  private requireDepth(depth: number, path: string): void {
    if (depth > 16) reject("DEPTH_EXCEEDED", path, "对象或数组嵌套超过16层");
  }

  /** 跳过JSON允许的四种ASCII空白。 */
  private skipWhitespace(): void {
    while (this.peek() === " " || this.peek() === "\t" || this.peek() === "\n" || this.peek() === "\r") this.index++;
  }

  /** 按预期字符推进读取位置。 */
  private consume(expected: string): boolean {
    if (this.peek() !== expected) return false;
    this.index++;
    return true;
  }

  /** 返回当前位置字符。 */
  private peek(): string | undefined {
    return this.source[this.index];
  }

  /** 判断是否到达文本末尾。 */
  private atEnd(): boolean {
    return this.index >= this.source.length;
  }
}

/** 创建、登记并冻结解析器可信数字token。 */
function registerNumber(lexical: string): StrictJsonNumber {
  const token: StrictJsonNumber = { type: "JSON_NUMBER", lexical };
  STRICT_JSON_NUMBERS.add(token);
  return Object.freeze(token);
}

/** 将对象键转换为稳定JSON路径片段。 */
function appendProperty(parent: string, key: string): string {
  // 只回显合同已知字段；未知或超长属性名使用固定占位，避免错误对象成为输入反射通道。
  return KNOWN_PATH_KEYS.has(key) ? `${parent}.${key}` : `${parent}["<unknown>"]`;
}

/** 可安全出现在错误路径中的Dashboard v1已知字段闭集。 */
const KNOWN_PATH_KEYS: ReadonlySet<string> = new Set([
  "schemaVersion", "presentation", "models", "variables", "pages", "mode", "theme", "columns", "rowHeight",
  "gap", "width", "height", "scaleMode", "key", "versionId", "digestAlgorithm", "digest", "profile", "type",
  "title", "required", "modelKey", "defaultDeviceId", "maxItems", "defaultDeviceIds", "defaultPreset",
  "allowedPresets", "options", "defaultValue", "value", "label", "id", "components", "kind", "componentVersion",
  "layout", "props", "bindings", "x", "y", "w", "h", "content", "align", "size", "tone", "text",
  "resourceId", "resourceDigest", "alt", "fit", "precision", "unitMode", "showLastOnlineAt", "status", "min",
  "max", "showLegend", "series", "device", "propertyKey", "timeRangeVariableKey", "granularity", "aggregation",
  "rowLimit", "initialExpandDepth", "pageSize", "showClearedAt", "alarms", "devices", "conditionStates",
  "ackStates", "severities", "placeholder", "directory", "variableKey", "source",
]);

/** 判断字符是否是ASCII十进制数字。 */
function isDigit(value: string | undefined): boolean {
  return value !== undefined && value >= "0" && value <= "9";
}

/** 判断字符是否是ASCII非零十进制数字。 */
function isNonZeroDigit(value: string | undefined): boolean {
  return value !== undefined && value >= "1" && value <= "9";
}

/** 判断字符是否能结束根首token。 */
function isTokenBoundary(value: string | undefined): boolean {
  return value === undefined || value === " " || value === "\t" || value === "\n" || value === "\r";
}

/** 判断UTF-16代码单元是否为高代理项。 */
function isHighSurrogate(value: number): boolean {
  return value >= 0xd800 && value <= 0xdbff;
}

/** 判断UTF-16代码单元是否为低代理项。 */
function isLowSurrogate(value: number): boolean {
  return value >= 0xdc00 && value <= 0xdfff;
}

/** 抛出稳定合同拒绝结果。 */
function reject(reason: ConstructorParameters<typeof DashboardContractViolation>[0], path: string, detail: string): never {
  throw new DashboardContractViolation(reason, path, detail);
}
